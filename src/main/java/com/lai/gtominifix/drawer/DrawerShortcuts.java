package com.lai.gtominifix.drawer;

import com.lai.gtominifix.GtoMiniFix;
import com.lai.gtominifix.GtoMiniFixConfig;
import com.lai.gtominifix.network.DrawerFillMessage;
import com.lai.gtominifix.network.ModNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端：Functional Storage 抽屉上的「Ctrl + Shift + 左键 = 尽量填满背包」。
 *
 * <h2>本类只做两件事</h2>
 * 认出这次点击、把意图发给服务端，然后取消本地这次点击。
 * <b>不搬任何物品</b> —— 搬运全在服务端（{@link DrawerFill}），
 * 因为 Functional Storage 的抽屉存储只存在于服务端，客户端的方块实体是空壳。
 *
 * <h2>为什么取消本地事件是必要的</h2>
 * 不取消的话，原版会开始累计挖掘进度、画出破坏裂纹（Functional Storage 自己的
 * 客户端处理器在命中抽屉时也会取消，本模组只是抢在它前面做同一件事）。
 * 取消挡不住 START_DESTROY_BLOCK 包发往服务端 —— 那是设计如此，本功能正是靠那个包
 * 才让服务端有机会在真实的点击事件上动手，详见 {@link DrawerFill} 的类注释。
 *
 * <h2>为什么要判 {@code isClientSide}</h2>
 * 事件在客户端和服务端都会 post，单人存档里两边还是同一个 JVM、同一条事件总线。
 * 不判的话，服务端那次 post 也会进到这里，等于在服务端也发一次包。
 */
@Mod.EventBusSubscriber(modid = GtoMiniFix.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DrawerShortcuts {

    private DrawerShortcuts() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        // 只处理客户端这一次 post。
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (!GtoMiniFixConfig.drawerFillEnabled || !FunctionalStorageCompat.isAvailable()) {
            return;
        }

        // 先判修饰键：不带 Ctrl + Shift 的点击在这一步就返回，
        // 后面那次方块状态查询和反射调用都轮不到普通点击。
        if (!isControlDown() || !isShiftDown()) {
            return;
        }

        BlockPos pos = event.getPos();
        if (!FunctionalStorageCompat.isDrawerBlock(event.getLevel().getBlockState(pos).getBlock())) {
            return;
        }

        // 只报意图，坐标由服务端重新校验（方块是不是抽屉、玩家够不够得着）。
        ModNetwork.CHANNEL.sendToServer(new DrawerFillMessage(pos));
        event.setCanceled(true);
    }

    /**
     * 统一走 {@link InputConstants#isKeyDown} 查询，和板条箱那块保持同一套做法。
     *
     * <p>不用 {@code Screen#hasControlDown()} / {@code hasShiftDown()}：
     * 那两个是「界面层」的判定，这里处理的是世界里的点击，不在界面栈上。
     */
    private static boolean isControlDown() {
        return isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) || isKeyDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private static boolean isShiftDown() {
        return isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private static boolean isKeyDown(int keyCode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return false;
        }
        return InputConstants.isKeyDown(mc.getWindow().getWindow(), keyCode);
    }
}
