# ae2 —— AE2 快速倒空

一句话：AE2 里要把背包里的流体容器（桶 / 流体单元 / 罐）灌进 ME 网络，原版必须先「用鼠标拿起容器 → 对着终端界面右键」；本模块让「按住 Ctrl + 右键背包里的容器」一步完成，按住拖动可整排倒。给在 ME 终端前反复倒桶的玩家用。

## 症状 / 为什么需要它

- 满桶 / 流体单元躺在背包里，要灌进 ME 网络只有一条路：先左键把它拿到鼠标上，再对着终端界面右键，倒完还得放回原槽。
- 一排 36 个容器就要重复 36 次「拿起 → 右键 → 放回」，中间任何一次忘了放回，下一次右键就变成**交换**，整排背包被搅乱。
- 本模块把这三下压成一次：按住 Ctrl + 右键背包里的容器 = 拿起 → 让 AE2 自己倒 → 放回；按住拖动时每个新碰到的容器各倒一次。
- 只认**玩家背包**的槽；终端自己的槽位（视图元件之类）不参与。
- 流体收不收、收多少全部由 AE2 自己算 —— 本模块不碰数量、不判断目标网络收不收、也不新增网络协议。

## 根因（证据）

AE2 侧只有两条路能触发「倒空」（对着内嵌 AE2 的字节码核过，jar 是 GTOCore 的 jar-in-jar `META-INF/jarjar/appliedenergistics2-forge-1.20.1-15.2610.2.jar`；它是 GTO 的分叉版，多一个 `gto_ae` 包）：

- **路 A：配置槽。** `appeng.client.gui.AEBaseScreen#slotClicked` 里，右键且 `getEmptyingAction(Slot, ItemStack) != null` 时发 `InventoryActionPacket(InventoryAction.EMPTY_ITEM, slotId, 0L)`；服务端 `appeng.menu.AEBaseMenu#doAction` 只对「`AppEngSlot` + `appeng.util.ConfigMenuInventory` 且其 delegate 的 `GenericStackInv.getMode() == Mode.STORAGE`」的槽生效。这条要求「目标是一个配置槽」，和玩家要的「对着界面右键」不是一回事。
- **路 B：ME 终端网格（本模块走这条）。** `gto_ae.hooks.gui.menu.IRepoSlot#handleGridInventoryEntryMouseClick(MEStorageMenu, int, ClickType, boolean)` 的 default 实现：`mouseButton == 1 && type == ClickType.PICKUP && !menu.getCarried().isEmpty()` 且 `ContainerItemStrategies.getEmptyingAction(menu.getCarried()) != null` 且 `menu.isKeyVisible(action.what())` → `menu.handleInteraction(-1L, InventoryAction.EMPTY_ITEM); return;`。**serial 是 -1，说明它根本不需要某个具体格子**，要的只是「鼠标上有流体容器」。
- 后续链路：`appeng.menu.me.common.MEStorageMenu#handleInteraction(long, InventoryAction)` 客户端分支只发 `MEInteractionPacket(containerId, serial, action)`；服务端 `serial == -1` → `handleNetworkInteraction(player, null, action)` → `EMPTY_ITEM` → `handleEmptyHeldItem(...)`，里面用 `ContainerItemStrategies.findCarriedContext(...)` 读**服务端菜单的鼠标手持物**。
- 结论：只要在客户端把容器放到鼠标上、再发一个 `serial = -1` 的 `EMPTY_ITEM`，服务端就会自己算；搬运逻辑一行都不用写。

## 实现

