# pipe —— GTO 机器「有货、下游有空位，却不再往外推」的修复

一句话：机器停住是**两个源头缺陷**叠加的结果，都在 CoreMod 里改 GTO 自己的类，不依赖 Mixin：

- **A：邻居缓存把「这个方向没邻居」永久记住了。** `BlockEntityDirectionCache#getAdjacentBlockEntity`
  用哨兵 `DirectionCache.NULL` 缓存否定结果，之后**再也不查世界** —— 机器只要在邻接管道区块还没加载时
  看过一眼，那一面就整局都瞎。
- **B：管网变了却不通知机器。** 机器在「正面邻居某一刻不能收」时会 `unsubscribe()` 掉自己的自动输出
  （`FluidHatchPartMachine#updateTankSubscription`），而唯一周期性重查的 `autoIO()` 正是被退订的那个；
  管网增删/拆分合并/区块加载都不会碰机器，于是订阅掉了就永远不回来。

A 让「重新查一次」真的能查到东西，B 让该重查的时候机器能被叫醒。两者都只在**事件/变化**时付出成本，
没有常驻轮询。

## 症状

- 一开始正常，**跑一段时间后**输出仓 / 机器不再往下游推送；目标容器明明还有空间。
- **重启游戏 / 拆一格管道 / 对机器扳手旋转**都会让它暂时恢复 —— 这些都是会触发事件的动作。
- 机器越多、管网越大越容易碰到；中招的机器不固定。

## 根因细节

推送**只由订阅驱动**：`FluidHatchPartMachine.autoIOSubs` → 每 20 tick 一次 `autoIO()`
→ `tank.exportToNearby(getFrontFacing())`。订阅只在事件里重建：`onLoad`、`onNeighborChanged`、
`onRotated`、`setWorkingEnabled`、储罐变化监听。

退订发生在 `updateTankSubscription()`：`isWorkingEnabled() && (io == OUT && !tank.isEmpty() || io == IN)
&& hasAdjacentTarget(level, pos, front, FLUIDS, io.neighbourAccess())` 任一为 false 就
`unsubscribe()` 并把订阅字段置空。`hasAdjacentTarget` 只看**能力**不看容量（满的邻居照样 true），
平时很稳；它被「一时解析不出来」打倒时（管道 BE 还没就绪、网是空的、**方向缓存里是哨兵**），
机器就永久停住。**A 修的是那个哨兵，B 修的是「没人来叫醒它」。**

现场证据（`logs/gtominifix-debug.log`，2026-10-14 实测）：巡视小结
`带自动输出的机器 16 台，其中「有货+能出+未订阅」5 台` —— 5 台都是 `FluidHatchPartMachine`，
储罐有货（如 `gtceu:naphtha 32000/32000`）、正面邻居能收、订阅字段为空；同一份日志里管道层是干净的
（六面各自重新寻路 24~25 条，目标收 1000 mB 的模拟结果与实际余量一致，没有一条「还有空间却收 0」）。

## 补丁

文件：

- `src/main/resources/META-INF/coremods.json`
  → `{"gtominifix_fluidpipe": "coremods/gtominifix_fluidpipe.js"}`
- `src/main/resources/coremods/gtominifix_fluidpipe.js` → 两个转换器（`gtominifix_neighbour_cache` /
  `gtominifix_pipe_net_notify`）

**A** `com.gregtechceu.gtceu.utils.cache.BlockEntityDirectionCache#getAdjacentBlockEntity`：
把方法里**每一处** `DirectionCache.NULL` 哨兵的使用换成 `aconst_null`（栈不变，无需手算帧）。
`DirectionCache#setCache` 原样存值，于是「这个方向没邻居」就等价于「没缓存」，下一次查询自然会再看世界。
只引用 GTO 自己的成员，**不碰任何原版/Forge 方法名**（不用 SRG 映射）。

**B** `com.gregtechceu.gtceu.common.pipelike.fluid.FluidPipeNet#onPipeConnectionsUpdate`：
方法头插入 `PipeNetNotify.onNetworkChanged(this)`。它把这张网的节点位置排队，在**下一个服务端 tick**
里对每个位置发一次 `level.blockUpdated(...)`，于是旁边的机器走自己的 `onNeighborChanged` →
`updateTankSubscription()` 重新评估订阅。排队到下一 tick 是有意的：`onPipeConnectionsUpdate` 会在管网
正在被遍历/重建时被调用，就地发方块更新可能重入管网代码。

**为什么用 CoreMod 而不是 Mixin**：本实例里本模组的 mixin 配置**从未被 `select`**（见文末「历史」），
CoreMod 走 ModLauncher 的 coremod 链，与 Mixin 的选择机制无关。实例里 `gtocore` 自己
（`coremods/gsonhelper_fast_path.js`）、Apotheosis、LibX、jecharacters、Redirector 都在用这条路。

**安全设计**：两个转换器各自包在 `try/catch`，出错就把 ClassNode 原样返回（不转换、不崩）；找不到方法
只打一行日志。`PipeNetNotify` 全程反射 + 只读字段 + 异常永久关闭自己；单 tick 最多处理 4096 个位置。

