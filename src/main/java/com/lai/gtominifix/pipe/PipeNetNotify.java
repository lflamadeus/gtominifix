package com.lai.gtominifix.pipe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 流体管道的两个**只观察**注入落点（CoreMod 只负责插调用，判定与行为都在这里，出错时永久关闭自己）。
 *
 * <p>监听默认关闭：{@code /gtominifix pipewatch <秒>} 开始时打开、结束时关掉。没开监听时这两个方法
 * 第一行就返回 —— 只剩一次静态调用 + 一个布尔读，不做任何别的事（无反射、无分配、无磁盘）。
 *
 * <p>本模组**不再对流体管道打任何修复补丁**：GTO 26.10.53 起上游已按本模组提交的建议修掉了那个缺陷
 * （路由表与调用者无关 + 消费侧剔除指回调用者自己的那条路由），这里只留排查用的观察点。
 */
public final class PipeNetNotify {

    private PipeNetNotify() {}

    private static volatile boolean broken;
    private static volatile String lastError = "";
    /** 观察是否打开：只由 /gtominifix pipewatch 的开关控制。 */
    private static volatile boolean tracing;

    private static final Map<Long, Boolean> LAST_SUB = new ConcurrentHashMap<>();
    private static final Map<Long, String> LAST_XFER = new ConcurrentHashMap<>();

    public static void setTracing(boolean on) {
        tracing = on;
        if (!on) {
            LAST_SUB.clear();
            LAST_XFER.clear();
        }
    }

    public static boolean isTracing() {
        return tracing;
    }

    /**
     * 由 CoreMod 注入到 {@code FluidHatchPartMachine#updateTankSubscription(Direction)} 头部。
     * 机器判据 = 工作启用 && 有货 && 正面邻居此刻能收，任一为假就退订，而退订后只有身边方块变化
     * 才能把它叫回来。只在「应订阅」状态翻转时记一行。
     */
    public static void onSubscriptionCheck(Object machine, Object facing) {
        if (!tracing || broken || machine == null) {
            return;
        }
        try {
            BlockPos pos = asPos(PipeProbe.call(machine, "getPos"));
            if (pos == null) {
                return;
            }
            Object working = PipeProbe.call(machine, "isWorkingEnabled");
            Object io = PipeProbe.read(machine, "io");
            Object tank = PipeProbe.tankOf(machine);
            boolean hasGoods = false;
            if (tank != null) {
                Object amount = PipeProbe.call(tank, "amountAt", 0);
                hasGoods = amount instanceof Number n && n.longValue() > 0L;
            }
            Object level = PipeProbe.call(machine, "getLevel");
            Object holder = PipeProbe.read(machine, "holder");
            Object cache = holder == null ? null : PipeProbe.read(holder, "blockEntityDirectionCache");
            Object canAccept = "?";
            if (cache != null && level instanceof Level lv) {
                canAccept = PipeProbe.call(cache, "hasAdjacentTarget", lv, pos, facing,
                        PipeProbe.aeKeyTypeFluids(), PipeProbe.storageAccess("INSERT"));
            }
            boolean should = Boolean.TRUE.equals(working) && hasGoods && Boolean.TRUE.equals(canAccept);
            Boolean last = LAST_SUB.put(pos.asLong(), should);
            if (last == null || last.booleanValue() != should) {
                PipeDebugLog.log("[订阅] " + pos.toShortString() + " " + machine.getClass().getSimpleName()
                        + " 应订阅=" + should
                        + " 当前订阅=" + (PipeProbe.read(machine, "autoIOSubs") == null ? "off" : "on")
                        + " 工作=" + working + " io=" + io + " 有货=" + hasGoods
                        + " 正面=" + facing + " 邻居能收=" + canAccept);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * 由 CoreMod 注入到 {@code FluidPipeBlockEntity#addTransferredFluids(int)} 头部。
     * 用来看配额计数器是被单次大额还是高频小额顶满的：每根管道每秒、每种 amount 只记一行。
     */
    public static void onTransferredAdded(Object pipe, int amount) {
        if (!tracing || broken || pipe == null || amount <= 0) {
            return;
        }
        try {
            BlockPos pos = asPos(PipeProbe.call(pipe, "getBlockPos"));
            if (pos == null) {
                pos = asPos(PipeProbe.call(pipe, "getPipePos"));
            }
            if (pos == null) {
                return;
            }
            String sig = amount + "@" + (System.currentTimeMillis() / 1000L);
            if (sig.equals(LAST_XFER.put(pos.asLong(), sig))) {
                return;
            }
            PipeDebugLog.log("[配额] " + pos.toShortString() + " += " + amount
                    + " → 累计=" + PipeProbe.read(pipe, "transferredFluids")
                    + " timer=" + PipeProbe.read(pipe, "timer")
                    + " levelTime=" + PipeProbe.call(pipe, "getLevelTime"));
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static BlockPos asPos(Object o) {
        return o instanceof BlockPos pos ? pos : null;
    }

    private static void fail(Throwable t) {
        if (!broken) {
            broken = true;
            lastError = t.getClass().getName() + ": " + t.getMessage();
            PipeDebugLog.log("PipeNetNotify 出错，已永久关闭自己：" + lastError);
        }
    }

    // ------------------------------------------------ 状态（只给 /gtominifix config 与探针用）

    public static String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("监听=").append(tracing ? "开（pipewatch 运行中）" : "关（用 /gtominifix pipewatch 打开）");
        sb.append("；本模组不对流体管道打修复补丁（GTO 26.10.53 起上游已修）");
        if (broken) {
            sb.append("；观察已永久关闭：").append(lastError);
        }
        return sb.toString();
    }
}
