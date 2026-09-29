package com.lai.gtominifix;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * 模组配置，按功能分成三组：{@code teleport}（旅行手杖落点）、{@code crate}（板条箱快捷键）、
 * {@code drawer}（Functional Storage 抽屉的「一次填满背包」）。
 *
 * <p>用 COMMON 类型：客户端也要读这些值（板条箱快捷键在客户端判定按键、
 * 传送落点的 Mixin 在客户端也会跑），所以不能放 SERVER。
 *
 * <p>取值统一走下面的静态缓存字段，由 {@link ModConfigEvent}（Loading / Reloading 都算）
 * 刷新。缓存字段的初始值与 SPEC 里的默认值一一对应，配置还没加载完时用默认值兜底。
 */
@Mod.EventBusSubscriber(modid = GtoMiniFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GtoMiniFixConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ---- teleport：旅行手杖落点修正 ----

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.BooleanValue ONLY_PLAIN_TELEPORT_EVENT;
    private static final ForgeConfigSpec.IntValue MAX_VERTICAL_SEARCH;
    private static final ForgeConfigSpec.IntValue MAX_HORIZONTAL_SEARCH;
    private static final ForgeConfigSpec.BooleanValue PREFER_SOLID_GROUND;
    private static final ForgeConfigSpec.BooleanValue ENABLE_RAYCAST_FALLBACK;
    private static final ForgeConfigSpec.BooleanValue CANCEL_IF_UNRESCUABLE;
    private static final ForgeConfigSpec.BooleanValue SUPPRESS_IN_WALL_AFTER_TELEPORT;
    private static final ForgeConfigSpec.IntValue IN_WALL_GRACE_TICKS;

    // ---- crate：板条箱界面上的容器批量转移快捷键 ----

    private static final ForgeConfigSpec.BooleanValue CRATE_SHORTCUTS_ENABLED;
    private static final ForgeConfigSpec.BooleanValue CRATE_TAKE_OVER_BULK_ALL;

    // ---- drawer：Functional Storage 抽屉上的「一次填满背包」 ----

    private static final ForgeConfigSpec.BooleanValue DRAWER_FILL_ENABLED;

    private static final ForgeConfigSpec.BooleanValue DEBUG_LOG;

    static {
        BUILDER.push("teleport");

        ENABLED = BUILDER
                .comment("旅行手杖落点修正的总开关。关掉后不再介入任何传送。")
                .define("enabled", true);

        ONLY_PLAIN_TELEPORT_EVENT = BUILDER
                .comment("只处理「直接 post 基类 EntityTeleportEvent」的传送（GTOCore 的手杖/锚/电梯就是这样）。",
                        "原版 /tp、末影珍珠、紫颂果、随机传送 post 的是子类，默认不受影响；",
                        "改成 false 则连原版子类也一起修正（例如 /tp 进墙里也会被挪开）。")
                .define("onlyPlainTeleportEvent", true);

        MAX_VERTICAL_SEARCH = BUILDER
                .comment("落点非法时，同柱上下各搜索多少格。",
                        "How many blocks up/down to search on the same column when the landing point is invalid.")
                .defineInRange("maxVerticalSearch", 3, 0, 16);

        MAX_HORIZONTAL_SEARCH = BUILDER
                .comment("落点非法时，水平邻域的切比雪夫半径。0 = 只做同柱竖直搜索。",
                        "Chebyshev radius of the horizontal neighbourhood. 0 = vertical-only search.")
                .defineInRange("maxHorizontalSearch", 2, 0, 8);

        PREFER_SOLID_GROUND = BUILDER
                .comment("优先选脚下有实心方块的候选点。",
                        "默认关闭：穿墙本来就经常落在半空，这是预期行为。")
                .define("preferSolidGround", false);

        ENABLE_RAYCAST_FALLBACK = BUILDER
                .comment("救不了时，是否回退到「纯射线落点」（站在你瞄的那个方块顶上）。")
                .define("enableRaycastFallback", true);

        CANCEL_IF_UNRESCUABLE = BUILDER
                .comment("彻底救不了时取消本次传送（宁可不传，也不能把人卡进方块里）。",
                        "false = 放行原始落点，退化成不打模组时的现状。")
                .define("cancelIfUnrescuable", true);

        SUPPRESS_IN_WALL_AFTER_TELEPORT = BUILDER
                .comment("兜底层：抑制「刚瞬移完」窗口内的 in_wall（窒息）伤害。",
                        "只针对刚传送完的窗口，不会无条件取消原版正常的窒息反馈。")
                .define("suppressInWallAfterTeleport", true);

        IN_WALL_GRACE_TICKS = BUILDER
                .comment("兜底层的保护窗口（tick）。默认 20，正好覆盖原版无敌帧的第一次命中。")
                .defineInRange("inWallGraceTicks", 20, 0, 200);

        BUILDER.pop();

        BUILDER.push("crate");

        CRATE_SHORTCUTS_ENABLED = BUILDER
                .comment("在 GT / GTO 的板条箱等 LDLib 界面上，补回「Ctrl + Shift + 左键 = 同类物品全部转移」。",
                        "这个操作本来由 Inventory Essentials 提供，但它在 LDLib 界面上失效：",
                        "IE 的 bulkTransferByType 只有客户端实现，靠 clicked(QUICK_MOVE) 一格一格搬，",
                        "正好撞上 LDLib quickMoveStack 的每 tick 转移限量 —— 搬满第一组后计数到顶，",
                        "之后每次都整次作废，结果就是「只搬得动点击的那一组」。",
                        "本模组接管它，在服务端批量搬运，不受那个限量约束。")
                .define("shortcutsEnabled", true);

        CRATE_TAKE_OVER_BULK_ALL = BUILDER
                .comment("是否接管「空格 + 左键 = 鼠标下那一侧整侧转移」。",
                        "默认 true。曾经设成 false 想让 Inventory Essentials 自己处理（它那条走服务端实现），",
                        "但实测它在板条箱上只有「从板条箱往背包搬」能用，",
                        "「背包往板条箱放」只零星搬几格甚至一格，所以改由本模组接管。",
                        "接管后的语义：鼠标停在背包上时只搬主背包 27 格、不动快捷栏 9 格；",
                        "停在容器上时搬容器里所有格。",
                        "设成 false 可以交回给 IE，用来对比排查。")
                .define("takeOverBulkAll", true);

        BUILDER.pop();

        BUILDER.push("drawer");

        DRAWER_FILL_ENABLED = BUILDER
                .comment("Functional Storage 一格 / 两格 / 四格抽屉上，「Ctrl + Shift + 左键 = 尽量填满玩家背包」。",
                        "填的是主背包 27 格 + 快捷栏 9 格（盔甲和副手不动）：先填已有的同类堆，再占空格。",
                        "背包已经装不下时这次点击什么都不做 —— 不会像原版那样取出 1 个丢在地上。",
                        "没装 Functional Storage 时该项无效果。")
                .define("fillEnabled", true);

        BUILDER.pop();

        DEBUG_LOG = BUILDER
                .comment("打印落点修正 / 批量转移 / 抽屉填包相关日志。")
                .define("debugLog", false);
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean enabled = true;
    public static boolean onlyPlainTeleportEvent = true;
    public static int maxVerticalSearch = 3;
    public static int maxHorizontalSearch = 2;
    public static boolean preferSolidGround = false;
    public static boolean enableRaycastFallback = true;
    public static boolean cancelIfUnrescuable = true;
    public static boolean suppressInWallAfterTeleport = true;
    public static int inWallGraceTicks = 20;

    public static boolean crateShortcutsEnabled = true;
    public static boolean crateTakeOverBulkAll = true;

    public static boolean drawerFillEnabled = true;

    public static boolean debugLog = false;

    private GtoMiniFixConfig() {}

    @SubscribeEvent
    static void onConfigChanged(final ModConfigEvent event) {
        enabled = ENABLED.get();
        onlyPlainTeleportEvent = ONLY_PLAIN_TELEPORT_EVENT.get();
        maxVerticalSearch = MAX_VERTICAL_SEARCH.get();
        maxHorizontalSearch = MAX_HORIZONTAL_SEARCH.get();
        preferSolidGround = PREFER_SOLID_GROUND.get();
        enableRaycastFallback = ENABLE_RAYCAST_FALLBACK.get();
        cancelIfUnrescuable = CANCEL_IF_UNRESCUABLE.get();
        suppressInWallAfterTeleport = SUPPRESS_IN_WALL_AFTER_TELEPORT.get();
        inWallGraceTicks = IN_WALL_GRACE_TICKS.get();
        crateShortcutsEnabled = CRATE_SHORTCUTS_ENABLED.get();
        crateTakeOverBulkAll = CRATE_TAKE_OVER_BULK_ALL.get();
        drawerFillEnabled = DRAWER_FILL_ENABLED.get();
        debugLog = DEBUG_LOG.get();
    }
}
