package com.lai.gtominifix.pipe;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * 管道排查用的命令节点（由 {@code com.lai.gtominifix.command.GtoMiniFixCommand} 挂到根命令下）：
 *
 * <ul>
 *   <li>{@code /gtominifix pipetrace}：<b>逻辑探针</b> —— 对着机器/管道执行，把「机器输出时
 *       真正会走的那条链」上的对象逐个抓出来：邻居缓存槽、解析到的 handler 身份、
 *       handler 里的 {@code pipe} / {@code net}、以及一次真实的
 *       {@code handler.insert(key,1000,simulate)} 结果；</li>
 *   <li>{@code /gtominifix pipeprobe}：<b>状态快照</b> —— 邻居缓存、路由表、机器订阅状态、
 *       六向邻居的流体能力；</li>
 *   <li>{@code /gtominifix pipewatch <秒>} / {@code /gtominifix pipewatch stop}：
 *       盯着那个方块逐秒记录（每秒一行逻辑摘要、每 5 秒一份逻辑探针），
 *       用来抓「跑一段时间才坏」的那一刻。</li>
 * </ul>
 *
 * <p>三个命令都只读状态，不会去填 / 抽任何流体 —— 唯一带副作用的是探针里对邻居做的
 * {@code fill(SIMULATE)} 与 {@code insert(SIMULATE)}，那是模拟，不改世界。
 *
 * <p>聊天栏只显示「缓存表游标停在哪 + 现在满不满足自吃复现条件」这几行；
 * 完整快照一律写进本模组日志（{@link PipeDebugLog}），免得刷屏。
 */
public final class PipeProbeCommand {

    private static final String PREFIX = "[gtominifix] ";

    private PipeProbeCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> probeNode() {
        return Commands.literal("pipeprobe").executes(PipeProbeCommand::probe);
    }

    /** 逻辑探针：机器输出时真正会走的那条链（handler 身份 / pipe / net / insert 模拟）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> traceNode() {
        return Commands.literal("pipetrace").executes(PipeProbeCommand::trace);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> watchNode() {
        return Commands.literal("pipewatch")
                .executes(context -> watch(context, 300))
                .then(Commands.literal("stop").executes(PipeProbeCommand::stopWatch))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                        .executes(context -> watch(context, IntegerArgumentType.getInteger(context, "seconds"))));
    }

    private static int probe(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        Level level = player.level();
        BlockPos pos = aimed(player);
        if (pos == null) {
            source.sendFailure(Component.literal(PREFIX + "没瞄到方块：请站在 10 格内、准星对着机器或管道再执行一次。"));
            return 0;
        }
        List<String> lines = PipeProbe.probe(level, pos);
        PipeDebugLog.logLines(lines);
        sendVerdict(source, level, pos, lines.size());
        return 1;
    }

    private static int trace(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        Level level = player.level();
        BlockPos pos = aimed(player);
        if (pos == null) {
            source.sendFailure(Component.literal(PREFIX + "没瞄到方块：请站在 10 格内、准星对着机器或管道再执行一次。"));
            return 0;
        }
        List<String> lines = PipeLogicProbe.probe(level, pos);
        PipeDebugLog.logLines(lines);
        sendVerdict(source, level, pos, lines.size());
        return 1;
    }

    /**
     * 聊天栏只发「游标停在哪 + 满不满足复现条件」这几行，其余全部落盘到本模组日志。
     */
    private static void sendVerdict(CommandSourceStack source, Level level, BlockPos pos, int fullLines) {
        for (String line : PipeLogicProbe.cursorVerdict(level, pos)) {
            source.sendSuccess(() -> Component.literal(PREFIX + line), false);
        }
        source.sendSuccess(() -> Component.literal(PREFIX + "（完整 " + fullLines + " 行已写入 "
                + PipeDebugLog.pathText() + "）"), false);
    }

    private static int watch(CommandContext<CommandSourceStack> context, int seconds) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        if (!(player.level() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal(PREFIX + "只能在服务端世界（存档里）用。"));
            return 0;
        }
        BlockPos pos = aimed(player);
        if (pos == null) {
            source.sendFailure(Component.literal(PREFIX + "没瞄到方块：请站在 10 格内、准星对着机器或管道再执行一次。"));
            return 0;
        }
        String where = PipeWatch.start(level, pos, seconds);
        source.sendSuccess(() -> Component.literal(PREFIX + "开始观察 " + where + "，共 " + seconds
                + " 秒（每秒一行逻辑摘要 / 每 5 秒一份完整逻辑探针）。"
                + "记录写在 " + PipeDebugLog.pathText() + "；提前停止用 /gtominifix pipewatch stop。"), false);
        return 1;
    }

    private static int stopWatch(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String message = PipeWatch.stop();
        source.sendSuccess(() -> Component.literal(PREFIX + message), false);
        return 1;
    }

    /** 取玩家准星指向的方块；那一格没有方块实体时，往命中面再探一格（方便对着机器正面敲）。 */
    private static BlockPos aimed(ServerPlayer player) {
        HitResult hit = player.pick(10.0D, 1.0F, true);
        if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() == HitResult.Type.MISS) {
            return null;
        }
        Level level = player.level();
        BlockPos pos = blockHit.getBlockPos();
        if (level.getBlockEntity(pos) == null) {
            BlockPos next = pos.relative(blockHit.getDirection());
            if (level.getBlockEntity(next) != null) {
                return next;
            }
        }
        return pos;
    }
}
