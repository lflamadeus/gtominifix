package com.lai.gtominifix.crate;

import com.lai.gtominifix.GtoMiniFix;
import com.lai.gtominifix.GtoMiniFixConfig;
import com.lai.gtominifix.network.CrateBulkTransferMessage;
import com.lai.gtominifix.network.ModNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端：在 GT / GTO 的板条箱界面上补回容器批量转移快捷键。
 *
 * <p>两个操作（Ctrl + Shift + 左键 = 同类物品全转移；空格 + 左键 = 整侧转移）
 * 本来由 Inventory Essentials 提供，在 LDLib 界面上失效，这里自己实现一套。
 *
 * <h2>为什么必须用 HIGHEST 优先级</h2>
 * Forge 分发可取消事件时，一旦某个 handler 把事件 cancel 掉，
 * 后面没标 {@code receiveCanceled = true} 的 handler 就<b>不会再被调用</b>。
 * IE 的这两个操作都在 {@code MouseButtonPressed.Pre} 上处理并 cancel
 * （balm 的 {@code ForgeBalmClientEvents} 把该事件桥接成 balm 的
 * {@code ScreenMouseEvent.Click.Pre}），而 IE 注册得比本模组早 ——
 * 于是本模组连 handler 第一行都执行不到，表现成「按了完全没反应」。
 *
 * <p>抢到最前面执行，然后自己 cancel：既拿到这次点击，又把 IE 挡在后面，
 * 不会出现两套实现各搬一部分。<b>这个优先级不能去掉。</b>
 *
 * <h2>为什么自己实现，而不是修好 IE</h2>
 * IE 的 {@code bulkTransferByType}（Ctrl + Shift）只有客户端实现，
 * 靠 {@code menu.clicked(..., QUICK_MOVE, ...)} 逐格搬，正好撞上 LDLib 的
 * 每 tick 转移限量（见 {@link CrateTransfer} 类注释），只能搬动第一组。
 * 而 {@code bulkTransferAll}（空格）虽然在服务端执行，实测在板条箱上
 * 只有「容器 → 背包」方向正常，「背包 → 容器」只零星搬几格。
 * 两条路都不好用，所以两个操作都接管。
 *
 * <h2>为什么接管后要 cancel</h2>
 * 不 cancel 的话这次左键还会被 LDLib 当成一次普通点击处理 —— 也就是把手上的
 * 物品放进鼠标下那一格 / 拿起来，和批量转移叠加在一起，结果会很怪。
 */
@Mod.EventBusSubscriber(modid = GtoMiniFix.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CrateShortcuts {

    private CrateShortcuts() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseClickPre(ScreenEvent.MouseButtonPressed.Pre event) {
        // 不是 LDLib 的机器界面就完全不介入：原版箱子有自己的一套，别去打扰。
        // 一次 instanceof，代价可忽略；放在最前面，后面的反射定位都轮不到普通点击。
        if (!LdlibCompat.isModularUiScreen(event.getScreen())) {
            return;
        }
        // 只接管左键：右键 / 中键另有用途（右键是拿一半、中键常是别的模组的功能）。
        if (event.getButton() != 0) {
            return;
        }
        if (!GtoMiniFixConfig.crateShortcutsEnabled) {
            return;
        }

        // 先判修饰键，再去找槽位 —— 普通点击（不带修饰键）在这一步就返回了，
        // 不会触发下面那次 widget 树遍历。
        CrateTransferMode mode = detectMode();
        if (mode == null) {
            return;
        }

        Screen screen = event.getScreen();
        Slot hovered = LdlibCompat.findHoveredSlot(screen, event.getMouseX(), event.getMouseY());
        if (hovered == null || !hovered.hasItem()) {
            return; // 没停在物品上：空格子没法当「同类」的样板，也没东西可转移
        }
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        AbstractContainerMenu menu = containerScreen.getMenu();
        int slotIndex = menu.slots.indexOf(hovered);
        if (slotIndex < 0) {
            return; // 这个格子不属于当前菜单（理论上不会发生，防御一下）
        }

        ModNetwork.CHANNEL.sendToServer(
                new CrateBulkTransferMessage(menu.containerId, slotIndex, mode.ordinal()));
        event.setCanceled(true);
    }

    /**
     * 当前按住的是哪个快捷键。
     *
     * <p>统一走 {@link InputConstants#isKeyDown} 查询，不用
     * {@code Screen#hasControlDown()} / {@code hasShiftDown()} ——
     * 同一套机制最省心，也避免那两个方法的语义在版本间漂移。
     *
     * <p>Ctrl + Shift 优先：两个组合同时按住时，玩家的意图更可能是「转移同类」。
     *
     * @return {@code null} 表示没按任何批量转移修饰键，这次就是普通点击
     */
    private static CrateTransferMode detectMode() {
        if ((isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) || isKeyDown(GLFW.GLFW_KEY_RIGHT_CONTROL))
                && (isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT))) {
            return CrateTransferMode.BY_TYPE;
        }
        if (GtoMiniFixConfig.crateTakeOverBulkAll && isKeyDown(GLFW.GLFW_KEY_SPACE)) {
            return CrateTransferMode.ALL;
        }
        return null;
    }

    private static boolean isKeyDown(int keyCode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return false;
        }
        return InputConstants.isKeyDown(mc.getWindow().getWindow(), keyCode);
    }
}
