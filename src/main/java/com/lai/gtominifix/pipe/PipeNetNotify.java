package com.lai.gtominifix.pipe;

import com.lai.gtominifix.GtoMiniFixConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 配套 CoreMod 的两个补丁（见 {@code src/main/resources/coremods/gtominifix_fluidpipe.js}）。
 *
 * <p>缺陷 A：{@code BlockEntityDirectionCache#getAdjacentBlockEntity} 把「这个方向没有邻居」以
 * {@code DirectionCache.NULL} 哨兵永久缓存，之后再也不查世界 —— 机器只要在邻接管道区块还没加载时
 * 看过一眼，那一面就整局都瞎。补丁把「存哨兵」改成 {@code remove(方向)}（存真正的 null = 没缓存），
 * 下一次查询自然会重新看世界。{@link #markCacheCheck()} 就是那次查询的计数点（只加一个 long）。
 *
 * <p>缺陷 B：管网结构变化（放/拆管道、网络拆分合并、区块加载卸载）不会通知旁边的机器，而机器一旦
 * 因为「邻居此刻收不下」退掉自动输出订阅（{@code FluidHatchPartMachine#updateTankSubscription}），
 * 就只剩「自己身边方块变化」能把它叫回来。补丁在 {@code FluidPipeNet#onPipeConnectionsUpdate}
 * 开头调用 {@link #onNetworkChanged(Object)}：把这张网的节点位置排队，在下一个服务端 tick 里对每个
 * 位置发一次邻居更新（{@code level.blockUpdated}），于是旁边的机器会重新评估订阅。
 *
 * <p>排队到下一 tick 执行是有意的：{@code onPipeConnectionsUpdate} 会在管网正在被遍历/重建时被调用，
 * 就地发方块更新可能重入管网代码。全部反射 + 只读字段，任何异常都只记一次并永久关闭自己。
 */
public final class PipeNetNotify {

    /** 每 tick 最多处理多少个节点，防止一次网络重组把 tick 拖长。 */
    private static final int MAX_PER_TICK = 4096;

    private static final Queue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<Long> SEEN = ConcurrentHashMap.newKeySet();

    private static volatile boolean broken;
    private static volatile String lastError = "";
    private static volatile long cacheChecks;
    private static volatile long notifyCalls;
    private static volatile long queued;
    private static volatile long notified;
    private static volatile long notifyTicks;

    private PipeNetNotify() {
    }

    private record Entry(Level level, BlockPos pos, long key) {
    }

    /**
     * 由 CoreMod 注入到 {@code BlockEntityDirectionCache#getAdjacentBlockEntity} 头部。
     * 这是热路径：只做一次 long 自增，绝不做别的事。
     */
    public static void markCacheCheck() {
        cacheChecks++;
    }

    /**
     * 由 CoreMod 注入到 {@code FluidPipeNet#onPipeConnectionsUpdate} 头部。
     *
     * @param net 那根管道所属的 {@code FluidPipeNet}（调用方传 this）
     */
    public static void onNetworkChanged(Object net) {
        notifyCalls++;
        if (broken || net == null) {
            return;
        }
        try {
            enqueue(net);
        } catch (Throwable t) {
            fail(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void enqueue(Object net) {
        Object levelObj = PipeProbe.call(net, "getLevel");
        if (!(levelObj instanceof Level level)) {
            return;
        }
        Object mapObj = PipeProbe.read(net, "nodeByBlockPos");
        if (!(mapObj instanceof Map<?, ?> nodes)) {
            return;
        }
        int dimension = level.dimension().location().hashCode();
        int added = 0;
        for (Object raw : nodes.keySet()) {
            if (!(raw instanceof Number number)) {
                continue;
            }
            long packed = number.longValue();
            long key = ((long) dimension << 32) ^ packed;
            if (!SEEN.add(key)) {
                continue;
            }
            QUEUE.add(new Entry(level, BlockPos.of(packed), key));
            added++;
        }
        queued += added;
    }

    /** 把排队的节点位置在下一次服务端 tick 里变成邻居更新。 */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (broken || event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel)) {
            return;
        }
        if (QUEUE.isEmpty()) {
            return;
        }
        int budget = MAX_PER_TICK;
        int done = 0;
        while (budget-- > 0) {
            Entry entry = QUEUE.poll();
            if (entry == null) {
                break;
            }
            SEEN.remove(entry.key());
            try {
                BlockState state = entry.level().getBlockState(entry.pos());
                if (state.isAir()) {
                    continue;
                }
                entry.level().blockUpdated(entry.pos(), state.getBlock());
                done++;
            } catch (Throwable t) {
                fail(t);
                break;
            }
        }
        if (done > 0) {
            notified += done;
            notifyTicks++;
        }
    }

    private static void fail(Throwable t) {
        if (!broken) {
            broken = true;
            lastError = t.getClass().getName() + ": " + t.getMessage();
            if (GtoMiniFixConfig.pipeDebugFile) {
                PipeDebugLog.log("PipeNetNotify 出错，已永久关闭自己：" + lastError);
            }
        }
    }

    /** 给 /gtominifix config 与探针用的一行状态。 */
    public static String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("A 缓存补丁=");
        if (cacheChecks > 0) {
            sb.append("已生效（").append(cacheChecks).append(" 次邻居查询）");
        } else {
            sb.append("尚未被调用（CoreMod 没生效或还没查过邻居）");
        }
        sb.append("，B 网络通知=");
        if (notifyCalls > 0) {
            sb.append("已生效（收到 ").append(notifyCalls).append(" 次网络变动，唤醒 ")
                    .append(notified).append(" 个管道位置）");
        } else {
            sb.append("尚未被调用（CoreMod 没生效或网络还没变动过）");
        }
        if (queued > 0) {
            sb.append("，待处理=").append(QUEUE.size());
        }
        if (broken) {
            sb.append("（已永久关闭：").append(lastError).append("）");
        }
        return sb.toString();
    }
}
