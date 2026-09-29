package com.lai.gtominifix.command;

import com.lai.gtominifix.GtoMiniFixConfig;
import com.lai.gtominifix.drawer.FunctionalStorageCompat;
import com.lai.gtominifix.teleport.GtoCompat;
import com.lai.gtominifix.teleport.TeleportProbeCommand;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * 根命令 {@code /gtominifix}，把各功能块的子命令挂在一起。
 *
 * <p>新增一个功能时，只要它也提供一个 {@code LiteralArgumentBuilder} 节点，
 * 在这里 {@code .then(...)} 一行接上就行，不用改别的类。
 */
public final class GtoMiniFixCommand {

    private static final String PREFIX = "[gtominifix] ";

    private GtoMiniFixCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gtominifix")
                .requires(source -> source.hasPermission(2))
                .then(TeleportProbeCommand.node())
                .then(Commands.literal("config").executes(GtoMiniFixCommand::config)));
    }

    private static int config(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal(String.format(PREFIX
                        + "teleport: enabled=%s, onlyPlainTeleportEvent=%s, maxVerticalSearch=%d, maxHorizontalSearch=%d, "
                        + "preferSolidGround=%s, enableRaycastFallback=%s, cancelIfUnrescuable=%s, "
                        + "suppressInWallAfterTeleport=%s, inWallGraceTicks=%d",
                GtoMiniFixConfig.enabled,
                GtoMiniFixConfig.onlyPlainTeleportEvent,
                GtoMiniFixConfig.maxVerticalSearch,
                GtoMiniFixConfig.maxHorizontalSearch,
                GtoMiniFixConfig.preferSolidGround,
                GtoMiniFixConfig.enableRaycastFallback,
                GtoMiniFixConfig.cancelIfUnrescuable,
                GtoMiniFixConfig.suppressInWallAfterTeleport,
                GtoMiniFixConfig.inWallGraceTicks)), false);
        source.sendSuccess(() -> Component.literal(String.format(PREFIX
                        + "crate: shortcutsEnabled=%s, takeOverBulkAll=%s",
                GtoMiniFixConfig.crateShortcutsEnabled,
                GtoMiniFixConfig.crateTakeOverBulkAll)), false);
        // FunctionalStorageCompat.isAvailable() 是反射解析的结果，比 ModList 更准：
        // 它同时说明了「类在场」和「本模组需要的成员都对得上」。
        source.sendSuccess(() -> Component.literal(String.format(PREFIX
                        + "drawer: fillEnabled=%s, FunctionalStorage=%s",
                GtoMiniFixConfig.drawerFillEnabled,
                FunctionalStorageCompat.isAvailable() ? "可用" : "不可用（功能关闭）")), false);
        source.sendSuccess(() -> Component.literal(String.format(PREFIX
                        + "debugLog=%s, GTOCore.blinkRange=%d（反射读值）",
                GtoMiniFixConfig.debugLog,
                GtoCompat.blinkRange(GtoMiniFixConfig.debugLog))), false);
        return 1;
    }
}
