# GTO 内部速查

要查 GTO / GTCEu / AE2 内部怎么实现时**先看这一页**；单个功能的结论写在 `docs/<功能>.md` 里。
所有行号都是**本实例实际 jar 反编译出来的**，换版本会漂。

## 实例与 jar 布局

- 实例根：`C:\Users\PC\AppData\Roaming\PrismLauncher\instances\GregTech.Odyssey-0.6.0\`
  - `.minecraft\mods\` —— 游戏实际加载的 166 个 jar
  - `.minecraft\_mymods\mods\` —— 另一份副本，**不参与加载**（只是备用堆放）
- 与本模组相关的 jar：

  | jar | 说明 |
  |---|---|
  | `gtocore-forge-1.20.1-26.10.5.jar`（57.4 MB） | GTO 本体，内含下面的 jar-in-jar |
  | `ldlib-forge-1.20.1-1.0.52.a.jar` | LDLib（`crate` 依赖） |
  | `functionalstorage-1.20.1-1.2.14.jar` | 抽屉（`drawer`） |
  | `inventoryessentials-forge-1.20.1-8.2.19.jar` | Inventory Essentials（`crate` 兼容对象） |
  | `leanobject-1.20-26.9.2-forge.jar` | 内含 `FastCollection-26.8.7.jar` → `com.gto.fastcollection.*` |

- **实例里没有独立的 gtceu jar**：它是 gtocore 的 jar-in-jar
  `META-INF/jarjar/gtceu-1.20.1-forge-1.20.1-26.10.41.jar`（13.2 MB）。
  同目录还有 `appliedenergistics2-forge-1.20.1-15.2610.x.jar`（GTO 自己的 AE2 分叉，多出 `gto_ae` 包）
  与 commons-math3。`tools\decompile.cmd` 会自动把它们抽到 `tools\out\_jars\`。
- **GTOCore 源码在本地**（**比实例旧**：`gradle.properties` 写 0.5.7-beta / gtceu 26.7.4）：
  `E:\Program Files\Programming\Minecraft\GTOCore-Main-main\src\main\java`
  用法：查 `com.gtocore.*` 的实现与**方法名**时优先读它（快、有注释）；
  要**精确行号或确认实例行为**必须反编译实例 jar。`com.gregtechceu.gtceu.*`（gtceu 分叉）**不在这份源码里**。

## 反编译

```powershell
tools\decompile.cmd -List                    # 列出 mods 与 gtocore 内嵌的 jar
tools\decompile.cmd gtceu pipelike\fluid     # 按包名片段
tools\decompile.cmd gtceu com.gregtechceu.gtceu.common.pipelike.fluid.FluidPipeNet   # 按完整类名
tools\decompile.cmd gtocore eio_travel       # GTOCore 自己的包
tools\decompile.cmd functionalstorage com.buuz135.functionalstorage.block.tile
tools\decompile.cmd gtceu fluid -Javap       # 顺便 javap -c（看描述符/字节码）
```
- 产物 `tools\out\<jar 名>\<目标路径>\*.java`；`-Force` 重新抽内嵌 jar。
- 只找某个方法/字段时用 `javap -p -s` 更快，不必整包反编译。
- 反编译产物是**临时品**，`tools\out\` 不进 git，可以随时删。

## 原版 / Forge 源码（不用反编译，直接读）

本机 gradle 缓存里有**反混淆过的 MC 1.20.1 + Forge 源码 jar**，要核原版行号就抽它：

```
C:\Users\PC\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\
    1.20.1-47.4.20_mapped_official_1.20.1\forge-1.20.1-47.4.20_mapped_official_1.20.1-sources.jar
```

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = "C:\Users\PC\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.4.20_mapped_official_1.20.1\forge-1.20.1-47.4.20_mapped_official_1.20.1-sources.jar"
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
$e = $zip.Entries | Where-Object { $_.FullName -eq "net/minecraft/world/entity/player/Player.java" }
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, "temp\mcsrc\Player.java", $true)
$zip.Dispose()
```

已核实的原版锚点：

- `Player.java:213` `tick()`；`:241` `super.tick()`（→ `LivingEntity.baseTick`，`:347` 处 `if (this.isInWall())` 就是窒息扣血点）；`:284` `this.updatePlayerPose()`。
  → **扣血判定在姿态更新之前**，这就是「落地那一 tick 眼睛还留在上方地板里、判成 `in_wall`」的成因。
- `LivingEntity.java:3257` `isInWall()`（`!isSleeping() && super.isInWall()`）。
- `Inventory.java:445-447` `getContainerSize() { return items.size() + armor.size() + offhand.size(); }` = 36 + 4 + 1 = **41**（玩家背包，`crate`/`drawer` 的格子判据靠这个数）。

## 类地图

| 想知道什么 | 看哪里 |
|---|---|
| 流体管道传输 | `com.gregtechceu.gtceu.common.pipelike.fluid.{FluidPipeNet,FluidNetHandler,FluidNetWalker,FluidPipeBlockEntity,FluidRoutePath}` |
| 物品 / 线缆 / 激光 / 光学管道 | `com.gregtechceu.gtceu.common.pipelike.{item,cable,laser,optical}` |
| 管道基类、网络遍历 | `com.gregtechceu.gtceu.api.pipenet.{PipeNet,LevelPipeNet,PipeNetWalker,Node}`、`common.blockentity.PipeBlockEntity` |
| 共享可变游标 | `com.gto.fastcollection.LoopIterator`（**在 leanobject 的 jar-in-jar 里，不在 gtceu jar 里**） |
| 机器 / 仓 / 能力桥接 | `com.gregtechceu.gtceu.api.machine.*`、`common.machine.*`、`api.transfer.key.{IKeyHandler,KeyTransfer,KeyTarget}` |
| AE2（含 GTO 分叉） | `appeng.*` + `gto_ae.*` |
| GTOCore 自己的管道 | `com.gtocore.common.pipe.{heat,mana,muffler,universal}` |
| GTOCore 旅行手杖 | `com.gtocore.eio_travel.logic.TravelHandler`、`TravelUtils`、`implementations.*TravelTarget` |
| GTO 配置 | `GTOConfig.INSTANCE.*`（例：`travelConfig.blinkRange`） |

