package com.lai.gtominifix.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * {@code /gtominifix pipewatch <秒>}：盯着一个方块实体，把它的状态<b>逐秒写进调试文件</b>。
 *
 * <p>这是为「跑一段时间才坏」准备的：先瞄准那台输出仓（或它旁边那根管道）开始观察，
 * 然后正常玩到它坏掉。文件里会留下坏掉前后的完整时间线 —— 是缓存槽被种了哨兵、
 * 是自动输出退了订阅、还是路由表里 handler 变成 null，一眼就能看出是哪一层先变。
 *
 * <p>每 1 秒一行摘要（{@link PipeLogicProbe#compactLine}），每 5 秒一份完整逻辑探针
 * （{@link PipeLogicProbe#probe}）加一次「静默退订」巡视（{@link PipeLogicProbe#audit}，
 * 专抓「有货、邻居能收、但自动输出/自动抽取没订阅」这种不会自己恢复的状态），
 * 开始与结束时各再来一份完整状态快照（{@link PipeProbe#probe}）。
 * 停止方式：{@code /gtominifix pipewatch stop}，或者到点自动停、退出游戏自然停。
 */
public final class PipeWatch {

    /** 退订巡视的半径（方块）：卡住时真正相关的机器/管道基本都在目标周围这一圈里。 */
    private static final int AUDIT_RADIUS = 12;

    private static volatile ResourceKey<Level> dim;
    private static volatile BlockPos pos;
    private static volatile long endTick = -1L;
    private static volatile long nextCompact;
    private static volatile long nextFull;
    private static volatile int seconds;

    private PipeWatch() {}

    public static boolean isRunning() {
        return endTick > 0L;
    }

    public static String start(ServerLevel level, BlockPos target, int secs) {
        dim = level.dimension();
        pos = target.immutable();
        seconds = secs;
        long now = level.getGameTime();
        endTick = now + (long) secs * 20L;
        nextCompact = now;
        nextFull = now;
        String where = target.getX() + "," + target.getY() + "," + target.getZ() + " @ " + dim.location();
        PipeDebugLog.log("==== pipewatch 开始：" + where + "，共 " + secs + " 秒"
                + "（每秒一行逻辑摘要 / 每 5 秒一份完整逻辑探针 + 一次退订巡视 / 开始时一份完整状态快照）====");
        PipeProbe.logProbe(level, target);
        PipeDebugLog.logLines(PipeLogicProbe.probe(level, target));
        PipeDebugLog.logLines(PipeLogicProbe.audit(level, target, AUDIT_RADIUS));
        return where;
    }

    public static String stop() {
        boolean was = isRunning();
        endTick = -1L;
        if (was) {
            PipeDebugLog.log("==== pipewatch 手动结束 ====");
        }
        return was ? "已停止观察" : "当前没有在观察";
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long end = endTick;
        if (end <= 0L) {
            return;
        }
        MinecraftServer server = event.getServer();
        ResourceKey<Level> d = dim;
        BlockPos p = pos;
        if (server == null || d == null || p == null) {
            endTick = -1L;
            return;
        }
        ServerLevel level = server.getLevel(d);
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        if (now >= end) {
            PipeProbe.logProbe(level, p);
            PipeDebugLog.logLines(PipeLogicProbe.probe(level, p));
            PipeDebugLog.logLines(PipeLogicProbe.audit(level, p, AUDIT_RADIUS));
            PipeDebugLog.log("==== pipewatch 到点结束（共 " + seconds + " 秒）====");
            endTick = -1L;
            return;
        }
        try {
            if (now >= nextFull) {
                nextFull = now + 100L;
                PipeDebugLog.logLines(PipeLogicProbe.probe(level, p));
                PipeDebugLog.logLines(PipeLogicProbe.audit(level, p, AUDIT_RADIUS));
            } else if (now >= nextCompact) {
                nextCompact = now + 20L;
                PipeDebugLog.log("watch " + p.getX() + "," + p.getY() + "," + p.getZ() + "  " + PipeLogicProbe.compactLine(level, p));
            }
        } catch (Throwable t) {
            PipeDebugLog.log("watch 出错（已忽略）：" + t);
        }
    }
}
