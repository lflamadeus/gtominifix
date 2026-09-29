package com.lai.gtominifix;

import com.lai.gtominifix.command.GtoMiniFixCommand;
import com.lai.gtominifix.drawer.DrawerFill;
import com.lai.gtominifix.drawer.FunctionalStorageCompat;
import com.lai.gtominifix.network.ModNetwork;
import com.lai.gtominifix.teleport.GtoCompat;
import com.lai.gtominifix.teleport.TeleportFix;
import com.lai.gtominifix.teleport.TeleportGuard;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * GTO 小修小补 —— 模组装配入口。
 *
 * <p>这里只做三件事：注册配置、注册网络通道、把各功能块挂到事件总线上。
 * 三块功能彼此不引用，可以各自开关、各自演化：
 * <ul>
 *   <li>{@code teleport}：旅行手杖向下瞬移落点卡进方块导致窒息掉血的修复（1.0.1 起）。</li>
 *   <li>{@code crate}：GT / GTO 板条箱等 LDLib 界面上，容器批量转移快捷键的补实现（1.0.2 起）。</li>
 *   <li>{@code drawer}：Functional Storage 一格 / 两格 / 四格抽屉上，
 *       「Ctrl + Shift + 左键 = 尽量填满背包」的补实现（1.0.3 起）。</li>
 * </ul>
 *
 * <p>三者对 GTOCore / LDLib / Functional Storage 都是<b>可选</b>依赖：全部走反射 + 字符串 id 比对，
 * 依赖不在场时对应的功能整体关闭，模组其余部分照常加载。
 *
 * <h2>为什么这里不引用 {@code LdlibCompat}</h2>
 * 那个类碰了 {@code net.minecraft.client.gui.screens.Screen}，只在客户端存在。
 * 服务端一旦加载主类就会连带加载它、直接 {@code NoClassDefFoundError}。
 * 板条箱那块的客户端逻辑通过 {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)} 注册，
 * 服务端根本不会碰到；这里报个「LDLib 是否在场」只查 {@code ModList} 的 mod id 字符串。
 */
@Mod(GtoMiniFix.MOD_ID)
public final class GtoMiniFix {

    public static final String MOD_ID = "gtominifix";

    /** LDLib 的 mod id。GT / GTO 的机器界面走它的 ModularUI。 */
    private static final String LDLIB_MOD_ID = "ldlib";

    /** Functional Storage 的 mod id。一格 / 两格 / 四格抽屉来自它。 */
    private static final String FUNCTIONAL_STORAGE_MOD_ID = "functionalstorage";

    private static final Logger LOG = LogUtils.getLogger();

    public GtoMiniFix(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, GtoMiniFixConfig.SPEC);
        context.getModEventBus().addListener(this::onCommonSetup);

        // 事件层：只有这几个类是「全局」的（服务端跑），客户端那些自己按 Dist.CLIENT 注册。
        MinecraftForge.EVENT_BUS.register(GtoMiniFix.class);
        MinecraftForge.EVENT_BUS.register(TeleportFix.class);
        MinecraftForge.EVENT_BUS.register(TeleportGuard.class);
        // drawer 的两条 handler 都在服务端生效（客户端那条由 DrawerShortcuts 自己注册），
        // 漏掉这一行会表现成「按了 Ctrl+Shift 跟普通点击一样」——而且日志一个字都没有。
        MinecraftForge.EVENT_BUS.register(DrawerFill.class);

        ModList modList = ModList.get();
        boolean gtocore = modList != null && modList.isLoaded(GtoCompat.MOD_ID);
        boolean ldlib = modList != null && modList.isLoaded(LDLIB_MOD_ID);
        boolean functionalStorage = modList != null && modList.isLoaded(FUNCTIONAL_STORAGE_MOD_ID);
        LOG.info("[gtominifix] 已加载；GTOCore {}，LDLib {}，Functional Storage {}。"
                        + "GTOCore 缺失时传送修正不触发；LDLib 缺失时板条箱快捷键不触发；"
                        + "Functional Storage 缺失时抽屉填包不触发。",
                gtocore ? "在场" : "不在场", ldlib ? "在场" : "不在场",
                functionalStorage ? "在场" : "不在场");

        // 上面那行只能说明「jar 在不在」，这里再确认一次「本模组要用的成员能不能解析到」——
        // 装了但版本对不上时前者显示在场、这里会是不可用，那种情况靠这行日志和
        // /gtominifix config 的 drawer 行才看得出来。
        if (functionalStorage && !FunctionalStorageCompat.isAvailable()) {
            LOG.warn("[gtominifix] Functional Storage 在场，但所需成员解析失败，抽屉填包已关闭（原因见上面一行）。");
        }
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        ModNetwork.register();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        GtoMiniFixCommand.register(event.getDispatcher());
    }
}