**开关（排查用）**：实例 `config/gtominifix-pipe-patch.off` 存在，或 JVM 参数 `-Dgtominifix.pipePatch=off`，
即关闭两个补丁（GTO 的类原样不动）。读开关本身抛异常时补丁**保持启用**。

**离线验证（不启动游戏）**：`temp/coremod_test/driver.js` + `nashorn-core-15.4` + `asm-9.8`，用
`COMPUTE_FRAMES` 写出补丁后的 class，再用 ASM `BasicVerifier` 逐方法校验 → **16 个方法 0 失败**
（`BlockEntityDirectionCache` 7 个、`FluidPipeNet` 9 个），并确认 3 处哨兵都换成了 `aconst_null`。

**怎么确认生效**：`/gtominifix config` 的 `pipe:` 行会打印两个补丁各自的**存活计数**（
`A 缓存补丁=已生效（N 次邻居查询）`、`B 网络通知=已生效（收到 N 次网络变动，唤醒 N 个管道位置）`）。
不要靠 CoreMod 里的日志判断 —— 那里写不了文件，`print()` 也只进启动器控制台。

## 排查工具（保留，全部只读）

- `/gtominifix pipetrace`：对着机器或管道（10 格内）执行，打印**真实逻辑状态** —— 机器段（订阅字段、
  自动 IO 监视器、储罐每槽、正面）、逐面邻居缓存的原始槽值 vs 世界真值、handler 身份与其 `net`、
  逐面重新寻路列出的每条路由与「目标能不能收 1000 mB（走 AE2 键层，和 `FluidNetHandler.fill` 同一条调用）」。
- `/gtominifix pipeprobe`：Forge 能力层的概览（自身流体能力、六向 `fill(SIMULATE)`、路由表条目数）。
- `/gtominifix pipewatch [秒]` / `pipewatch stop`：逐秒摘要 + 每 5 秒一份完整探针 + 每 5 秒一次
  「有货+能出+未订阅」巡视，抓「跑一段时间才坏」的那一刻。
- 调试文件：`logs/gtominifix-debug.log`（本模组的 SLF4J 在部分实例里一个字都落不了盘，所以自己写文件；
  由 `pipe.debugFile` 控制，超过 4 MB 轮转成 `.1`）。

## 实测清单

- [ ] `/gtominifix config` 的 `pipe:` 行两个计数都在涨（A 的查询数必然立刻涨；B 要等管网变动过）。
- [ ] 原本会卡住的机器跑久后仍持续输出；`pipewatch` 的巡视里不再出现「有货+能出+未订阅」。
- [ ] 放/拆一格管道后，B 的计数增长（说明通知链路在跑）。
- [ ] 放一个 `config/gtominifix-pipe-patch.off` 重启，症状能复现（反向验证）。

## 历史：不要再走的路（2026-10-14 之前）

1. **Mixin 补丁**（`FluidPipeNetMixin` / `BlockEntityDirectionCacheMixin`）—— 在本实例里**从未被 select**，
   一次都没跑过，已删除。原因（Mixin 0.8.5 源码）：`Mixins.addConfiguration` 在**注册那一刻**用当时的
   `MixinEnvironment.getDefaultEnvironment()` 固定 `MixinConfig.env`（`Config.allConfigs` 静态缓存），
   而 `MixinProcessor.selectConfigs` 用 `config.select(environment)`（**引用相等**）比较；
   传进 select 的实例比注册时更早 → 后注册的配置永不进 `pendingConfigs`。日志佐证：`Selecting config`
   只有 120 条、全是注册行号 ≤ L2551 的配置，`gtominifix.mixins.json`（L2956）与另外 57 个
   （含 `gtceu`、`ae2`、`jade`、`architectury*`、`ferritecore.*`）一条都没有。
   → **本模组不要再用 Mixin 打 GTO 的类**（`TravelHandlerMixin` 2026-10-15 也已按此删除；
   至此本模组不含任何 Mixin）。
2. **运行期给自动输出套包装**（`PipeIoHook`）与**条件式重订阅巡检**（`AutoOutputReArm`）—— 都能缓解，
   但前者每次输出都要重新寻路、后者要常驻登记/补扫；A+B 从源头修掉后一起删除。
3. **判定点日志**（`PipeTrace` + CoreMod 的第二个转换器）—— 事后证明卡点不在管道层，一次都没打印出来；
   而且 CoreMod 里既写不了文件（`java.io` 被 coremod 类过滤器挡住）、`print()` 也进不了日志文件。
   → 教训：**不要靠 CoreMod 里的日志来验证补丁**，从模组侧读目标类的字段/方法来判断。
4. **路由表按 (管道坐标, 入流面) 缓存**（旧 CoreMod 补丁）—— 那个缺陷（`FluidPipeNet#getNetData`
   只用 `pipePos` 做键）是真的，但实测证明它不是本次卡住的原因（输出仓不允许输入、流体灌不回去），
   已随本次重写删除。
