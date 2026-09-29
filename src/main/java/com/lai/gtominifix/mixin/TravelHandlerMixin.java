package com.lai.gtominifix.mixin;

import com.lai.gtominifix.GtoMiniFixConfig;
import com.lai.gtominifix.teleport.GtoCompat;
import com.lai.gtominifix.teleport.SafeLanding;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 可选增强层：直接修正 GTOCore {@code TravelHandler#teleportPosition} 的返回值。
 *
 * <p>事件层（{@code EntityTeleportEvent}）已经能覆盖手杖/旅行锚/电梯的全部传送，
 * 这个 Mixin 只额外买到两件事：
 * <ol>
 *   <li><b>客户端粒子预览与真实落点一致</b>。{@code TravelParticleHandler#clientTick}
 *       每 3 tick 直接调 {@code teleportPosition} 放预览粒子，不走事件，
 *       只做事件层的话粒子会停在旧落点上。</li>
 *   <li><b>取消传送时不再白吃冷却和电</b>。返回 {@code Optional.empty()} 会让
 *       {@code shortTeleport} 走 {@code return false}，{@code TravelStaffBehavior}
 *       拿到 false 就不加冷却、不扣 128 EU（事件层取消时这两样照扣，只多播一个失败音效）。</li>
 * </ol>
 *
 * <p>两层叠加是幂等的：Mixin 先跑，落点已经合法的话事件层的第一道判断直接放行。
 *
 * <h2>为什么不需要 refmap / Mixin 注解处理器</h2>
 * <ul>
 *   <li>用 {@code targets = "..."} 字符串形式指定目标，不引用 GTOCore 的类，
 *       所以不需要 GTOCore 的编译期依赖。</li>
 *   <li>1.20.1 生产环境里类名用的就是官方名（见 {@code build/extractSrg/output.srg}：
 *       {@code eei -> net/minecraft/world/phys/Vec3}），只有成员名会被重映射成 {@code m_xxx}。
 *       本方法体里<b>没有调用任何原版成员</b>，只有 {@code Optional} 和本模组自己的类，
 *       所以没有需要重映射的引用。</li>
 * </ul>
 * <b>改动这个方法体时要留意这一点</b>：一旦在里面直接调了原版方法（比如
 * {@code player.getLookAngle()}），就必须补上 Mixin 注解处理器生成 refmap，
 * 否则 dev 能跑、生产环境会 {@code NoSuchMethodError}。
 *
 * <h2>为什么注入点用 RETURN</h2>
 * 需要读/改整个返回值，用 {@code @Inject(cancellable = true)} 比 {@code @Redirect} 干净。
 * {@code teleportPosition} 有两个 return（无落点 / 有落点），{@code at = @At("RETURN")}
 * 会两个都注入，空的那个被下面的早退挡掉。
 */
@Mixin(targets = "com.gtocore.eio_travel.logic.TravelHandler", remap = false)
public class TravelHandlerMixin {

    @Inject(method = "teleportPosition", at = @At("RETURN"), cancellable = true)
    private static void gtominifix$safeLanding(Level level, Player player,
                                               CallbackInfoReturnable<Optional<Vec3>> cir) {
        Optional<Vec3> result = cir.getReturnValue();
        if (result == null || result.isEmpty()) {
            return;
        }
        Vec3 desired = result.get();
        if (SafeLanding.fits(level, player, desired)) {
            return; // ★ 不变量：合法就原样返回
        }
        Vec3 safe = SafeLanding.rescue(level, player, desired,
                GtoMiniFixConfig.maxVerticalSearch,
                GtoMiniFixConfig.maxHorizontalSearch,
                GtoMiniFixConfig.preferSolidGround);
        if (safe == null && GtoMiniFixConfig.enableRaycastFallback) {
            safe = SafeLanding.raycastFallback(level, player, GtoCompat.blinkRange(GtoMiniFixConfig.debugLog));
        }
        // 救不了就返回空：shortTeleport 走 return false，不加冷却、不扣电、不传送。
        cir.setReturnValue(safe == null ? Optional.empty() : Optional.of(safe));
    }
}