- 挂载点（纯客户端）：`src/main/java/com/lai/gtominifix/ae2/Ae2EmptyShortcuts.java`，`@Mod.EventBusSubscriber(modid = GtoMiniFix.MOD_ID, value = Dist.CLIENT, bus = FORGE)`；`onMousePressed`（`ScreenEvent.MouseButtonPressed.Pre`，`:82-121`）与 `onMouseDragged`（`ScreenEvent.MouseDragged.Pre`，`:130-158`）都是 `priority = EventPriority.HIGHEST`，`onMouseReleased`（`:160-165`）只清 `HANDLED`。
- 判据链（全满足才动手，任一不满足就原样交给原版）：右键 `RIGHT_BUTTON = 1` → `GtoMiniFixConfig.ae2EmptyingEnabled` + `Ae2Compat.isAvailable()`（`:91`）→ `isHotkeyDown()`（`:95`）→ `screen instanceof AbstractContainerScreen` → `Ae2Compat.isGridInteractionMenu(menu)`（`:102`，判据是接口 `appeng.menu.me.common.IMEInteractionHandler`，不是具体菜单类）→ `menu.getCarried().isEmpty()`（`:105`）→ 鼠标下是玩家背包槽 → `Ae2Compat.hasEmptyingAction(slot.getItem())`（`:180`）。
- 三个包，同一 tick 内（`simulateEmptying`，`:175-207`）：① `mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, 0, ClickType.PICKUP, mc.player)`（`:187`）把容器拿到鼠标上 —— 它会**本地预测**，返回时 `menu.getCarried()` 已经是那个容器；② `Ae2Compat.requestEmptying(menu)`（`:196`）→ 反射 `IMEInteractionHandler.handleInteraction(-1L, InventoryAction.EMPTY_ITEM)`（`Ae2Compat.java:151`）；③ 再来一次 `PICKUP`（`:204`）把空容器放回原槽。三个包都走原版 / AE2 自己的入口，在同一条 TCP 连接上按发送顺序到达服务端。
- **第 ③ 步不能省**：容器留在鼠标上，拖到下一格时原版会把「鼠标上的东西」和「目标槽里的东西」**交换**，整排背包会被搅乱。放回之后每个容器都是独立的「拿起 → 倒 → 放回」，拖动才等价于把单击重复一遍。
- **接管规则**：只要发过包就 `event.setCanceled(true)`（`simulateEmptying` 返回 `true`），哪怕第 ② 步失败、只能把手持物放回（`:199-200`）也一样；只有「还没动过任何状态」（不是流体容器 / 菜单不对 / 压根没拿起东西）才放给原版 —— 否则原版会拿同一份菜单状态再点一次。
- 反射层 `src/main/java/com/lai/gtominifix/ae2/Ae2Compat.java`：静态块一次性 `Class.forName` 解析 3 个类 / 2 个方法 / `EMPTY_ITEM` 枚举常量并缓存（`:63-89`），`ReflectiveOperationException | RuntimeException | LinkageError` 一起吞（`:85`；缺依赖类抛的是 `Error`，只 catch `Exception` 会从 GUI 事件里冒出去、最坏崩游戏），失败即 `AVAILABLE = false`，之后所有调用直接返回兜底值。
- **可用性检查必须放游戏内**：`Ae2Compat` 的静态块会触发 AE2 那几个类的静态初始化，模组构造器时机太早；`GtoMiniFix` 构造器里只比对 ModList 的字符串 id `AE2_MOD_ID = "ae2"`（`src/main/java/com/lai/gtominifix/GtoMiniFix.java:59`）。
- **不新增网络消息**：服务端不需要认识本模块，`ModNetwork.PROTOCOL_VERSION` 没动（仍是 `"2"`，`src/main/java/com/lai/gtominifix/network/ModNetwork.java:25`），AE2 这块没有注册任何消息 → 单人 / 多人都不用同步版本。

## 配置

COMMON 配置（`src/main/java/com/lai/gtominifix/GtoMiniFix.java:64` 注册 `ModConfig.Type.COMMON`）的 `[ae2]` 段：

| 键 | 默认 | 说明 |
|---|---|---|
| `ae2.emptyingEnabled` | `true` | 「按住快捷键 + 右键背包里的流体容器 = 把流体倒进 ME 网络」的总开关 |
| `ae2.emptyingKey` | `ctrl` | 要按住的键。可写 `ctrl` / `shift` / `alt`（左右两侧都算），也可写 `InputConstants` 键名（如 `key.keyboard.v`）；英文逗号分隔可配多个，任一按住即生效 |

## 怎么验证它在工作

