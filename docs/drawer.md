# drawer —— 抽屉批量填充

一句话：给 Functional Storage 的一格 / 两格 / 四格物品抽屉补上「Ctrl + Shift + 左键 = 一次尽量填满玩家背包」，只做修复、不抢地盘。

## 症状 / 为什么需要它

- Functional Storage 自带只有两种取法：左键取 1 个、Shift + 左键取**整格全部**（一格几千个也一次全取）。
- 想搬进背包只能按住 Shift 长按左键，每约 3 tick 取一次整格；背包一满，多出来的部分就被 `ItemHandlerHelper.giveItemToPlayer` 当掉落物丢在脚下（`ItemHandlerHelper.java:165,176-178`）。
- 于是「把抽屉搬进背包」在几千存量下变成：慢，而且**地上堆一大片掉落物**。
- 本模组补第三条路：**Ctrl + Shift + 左键 = 把被点的那一格尽量塞满主背包 27 格 + 快捷栏 9 格**（盔甲 / 副手不动），先补同类堆、再占空格；**一个都塞不下就整次点击什么都不做**，绝不掉地上。

## 根因（证据）

> 行号约定：`MultiPlayerGameMode.java` / `ServerPlayerGameMode.java` 是 Forge 1.20.1-47.4.20 的 MC 源码，`ItemHandlerHelper.java` 是 Forge 自身源码（都在 `~/.gradle/caches/forge_gradle/minecraft_user_repo/.../forge-1.20.1-47.4.20_mapped_official_1.20.1-sources.jar`）；其余 `Xxx.java:NN` 是本仓库 `src/main/java/` 下的文件。

**1. 取物入口与真实实现**（对实例实际安装的 `functionalstorage-1.20.1-1.2.14.jar` + `titanium-1.20.1-3.8.35.jar` 字节码核过）

- 取物入口在 `FunctionalStorage` 构造器里注册的 `PlayerInteractEvent.LeftClickBlock`（`EventManager.forge(LeftClickBlock.class)`，`EventPriority.NORMAL` + `Bus.FORGE`）：`block instanceof Drawer` → `Drawer#getHit` 得格子下标 → 非 -1 就 `TileUtil.getTileEntity(level, pos, ControllableDrawerTile.class)` → `onClicked(player, hit)` + `event.setCanceled(true)`。
- 真正实现在 `ItemControllableDrawerTile#onClicked`（基类 `ControllableDrawerTile` 那个是空方法）：`if (isServer() && slot != -1 && removeTicks == 0)` → `removeTicks = 3` → **16 格**射线确认命中面 == 抽屉正面 → `storage.extractItem(slot, player.isShiftKeyDown() ? 该格整数量 : 1, false)` → `ItemHandlerHelper.giveItemToPlayer`（**背包满时把多余的丢在地上**）。
- 「Shift 长按固定频率往外取」的真相：客户端每 tick 重发 START_DESTROY_BLOCK（`continueDestroyBlock` 因 `isDestroying` 未置位而落到 `MultiPlayerGameMode.java:243` 的 `return this.startDestroyBlock(...)`），服务端每 tick 被事件挡一次，再被 `removeTicks = 3` 限成约 3 tick 一次。

**2. ★ 客户端取消 LeftClickBlock 拦不住这次点击**（最容易想错的一步）

Forge 补丁后的 `MultiPlayerGameMode#startDestroyBlock`（`MultiPlayerGameMode.java:153-178`，逐行核过）：

```java
LeftClickBlock event = ForgeHooks.onLeftClickBlock(player, pos, dir, START_DESTROY_BLOCK);   // :153
startPrediction(level, seq -> {
    ServerboundPlayerActionPacket packet = new ServerboundPlayerActionPacket(START_DESTROY_BLOCK, ...); // :164
    if (event.getUseItem() == Result.DENY) return packet;                                   // :165
    ... this.isDestroying = true; ...                                                       // :169
    return packet;                                                                          // :177
});
```

