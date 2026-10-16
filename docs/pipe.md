# pipe —— GTO 流体管道排查工具（修复已由上游完成）

一句话：**本模组不再对流体管道打任何修复补丁**。GTO `gtceu 26.10.53` 起上游已按我们提交的建议修掉了
「路由表按管道坐标缓存、内容却按入流面生成」这个缺陷（表与调用者无关 + 取表时剔除指回调用者自己的那条
路由），所以这里只剩**只读排查工具**和两个只观察的 CoreMod 注入点。

## 工具（全部只读）

- `/gtominifix pipetrace` —— 对着机器或管道（10 格内）执行，打印真实逻辑状态：机器段（订阅字段、自动 IO
  监视器、储罐每槽、正面）、逐面邻居缓存的原始槽值 vs 世界真值、handler 身份与其 `net`、逐面重新寻路列出的
  每条路由与「目标能不能收 1000 mB」（走 AE2 键层，和 `FluidNetHandler.fill` 同一条调用）、缓存表内容
  （`★紧贴本管道` = 源机器很可能就是它）、游标 `index` 停在哪。聊天栏只发游标与判读，其余写日志文件。
- `/gtominifix pipeprobe` —— Forge 能力层概览（自身流体能力、六向 `fill(SIMULATE)`、路由表条目数）。
- `/gtominifix pipewatch [秒]` / `pipewatch stop` —— 逐秒摘要 + 每 5 秒一份完整探针 + 每 5 秒一次
  「有货+能出+未订阅」巡视，用来抓「跑一段时间才坏」的那一刻。

探针**不改现场**：不调 `getNetData`（miss 时会建表写缓存）、不清 `netData`、不写任何字段。

## 监听：默认关，只在手动开启时有开销

两个只观察转换器注入到：

- `FluidHatchPartMachine#updateTankSubscription(Direction)` 头部 → `PipeNetNotify.onSubscriptionCheck`
- `FluidPipeBlockEntity#addTransferredFluids(int)` 头部 → `PipeNetNotify.onTransferredAdded`

**开关在 Java 侧**（coremod 沙箱读不到文件、`print()` 也进不了日志文件）：只有 `/gtominifix pipewatch`
运行期间 `PipeNetNotify.setTracing(true)`，`stop` / 到点 / 退出即自动关。不监听时这两个方法第一行就返回 ——
只剩「一次静态调用 + 一个布尔读」，无反射、无分配、无磁盘。

日志文件：`logs/gtominifix-debug.log`（本模组的 SLF4J 在部分实例里一个字都落不了盘，所以自己写文件；由
`pipe.debugFile` 控制，超 4 MB 轮转 `.1`）。只观察转换器只在 `pipewatch` 期间写 `[订阅] …`（状态翻转才写）
与 `[配额] …`（每管每秒一行）。

`/gtominifix config` 的 `pipe:` 行报监听状态（`PipeNetNotify.statusText()`），`pipeprobe` 的「本模组」段同源。

## 那个缺陷（已由上游修复，留档）

`FluidPipeNet#getNetData` 的缓存 `netData` 键只有管道坐标，而表内容是按入流面生成的
（`FluidNetWalker.checkNeighbour` 只排除「本次寻路的源管道 + 入流面」那一格），于是**谁先插入谁定表**，
同一格上的其他机器复用别人的表时会同时出现两种相反的错误：

1. **表里多一条指回调用者自己的路由**：它一推就把流体送回自己，按目标自报的接受量记满该格每秒配额
   （`FluidPipeBlockEntity.transferredFluids`，上限 = 路由吞吐 × 20）→ 下游收不到，而它自己液位不变，
   看起来就是「有货不推」。实测：六台终端超级缸旁的管道，6 面里 5 面的表把紧贴的缸排在第 0 条且
   `目标收(key,1000,模拟)=1000mB`（只有缸自己那一面把它排除）。
2. **表里少一条**（2026-10-15 实测）：建表者自己那一面的邻居被永久排除在这张六面共用的表之外。现场：
   重燃油超级缸在管道 `1604,65,5723` 上方、蒸馏室输入 `1604,65,5724` 在南面，表由从南面插入的调用者先建
   → 42 条路由里没有蒸馏室；缸从上面推时永远送不到它（该格每秒只记账几十~一百多 mB，配额是 24000）。

