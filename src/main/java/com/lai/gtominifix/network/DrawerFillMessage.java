package com.lai.gtominifix.network;

import com.lai.gtominifix.drawer.DrawerFill;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：「我按着 Ctrl + Shift 左键点了这个抽屉」。
 *
 * <p>包里只有一个坐标，没有任何物品数据。它本身<b>不会</b>搬运任何东西 ——
 * 服务端把它记成一条待兑现的请求，等这次点击真正到达时（同一个位置的
 * {@code PlayerInteractEvent.LeftClickBlock}）才动手。
 * 也就是说：被改过的客户端最多只能提前声明意图，声明错了就什么都不发生。
 *
 * @param pos 被点击的抽屉方块坐标
 */
public record DrawerFillMessage(BlockPos pos) {

    public static void encode(DrawerFillMessage message, FriendlyByteBuf buf) {
        buf.writeBlockPos(message.pos());
    }

    public static DrawerFillMessage decode(FriendlyByteBuf buf) {
        return new DrawerFillMessage(buf.readBlockPos());
    }

    public static void handle(DrawerFillMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            // enqueueWork 保证跑在服务端主线程上。
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            DrawerFill.arm(sender, message.pos());
        });
        context.setPacketHandled(true);
    }
}