**这段代码从头到尾没有读 `event.isCanceled()`**（只读 `getUseBlock()` / `getUseItem()` 的 `DENY`）；packet 在 :164 就已造好，:177 无条件 `return` 出去，`startPrediction` 随即 `connection.send(packet)`。所以客户端 `setCanceled(true)` 只让客户端不置 `isDestroying`、不画破坏裂纹，**包照样发**。服务端才认取消：`ServerPlayerGameMode#handleBlockBreakAction`（`ServerPlayerGameMode.java:127-130`）

```java
LeftClickBlock event = ForgeHooks.onLeftClickBlock(player, pos, dir, action);               // :127
if (event.isCanceled() || (!this.isCreative() && event.getResult() == Result.DENY)) return; // :128
```

→ **只有取消服务端那次 post 才有用**；在客户端取消等于没取消，服务端照常 post、Functional Storage 照常取 1 个。

**3. 存储与格子定位**

- 存储走标准能力，不反射：`DrawerTile#getCapability` 对 `ForgeCapabilities.ITEM_HANDLER` 直接 `return lazyStorage.cast()`（`lazyStorage` 是 `LazyOptional<IItemHandler>`，底下的 `public BigInventoryHandler handler` 就是那个抽屉存储），其它能力才 `super.getCapability(...)`。方向和是否接存储控制器都不影响。
- 格子下标必须问 Functional Storage 自己：`DrawerBlock#getHit` = 打 32 格射线 → `rayTraceVoxelShape`（同样 32 格）→ 和 `CACHED_SHAPES[type][state.getValue(RotatableBlock.FACING_HORIZONTAL)]` 里的子碰撞箱逐个 `Shapes.joinIsNotEmpty` 求交 → 返回第一个命中的下标，没命中返回 -1。两格 / 四格抽屉的划分完全由它决定，自己重写一份迟早和那套带旋转的形状表漂移。这一处走反射（`FunctionalStorageCompat`）。

**4. 背包容量两个坑**（字节码核实）

- `Inventory#getContainerSize()` 返回 **41**（36 + 盔甲 4 + 副手 1），当上界会把盔甲槽也算进去 → 固定用 36。
- `Inventory` **没有覆写** `getMaxStackSize()`，继承 `Container` 的默认值 **64**（`bipush 64; ireturn`）→ 读 `inventory.getMaxStackSize()` 而不写死。

## 实现

挂载点：客户端 `drawer/DrawerShortcuts.java`、服务端 `drawer/DrawerFill.java`、反射适配 `drawer/FunctionalStorageCompat.java`、网络包 `network/DrawerFillMessage.java`。

