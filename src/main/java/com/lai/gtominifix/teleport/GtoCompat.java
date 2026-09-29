package com.lai.gtominifix.teleport;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 反射访问 GTOCore 的适配层。
 *
 * <p>整个模组<b>不依赖 GTOCore 的编译期依赖</b>：物品 id 用字符串比对，
 * 配置项和方法都走反射。没装 GTOCore（或版本对不上）时这里统一返回兜底值，
 * 模组的其余部分照常加载，只是不会触发任何修正。
 *
 * <p>反射结果不做缓存：{@code GTOConfig} 是可以在游戏内重载的配置，
 * 缓存会让「改了 blinkRange 之后回退落点不对」这种问题变得难查；
 * 而这些方法只在「落点救不了」和调试命令里被调用，频率极低。
 */
public final class GtoCompat {

    /** GTOCore 的 mod id（也是物品 id 的命名空间，旅行手杖是 {@code gtocore:travel_staff}）。 */
    public static final String MOD_ID = "gtocore";

    private static final Logger LOG = LogUtils.getLogger();

    private static final String CONFIG_CLASS = "com.gtocore.config.GTOConfig";
    private static final String TRAVEL_HANDLER_CLASS = "com.gtocore.eio_travel.logic.TravelHandler";

    /** GTO 配置读不到时的兜底值，与 GTOConfig.TravelConfig.blinkRange 的默认值一致。 */
    public static final int DEFAULT_BLINK_RANGE = 24;

    private GtoCompat() {}

    /**
     * 反射读 {@code GTOConfig.INSTANCE.travelConfig.blinkRange}。
     *
     * <p>GTOConfig.java:33 是 {@code public final static GTOConfig INSTANCE}，
     * 第 49 行 {@code public TravelConfig travelConfig}，blinkRange 在 TravelConfig 里
     * （GTOConfig.java:507，默认 24）。
     */
    public static int blinkRange(boolean debugLog) {
        try {
            Class<?> configClass = Class.forName(CONFIG_CLASS);
            Field instanceField = configClass.getField("INSTANCE");
            Object instance = instanceField.get(null);
            Object travelConfig = instance.getClass().getField("travelConfig").get(instance);
            return travelConfig.getClass().getField("blinkRange").getInt(travelConfig);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (debugLog) {
                LOG.debug("[gtominifix] 读不到 GTOCore 的 blinkRange，使用兜底值 {}：{}",
                        DEFAULT_BLINK_RANGE, e.toString());
            }
            return DEFAULT_BLINK_RANGE;
        }
    }

    /**
     * 反射调用 {@code TravelHandler.teleportPosition(Level, Player)}，拿到 GTOCore 的原始落点。
     *
     * <p>只给调试命令 {@code /gtominifix probe} 用，不参与实际传送。
     *
     * @return {@code null} 表示调用失败（没装 GTOCore 或方法签名变了）；
     *         {@link Optional#empty()} 表示 GTO 自己也没算出落点
     */
    @Nullable
    public static Optional<Vec3> rawTeleportPosition(Level level, Player player) {
        try {
            Class<?> handlerClass = Class.forName(TRAVEL_HANDLER_CLASS);
            Method method = handlerClass.getMethod("teleportPosition", Level.class, Player.class);
            Object result = method.invoke(null, level, player);
            if (result instanceof Optional<?> optional) {
                @SuppressWarnings("unchecked")
                Optional<Vec3> typed = (Optional<Vec3>) optional;
                return typed;
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
