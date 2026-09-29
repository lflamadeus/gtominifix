package com.lai.gtominifix.drawer;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 反射访问 Functional Storage 的适配层 —— 本模组里<b>唯一</b>接触它的地方。
 *
 * <h2>为什么不做编译期依赖</h2>
 * 沿用本模组一贯的约定：第三方模组一律走反射。一旦变成编译期依赖，
 * 没装 Functional Storage 的环境会直接 {@code NoClassDefFoundError}，模组整体加载失败。
 * 这里只解析两个类，解析不到就把 {@link #isAvailable()} 记为 false，
 * 之后所有调用直接走兜底值，不会每次点击都抛异常。
 *
 * <h2>为什么用反射是稳定的</h2>
 * Functional Storage 是<b>模组</b>类，Forge 只重映射 Minecraft 自己的类，
 * 模组的类名 / 方法名在开发和生产环境是同一套（没有 SRG 名），所以按字面名字反射没问题。
 *
 * <h2>为什么只认 {@code DrawerBlock}，不认 {@code Drawer} 接口</h2>
 * 实现 {@code Drawer} 接口的方块有五种（字节码核实，1.2.13）：
 * <ul>
 *   <li>{@code DrawerBlock}（木质一格 / 两格 / 四格抽屉）—— 本功能的目标</li>
 *   <li>{@code FramedDrawerBlock extends DrawerBlock}（镶框版，同一套逻辑）</li>
 *   <li>{@code CompactingDrawerBlock} / {@code SimpleCompactingDrawerBlock}（压缩抽屉，三格是压缩等级不是并列槽位）</li>
 *   <li>{@code EnderDrawerBlock}（末影抽屉，存储挂在频段上）</li>
 *   <li>{@code FluidDrawerBlock}（流体抽屉，根本没有物品处理器）</li>
 * </ul>
 * 后三者的「格子」语义和普通抽屉不是一回事，玩家要的也只是普通抽屉，
 * 所以这里按 {@code DrawerBlock} 精确圈定范围 —— 它们没有一个是 DrawerBlock 的子类。
 */
public final class FunctionalStorageCompat {

    private static final Logger LOG = LogUtils.getLogger();

    /** 声明 {@code getHit} 的接口。用它取方法比用具体方块类更稳（接口是给外部的 API 面）。 */
    private static final String C_DRAWER = "com.buuz135.functionalstorage.block.Drawer";

    /** 一格 / 两格 / 四格物品抽屉（含镶框版）的公共基类。 */
    private static final String C_DRAWER_BLOCK = "com.buuz135.functionalstorage.block.DrawerBlock";

    private static final boolean AVAILABLE;

    private static Class<?> drawerBlockClass;
    private static Method drawerGetHit;

    static {
        boolean ok = false;
        try {
            // initialize = false：只要「类能加载」就行，不触发对方的静态初始化。
            // 本类会在主类构造器里被问一次（为了打一行启动日志），那时还在模组构造阶段，
            // 提前跑第三方的 static 块是不必要的风险 —— 真正需要初始化的时候，
            // 方块实例早就存在了，JVM 会自己初始化。
            ClassLoader loader = FunctionalStorageCompat.class.getClassLoader();
            Class<?> drawerClass = Class.forName(C_DRAWER, false, loader);
            drawerBlockClass = Class.forName(C_DRAWER_BLOCK, false, loader);
            drawerGetHit = drawerClass.getMethod("getHit",
                    BlockState.class, Level.class, BlockPos.class, Player.class);
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 连 LinkageError 一起吞：目标是「装了但版本对不上」时也只会静默关闭，
            // 绝不把 NoClassDefFoundError / IncompatibleClassChangeError 抛出去变成游戏崩溃。
            LOG.info("[gtominifix] 未找到 Functional Storage（{}），抽屉填包功能整体关闭。", e.toString());
        }
        AVAILABLE = ok;
    }

    private FunctionalStorageCompat() {}

    /** Functional Storage 是否在场，且所需的类 / 成员都解析到了。 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** 这个方块是不是「一格 / 两格 / 四格」那种物品抽屉（含镶框版）。 */
    public static boolean isDrawerBlock(@Nullable Block block) {
        return AVAILABLE && block != null && drawerBlockClass.isInstance(block);
    }

    /**
     * 玩家正对着抽屉的<b>哪一格</b>。
     *
     * <p>直接复用 Functional Storage 自己的判定，不重写一套：
     * 它 {@code DrawerBlock#getHit} 的做法是从玩家眼睛打一条 32 格的射线，
     * 再和这个抽屉朝向对应的若干子碰撞箱逐个求交，返回第一个命中的下标。
     * 两格 / 四格抽屉上「点的是哪一格」完全由它决定，自己重写一份迟早会和它的
     * 碰撞箱定义漂移（那套形状还带旋转，是 {@code CACHED_SHAPES} 里的静态表）。
     *
     * <p>客户端也能调（它只用方块状态 + 静态形状表，不需要方块实体），
     * 不过本模组只在服务端用它 —— 服务端才是权威。
     *
     * @return 命中的格子下标；{@code -1} 表示没对着任何一格（比如看着抽屉的侧面 / 背面）
     */
    public static int findHitSlot(Block block, BlockState state, Level level, BlockPos pos, Player player) {
        if (!AVAILABLE) {
            return -1;
        }
        try {
            Object result = drawerGetHit.invoke(block, state, level, pos, player);
            return result instanceof Integer slot ? slot : -1;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException | LinkageError e) {
            LOG.debug("[gtominifix] 定位抽屉格子失败：{}", e.toString());
            return -1;
        }
    }
}
