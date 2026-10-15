package com.lai.gtominifix.ae2;

import com.lai.gtominifix.GtoMiniFix;
import com.lai.gtominifix.GtoMiniFixConfig;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 客户端：AE2 终端界面上的「倒空背包里的流体容器」快捷键。
 *
 * <p>按住配置的按键（默认 Ctrl）+ 右键背包里的流体容器，就等价于
 * 「用鼠标把那个容器拿起来，再对着终端界面右键」—— 容器里的流体整罐灌进 ME 网络。
 * 按住不放拖过一排容器时，每个新碰到的容器各做一次。
 *
 * <h2>为什么是「三个包」</h2>
 * 玩家手动做这件事是两步（拿起容器、右键终端），本模组把它拆成同一 tick 内的三次动作：
 * <ol>
 *   <li>{@code ClickType.PICKUP}（左键、源槽）—— 把容器拿到鼠标上；</li>
 *   <li>交给 AE2 的 {@code EMPTY_ITEM}（见 {@link Ae2Compat#requestEmptying}）—— 倒空；</li>
 *   <li>再一次 {@code ClickType.PICKUP}（左键、源槽）—— 把倒空的容器放回原槽。</li>
 * </ol>
 * 第三步不是可选的：容器如果一直留在鼠标上，拖动到下一个容器时原版会把它和鼠标上的残留物
 * <b>交换</b>，整排背包会被搅乱。
 *
 * <p>三次动作都走原版 / AE2 自己的入口（{@code MultiPlayerGameMode#handleInventoryMouseClick}
 * 与原版点击包、{@code MEInteractionPacket}），它们在同一条 TCP 连接上按发送顺序到达服务端，
 * 所以服务端看到的顺序和这里是同一套：先拿起、再倒、最后放回。
 * 客户端本地也会对每次原版点击做预测（{@code handleInventoryMouseClick} 内部先
 * {@code menu.clicked(...)} 再发包），因此第 2 步发出时客户端菜单的「鼠标手持物」
 * 已经真的是那个容器了 —— AE2 客户端判断与服务端 {@code findCarriedContext} 的前提都满足。
 *
 * <h2>已知副作用</h2>
 * 客户端不知道服务端已经把容器倒空了，所以第 3 步是「按满容器」预测的：那一格在客户端会
 * 有最多一 tick 显示成满容器，服务端下一次 {@code broadcastChanges} 就把它纠正成空容器。
 * 数量不会错（服务端才是权威），只是极短的画面闪动。
 *
 * <h2>为什么必须用 HIGHEST 优先级</h2>
 * 与 {@code CrateShortcuts} 同理：Forge 分发可取消事件时，一旦某个 handler 把事件 cancel 掉，
 * 后面没标 {@code receiveCanceled = true} 的 handler 就不会再被调用。
 * 抢到最前面执行，才不会被别的模组（Inventory Essentials 之类）抢先 cancel 掉。
 */
@Mod.EventBusSubscriber(modid = GtoMiniFix.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class Ae2EmptyShortcuts {

    private static final Logger LOG = LogUtils.getLogger();

    /** 右键。 */
    private static final int RIGHT_BUTTON = 1;

    /** 原版 {@code PICKUP} 的「左键」按钮号：整个堆叠拿起 / 整个堆叠放下。 */
    private static final int PICKUP_BUTTON = 0;

    /** 本次按住拖动里已经处理过的槽位下标，避免同一个格子被反复倒。 */
    private static final Set<Integer> HANDLED = new HashSet<>();

    /** 配置字符串解析出来的 GLFW 按键码，随配置值变化重解析。 */
    private static final List<Integer> HOTKEY_CODES = new ArrayList<>();
    private static String parsedKeyName;

    private Ae2EmptyShortcuts() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != RIGHT_BUTTON) {
            // 别的按键按下不算新一轮拖动：右键按着拖动的时候顺手点一下左键，
            // 不应该把这一轮的「已处理槽位」清掉（要等右键松开才重开）。
            return;
        }
        // 每次右键按下都重新开一轮拖动。
        HANDLED.clear();
        if (!GtoMiniFixConfig.ae2EmptyingEnabled || !Ae2Compat.isAvailable()) {
            return;
        }
        // 先判按键，再去找槽位：普通右键在这一步就返回了。
        if (!isHotkeyDown()) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (!Ae2Compat.isGridInteractionMenu(menu)) {
            return; // 当前界面不是 AE2 终端：完全交给原版 / 别的模组
        }
        if (!menu.getCarried().isEmpty()) {
            return; // 鼠标上已经有东西了，这时候再模拟「拿起」会变成交换，不做
        }

        Slot slot = findHoveredPlayerSlot(screen, event.getMouseX(), event.getMouseY());
        if (slot == null) {
            return;
        }
        int slotIndex = menu.slots.indexOf(slot);
        if (slotIndex < 0) {
            return;
        }
        if (simulateEmptying(menu, slot, slotIndex)) {
            HANDLED.add(slotIndex);
            event.setCanceled(true);
        }
    }

    /**
     * 按住拖动：鼠标扫过新的背包容器时，各自做一次和单击一样的模拟。
     *
     * <p>用 {@link #HANDLED} 非空来判断「这次拖动是本模组接管的那次」——
     * 如果按下时没被接管（没按快捷键 / 没停在流体容器上），拖动就完全不介入，
     * 免得把原版「拖动分堆」之类的操作搅坏。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (event.getMouseButton() != RIGHT_BUTTON || HANDLED.isEmpty()) {
            return;
        }
        if (!GtoMiniFixConfig.ae2EmptyingEnabled || !isHotkeyDown()) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (!Ae2Compat.isGridInteractionMenu(menu) || !menu.getCarried().isEmpty()) {
            return;
        }

        Slot slot = findHoveredPlayerSlot(screen, event.getMouseX(), event.getMouseY());
        if (slot == null) {
            return;
        }
        int slotIndex = menu.slots.indexOf(slot);
        if (slotIndex < 0 || HANDLED.contains(slotIndex)) {
            return;
        }
        if (simulateEmptying(menu, slot, slotIndex)) {
            HANDLED.add(slotIndex);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (event.getButton() == RIGHT_BUTTON) {
            HANDLED.clear();
        }
    }

    /**
     * 一次完整的模拟：拿起 → 让 AE2 倒 → 放回。
     *
     * @return 这一下要不要由本模组接管（{@code true} = 调用方 cancel 掉这次点击）。
     *         {@code false} 只在<b>还没动过任何状态</b>时返回（不是流体容器、拿不起来、菜单状态不对），
     *         这时事件原样交给原版。一旦已经发过包就一律返回 {@code true}：菜单状态已经被我们改过，
     *         再让原版拿同一份状态处理这次右键，只会多做一次点击（比如空手右键又拿起半组）。
     */
    private static boolean simulateEmptying(AbstractContainerMenu menu, Slot slot, int slotIndex) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            return false;
        }
        if (!slot.hasItem() || !Ae2Compat.hasEmptyingAction(slot.getItem())) {
            return false; // 不是流体容器，当普通右键处理
        }

        int containerId = menu.containerId;

        // ① 模拟「用鼠标把容器拿起来」。客户端会本地预测，menu.getCarried() 立刻变成容器。
        mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, PICKUP_BUTTON, ClickType.PICKUP, mc.player);
        if (menu.getCarried().isEmpty()) {
            // 没拿起来（槽位被锁 / 被别的模组接管）。这一下什么都不做，但包已经发出去了，
            // 所以仍然算我们接管，不放给原版再处理一次。
            debug("没能拿起槽位 {} 的物品，跳过", slotIndex);
            return true;
        }

        // ② 整个交给 AE2 自己算：等价于「手持容器对着终端网格右键」。
        if (!Ae2Compat.requestEmptying(menu)) {
            // 连包都没发出去（AE2 那边抛了异常），把手上的东西放回去、恢复原状。
            // 这一下仍然算我们接管：不放给原版，否则原版会拿着「空手」的状态再点一次。
            mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, PICKUP_BUTTON, ClickType.PICKUP, mc.player);
            return true;
        }

        // ③ 把倒空的容器放回原槽（服务端此时手上的已经是空容器）。
        mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, PICKUP_BUTTON, ClickType.PICKUP, mc.player);
        debug("已模拟倒空槽位 {} 的流体容器", slotIndex);
        return true;
    }

    /**
     * 鼠标下的槽位 —— 只认<b>玩家背包</b>里的槽（用户要的就是「右键背包里的容器」）。
     *
     * <p>自己按 {@code slot.x / slot.y} + 界面左上角算，不读
     * {@code AbstractContainerScreen#hoveredSlot}：那个字段由鼠标移动事件维护，
     * 在点击事件的这一刻可能还是上一次移动的旧值，而原版自己判点击用的也是坐标重算。
     */
    private static Slot findHoveredPlayerSlot(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        for (Slot slot : screen.getMenu().slots) {
            if (!(slot.container instanceof Inventory)) {
                continue; // 终端自己的槽（视图元件之类）不参与
            }
            if (mouseX >= left + slot.x && mouseX < left + slot.x + 16
                    && mouseY >= top + slot.y && mouseY < top + slot.y + 16) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isHotkeyDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return false;
        }
        long window = mc.getWindow().getWindow();
        for (int keyCode : hotkeyCodes()) {
            if (InputConstants.isKeyDown(window, keyCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 配置里的按键字符串 → GLFW 按键码。配置值变了才重新解析。
     *
     * <p>支持 {@code ctrl} / {@code shift} / {@code alt} 这三个别名（左右两侧都算），
     * 其余按 {@code InputConstants} 的键名（如 {@code key.keyboard.v}）解析；
     * 逗号分隔可以配多个。
     */
    private static List<Integer> hotkeyCodes() {
        String name = GtoMiniFixConfig.ae2EmptyingKey;
        if (name == null || name.isBlank()) {
            return List.of();
        }
        if (!name.equals(parsedKeyName)) {
            parsedKeyName = name;
            HOTKEY_CODES.clear();
            for (String token : name.split(",")) {
                addKey(token.trim());
            }
            if (HOTKEY_CODES.isEmpty()) {
                LOG.warn("[gtominifix] 配置 ae2.emptyingKey=\"{}\" 解析不出任何按键，倒空快捷键按未配置处理。", name);
            }
        }
        return HOTKEY_CODES;
    }

    private static void addKey(String token) {
        if (token.isEmpty()) {
            return;
        }
        switch (token.toLowerCase(Locale.ROOT)) {
            case "ctrl", "control" -> {
                HOTKEY_CODES.add(GLFW.GLFW_KEY_LEFT_CONTROL);
                HOTKEY_CODES.add(GLFW.GLFW_KEY_RIGHT_CONTROL);
            }
            case "shift" -> {
                HOTKEY_CODES.add(GLFW.GLFW_KEY_LEFT_SHIFT);
                HOTKEY_CODES.add(GLFW.GLFW_KEY_RIGHT_SHIFT);
            }
            case "alt" -> {
                HOTKEY_CODES.add(GLFW.GLFW_KEY_LEFT_ALT);
                HOTKEY_CODES.add(GLFW.GLFW_KEY_RIGHT_ALT);
            }
            default -> {
                try {
                    InputConstants.Key key = InputConstants.getKey(token);
                    if (key.getValue() > 0) {
                        HOTKEY_CODES.add(key.getValue());
                    } else {
                        LOG.warn("[gtominifix] 认不出按键 \"{}\"，已忽略。", token);
                    }
                } catch (RuntimeException e) {
                    LOG.warn("[gtominifix] 认不出按键 \"{}\"，已忽略：{}", token, e.toString());
                }
            }
        }
    }

    private static void debug(String format, Object... args) {
        if (GtoMiniFixConfig.debugLog) {
            LOG.info("[gtominifix] " + format, args);
        }
    }
}