上游的修法（`gtceu 26.10.53`，与本模组提的建议一致）：

- `FluidNetWalker#createNetData(FluidPipeNet, BlockPos)` **删掉了 `sourceFacing` 参数**，`sourcePipe` /
  `facingToHandler` 两个字段整个消失，`checkNeighbour` 里那句排除整段移除 → 表与调用者无关；
- `FluidNetHandler#fillFirst` 的两个分支（simulate 走 `data.array`、execute 走游标）都加了
  `!this.isBackToSelf(inv)`，而 `isBackToSelf` = `routePath.getTargetPipe() == this.pipe &&
  routePath.getTargetFacing() == this.facing` → 只挡调用者自己那一面，不影响别的机器往里送；
- `FluidPipeNet#getNetData(long, BlockPos)`（少了 `Direction` 参数）；`createNetData` 还加了 try/catch 与
  `error while create net data for FluidPipeNet` 日志。

## 历史：不要再走的路

1. **Mixin 补丁**（`FluidPipeNetMixin` / `BlockEntityDirectionCacheMixin` / `TravelHandlerMixin`）—— 在本实例里
   **从未被 select**，一次都没跑过，已全部删除。原因（Mixin 0.8.5 源码）：`Mixins.addConfiguration` 在注册
   那一刻用当时的 `MixinEnvironment.getDefaultEnvironment()` 固定 `MixinConfig.env`（`Config.allConfigs`
   静态缓存），而 `MixinProcessor.selectConfigs` 用 `config.select(environment)`（**引用相等**）比较；传进
   select 的实例比注册时更早 → 后注册的配置永不进 `pendingConfigs`。日志佐证：`Selecting config` 只有 120 条、
   全是注册行号 ≤ L2551 的配置，`gtominifix.mixins.json`（L2956）与另外 57 个（含 `gtceu`、`ae2`、`jade`、
   `architectury*`、`ferritecore.*`）一条都没有。→ **本模组不要再用 Mixin 打 GTO 的类**。
2. **运行期给自动输出套包装**（`PipeIoHook`）与**条件式重订阅巡检**（`AutoOutputReArm`）—— 都能缓解，但前者
   每次输出都要重新寻路、后者要常驻登记/补扫；源头修掉后一起删除。
3. **判定点日志**（`PipeTrace` + CoreMod 的第二个转换器）—— 一次都没打印出来，而且 CoreMod 里既写不了文件
   （`java.io` 被 coremod 类过滤器挡住）、`print()` 也进不了日志文件。→ 教训：**不要靠 CoreMod 里的日志验证
   补丁**，从模组侧读目标类的字段/方法来判断。
4. **A：邻居缓存的 NULL 哨兵永久化 + B：管网变动时通知旁边机器**（都实现并实测过）—— 缺陷是真的，但不是
   本次症状的原因（探针现场：那几个「哨兵」方向的世界上真的是空的）。按用户要求删除。若将来遇到「机器在
   邻接区块还没加载时看错一眼、之后整局那一面都瞎」的现场，A 是现成的方向。
5. **C：路由表按 (管道坐标, 入流面) 各存一张表** —— 能治自吃，但要为每个面各存一张表、冷缓存时最多多跑
   6 次寻路。最终由上游用「一张与调用者无关的表 + 取表时按调用者剔除」取代，不需要按面存表。
6. **本模组自己的 D/E CoreMod 补丁**（2026-10-16 删除）—— 上游修好后它们匹配不到新签名/新方法体
   （`getNetData` 描述符少了 `Direction`、`createNetData` 的描述符与方法体都变了），只会静默跳过，留着还会让
   `pipe:` 行一直显示「尚未被调用」。→ 教训：**上游跟进后要主动删掉自己的补丁**，别让死代码假装在工作。

## 实测清单

- [ ] `/gtominifix pipewatch 60` 期间日志出现 `[订阅]` / `[配额]` 行；`stop` 之后不再出现新行。
- [ ] 不监听时 `/gtominifix config` 的 `pipe:` 行显示 `监听=关`。
- [ ] 原本会卡住的机器跑久后仍持续输出（上游修复生效）；`pipewatch` 的巡视里不再出现「有货+能出+未订阅」。