- **客户端只报意图**：`DrawerShortcuts`（`@Mod.EventBusSubscriber(modid = GtoMiniFix.MOD_ID, value = Dist.CLIENT, bus = Bus.FORGE)`）的 `onLeftClickBlock`（`DrawerShortcuts.java:41-65`，`HIGHEST`）依次判 `isClientSide()` → `GtoMiniFixConfig.drawerFillEnabled` + `FunctionalStorageCompat.isAvailable()` → Ctrl+Shift（`isControlDown()` / `isShiftDown()`，统一走 `InputConstants.isKeyDown`）→ `isDrawerBlock`，然后 `ModNetwork.CHANNEL.sendToServer(new DrawerFillMessage(pos))` + `event.setCanceled(true)`。**不搬任何物品**（客户端方块实体是空壳）。
- **服务端必须手动注册**：`GtoMiniFix.java:73` 的 `MinecraftForge.EVENT_BUS.register(DrawerFill.class)`。`DrawerFill` 没有 `@Mod.EventBusSubscriber`，漏掉这行的症状是「按了 Ctrl+Shift 和普通点击一模一样、日志一个字都没有」——**改这块先确认这行还在**。
- **一次性请求**：`DrawerFill.arm`（`DrawerFill.java:106-114`）查 `isAvailable()` 和 `distanceToSqr(...) > MAX_ARM_DISTANCE_SQR` 后 `ARMED.put(player.getUUID(), new Armed(pos.immutable(), level.getGameTime()))`；`ARMED` 是 `Map<UUID, Armed>`，每个玩家最多一条（新的覆盖旧的），登出时 `onPlayerLoggedOut` 清掉。
- **兑现**：`DrawerFill#onLeftClickBlock`（`DrawerFill.java:122-157`，`HIGHEST`）先挡掉客户端 / 非 `ServerPlayer` → `ARMED.remove(player.getUUID())`（**无论对不对得上都先取走**，否则过期前落在同一抽屉上的普通点击会被劫持）→ 坐标相等且 `level.getGameTime() - armed.tick() <= ARM_WINDOW_TICKS` → `isDrawerBlock` → **先 `event.setCanceled(true)` 再干活** → `findHitSlot`（返回 -1 时取消点击就够了，没有东西可取）→ `fill`。
- **优先级必须高于 Functional Storage**：Forge 分发可取消事件时，一旦被 cancel，后面没标 `receiveCanceled = true` 的 handler 不再被调用；Titanium 的 `subscribe()` 调的是 `bus.addListener(priority, false, ...)`（第二个参数即 `receiveCanceled = false`，字节码核实），所以 `HIGHEST` 一定抢在它的 `NORMAL` 前面，抢到后自己取消，把「取一个 / 取一组」整个挡在后面。
- **顺序有保证**：请求包在事件回调里发（`MultiPlayerGameMode.java:153` 的 post 之内），点击包在紧随其后的 `startPrediction`（`:157`）里发、由 `:251` 的 `connection.send` 送出，同一个客户端 tick、同一条连接、按序到达，服务端也在同一个任务队列里按序处理。
- **填充**（`DrawerFill#fill`，`DrawerFill.java:176-216`）：`getCapability(ForgeCapabilities.ITEM_HANDLER, null)` → `extractItem(slot, Integer.MAX_VALUE, true)` 模拟问一次 → `countRoom`，`room <= 0` 直接返回（**什么都不取，也不让 FS 去取那 1 个**）→ `extractItem(slot, room, false)` 真取 → `moveIntoInventory` → 剩余（理论到不了）才 `ItemHandlerHelper.giveItemToPlayer` → `player.containerMenu.broadcastChanges()` + `player.getInventory().setChanged()`。
- **不变量**：`PLAYER_MAIN_SLOTS = 36`（只碰 `Inventory` 的 0..35）；堆叠上限 = `Math.min(inventory.getMaxStackSize(), stack.getMaxStackSize())`；`moveIntoInventory` 两轮扫描（先补同类堆、再占空格，同原版 `addResource`），且显式 `setItem` 写回，不对 `getItem()` 的返回值原地 `grow`（返回副本时物品会凭空消失）。
- **没装 Functional Storage 时**：`FunctionalStorageCompat` 静态块解析 `com.buuz135.functionalstorage.block.Drawer` / `...block.DrawerBlock` 与 `getHit(BlockState, Level, BlockPos, Player)`，失败即 `AVAILABLE = false` 并打一行 `[gtominifix] 未找到 Functional Storage（...），抽屉填包功能整体关闭。`；`isAvailable()` 为 false 后所有入口第一行就返回。

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `drawer.fillEnabled` | `true` | 「Ctrl + Shift + 左键 = 尽量填满玩家背包」的总开关。Java 字段 `GtoMiniFixConfig.drawerFillEnabled`（`GtoMiniFixConfig.java:196`），配置节 `drawer`（`GtoMiniFixConfig.java:128`） |

抽屉侧只有一行 `LOG.debug`（`FunctionalStorageCompat.java:110` 的「定位抽屉格子失败」），**不受 `debugLog` 控制**（`GtoMiniFixConfig.java:176-178` 那个根层级 `debugLog` 是给 teleport / ae2 用的）；且本机实例 SLF4J 落不了盘，别指望它。

## 怎么验证它在工作

- `/gtominifix config`（需 OP）看 `drawer:` 那行（`GtoMiniFixCommand.java:55-58`）：期望 `drawer: fillEnabled=true, FunctionalStorage=可用`。显示 `不可用（功能关闭）` = 反射解析失败（类不在场或成员签名对不上），比查 `ModList` 更准。
- 游戏内：对着抽屉**正面**按住 Ctrl + Shift 左键点一下，看背包被填满、抽屉对应格减数、地上没有掉落物。

