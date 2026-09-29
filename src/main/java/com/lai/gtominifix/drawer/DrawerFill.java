package com.lai.gtominifix.drawer;

import com.lai.gtominifix.GtoMiniFixConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端：把 Functional Storage 抽屉里的东西<b>尽量填满玩家背包</b>（含快捷栏）。
 *
 * <h2>为什么这件事必须由服务端做</h2>
 * Functional Storage 的取物是在服务端完成的：它挂在 {@code PlayerInteractEvent.LeftClickBlock}
 * 上（Titanium 的 {@code EventManager.forge(...)}，优先级 {@code NORMAL}），
 * 命中抽屉时调 {@code ControllableDrawerTile#onClicked}，而那个方法第一行就是
 * {@code if (isServer() && ...)}（字节码核实）。客户端的抽屉方块实体是空壳，没有存储。
 *
 * <h2>为什么不能靠「客户端取消事件」把这次点击拦下来</h2>
 * 看着像可以，实际不行 —— 这是本功能最关键的一条实现约束。
 * Forge 补丁后的 {@code MultiPlayerGameMode.startDestroyBlock}（源码核实）是这样的：
 * <pre>
 *   LeftClickBlock event = ForgeHooks.onLeftClickBlock(...);   // 事件在这里 post
 *   startPrediction(level, seq -&gt; {
 *       ...
 *       ServerboundPlayerActionPacket packet = new ServerboundPlayerActionPacket(START_DESTROY_BLOCK, ...);
 *       if (event.getUseItem() == DENY) return packet;         // ← 取消也照样返回 packet
 *       ...
 *   });
 * </pre>
 * 也就是说客户端取消只会挡掉本地的挖掘预测（{@code isDestroying} 不会置位，
 * 破坏进度条不出现），<b>START_DESTROY_BLOCK 包仍然会发到服务端</b>，
 * 服务端照常 post 自己的 {@code LeftClickBlock}，Functional Storage 照常取 1 个物品。
 * 而 {@code ServerPlayerGameMode.handleBlockBreakAction} 只在 {@code event.isCanceled()} 时早退 ——
 * 那是<b>服务端</b>那次 post 的取消，客户端拦不到。
 *
 * <h2>所以：客户端报意图，服务端在这次点击上动手</h2>
 * 客户端只发一个「我按了 Ctrl + Shift 点了这个抽屉」的小包（见 {@link DrawerFill#arm}），
 * 本类把它记成一条待兑现的请求；等同一个位置的 {@code LeftClickBlock} 在服务端 post 上来时，
 * 以 {@link EventPriority#HIGHEST} 抢先执行：取消事件（把 Functional Storage 自己的
 * 「取一个 / 取一组」整个挡在后面，见下），然后自己做批量填充。
 *
 * <p>顺序是有保证的：两个包由客户端在<b>同一个 tick 内</b>发出 —— 请求包在事件回调里发，
 * 点击包在紧随其后的 {@code startPrediction} 里发（源码核实，见上），
 * 走同一条连接、按序到达，服务端也在同一个任务队列里按序处理。
 *
 * <h2>为什么优先级必须高于 Functional Storage</h2>
 * Forge 分发可取消事件时，一旦某个 handler 把事件 cancel 掉，
 * 后面没标 {@code receiveCanceled = true} 的 handler 就<b>不会再被调用</b>。
 * Functional Storage 走 Titanium 的 {@code EventManager.forge(Class)}，优先级是
 * {@code EventPriority.NORMAL}（字节码核实），所以 {@code HIGHEST} 一定抢得到它前面。
 * 抢到之后自己取消，把「取一个 / 取一组」整个挡在后面 —— 不这么做的话，
 * 服务端会先按它那套取一次（shift 时是整格、否则 1 个），
 * 背包满了还会被 {@code ItemHandlerHelper.giveItemToPlayer} 掉在地上。
 *
 * <h2>没有安装 Functional Storage 时</h2>
 * {@link FunctionalStorageCompat#isAvailable()} 为 false，本类的处理器第一行就返回，
 * 不碰事件、不碰任何东西。
 */
public final class DrawerFill {

    /** 玩家背包里「主背包 27 格 + 快捷栏 9 格」，也就是原版 {@code Inventory} 的 0..35。 */
    private static final int PLAYER_MAIN_SLOTS = 36;

    /**
     * 一条待兑现请求的有效期（tick）。
     *
     * <p>正常情况请求包和点击包在同一个 tick 内被处理，这里给几 tick 余量。
     * 之所以要过期而不是永久保留：请求是「一次性」的，过期后如果还留着，
     * 之后任何一次普通左键点击落到同一个抽屉上都会被它劫持成「填满背包」。
     */
    private static final int ARM_WINDOW_TICKS = 4;

    /** 请求包里的坐标离玩家超过这个距离就直接丢掉（正常点击不可能超出这个范围）。 */
    private static final double MAX_ARM_DISTANCE_SQR = 64.0;

    /** 待兑现的请求，每个玩家最多一条（新的覆盖旧的）。只在服务端主线程访问。 */
    private static final Map<UUID, Armed> ARMED = new HashMap<>();

    private record Armed(BlockPos pos, long tick) {}

    private DrawerFill() {}

    /**
     * 记下一条「客户端按了 Ctrl + Shift 点了这个抽屉」的请求，等这次点击到达服务端时兑现。
     *
     * <p>这里刻意<b>不</b>直接搬运物品：真正的动作必须挂在真实的点击事件上，
     * 这样「填包」永远只可能发生在玩家确实点了一个抽屉的时候。
     * 被改过的客户端最多也只能提前声明意图，声明错了（位置不是抽屉、超出距离）就什么都不发生。
     */
    public static void arm(ServerPlayer player, BlockPos pos) {
        if (!FunctionalStorageCompat.isAvailable()) {
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_ARM_DISTANCE_SQR) {
            return;
        }
        ARMED.put(player.getUUID(), new Armed(pos.immutable(), player.level().getGameTime()));
    }

    /**
     * 兑现一次填包请求。
     *
     * <p>事件在客户端和服务端都会 post（单人存档里是同一个 JVM、同一条事件总线），
     * 所以第一件事就是按 {@code level.isClientSide()} 把客户端那次挡掉。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!GtoMiniFixConfig.drawerFillEnabled || !FunctionalStorageCompat.isAvailable()) {
            return;
        }
        Level level = event.getLevel();
        if (level.isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 请求是一次性的：无论这次点击对不对得上，都先把它取走。
        // 留着的话，过期之前落到同一个抽屉上的普通点击会被劫持。
        Armed armed = ARMED.remove(player.getUUID());
        BlockPos pos = event.getPos();
        if (armed == null || !armed.pos().equals(pos)
                || level.getGameTime() - armed.tick() > ARM_WINDOW_TICKS) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!FunctionalStorageCompat.isDrawerBlock(state.getBlock())) {
            return;
        }

        // ★ 先取消，再干活。取消把 Functional Storage 的「取一个 / 取一组」挡在后面，
        //   顺带也挡掉原版把抽屉挖掉那条路（玩家按着 Ctrl + Shift 时显然不是想挖它）。
        event.setCanceled(true);

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        int slot = FunctionalStorageCompat.findHitSlot(state.getBlock(), state, serverLevel, pos, player);
        if (slot < 0) {
            return; // 没对着任何一格（看着侧面 / 背面）：取消掉这次点击就够了，没有东西可取
        }
        fill(player, serverLevel, pos, slot);
    }

    /** 玩家登出时清掉他的请求，免得 map 里留下再也用不到的条目。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player != null) {
            ARMED.remove(player.getUUID());
        }
    }

    /**
     * 从抽屉的第 {@code slot} 格取物品，尽量塞满玩家背包。
     *
     * <p>存储走的是标准的 {@code ForgeCapabilities.ITEM_HANDLER} 能力，不是 Functional Storage
     * 的内部方法 —— 这是唯一一处不需要反射就能拿到抽屉内容的地方。
     * {@code DrawerTile#getCapability} 对 ITEM_HANDLER 直接返回它自己那个
     * {@code BigInventoryHandler}（字节码核实），方向和是否接了存储控制器都不影响。
     */
    private static void fill(ServerPlayer player, ServerLevel level, BlockPos pos, int slot) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }
        IItemHandler storage = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        if (storage == null || slot < 0 || slot >= storage.getSlots()) {
            return;
        }

        // 先模拟取一次，问「这一格里有东西吗」。
        // 模拟分支不改动任何状态（BigInventoryHandler#extractItem 在 simulate 时只返回副本）。
        // 这里刻意不用 getStackInSlot：抽屉里存的 ItemStack 计数可以是几千，
        // 但「能取多少」应该由服务端自己的 extractItem 说了算。
        ItemStack available = storage.extractItem(slot, Integer.MAX_VALUE, true);
        if (available.isEmpty()) {
            return;
        }

        Inventory inventory = player.getInventory();
        int room = countRoom(inventory, available);
        if (room <= 0) {
            return; // 背包塞不下了：什么都不取，也不让 Functional Storage 去取那 1 个
        }

        ItemStack taken = storage.extractItem(slot, room, false);
        if (taken.isEmpty()) {
            return;
        }
        moveIntoInventory(inventory, taken);
        if (!taken.isEmpty()) {
            // 理论上到不了这里：room 和真正插入用的是同一套规则。
            // 真到了说明两步之间服务端状态变了，宁可掉在地上，也不要凭空吞掉物品。
            ItemHandlerHelper.giveItemToPlayer(player, taken);
        }

        // 抽屉那侧的显示由 BigInventoryHandler#onChange -> markForUpdate 自动同步
        // （字节码核实），玩家背包这侧显式推一次，省得等下一个 tick。
        player.containerMenu.broadcastChanges();
        player.getInventory().setChanged();
    }

    /**
     * 玩家背包还能装下多少个 {@code sample}。
     *
     * <p>只看主背包 27 格 + 快捷栏 9 格，<b>不碰盔甲和副手</b>。
     * 注意 {@code Inventory#getContainerSize()} 返回的是 41（36 + 盔甲 4 + 副手 1，字节码核实），
     * 直接拿它当上界会把盔甲槽也算进去，所以这里用固定上界 36。
     */
    private static int countRoom(Inventory inventory, ItemStack sample) {
        int limit = stackLimit(inventory, sample);
        long room = 0;
        for (int i = 0; i < PLAYER_MAIN_SLOTS; i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()) {
                room += limit;
            } else if (ItemStack.isSameItemSameTags(existing, sample)) {
                room += Math.max(0, limit - existing.getCount());
            }
        }
        return (int) Math.min(room, Integer.MAX_VALUE);
    }

    /**
     * 把 {@code moving} 尽量塞进玩家背包，塞不下的留在 {@code moving} 里。
     *
     * <p>两轮扫描是有意的，和原版 {@code Inventory#addResource} 一致：
     * 先把已有的同类堆填满，再占用空格。反过来会把物品摊得到处都是，
     * 玩家看到的是背包被一堆半满的格子铺满。
     *
     * <p>显式 {@code setItem} 写回，不对 {@code getItem()} 的返回值原地 grow ——
     * 后者要求容器返回的是内部那个实例，返回副本时物品会凭空消失（源侧扣了、目标侧没加）。
     */
    private static void moveIntoInventory(Inventory inventory, ItemStack moving) {
        int limit = stackLimit(inventory, moving);
        for (int i = 0; i < PLAYER_MAIN_SLOTS && !moving.isEmpty(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty() || !ItemStack.isSameItemSameTags(existing, moving)) {
                continue;
            }
            int room = limit - existing.getCount();
            if (room <= 0) {
                continue;
            }
            int take = Math.min(room, moving.getCount());
            ItemStack merged = existing.copy();
            merged.grow(take);
            inventory.setItem(i, merged);
            moving.shrink(take);
        }
        for (int i = 0; i < PLAYER_MAIN_SLOTS && !moving.isEmpty(); i++) {
            if (!inventory.getItem(i).isEmpty()) {
                continue;
            }
            int take = Math.min(limit, moving.getCount());
            ItemStack placed = moving.copy();
            placed.setCount(take);
            inventory.setItem(i, placed);
            moving.shrink(take);
        }
    }

    /**
     * 一格最终能堆多少个 —— 背包自己的上限和物品本身的上限取小。
     *
     * <p>{@code Inventory} 没有覆写 {@code getMaxStackSize()}，继承的是
     * {@code Container} 的默认值 64（字节码核实：{@code bipush 64; ireturn}），
     * 这里照样读它而不是写死 64，将来有模组改这个默认值也能跟上。
     */
    private static int stackLimit(Inventory inventory, ItemStack stack) {
        return Math.min(inventory.getMaxStackSize(), stack.getMaxStackSize());
    }
}
