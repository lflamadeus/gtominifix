# crate —— 板条箱快捷存取

一句话：GT / GTO 板条箱（LDLib 的 `ModularUI` 界面）上，「Ctrl + Shift + 左键 = 同类物品全部转移」与「空格 + 左键 = 整侧转移」失效；本模块在服务端自己实现这两个操作。给装了 Inventory Essentials、却在这些界面上按了没反应的玩家用。

## 症状 / 为什么需要它

- 青铜板条箱 / 钢板条箱等界面里 Ctrl + Shift + 左键点一组物品，只操作鼠标下那一组，和普通左键一样。
- 空格 + 左键也不可靠：只有「板条箱 → 背包」方向能用，「背包 → 板条箱」只零星搬几格甚至一格。
- 这两个快捷键不是原版功能，来自整合包里的 Inventory Essentials（`bulk_transfer` / `bulk_transfer_all`）。
- 有时表现为「按了完全没反应、聊天栏一个字都没有」——那不是没实现，是 handler 根本没被调用（见根因 2）。

## 根因（证据）

> 下面 IE / LDLib 侧的事实来自 1.0.2 时期的实测与字节码核对，**不是从本仓库源码读出来的**——本仓库里没有 IE / LDLib 的 jar，无法再核一遍。第 2 条里 `CrateShortcuts.java:55` 的 `HIGHEST` 优先级是本仓库可核的。

IE 的两条路径在 LDLib 界面上走的不是同一套实现：

| IE 操作 | 实现 | 结果 |
|---|---|---|
| `bulkTransferAll`（空格） | 被 `ServerSupportedInventoryControls` 覆写成**服务端**实现 | 绕开客户端 quickMove，板条箱→背包方向可用 |
| `bulkTransferByType`（Ctrl+Shift） | **只有客户端实现**，靠 `clicked(..., QUICK_MOVE)` 逐格搬 | 撞上 LDLib 限流，只能搬第一组 |

1. **LDLib 的每 tick 转移限量**（`ModularUIContainer#quickMoveStack`，原文引用在 `CrateTransfer.java:23-27`）：

   ```java
   int total = moved + transferredPerTick.get(level);
   if (total > stack.getMaxStackSize()) return ItemStack.EMPTY;   // 整次作废
   ```

   第一次搬满 64 就把计数顶到上限，之后每次都整次作废、一个不搬。玩家手动连按 shift + 点击也会被它卡住，这是 LDLib 自身行为，本模组只绕开批量转移这条路。

2. **本模组的 handler 会被 IE 整个跳过**：Forge 分发可取消事件时，某个 handler 一旦 cancel，后面没标 `receiveCanceled` 的 handler 就**不再被调用**。IE 的 Ctrl + Shift / 空格都在 `ScreenEvent.MouseButtonPressed.Pre` 上处理并 cancel（balm 的 `ForgeBalmClientEvents` 把它桥接成 `ScreenMouseEvent.Click.Pre`），而 IE 注册得更早 —— 本模组连 handler 第一行都执行不到。所以 `CrateShortcuts.java:55` 必须是 `@SubscribeEvent(priority = EventPriority.HIGHEST)`。**这个优先级不能去掉。**

3. **槽位坐标不能用原版那套**：板条箱界面是 LDLib 的 `ModularUIGuiContainer` + `ModularUIContainer`（`LdlibCompat.java:38-41`），槽位屏幕位置由 widget 决定，原版按 `slot.x` / `slot.y` 反查的做法在这里失效。所以定位走 LDLib 自己的 widget 树 `mainGroup.getHoverElement(mx, my)`（`LdlibCompat.java:114`），板条箱那 576 格在 `DraggableScrollableWidgetGroup` 里，滚动坐标换算由 LDLib 负责。

## 实现

