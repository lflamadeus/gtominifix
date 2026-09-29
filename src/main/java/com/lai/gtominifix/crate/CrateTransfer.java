package com.lai.gtominifix.crate;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端批量转移执行器。
 *
 * <p>只依赖原版类型（{@link AbstractContainerMenu} / {@link Slot}），
 * 不引用 LDLib 或 GTOCore 的任何类——LDLib 的探测全在 {@link LdlibCompat} 里，而且只在客户端跑。
 * 这样即使将来 LDLib 改了类名，受影响的也只是「定位鼠标下的槽位」，
 * 服务端的执行逻辑不会跟着一起崩。
 *
 * <h2>为什么不复用 {@code menu.clicked(..., QUICK_MOVE, ...)}</h2>
 * 按理说 shift + 左键就该走原版的 {@code clicked}。但 LDLib 的
 * {@code ModularUIContainer#quickMoveStack} 里有一道<b>每 tick 转移限量</b>：
 * <pre>
 *   int total = moved + transferredPerTick.get(level);
 *   if (total &gt; stack.getMaxStackSize()) return ItemStack.EMPTY;   // 整次作废
 *   transferredPerTick.increment(level, moved);
 * </pre>
 * 也就是同一个 tick 内累计转移超过一个物品堆的上限（64）之后，
 * <b>整次</b> quickMove 直接作废、一个都不搬。玩家手动连按 shift + 点击会被它卡住，
 * 批量转移走这条路更是必然大面积失败（27 格背包里随便几格就超过 64 个物品了）。
 *
 * <p>所以这里改成在服务端直接搬 ItemStack，复刻原版
 * {@code AbstractContainerMenu#moveItemStackTo} 的语义：
 * 先往已有的同类堆里塞满，再往空格里放，全程遵守 {@code Slot#mayPlace} 和槽位堆叠上限。
 * 行为上等价于「连按 27 次 shift + 左键」，只是不受那个每 tick 限量约束。
 *
 * <h2>为什么仍然安全</h2>
 * 客户端送过来的只是「意图」（哪个界面 + 第几格 + 哪种模式），
 * 真正的判定全部在服务端用玩家<b>真实打开着的那个菜单</b>重做一遍：
 * 界面 id 对不上、下标越界、槽位不存在，一律直接返回。
 */
public final class CrateTransfer {

    /** 单次请求最多处理多少个槽位。板条箱有 576 格，给足余量同时防止极端情况下卡住主线程。 */
    private static final int MAX_SLOTS_PER_REQUEST = 2048;

    /**
     * 连续多少次「一个都没搬动」就提前结束。
     * 目标侧装满之后剩下的源槽位全都搬不动，没必要继续遍历完整个容器。
     */
    private static final int MAX_CONSECUTIVE_IDLE = 8;

    private CrateTransfer() {}

    /**
     * 执行一次批量转移。
     *
     * @param containerId  客户端当时打开的界面 id，用来确认「说的是同一个界面」
     * @param hoveredIndex 鼠标下那一格在 {@code menu.slots} 里的下标
     */
    public static void execute(ServerPlayer player, int containerId, int hoveredIndex, CrateTransferMode mode) {
        if (mode == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        // ★ 服务端校验：客户端说的必须就是玩家当前真实打开着的那个界面。
        // 不校验的话，客户端可以拿任意 containerId 去操作别的界面。
        if (menu == null || menu.containerId != containerId) {
            return;
        }
        if (hoveredIndex < 0 || hoveredIndex >= menu.slots.size()) {
            return;
        }
        Slot hovered = menu.slots.get(hoveredIndex);
        if (!hovered.isActive()) {
            return;
        }

        Container playerInventory = player.getInventory();
        boolean fromPlayerSide = isPlayerSide(hovered, playerInventory);
        boolean toPlayerSide = !fromPlayerSide;

        // ★ 必须 copy，不能拿 hovered.getItem() 的引用当样板。
        // 遍历时第一个源格往往就是 hovered 自己，它会被搬空（source.set(EMPTY)）；
        // 一旦容器的 setStackInSlot 是「就地改这个 ItemStack」而不是「替换引用」，
        // 样板就跟着被清空，后面所有同类物品都匹配不上 —— 表现成只搬了第一格。
        ItemStack template = mode == CrateTransferMode.BY_TYPE ? hovered.getItem().copy() : ItemStack.EMPTY;
        if (mode == CrateTransferMode.BY_TYPE && template.isEmpty()) {
            return;
        }

        // 先按「属于哪一侧」分好组。后面每个源格都要扫一遍目标侧，
        // 不预分组的话就是 源格数 × 全部槽位数 的全量扫描 —— 板条箱 576 格时差距很明显。
        List<Slot> targetSlots = collectSide(menu, playerInventory, toPlayerSide);

        int processed = 0;
        int idle = 0;
        for (int i = 0; i < menu.slots.size() && processed < MAX_SLOTS_PER_REQUEST; i++) {
            Slot source = menu.slots.get(i);
            if (!source.isActive() || !source.mayPickup(player)) {
                continue;
            }
            if (isPlayerSide(source, playerInventory) != fromPlayerSide) {
                continue; // 只搬「鼠标下那一格所在的一侧」
            }
            if (mode == CrateTransferMode.ALL && fromPlayerSide && isHotbar(source, playerInventory)) {
                continue; // 整侧转移时快捷栏 9 格不参与
            }
            ItemStack stack = source.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (mode == CrateTransferMode.BY_TYPE && !ItemStack.isSameItemSameTags(stack, template)) {
                continue;
            }

            int before = stack.getCount();
            ItemStack moving = stack.copy();
            pushToOtherSide(targetSlots, player, moving, playerInventory, toPlayerSide);
            int moved = before - moving.getCount();
            if (moved > 0) {
                // 写回剩余量（全搬走就是清空）。
                // 不需要再调 setChanged()：Slot#set 内部已经触发了
                // （原版 Slot 如此，LDLib 的 WidgetSlotItemTransfer 覆写版本里也调了 m_6654_）。
                source.set(moving.isEmpty() ? ItemStack.EMPTY : moving);
                idle = 0;
            } else if (++idle >= MAX_CONSECUTIVE_IDLE) {
                break; // 目标侧已经装不下了，后面的格子也不用试了
            }
            processed++;
        }

        // 一次广播比逐格同步便宜，也保证客户端拿到的是最终状态。
        menu.broadcastChanges();
        player.getInventory().setChanged();
    }

    /**
     * 把 {@code moving} 尽量塞进另一侧的槽位，塞不下的留在 {@code moving} 里。
     *
     * <p>两轮扫描是有意的，和原版 {@code moveItemStackTo} 一致：
     * 先把已有的同类堆填满，再占用空格。反过来（先占空格）会把物品摊得到处都是，
     * 玩家看到的结果是背包被一堆半满的格子铺满，后面再也凑不出完整的堆。
     */
    private static void pushToOtherSide(List<Slot> targetSlots, ServerPlayer player, ItemStack moving,
                                        Container playerInventory, boolean toPlayerSide) {
        if (moving.isEmpty()) {
            return;
        }
        // 第 1 轮：合并进已有的同类堆
        for (Slot target : targetSlots) {
            if (moving.isEmpty()) {
                return;
            }
            if (!isTargetSlot(target, player, playerInventory, toPlayerSide, moving)) {
                continue;
            }
            ItemStack inSlot = target.getItem();
            if (inSlot.isEmpty() || !ItemStack.isSameItemSameTags(inSlot, moving)) {
                continue;
            }
            int room = stackLimit(target, moving) - inSlot.getCount();
            if (room <= 0) {
                continue;
            }
            int take = Math.min(room, moving.getCount());
            // 显式 set 写回，而不是对 getItem() 的返回值原地 grow：
            // 后者的前提是「getStackInSlot 返回的是容器内部那同一个 ItemStack 实例」，
            // 原版容器确实如此，但 GT 的 NotifiableItemStackHandler / LDLib 的
            // IItemTransfer 未必 —— 万一它返回副本，原地 grow 就改了个影子，
            // 物品会凭空消失（源侧扣了、目标侧没加）。
            ItemStack merged = inSlot.copy();
            merged.grow(take);
            target.set(merged);
            moving.shrink(take);
        }
        // 第 2 轮：占用空格
        for (Slot target : targetSlots) {
            if (moving.isEmpty()) {
                return;
            }
            if (!isTargetSlot(target, player, playerInventory, toPlayerSide, moving)) {
                continue;
            }
            if (!target.getItem().isEmpty()) {
                continue;
            }
            int take = Math.min(stackLimit(target, moving), moving.getCount());
            ItemStack placed = moving.copy();
            placed.setCount(take);
            target.set(placed);
            moving.shrink(take);
        }
    }

    /**
     * 收集某一侧所有可用的槽位。
     *
     * <p>只按「激活 + 属于哪一侧」筛一遍。能不能放某个具体物品（{@code mayPlace}）要等
     * 真正轮到那堆物品时再看，因为同一侧的不同物品结论可能不同（比如 GT 带过滤器的槽位）。
     */
    private static List<Slot> collectSide(AbstractContainerMenu menu, Container playerInventory, boolean playerSide) {
        List<Slot> result = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.isActive() && isPlayerSide(slot, playerInventory) == playerSide) {
                result.add(slot);
            }
        }
        return result;
    }

    private static boolean isTargetSlot(Slot target, ServerPlayer player, Container playerInventory,
                                        boolean toPlayerSide, ItemStack stack) {
        return target.isActive()
                && isPlayerSide(target, playerInventory) == toPlayerSide
                && target.mayPlace(stack);
    }

    /**
     * 这一格最终能堆多少个 —— 槽位自己的上限和物品本身的上限取小。
     * 两者都要看：有些容器槽位会被限成 1（比如 GT 的某些过滤槽），
     * 而物品本身可能不是 64（比如工具是 1、桶是 16）。
     *
     * <h2>为什么用无参 {@code getMaxStackSize()} 而不是带 {@code ItemStack} 的重载</h2>
     * 带参数的那个版本在 LDLib 的 {@code WidgetSlotItemTransfer} 里是「探测式」实现的：
     * 它会<b>先把槽位清空</b>、再模拟插入、最后把原值写回去，靠这个差值推断能装多少。
     * 也就是说每调一次就有两次 {@code setStackInSlot} —— GT 那边的
     * {@code NotifiableItemStackHandler} 会因此发变更通知、触发机器更新。
     * 板条箱有 576 格，批量转移里这么调一遍就是上千次无谓的写入通知。
     * 无参版本直接返回 {@code getSlotLimit(index)}，是纯读取。
     * 顺带一提，原版 {@code moveItemStackTo} 用的也是无参版本。
     */
    private static int stackLimit(Slot slot, ItemStack stack) {
        return Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
    }

    private static boolean isPlayerSide(Slot slot, Container playerInventory) {
        return slot.container == playerInventory;
    }

    /** 快捷栏：玩家背包里下标 0..8 的那 9 格。 */
    private static boolean isHotbar(Slot slot, Container playerInventory) {
        return slot.container == playerInventory && slot.getContainerSlot() >= 0 && slot.getContainerSlot() < 9;
    }
}
