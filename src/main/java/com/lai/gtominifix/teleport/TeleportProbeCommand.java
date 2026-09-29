package com.lai.gtominifix.teleport;

import com.lai.gtominifix.GtoMiniFixConfig;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * {@code /gtominifix probe}：打印「当前视角下 GTO 的原始落点 / 是否合法 / 会被修正到哪」，
 * <b>不实际传送</b>。调参和验证时不用真的去卡一次墙。
 *
 * <p>只暴露一个 {@code probe} 命令节点，由 {@code com.lai.gtominifix.command.GtoMiniFixCommand}
 * 挂到根命令下 —— 这样「传送」这块不需要知道根命令长什么样。
 */
public final class TeleportProbeCommand {

    private static final String PREFIX = "[gtominifix] ";

    private TeleportProbeCommand() {}

    /** @return 挂到根命令下的 {@code probe} 节点 */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("probe").executes(TeleportProbeCommand::probe);
    }

    private static int probe(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        Level level = player.level();

        Optional<Vec3> raw = GtoCompat.rawTeleportPosition(level, player);
        if (raw == null) {
            source.sendFailure(Component.literal(PREFIX
                    + "调不到 GTOCore 的 TravelHandler.teleportPosition（没装 GTOCore，或方法签名变了）"));
            return 0;
        }
        if (raw.isEmpty()) {
            source.sendSuccess(() -> Component.literal(PREFIX
                    + "GTO 原始落点：无。GTO 自己就没算出落点（距离太近或前方没有可站立位置），模组不会介入。"), false);
            return 1;
        }

        Vec3 desired = raw.get();
        boolean fits = SafeLanding.fits(level, player, desired);
        source.sendSuccess(() -> Component.literal(String.format(PREFIX
                        + "GTO 原始落点：(%.3f, %.3f, %.3f)  装得下玩家=%s",
                desired.x, desired.y, desired.z, fits ? "是（模组不介入）" : "否（会修正）")), false);

        if (fits) {
            return 1;
        }

        Vec3 rescued = SafeLanding.rescue(level, player, desired,
                GtoMiniFixConfig.maxVerticalSearch, GtoMiniFixConfig.maxHorizontalSearch,
                GtoMiniFixConfig.preferSolidGround);
        if (rescued != null) {
            source.sendSuccess(() -> Component.literal(String.format(PREFIX
                    + "就近修正到：(%.3f, %.3f, %.3f)", rescued.x, rescued.y, rescued.z)), false);
            return 1;
        }

        int blinkRange = GtoCompat.blinkRange(GtoMiniFixConfig.debugLog);
        Vec3 fallback = GtoMiniFixConfig.enableRaycastFallback
                ? SafeLanding.raycastFallback(level, player, blinkRange)
                : null;
        if (fallback != null) {
            source.sendSuccess(() -> Component.literal(String.format(PREFIX
                    + "就近修正失败，回退到纯射线落点：(%.3f, %.3f, %.3f)",
                    fallback.x, fallback.y, fallback.z)), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(PREFIX + (GtoMiniFixConfig.cancelIfUnrescuable
                ? "救不了，实际传送时会被取消。"
                : "救不了，但 teleport.cancelIfUnrescuable=false，实际传送时会放行原始落点。")), false);
        return 1;
    }
}
