# 约定与踩坑

写代码前读这一页。这里是**跨功能的硬性规范**和历史上真正踩过的坑；
单个功能特有的边界写在各自的 `docs/<功能>.md` 里。

## 依赖处理：一律反射，没有编译期依赖

- 对 GTO / AE2 / LDLib / Functional Storage 的引用**只能出现在字符串里**（类名、方法名、字段名），
  不能出现在 `import`、字段类型或方法签名里。
- compat 类模板照 `src\main\java\com\lai\gtominifix\ae2\Ae2Compat.java`：
  静态块里解析 → 成功置 `available = true`；`catch` 要连 `LinkageError` / `Throwable` 一起吞
  （对方可能只装了一半）；`isAvailable()` 给命令当探针。
- **绝不在模组构造器或静态初始化里碰 compat 类**（类加载顺序不确定，会把整个模组拖崩）。
- 解析失败必须能退化：对应功能静默关闭，模组照常加载。

## Mixin（本模组不再使用）

- ⚠️ **不要用 Mixin 打 GTO 的类**（2026-10-14 查实，证据链见 `docs/pipe.md` 的「历史」一节）：
  本实例里我们的 Mixin 配置**从未被 `select`**、因此从未应用过。原因（Mixin 0.8.5 源码）：
  `Mixins.addConfiguration` 在**注册那一刻**用当时的 `MixinEnvironment.getDefaultEnvironment()`
  固定 `MixinConfig.env`（`Config.allConfigs` 静态缓存），而 `MixinProcessor.selectConfigs` 用
  `config.select(environment)`（**引用相等**）比较；传进 select 的实例比注册时更早 →
  后注册的配置永不进 `pendingConfigs`。日志佐证：`Selecting config` 只有 120 条、全是注册行号
  ≤ L2551 的配置，本模组的配置注册在 L2956，与另外 57 个（含 `gtceu`、`ae2`、`jade`）一条都没有。
- 2026-10-15 已删除最后一个 Mixin（`TravelHandlerMixin`）及其配置 `gtominifix.mixins.json`、
  jar manifest 里的 `MixinConfigs` 属性。**要改 GTO 的类一律用 CoreMod**（见下节），
  或者干脆不碰字节码（命令 / Forge 事件 + 反射）。

## CoreMod（改 GTO 字节码的正路）

- 本模组已无 Mixin（见上节）→ 要改 GTO 的类就用 CoreMod：`src/main/resources/META-INF/coremods.json`
  （`{"<名字>": "coremods/<文件>.js"}`）+ `coremods/<文件>.js` 里 `initializeCoreMod()` 返回转换器。
  实例里 `gtocore`（`coremods/gsonhelper_fast_path.js`）、Apotheosis、LibX、jecharacters、Redirector 都在用。
- **整个转换包在 `try/catch`**，出错就把 ClassNode 原样返回（不转换、不崩游戏）；找不到方法只打一行日志。
- 补丁里只引用**不会被重映射的名字**（mod 自己的成员、`Direction#ordinal()`、JDK / fastutil），
  别碰原版成员名。需要重算帧时用 `COMPUTE_FRAMES`（ModLauncher 的 `TransformerClassWriter` 会接住）。
- **离线验证**（不启动游戏）：`temp/coremod_test/driver.js` + `nashorn-core-15.4` + `asm-9.8`，
  写出补丁后的 class 再用 ASM `BasicVerifier` 逐方法校验。
- **绝不要在 CoreMod 里写日志来验证自己**：`print()` 只进启动器控制台、不进日志文件，
  `java.io` 被 coremod 类过滤器挡住（异常还会被自己的 try/catch 吞掉）。
  验证一律**从模组侧读目标类的字段 / 方法**（例：`FluidPipeNet.netData` 的声明类型是不是数组）。
- 加个「关掉补丁」的开关（实例 `config/<名字>.off` 文件或 `-D<属性>=off`），排查时能单独排除它。

## 配置

- 每个键三件套：`BUILDER.push("<组>")` + 私有 `ForgeConfigSpec.XxxValue` 静态字段 +
  同名 `public static` 缓存字段（默认值兜底）+ `onConfigChanged(ModConfigEvent)` 里逐个 `.get()` 刷新。
- 新键会自动写进玩家已有的 toml；**已有键保留玩家改过的值** → 改默认值对老配置无效，
  真要改语义就得换键名。
- 文档里写全路径 `组.键`（如 `drawer.fillEnabled`）。

## 事件与网络

- 可取消事件里，排在更早 `cancel` 之后的 handler 会被**整个跳过** → 需要抢在前面就用
  `EventPriority.HIGHEST`（例：`crate\CrateShortcuts.java:55`）。
