package com.lai.gtominifix;

import com.lai.gtominifix.command.GtoMiniFixCommand;
import com.lai.gtominifix.drawer.DrawerFill;
import com.lai.gtominifix.drawer.FunctionalStorageCompat;
import com.lai.gtominifix.network.ModNetwork;
import com.lai.gtominifix.pipe.PipeDebugLog;
import com.lai.gtominifix.pipe.PipeNetNotify;
import com.lai.gtominifix.pipe.PipeWatch;
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
import net.minecraftforge.fml.loading.FMLPaths;
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
 *   <li>{@code ae2}：AE2 终端界面上「按住 Ctrl + 右键背包里的流体容器 = 把流体倒进网络」
 *       的补实现（1.0.5 起）。</li>
 *   <li>{@code pipe}：GTO 流体管道「跑一段时间后输出仓不往下游推送」的源头修复
 *       （CoreMod 改 {@code FluidPipeNet#getNetData}：路由表按「管道坐标 + 入流面」缓存，
 *       不再让后插入的机器复用别人建的表而把流体推回自己），
 *       外加 {@code /gtominifix pipeprobe}、{@code pipewatch} 与 {@code logs/gtominifix-debug.log}
 *       这套现场排查工具（1.0.5 起）。</li>
 * </ul>
 *
 * <p>五者对 GTOCore / LDLib / Functional Storage / AE2 都是<b>可选</b>依赖：全部走反射 + 字符串 id 比对，
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

    /**
     * AE2 的 mod id。本整合包里 AE2 是 GTOCore 的 jar-in-jar，所以在 {@code mods} 目录里
     * 找不到独立的 AE2 jar —— 但它在 ModList 里仍然是一个正常的 mod。
     */
    private static final String AE2_MOD_ID = "ae2";

    private static final Logger LOG = LogUtils.getLogger();

    public GtoMiniFix(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, GtoMiniFixConfig.SPEC);
        context.getModEventBus().addListener(this::onCommonSetup);

        // 事件层：只有这几个类是「全局」的（服务端跑），客户端那些自己按 Dist.CLIENT 注册。
        MinecraftForge.EVENT_BUS.register(GtoMiniFix.class);
        MinecraftForge.EVENT_BUS.register(TeleportFix.class);
        MinecraftForge.EVENT_BUS.register(TeleportGuard.class);
        // pipe 的逐秒观察：/gtominifix pipewatch 用，没开始观察时它的 tick 处理器会立刻返回。
        MinecraftForge.EVENT_BUS.register(PipeWatch.class);
        // 管网变动通知：CoreMod 在 FluidPipeNet#onPipeConnectionsUpdate 里调 PipeNetNotify，
        // 它把节点位置排队，在下一 tick 发邻居更新，让旁边的机器重新评估自动输出订阅。
        MinecraftForge.EVENT_BUS.register(PipeNetNotify.class);
        // 本模组的 SLF4J 在部分实例里落不了盘（连 debug.log 都没有），管道排查改成自己写文件。
        // 这里只定位路径；真正的写入由 /gtominifix pipeprobe 与 pipewatch 触发。
        try {
            PipeDebugLog.init(FMLPaths.GAMEDIR.get());
        } catch (Throwable ignored) {
            // 拿不到游戏目录就只是没有调试文件，不能影响加载。
        }
        // drawer 的两条 handler 都在服务端生效（客户端那条由 DrawerShortcuts 自己注册），
        // 漏掉这一行会表现成「按了 Ctrl+Shift 跟普通点击一样」——而且日志一个字都没有。
        MinecraftForge.EVENT_BUS.register(DrawerFill.class);

        ModList modList = ModList.get();
        boolean gtocore = modList != null && modList.isLoaded(GtoCompat.MOD_ID);
        boolean ldlib = modList != null && modList.isLoaded(LDLIB_MOD_ID);
        boolean functionalStorage = modList != null && modList.isLoaded(FUNCTIONAL_STORAGE_MOD_ID);
        boolean ae2 = modList != null && modList.isLoaded(AE2_MOD_ID);
        LOG.info("[gtominifix] 已加载；GTOCore {}，LDLib {}，Functional Storage {}，AE2 {}。"
                        + "GTOCore 缺失时传送修正不触发；LDLib 缺失时板条箱快捷键不触发；"
                        + "Functional Storage 缺失时抽屉填包不触发；AE2 缺失时终端倒空快捷键不触发。",
                gtocore ? "在场" : "不在场", ldlib ? "在场" : "不在场",
                functionalStorage ? "在场" : "不在场", ae2 ? "在场" : "不在场");

        // 上面那行只能说明「jar 在不在」，这里再确认一次「本模组要用的成员能不能解析到」——
        // 装了但版本对不上时前者显示在场、这里会是不可用，那种情况靠这行日志和
        // /gtominifix config 的 drawer 行才看得出来。
        if (functionalStorage && !FunctionalStorageCompat.isAvailable()) {
            LOG.warn("[gtominifix] Functional Storage 在场，但所需成员解析失败，抽屉填包已关闭（原因见上面一行）。");
        }

        // AE2 这边<b>不能</b>照抄上面的检查：Ae2Compat 的静态块会去 Class.forName 加载 AE2 的类，
        // 在模组构造器这么早的时机触发别的模组的类初始化，出问题会很难查。
        // AE2 的可用性改成游戏内按需判定（第一次事件 / /gtominifix config 时），
        // 结果同样会打一行日志。
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        ModNetwork.register();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        GtoMiniFixCommand.register(event.getDispatcher());
    }
}
