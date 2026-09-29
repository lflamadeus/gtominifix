package com.lai.gtominifix.crate;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射访问 LowDragLib（LDLib）的适配层 —— 本模组里<b>唯一</b>接触 LDLib 的地方。
 *
 * <p>GT / GTO 的机器界面（含板条箱）不是原版 {@code AbstractContainerMenu} 的直接实现，
 * 而是 LDLib 的 {@code ModularUIGuiContainer} + {@code ModularUIContainer}：
 * 事件流走 LDLib 自己的 widget 树，槽位的屏幕位置由 widget 决定，
 * 原版 {@code AbstractContainerScreen} 里「按 slot.x / slot.y 反查」那套在这里用不上。
 * 所以要从 LDLib 的 widget 树定位鼠标下的槽位。
 *
 * <h2>为什么可以放心用反射</h2>
 * LDLib 是<b>模组</b>类，Forge 只对 Minecraft 的类做重映射，模组自己的类名 / 方法名 /
 * 字段名在开发和生产环境里是同一套。所以这里按字面名字反射是稳定的，
 * 不需要处理 {@code m_xxx_} / {@code f_xxx_} 那套 SRG 名。
 *
 * <h2>为什么不做编译期依赖</h2>
 * 沿用本模组一贯的约定：LDLib / GTOCore 都是<b>可选</b>依赖，用反射访问，
 * 没装的时候这里整体不可用、模组的其余部分照常加载。
 * 一旦变成编译期依赖，没装 LDLib 的环境会直接 {@code NoClassDefFoundError}。
 *
 * <p>反射目标在静态块里一次性解析并缓存；解析不到就记为不可用，
 * 之后所有调用直接返回兜底值，不会每次点击都抛异常。
 */
public final class LdlibCompat {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String C_UI_SCREEN = "com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer";
    private static final String C_MODULAR_UI = "com.lowdragmc.lowdraglib.gui.modular.ModularUI";
    private static final String C_WIDGET = "com.lowdragmc.lowdraglib.gui.widget.Widget";
    private static final String C_SLOT_WIDGET = "com.lowdragmc.lowdraglib.gui.widget.SlotWidget";

    private static final boolean AVAILABLE;

    private static Class<?> uiScreenClass;
    private static Class<?> slotWidgetClass;

    private static Field screenModularUi;
    private static Field uiMainGroup;
    private static Method widgetGetHoverElement;
    private static Method slotWidgetGetHandler;

    static {
        boolean ok = false;
        try {
            uiScreenClass = Class.forName(C_UI_SCREEN);
            Class<?> uiClass = Class.forName(C_MODULAR_UI);
            Class<?> widgetClass = Class.forName(C_WIDGET);
            slotWidgetClass = Class.forName(C_SLOT_WIDGET);

            screenModularUi = require(uiScreenClass.getField("modularUI"), "ModularUIGuiContainer.modularUI");
            uiMainGroup = require(uiClass.getField("mainGroup"), "ModularUI.mainGroup");
            widgetGetHoverElement = require(widgetClass.getMethod("getHoverElement", double.class, double.class),
                    "Widget.getHoverElement(double,double)");
            slotWidgetGetHandler = require(slotWidgetClass.getMethod("getHandler"), "SlotWidget.getHandler()");
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.info("[gtominifix] 未找到 LDLib（{}），板条箱快捷键功能整体关闭。", e.toString());
        }
        AVAILABLE = ok;
    }

    private LdlibCompat() {}

    /** LDLib 是否在场且所需的类/成员都解析到了。 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** {@code screen} 是不是 LDLib 的机器界面（GT / GTO 的板条箱、机器 UI 都是它）。 */
    public static boolean isModularUiScreen(@Nullable Screen screen) {
        return AVAILABLE && screen != null && uiScreenClass.isInstance(screen);
    }

    /**
     * 用 LDLib 自己的 widget 树找出鼠标下的槽位。
     *
     * <p>走 {@code mainGroup.getHoverElement(mouseX, mouseY)} —— 这和 LDLib 内部判定
     * 「鼠标在哪个 widget 上」用的是同一条路径，滚动容器（板条箱那 576 格在
     * {@code DraggableScrollableWidgetGroup} 里）里的坐标换算也由 LDLib 自己负责，
     * 结果一定和玩家看到的画面一致。
     *
     * <p>刻意不去碰 {@code AbstractContainerScreen#hoveredSlot}：
     * 实测 Inventory Essentials 在这类界面上<b>能</b>拿到那个字段（它的空格快捷键
     * 本来就能用），所以那个字段并没有坏，补它属于自作多情。
     *
     * @param mouseX / mouseY 屏幕绝对坐标（Forge 的鼠标事件给的就是这个）
     * @return {@code null} 表示鼠标没停在槽位上，或者当前不在 LDLib 界面里
     */
    @Nullable
    public static Slot findHoveredSlot(@Nullable Screen screen, double mouseX, double mouseY) {
        if (!isModularUiScreen(screen)) {
            return null;
        }
        try {
            Object ui = screenModularUi.get(screen);
            if (ui == null) {
                return null;
            }
            Object mainGroup = uiMainGroup.get(ui);
            if (mainGroup == null) {
                return null;
            }
            Object hovered = widgetGetHoverElement.invoke(mainGroup, mouseX, mouseY);
            if (hovered == null || !slotWidgetClass.isInstance(hovered)) {
                return null;
            }
            return (Slot) slotWidgetGetHandler.invoke(hovered);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("[gtominifix] 查找 LDLib 界面槽位失败：{}", e.toString());
            return null;
        }
    }

    private static Field require(@Nullable Field field, String what) {
        if (field == null) {
            throw new IllegalStateException("LDLib 缺少成员：" + what);
        }
        return field;
    }

    private static Method require(@Nullable Method method, String what) {
        if (method == null) {
            throw new IllegalStateException("LDLib 缺少成员：" + what);
        }
        return method;
    }
}