- 服务端事件 handler 要手动 `register`。
- 网络：改动消息结构要同步 `ModNetwork.PROTOCOL_VERSION`（现为 `"2"`，`network\ModNetwork.java:25`）。
  需要「客户端意图 + 服务端在真实行为上动手」时照 `drawer` 的模式（客户端只报意图，服务端校验后再改）。

## 调试

- **本机看不到本模组的日志**（SLF4J 落不了盘）→ 只用 `/gtominifix config` 或聊天栏输出；
  排查完把调试输出删干净。
- `debugLog` 只覆盖它自己那几处，别假设全功能都打。
- 反编译核实：`tools\decompile.cmd <jar> <包或类>`，看描述符加 `-Javap`。细节见 `docs/gto-internals.md`。
- **流体路由表：缓存的是「拓扑」，判据却带「入流面」**（GTO 的坑，本项目踩最久的一个）：
  `FluidPipeNet#getNetData(long pipePos, BlockPos pos, Direction facing)` 的表只按 `pipePos` 缓存，
  内容却按 `facing` 生成（`FluidNetWalker.checkNeighbour` 排除本次寻路的源面）→ 同一格管道上
  「谁先插入谁定表」，后来的机器复用别人的表就会把流体推回自己（或反过来：表里少了建表者那一面的
  目标），并按目标自报的接受量记满该格每秒配额（`FluidPipeBlockEntity.transferredFluids`）→ 下游收不到。
  **上游 gtceu 26.10.53 已修**（表与调用者无关 + `FluidNetHandler#fillFirst` 用 `isBackToSelf` 剔除自己）；
  本模组等价的 D/E 两个 CoreMod 补丁已删。将来若要自己打这类补丁，判据取
  「`getTargetPipePos().asLong() == 本管道坐标 && getTargetFacing() == 本入流面`」即可。
- **订阅型机制退了不会自己回来**（真实缺陷，本模组暂不为它打补丁）：GTO 机器
  （`FluidHatchPartMachine#updateTankSubscription`）与管道（`FluidPipeBlockEntity#autoTransfer`）的
  订阅只在事件里重建，而它们的周期性任务正是被退订掉的那个。历史上试过两条路并删除：
  管网变动时给相邻机器发方块更新（`onNetworkChanged`）、不缓存否定结果（`BlockEntityDirectionCache`
  的 `NULL` 哨兵）、以及「条件式重新订阅」巡检（`AutoOutputReArm`，常驻登记 + 补扫，绕法不是修法）。
  若将来遇到「邻接区块还没加载时看错一眼、之后整局那一面都瞎」的现场，上面第一条是现成方向。
  见 `docs/pipe.md` 的「历史」一节。
- 探针读机器状态要下钻 `MetaMachineBlockEntity.metaMachine`（`tank` / `io` / `autoIOSubs` /
  `getFrontFacing` 都在机器对象上）；管道走 AE2 键层（`IKeyHandler.insert`）而不是 Forge 能力层，
  测可收性要用前者。

## 排查方法论（都是被实测教过的）

- **静态分析只是假设**，本仓库历史上多次被实测推翻。先看字节码/源码，再实测。
- `Inventory#getContainerSize()` 对玩家背包返回 **41**（36 + 5），不是 36；别用它算主背包。
- `slot.container instanceof Inventory` 是判断「玩家背包」的可靠判据。
- 本实例的 AE2 是 GTOCore 的 jar-in-jar 分叉，多出 `gto_ae` 包；「倒空」只有 `serial = -1` 那条路。
- 接管了就不能半途放手（`crate` 的教训）。
- 交付时区分「已验过」和「需要实测」；改用户环境（部署 jar、动配置）前先说一声。

## 本机环境怪癖

- 会话 shell 是 **Windows PowerShell 5.1**（`pwsh` 不在 PATH）。无 BOM 的 UTF-8 `.ps1` 会被当 ANSI 读，
  中文注释会吃掉引号 → 脚本要么写**纯 ASCII**，要么存成带 BOM 的 UTF-8（`edit` 工具会抹掉 BOM，改完要重加）。
  `.cmd` 必须纯 ASCII（cmd.exe 按 ANSI 读）。
- PowerShell 变量名大小写不敏感：局部变量 `$javap` 会撞上 `[switch]$Javap` 参数。
- Gradle 构建：`-D` 属性必须加引号，否则被 PowerShell 拆成任务名。
- Gradle 日志是 GBK，读的时候要 `[System.IO.File]::ReadAllLines($p, [System.Text.Encoding]::GetEncoding(936))`。