## 实测清单

- [ ] 1. Ctrl+Shift+左键点一格抽屉 → 抽屉里的东西进背包直到装满；背包里没有多余的单件掉落物
- [ ] 2. 同上但背包半满且有同类堆 → 先补满同类堆，再占空格
- [ ] 3. 同上但背包已经全满 → 什么都不发生，**不会**有 1 个物品掉在地上
- [ ] 4. 两格 / 四格抽屉，分别点上下 / 四个象限 → 只取被点的那一格，其它格不动
- [ ] 5. 不按 Ctrl+Shift 的普通左键 → 行为与不打本模组时一致（取 1 个 / Shift 取一组）
- [ ] 6. Ctrl+Shift+左键点抽屉的侧面 / 背面 → 抽屉不会被挖掉，也不会取出东西
- [ ] 7. 对着抽屉按住 Ctrl+Shift 不放 → 持续补满（第一下装满后基本无变化），不会疯狂掉物品
- [ ] 8. 末影抽屉 / 压缩抽屉 / 流体抽屉上按 Ctrl+Shift+左键 → 本模组不介入，行为与不打时一致
- [ ] 9. 接在存储控制器上的抽屉 → 正常填满
- [ ] 10. 卸载 Functional Storage → 模组正常加载，功能整体关闭，日志里有一行「未找到 Functional Storage」
- [ ] 11. 多人服务器上 → 客户端和服务端都装同版本（`ModNetwork.PROTOCOL_VERSION = "2"`，`ModNetwork.java:25`）

## 已知边界与坑

- **取消服务端这次点击 = 这次点击不会挖掉抽屉**，有意为之；副作用是**同一优先级链上排在后面的 handler 也看不到这次点击**，包括靠 `LeftClickBlock` 拦截破坏的防护类模组（领地 / 反破坏）。影响范围仅限「玩家按着 Ctrl+Shift 点了抽屉正面 / 侧面」，而那种情况本来也不产生破坏。把优先级从 `HIGHEST` 降下来能让位，但 Functional Storage 自己会先取走 1 个 / 一整格，功能就不成立——这个取舍必须付。
- **不接管压缩 / 末影 / 流体抽屉**：实现 `Drawer` 接口的还有 `CompactingDrawerBlock` / `SimpleCompactingDrawerBlock` / `EnderDrawerBlock` / `FluidDrawerBlock`，**都不是 `DrawerBlock` 的子类**；`FramedDrawerBlock extends DrawerBlock` 跟着一起生效。它们的「格子」语义不是并列槽位，硬套「取第 N 格」只会给出看起来像 bug 的行为。
- **反射靠字面名字**：`Drawer` 接口上只有 `getHit(BlockState, Level, BlockPos, Player)` 这一个方法，上游改签名就只能静默关闭（`isAvailable()` 变 false + 日志一行）。静态初始化里连 `LinkageError` 一起吞，是为了「装了但版本对不上」也不留缺口。Functional Storage 是模组类，没有 SRG 名，开发 / 生产同一套名字，所以字面反射是稳的。
- **请求窗口只有 4 tick**（`ARM_WINDOW_TICKS = 4`，`DrawerFill.java:87`）：更长会让「请求」在玩家已改主意之后仍生效；更短可能被服务端任务队列抖动甩掉。
- **按住不放**时客户端每 tick 发一个请求包，和 Functional Storage 自己那个 START_DESTROY_BLOCK 同频；第一下装满后服务端 `room <= 0` 直接早退，开销可忽略。
- **命中面没有独立校验**：`DrawerFill` 不像 FS 的 `ItemControllableDrawerTile#onClicked` 那样比对 `getFacingDirection()`，只靠 `findHitSlot` 返回 -1 兜底（`DrawerFill.java:153-155`）。侧面 / 背面点会不会误填，**取决于 `DrawerBlock#getHit` 的形状表，未实测**（清单第 6 条）。
- 坐标校验 `MAX_ARM_DISTANCE_SQR = 64.0`（`DrawerFill.java:90`）= 8 格；被改过的客户端最多只能提前声明意图，声明错了（位置不是抽屉、超距）什么都不发生。
