package com.lai.gtominifix.teleport;

import com.lai.gtominifix.GtoMiniFixConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 兜底层：抑制「刚瞬移完」窗口内的 in_wall（窒息）伤害。
 *
 * <p>位置修正才是根本解法，这一层只是安全网，覆盖那些落点非法但没被修正的情况
 * （例如 {@code cancelIfUnrescuable = false} 放行了原始落点、或者别的模组把玩家塞进了墙里）。
 *
 * <p>刻意<b>不</b>无条件取消 {@code in_wall}：那是原版正常的「被活塞挤进墙里」反馈。
 * 只保护传送后的 {@link GtoMiniFixConfig#inWallGraceTicks} 个 tick，
 * 而且只在「人确实还嵌在方块里」时才吞掉伤害。
 *
 * <p>只记服务端玩家：{@code EntityTeleportEvent} 本来就只在服务端触发，
 * 客户端这个 map 永远是空的。
 */
public final class TeleportGuard {

    /** 键是玩家 UUID，值是最后一次「落点非法」的传送发生时的游戏刻。 */
    private static final Map<UUID, Long> LAST_UNSAFE_TELEPORT = new HashMap<>();

    private TeleportGuard() {}

    /** 记一次落点非法的传送。 */
    public static void mark(ServerPlayer player) {
        LAST_UNSAFE_TELEPORT.put(player.getUUID(), player.level().getGameTime());
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!GtoMiniFixConfig.enabled || !GtoMiniFixConfig.suppressInWallAfterTeleport) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!event.getSource().is(DamageTypes.IN_WALL)) {
            return;
        }
        Long mark = LAST_UNSAFE_TELEPORT.get(player.getUUID());
        if (mark == null) {
            return;
        }
        long elapsed = player.level().getGameTime() - mark;
        if (elapsed < 0 || elapsed > GtoMiniFixConfig.inWallGraceTicks) {
            LAST_UNSAFE_TELEPORT.remove(player.getUUID());
            return;
        }
        if (player.level().noCollision(SafeLanding.standingBox(player, player.position()))) {
            // 人已经出来了，别再保护，顺手把记录清掉
            LAST_UNSAFE_TELEPORT.remove(player.getUUID());
            return;
        }
        event.setAmount(0F);
    }

    /** 玩家登出时清记录，避免 UUID → 刻 的表无限增长。 */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_UNSAFE_TELEPORT.remove(event.getEntity().getUUID());
    }
}
