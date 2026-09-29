package com.lai.gtominifix.network;

import com.lai.gtominifix.crate.CrateTransfer;
import com.lai.gtominifix.crate.CrateTransferMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：「刚刚在板条箱界面上按了某个批量转移快捷键」。
 *
 * <p>包里<b>只有意图</b>，没有任何物品数据 —— 搬什么、搬多少、能不能搬，
 * 全部由服务端拿着玩家真实打开的菜单重新判定（见 {@link CrateTransfer#execute}）。
 * 这样即使客户端被改过，最多也只是「搬错一格」，不会凭空造出物品。
 *
 * @param containerId 客户端当时打开的那个界面的 id，服务端用它确认「说的是同一个界面」
 * @param slotIndex   鼠标下那一格在 {@code menu.slots} 里的下标
 * @param mode        {@link CrateTransferMode} 的 ordinal
 */
public record CrateBulkTransferMessage(int containerId, int slotIndex, int mode) {

    public static void encode(CrateBulkTransferMessage message, FriendlyByteBuf buf) {
        buf.writeVarInt(message.containerId());
        buf.writeVarInt(message.slotIndex());
        buf.writeByte(message.mode());
    }

    public static CrateBulkTransferMessage decode(FriendlyByteBuf buf) {
        return new CrateBulkTransferMessage(buf.readVarInt(), buf.readVarInt(), buf.readByte());
    }

    public static void handle(CrateBulkTransferMessage message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            // enqueueWork 保证跑在服务端主线程上，可以直接动菜单和物品。
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            CrateTransferMode mode = CrateTransferMode.fromId(message.mode());
            if (mode == null) {
                return;
            }
            CrateTransfer.execute(sender, message.containerId(), message.slotIndex(), mode);
        });
        context.setPacketHandled(true);
    }
}