## 已核实的锚点

### 旅行手杖（gtocore 26.10.5 反编译）

- `TravelHandler.java:53` `shortTeleport(Level, Player)`：`:54` 取 `teleportPosition`；`:56` 只在 `ServerPlayer` 分支真正传送；
  `:57` 调 `teleportEvent(player, pos)`（Forge 事件，方法体在 `:250`）；`:59` `player.teleportTo(...)`；
  `:60` `serverPlayer.connection.teleport(...)`；**`:61` `player.f_19789_ = 0.0F`（= `fallDistance = 0`）**；
  `:62` 播传送音效。
- `TravelHandler.java:124` `teleportPosition(Level, Player)`：`:131-132` 用 `ClipContext(..., Block.OUTLINE, Fluid.NONE, null)` + `level.clip(...)` 打射线；
  `:133-147` 命中面分三种（UP → 命中方块本身；DOWN → `hit.getBlockPos().above(ceil(player.getBbHeight()))`；
  侧面 → 侧向偏移一格，若该格碰撞箱为空则取 `target.below()`）。
- `TravelHandler.java:149-166`：**射线命中点距眼睛 < 3 格**（`distanceToSqr < 9.0`）时用
  `BlockGetter.traverseBlocks(traverseFrom, toPos, clipCtx, callback, failCtx -> failPosition)` 重找落点；
  回调 `:155` **先**试 `traversalCheck(level, traversePos.below())`（`aimingUp` 时跳过），`:161` 再试 `traversalCheck(level, traversePos)`。
- `TravelHandler.java:180-185` `traversalCheck(Level, BlockPos)`：该格碰撞箱为空**且**
  `isTeleportPositionClear(level, traversePos.below()).isPresent()` 才返回 `traversePos`。
- `TravelHandler.java:169` `isTeleportPositionClear(level, target.below())`；取不到高度就 `:173 target = null`；
  `:177` 还要 `player.blockPosition().distManhattan(target) >= 2`。
- `TravelHandler.java:234` `isTeleportPositionClear(BlockGetter, BlockPos)`（有 `<= 0.2` 的早退，为的是能站到地毯上）。
- `TravelHandler.java:98-122` `blockTeleportTo(...)`（旅行锚 / 电梯路径）：`:99` 客户端直接 `Optional.of(2.0)`，
  服务端才真算；`:103-106` 造坐标并过 `teleportEvent`；`:111` `level.playSound(...)`。

### 流体管道与共享游标（gtceu 26.10.41 + FastCollection 26.8.7 反编译）

- `FluidPipeNet.java:22` `private final Long2ObjectOpenHashMap<LoopIterator<FluidRoutePath>> netData`；
  `getNetData(long pipePos, BlockPos pos, Direction facing)`：先 `netData.get(pipePos)`（**键只有 pipePos，没有 facing**），
  miss 才跑 `FluidNetWalker.createNetData(...)` 并 `put`。
- 清缓存只有三处：`FluidPipeNet.java:44` `onNeighbourUpdate(BlockPos)`、`:49` `onPipeConnectionsUpdate()`、
  `:58-59` `transferNodeData(...)` 里连父网一起清。→ **改过连接、放/拆管道、邻居方块更新都会清缓存**。
- `FluidNetHandler.fillFirst(AEFluidKey, long, boolean)`：simulate 分支直接遍历 `data.array`（安全），
  execute 分支 `for (FluidRoutePath inv : data)` 走共享游标；循环体 `amount -= fill; if (amount <= 0) break;`。
- `PipeNetWalker` 的邻居遍历对**成环 / 单向 / 被扳手阻塞**的邻居管道也会建路由，
  所以 `fill` 可能递归进另一根管道的 `fillFirst` → 共享游标被重入（这是 `pipe` 修的那个 bug）。
- `LoopIterator`（`com.gto.fastcollection`，在 leanobject 的 jar-in-jar 里）：
  `public final T[] array`、`public final int size`、`private int index`、`private int remaining`、
  `public LoopIterator(T[])`、`public static final LoopIterator EMPTY`；
  `iterator()` 返回 `this` 且**只重置 `remaining`、不重置 `index`** → 它自己的 Javadoc 写明
  「not safe for concurrent or nested iteration」。**换新游标的前提是消费方用 for-each**
  （流体/线缆是 for-each；物品管道只读 `.array` / `.size`，不受影响）。
- 另外两个 GTO 内部坑（本模组没修，遇到怪症状先怀疑它们）：
  - `FluidPipeBlockEntity.getTransferredFluids()` 这个 **getter 带副作用**（会按时间窗口重置限流计数）。
  - `BlockEntityDirectionCache` 一旦把邻居缓存成 `NULL` 哨兵就**永不重查**（只有缓存到的 BE 被移除才会重取）。
