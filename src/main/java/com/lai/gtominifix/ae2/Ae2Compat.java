package com.lai.gtominifix.ae2;

import com.mojang.logging.LogUtils;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * 反射访问 AE2（Applied Energistics 2）的适配层 —— 本模组里<b>唯一</b>接触 AE2 的地方。
 *
 * <h2>为什么要处理 AE2</h2>
 * AE2 的 ME 终端有一整套「流体容器」交互：鼠标拿起一个装满流体的容器（桶、单元、罐……），
 * 对着终端界面右键，容器里的流体就整罐灌进 ME 网络。这套逻辑在 AE2 里叫
 * {@code InventoryAction.EMPTY_ITEM}，实现全部在服务端（见下面 {@link #requestEmptying} 的注释）。
 * 本模组要做的是让玩家<b>不用先拿起容器</b>：按住快捷键右键背包里的容器，就等价于
 * 「拿起它 → 对终端右键」。
 *
 * <h2>为什么可以放心用反射</h2>
 * AE2 是<b>模组</b>类，Forge 只对 Minecraft 的类做重映射，模组自己的类名 / 方法名 / 字段名
 * 在开发和生产环境里是同一套，所以这里按字面名字反射是稳定的。
 *
 * <h2>为什么不做编译期依赖</h2>
 * 沿用本模组一贯的约定：AE2（在本整合包里是 GTOCore 的 jar-in-jar）是<b>可选</b>依赖。
 * 一旦变成编译期依赖，没装 AE2 的环境会直接 {@code NoClassDefFoundError}。
 * 反射目标在静态块里一次性解析并缓存；解析不到就记为不可用，之后所有调用直接返回兜底值。
 *
 * <h2>为什么连 {@code LinkageError} 一起吞</h2>
 * 与 {@code LdlibCompat} / {@code FunctionalStorageCompat} 同一条约定：AE2 的 jar 在场、
 * 但它依赖的某个类缺失时，{@code Class.forName} 抛的是 {@code NoClassDefFoundError} /
 * {@code ExceptionInInitializerError}（都是 {@code Error}，后者本身还是 {@code LinkageError} 的子类），
 * 光catch {@code Exception} 会漏掉它们 —— 那就不再是"功能静默关闭"，而是从 GUI 事件里
 * 直接冒出去、最坏崩游戏。
 *
 * <p>注意：本类的静态块会触发 AE2 那几个类的静态初始化，所以<b>不要</b>在模组构造器
 * （{@code GtoMiniFix} 的构造器）里碰它 —— 那时 AE2 自己还没 setup 完。
 * 只在游戏内的事件处理、以及 {@code /gtominifix config} 命令里用。
 */
public final class Ae2Compat {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * AE2 里「接受网格交互」的菜单接口：{@code public abstract void handleInteraction(long, InventoryAction)}。
     * ME 终端 / 合成终端 / 无线终端等菜单都实现了它（{@code MEStorageMenu} 及其子类）。
     */
    private static final String C_IME_INTERACTION = "appeng.menu.me.common.IMEInteractionHandler";

    /** 交互动作枚举，我们只要其中的 {@code EMPTY_ITEM}（把手上容器的流体倒出来）。 */
    private static final String C_INVENTORY_ACTION = "appeng.helpers.InventoryAction";

    /** AE2 判断「这个物品是不是能倒的流体容器」的地方。 */
    private static final String C_CONTAINER_STRATEGIES = "appeng.api.behaviors.ContainerItemStrategies";

    private static final boolean AVAILABLE;

    private static Class<?> interactionClass;
    private static Method handleInteraction;
    private static Object emptyItemAction;
    private static Method getEmptyingAction;

    static {
        boolean ok = false;
        try {
            interactionClass = Class.forName(C_IME_INTERACTION);
            Class<?> actionClass = Class.forName(C_INVENTORY_ACTION);
            Class<?> strategies = Class.forName(C_CONTAINER_STRATEGIES);

            // 只按名字 + 形参类型取方法，不引用任何 AE2 类型。
            handleInteraction = interactionClass.getMethod("handleInteraction", long.class, actionClass);
            getEmptyingAction = strategies.getMethod("getEmptyingAction", ItemStack.class);

            Object empty = null;
            for (Object constant : actionClass.getEnumConstants()) {
                if (constant instanceof Enum<?> e && "EMPTY_ITEM".equals(e.name())) {
                    empty = constant;
                }
            }
            if (empty == null) {
                throw new IllegalStateException("InventoryAction 里没有 EMPTY_ITEM");
            }
            emptyItemAction = empty;
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.info("[gtominifix] AE2 不可用（{}），终端倒空流体容器功能整体关闭。", e.toString());
        }
        AVAILABLE = ok;
    }

    private Ae2Compat() {}

    /** AE2 是否在场且所需的类 / 成员都解析到了。 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * 这个菜单是不是「会处理网格交互」的 AE2 终端菜单。
     *
     * <p>判据是 {@code IMEInteractionHandler} 接口，不是具体的 {@code MEStorageMenu} 类：
     * 合成终端、无线终端、扩展终端那些菜单都是它的实现 / 子类，用接口能一次全覆盖。
     */
    public static boolean isGridInteractionMenu(AbstractContainerMenu menu) {
        return AVAILABLE && menu != null && interactionClass.isInstance(menu);
    }

    /**
     * 这个物品是不是 AE2 认得出来的「可倒流体容器」（桶 / 流体单元 / 罐……）。
     *
     * <p>等价于 AE2 客户端在决定「右键要不要走 EMPTY_ITEM」时做的那个判断
     * （{@code AEBaseScreen#getEmptyingAction} 与 {@code IRepoSlot} 里的调用）。
     * 返回 {@code null} 就说明这个物品跟流体容器没关系。
     */
    public static boolean hasEmptyingAction(ItemStack stack) {
        if (!AVAILABLE || stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return getEmptyingAction.invoke(null, stack) != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.debug("[gtominifix] 判断流体容器失败：{}", e.toString());
            return false;
        }
    }

    /**
     * 让 AE2 自己去「把鼠标手上那个容器里的流体倒进网络」—— 也就是模拟
     * 「手持容器对着终端网格右键」。
     *
     * <p>走的是 {@code MEStorageMenu#handleInteraction(long, InventoryAction)}：
     * 客户端侧它只做一件事 —— 发一个 {@code MEInteractionPacket(containerId, serial, action)} 给服务端；
     * 服务端侧才真正干活：{@code handleNetworkInteraction} → {@code handleEmptyHeldItem}，
     * 里面用 {@code ContainerItemStrategies.findCarriedContext(...)} 读<b>服务端菜单的鼠标手持物</b>，
     * 算出能抽多少、先 SIMULATE 再 MODULATE 地灌进网络，最后播放倒空音效。
     * 所以本模组一个字节的搬运逻辑都不需要写。
     *
     * <p>{@code serial} 传 {@code -1}：表示「这次交互不针对网格里的某一格」。
     * AE2 自己 {@code IRepoSlot} 的默认实现里，右键网格倒流体就是这么调的
     * （{@code menu.handleInteraction(-1L, InventoryAction.EMPTY_ITEM)}）。
     * 顺带跳过了那个方法里的 {@code isKeyVisible} 判断 —— 那只是个「网格里看得见才发包」的
     * UI 守卫，服务端插不进去的时候本来就什么都不做，跳过它只会更宽松、不会出错。
     *
     * @return 包有没有发出去；{@code false} 时调用方应该把手上的东西放回原处
     */
    public static boolean requestEmptying(AbstractContainerMenu menu) {
        if (!AVAILABLE || menu == null) {
            return false;
        }
        try {
            handleInteraction.invoke(menu, -1L, emptyItemAction);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.debug("[gtominifix] 请求 AE2 倒空流体失败：{}", e.toString());
            return false;
        }
    }
}