- **客户端挂载点**：`CrateShortcuts.onMouseClickPre(ScreenEvent.MouseButtonPressed.Pre)`，`@Mod.EventBusSubscriber(modid = GtoMiniFix.MOD_ID, value = Dist.CLIENT, bus = FORGE)`，`priority = EventPriority.HIGHEST`。顺序是：非 LDLib 界面返回（`:59`）→ 非左键返回（`:63`）→ `crateShortcutsEnabled` 关闭返回（`:66`）→ `detectMode()` 判修饰键（`:72`）→ 找槽位（`:78`）→ 发 `CrateBulkTransferMessage(menu.containerId, slotIndex, mode.ordinal())`（`:92`）→ `event.setCanceled(true)`（`:94`）。**必须 cancel**，否则这次左键还会被 LDLib 当成普通点击，和批量转移叠加。
- **修饰键**统一用 `InputConstants.isKeyDown`（`:124`），不用 `Screen#hasControlDown()`；Ctrl + Shift 优先于空格（`:109-115`）。
- **网络包**只带意图：`record CrateBulkTransferMessage(int containerId, int slotIndex, int mode)`（`CrateBulkTransferMessage.java:22`），`mode` 是 `CrateTransferMode` 的 ordinal；`handle` 里 `context.enqueueWork` 保证在服务端主线程执行（`:36`）。
- **服务端执行**：`CrateTransfer.execute(ServerPlayer, int containerId, int hoveredIndex, CrateTransferMode)`（`CrateTransfer.java:61`）。关键不变量：`menu.containerId != containerId` 一律返回（`:68`）、下标越界返回（`:71`）、`!hovered.isActive()` 返回（`:75`）——客户端被改最多「搬错一格」，不会凭空造物品。
- **不复用 `menu.clicked(QUICK_MOVE)`**：直接复刻原版 `moveItemStackTo` 语义搬 ItemStack。`pushToOtherSide` 两轮扫描（`CrateTransfer.java:145-194`）：第 1 轮填满已有同类堆，第 2 轮占空格；全程 `Slot#mayPlace` + `stackLimit`。先占空格会把背包铺成一堆半满格子，所以顺序不能反。
- **样板必须 `copy()`**（`:87`）：遍历时第一个源格往往就是 `hovered` 自己，它会被搬空；若容器 `setStackInSlot` 是「就地改这个 ItemStack」而非替换引用，样板跟着被清空，后面同类全匹配不上，表现成只搬第一格。
- **合并要显式 `target.set(merged)`**（`:172-174`），不能对 `getItem()` 返回值原地 `grow`：原版容器返回内部实例，但 GT 的 `NotifiableItemStackHandler` / LDLib 的 `IItemTransfer` 未必，返回副本时物品会凭空消失（源侧扣了、目标侧没加）。
- **`stackLimit` 用无参 `slot.getMaxStackSize()`**（`:233-235`）：带 `ItemStack` 的重载在 LDLib `WidgetSlotItemTransfer` 里是探测式实现（清空 → 模拟插入 → 写回），576 格就是上千次无谓写入通知。原版 `moveItemStackTo` 用的也是无参版本。
- **`ALL` 模式跳过快捷栏**：仅当「从背包搬出」时跳过 `isHotbar` 的 9 格（`:106`，`isHotbar` = `slot.container == playerInventory && getContainerSlot() < 9`，`:243`）。
- 收尾一次 `menu.broadcastChanges()` + `player.getInventory().setChanged()`（`:134-135`），比逐格同步便宜且客户端拿到最终状态。

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `crate.shortcutsEnabled` | `true` | 本模块总开关；设 `false` 后两个快捷键（Ctrl+Shift / 空格）都不再接管（`CrateShortcuts.java:66`） |
| `crate.takeOverBulkAll` | `true` | 单独控制「空格 + 左键 = 整侧转移」。设 `false` 交回 IE，用来对比排查 |

## 怎么验证它在工作

- `/gtominifix config` 的 crate 行只打印这两个键：`crate: shortcutsEnabled=<bool>, takeOverBulkAll=<bool>`（`GtoMiniFixCommand.java:49-52`）。这一行**不显示 LDLib 状态**——LDLib 是否在场看启动日志 `[gtominifix] 已加载；GTOCore …，LDLib 在场/不在场`；LDLib 缺失或成员解析不到时另有一条 INFO「未找到 LDLib（…），板条箱快捷键功能整体关闭」。
- 游戏内：开板条箱，Ctrl + Shift + 左键点箱内一组物品 → 箱内同类应全部进背包（装满即止）；再点背包里的一组 → 应全部进箱。
- 反向验证：把 `crate.takeOverBulkAll` 改成 `false` 重开，空格 + 左键的行为应退回 IE 那套（这正是排查「是本模组还是 IE 的锅」的手段）。

## 实测清单

- [ ] 板条箱里 Ctrl+Shift+左键点一组物品 → 板条箱内所有同类物品进背包，直到背包装满
- [ ] 背包里 Ctrl+Shift+左键点一组物品 → 背包内所有同类物品进板条箱
- [ ] 板条箱里某种物品多到背包装不下 → 装满背包即止，剩下的留在板条箱，**物品总数守恒**
- [ ] 把 `crate.takeOverBulkAll` 改成 `false` 后空格+左键 → 由 IE 处理，行为应与不打本模组时一致
- [ ] 打开普通箱子 / 原版容器 → 本模组完全不介入
- [ ] 默认配置（`takeOverBulkAll = true`）下空格+左键点背包物品 → 主背包 27 格进板条箱，快捷栏 9 格不动

## 已知边界与坑

- 只对 LDLib 的 `ModularUIGuiContainer` 生效（`LdlibCompat.isModularUiScreen`）；原版箱子等一律不介入。
- 只处理左键（`event.getButton() != 0` 直接返回）；右键 / 中键不受影响。
- 鼠标必须停在**有物品的**槽位上，空格子直接返回（不能当同类样板，也没东西可转移）。
- 没装 LDLib，或 `modularUI` / `mainGroup` / `Widget.getHoverElement(double,double)` / `SlotWidget.getHandler()` 任一解析不到 → `LdlibCompat.AVAILABLE = false`，整块功能关闭（静态块只解析一次并缓存，不会每次点击抛异常）。
- `CrateTransfer` 只依赖原版类型，不引用 LDLib / GTOCore：将来 LDLib 改类名，坏的也只是「定位鼠标下槽位」。
- 服务端单次请求最多处理 `MAX_SLOTS_PER_REQUEST = 2048` 个槽位（`CrateTransfer.java:45`），连续 `MAX_CONSECUTIVE_IDLE = 8` 次搬不动就提前结束（`:51`、`:127`）。
- `CrateTransferMode` 的 ordinal 就是线路标识：**改动枚举顺序要同步提升协议版本**（`CrateTransferMode.java:28`）。
- `debugLog` 对本模块无效——crate 路径没有任何 `GtoMiniFixConfig.debugLog` 日志，只有 `LdlibCompat` 的 slf4j `LOG.debug`（受日志级别控制）。
