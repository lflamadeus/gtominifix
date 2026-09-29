package com.lai.gtominifix.teleport;

import com.lai.gtominifix.GtoMiniFixConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * 旅行手杖「向下瞬移卡进方块掉血」修复 —— 事件层。
 *
 * <p>由 {@code com.lai.gtominifix.GtoMiniFix} 注册到 Forge 事件总线。
 * 这里只放事件回调，模组装配（配置注册、总线注册、命令注册）都在主类里，
 * 这样「传送修复」和「板条箱快捷键」两块功能互不引用。
 *
 * <h2>挂载点为什么选 {@link EntityTeleportEvent}</h2>
 * GTOCore 的所有旅行传送都汇聚到这一个事件上（都在 {@code TravelHandler} 里）：
 * <ul>
 *   <li>手杖 shift+右键瞬移：{@code TravelStaffBehavior#performAction} → {@code shortTeleport}
 *       → {@code teleportEvent}（TravelHandler.java:290）</li>
 *   <li>旅行锚传送 / 锚上跳蹲电梯：{@code blockTeleport} / {@code blockElevatorTeleport}
 *       → {@code blockTeleportTo} → 同一个 {@code teleportEvent}（TravelHandler.java:111）</li>
 * </ul>
 * 而 {@code EntityTeleportEvent} 是 Forge 自己的类，所以这条路线
 * <b>零 Mixin、零 GTOCore 编译期依赖</b>，还顺带覆盖了旅行锚。
 *
 * <p>代价：{@code EntityTeleportEvent} 只在服务端触发，所以客户端粒子预览
 * （{@code TravelParticleHandler} 直接调 {@code teleportPosition}）看到的还是原始落点。
 * 想让预览一致就靠 {@code TravelHandlerMixin}，两者叠加是幂等的：
 * 第二遍跑的时候落点已经合法，第一道判断直接放行。
 */
public final class TeleportFix {

    private static final Logger LOG = LogUtils.getLogger();

    private TeleportFix() {}

    /**
     * 核心：把「会卡住玩家」的落点挪到最近的合法点，救不了就取消这次传送。
     *
     * <p>第一道判断就是设计不变量：<b>落点本身装得下玩家就一个字节都不改</b>。
     * 所以穿墙、锚传送、电梯、落在半空、落在水里……一切正常场景的行为与不打模组时完全一致。
     */
    @SubscribeEvent
    public static void onEntityTeleport(EntityTeleportEvent event) {
        if (!GtoMiniFixConfig.enabled) {
            return;
        }
        // 原版 /tp、末影珍珠、紫颂果、随机传送 post 的都是 EntityTeleportEvent 的子类，
        // 默认不动它们（见配置 onlyPlainTeleportEvent）。
        if (GtoMiniFixConfig.onlyPlainTeleportEvent && event.getClass() != EntityTeleportEvent.class) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.isSpectator() || player.isPassenger()) {
            return;
        }

        Vec3 desired = event.getTarget();
        if (SafeLanding.fits(player.level(), player, desired)) {
            return; // ★ 不变量
        }

        TeleportGuard.mark(player);

        Vec3 safe = SafeLanding.rescue(player.level(), player, desired,
                GtoMiniFixConfig.maxVerticalSearch,
                GtoMiniFixConfig.maxHorizontalSearch,
                GtoMiniFixConfig.preferSolidGround);
        if (safe == null && GtoMiniFixConfig.enableRaycastFallback) {
            safe = SafeLanding.raycastFallback(player.level(), player,
                    GtoCompat.blinkRange(GtoMiniFixConfig.debugLog));
        }

        if (safe == null) {
            if (GtoMiniFixConfig.cancelIfUnrescuable) {
                if (GtoMiniFixConfig.debugLog) {
                    LOG.info("[gtominifix] 落点装不下玩家且救不了，取消传送：{}", desired);
                }
                // 取消的代价：GTO 的 shortTeleport 在事件被取消时依然返回 true，
                // 所以会白吃 128 EU + 5 tick 冷却，但会播 DISPENSER_FAIL 给玩家反馈。
                event.setCanceled(true);
            } else if (GtoMiniFixConfig.debugLog) {
                LOG.info("[gtominifix] 落点装不下玩家但救不了，配置为放行原始落点：{}", desired);
            }
            return;
        }

        if (GtoMiniFixConfig.debugLog) {
            LOG.info("[gtominifix] 落点修正 {} -> {}", desired, safe);
        }
        event.setTargetX(safe.x);
        event.setTargetY(safe.y);
        event.setTargetZ(safe.z);
    }
}