- `/gtominifix config` 的 ae2 行：`ae2: emptyingEnabled=<bool>, emptyingKey=<str>, AE2=可用|不可用（功能关闭）`（`src/main/java/com/lai/gtominifix/command/GtoMiniFixCommand.java:60-64`）。`AE2=` 来自 `Ae2Compat.isAvailable()`，比 ModList 更准：它同时说明「类在场」和「本模块要用的成员都对得上」。
- AE2 不可用时启动日志里有一行 `[gtominifix] AE2 不可用（…），终端倒空流体容器功能整体关闭。`（`Ae2Compat.java:86`）；启动日志另有 `[gtominifix] 已加载；GTOCore …，LDLib …，Functional Storage …，AE2 在场/不在场`（`GtoMiniFix.java:80`）。
- 游戏内：开 ME 终端，鼠标空手，Ctrl + 右键背包里的满桶 → 桶变空并回原槽，网络里多出那一桶。不按 Ctrl 时行为应与原版完全一致（右键只做拿起 / 放下）。
- `debugLog = true` 时每次成功倒空打一行 `已模拟倒空槽位 {} 的流体容器`（`Ae2EmptyShortcuts.java:205`）。

## 实测清单

- [ ] ME 终端里，鼠标空手、Ctrl + 右键背包里的满流体容器 → 流体进网络，容器变空、回到原槽
- [ ] 同上但不按 Ctrl → 与原版一致（右键只做拿起 / 放下）
- [ ] Ctrl + 右键一个非流体物品（石头等）→ 与原版一致，本模块不介入
- [ ] 鼠标上已经拿着东西时 Ctrl + 右键背包容器 → 本模块不介入（避免变成交换）
- [ ] 按住 Ctrl 右键不放，拖过一排流体容器 → 每个新碰到的容器各倒空一次，且只倒一次
- [ ] 不按 Ctrl 时按住右键拖动 → 与原版拖动分堆完全一致，本模块不介入
- [ ] 在普通箱子 / 背包界面（不是 AE2 终端）上按 Ctrl + 右键 → 与原版一致
- [ ] 合成终端 / 无线终端等其它 AE2 界面 → 同样生效（判据是菜单实现 `IMEInteractionHandler`）
- [ ] 多人服务器 → 客户端和服务端都装同版本；协议版本没变，仍可与旧版本服务端连
- [ ] 卸载 / 关掉 AE2 → 模组正常加载，功能整体关闭，`/gtominifix config` 的 `ae2` 行显示「不可用（功能关闭）」

## 已知边界与坑

以下行号除注明外均指 `src/main/java/com/lai/gtominifix/ae2/Ae2EmptyShortcuts.java`。

- 只认玩家背包槽：`slot.container instanceof net.minecraft.world.entity.player.Inventory`（`:220`），终端自己的槽位不参与。
- 鼠标坐标自己算，不读 `AbstractContainerScreen#hoveredSlot`：那个字段由鼠标移动事件维护，在点击这一刻可能还是上一次移动的旧值；这里用 `screen.getGuiLeft()` / `getGuiTop()` + `slot.x` / `slot.y` 重算 16×16 命中（`:216-229`）。
- `HANDLED` 去重与拖动门槛：`HANDLED` 只在右键按下时 `clear()`（`:90`），左键按下不清（`:84-88` 直接 return）；拖动时以 `HANDLED.isEmpty()` 判断「这一轮是不是本模块接管的」（`:132`）—— 按下时没被接管，整轮拖动就完全不介入，不会破坏原版「拖动分堆」。
- 命中判据只认「有可倒空的流体」：`ContainerItemStrategies.getEmptyingAction(stack) != null` 才算流体容器（`:180`），否则这一下原样交给原版，不会抢走玩家正常右键拿起物品的操作。
- 必须 `EventPriority.HIGHEST`：可取消事件一旦被别的模组先 cancel，排在后面、没标 `receiveCanceled = true` 的 handler 根本不会被调用。
- 客户端最多一 tick 的视觉残留：客户端不知道服务端已经把容器倒空了，第 ③ 步是按「满容器」预测的，那一格在客户端最多一 tick 显示成满容器，服务端下一次 `broadcastChanges` 就纠正成空容器。数量不会错（服务端才是权威）。
- 快捷键解析结果被缓存（`HOTKEY_CODES` / `parsedKeyName`，`:252-268`），只在 `ae2EmptyingKey` 字符串变化时重解析；解析不出任何按键会打一行 WARN 并按「未配置」处理（`:264`）。
- 反射按字面名字取 AE2 的**模组**类（Forge 只重映射 MC 的类，模组自己的类名 / 方法名在 dev 和生产是同一套）；AE2 改类名 / 方法名 / 删掉 `EMPTY_ITEM` → 静态块解析失败，整块功能静默关闭（`/gtominifix config` 显示「不可用（功能关闭）」），不会崩游戏。
