package com.lai.gtominifix.network;

import com.lai.gtominifix.GtoMiniFix;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 模组网络通道。
 *
 * <p>只有一个方向要传：客户端告诉服务端「刚刚按了哪个快捷键」。
 * 真正的物品搬运全在服务端做，所以通道里跑的都是小包。
 *
 * <h2>{@code PROTOCOL_VERSION} 什么时候必须改</h2>
 * 改了任何一个消息的字段顺序 / 类型 / 含义，就要把这个字符串往前推一个版本，
 * 否则旧客户端和新服务端之间会静默解错包（表现为「按了没反应」而不是报错，很难查）。
 *
 * <p><b>新增消息同样要改</b>：消息靠 {@code registerMessage} 的下标寻址，
 * 新消息占的是旧版本没有的下标，混装时服务端收到它会找不到处理器、在连接层报错。
 * 推一个版本号能让这种混装被版本校验干净地拦下来，比在运行时炸要好看得多。
 * （1.0.3 加抽屉填包那条消息时从 "1" 推到了 "2"。）
 */
public final class ModNetwork {

    public static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(GtoMiniFix.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private ModNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(nextId++, CrateBulkTransferMessage.class,
                CrateBulkTransferMessage::encode,
                CrateBulkTransferMessage::decode,
                CrateBulkTransferMessage::handle);
        CHANNEL.registerMessage(nextId++, DrawerFillMessage.class,
                DrawerFillMessage::encode,
                DrawerFillMessage::decode,
                DrawerFillMessage::handle);
    }
}
