# AGENTS.md

本仓库是 **gtominifix**：给 **GTO（GregTech Odyssey）** 做针对性增强与修复的 Minecraft 模组
（MC 1.20.1 / Forge 47.x / Java 17）。这是给在本仓库工作的编码 agent 的入口文档；
人类向文档见 `README.txt`（功能简介）与 `changelog.txt`（逐版本技术细节）。

## 现状

- `mod_id = gtominifix`，包 `com.lai.gtominifix`，版本看 `gradle.properties` 的 `mod_version`（当前 1.0.5）。
- **五个功能，各占一个包，互相独立**：

  | 包 | 功能 | 文档 |
  |---|---|---|
  | `teleport` | 旅行手杖落点修正（不再卡进地板掉血） | `docs/teleport.md` |
  | `crate` | 板条箱快捷存取（兼容 Inventory Essentials） | `docs/crate.md` |
  | `drawer` | 抽屉批量填充（Functional Storage） | `docs/drawer.md` |
  | `ae2` | AE2 快速倒空 | `docs/ae2.md` |
  | `pipe` | GTO 机器「有货却不再往外推」的两处源头修复（CoreMod：邻居缓存不再缓存否定结果 + 管网变动时叫醒相邻机器）+ `pipetrace` / `pipeprobe` / `pipewatch` 排查工具 | `docs/pipe.md` |

  另有基础设施：`network`（网络消息）、`command`（`/gtominifix` 命令）、`GtoMiniFixConfig`（配置）。
- **铁律**：对 GTO / AE2 / LDLib / Functional Storage 的依赖一律走**反射**或**CoreMod（JS 字节码）**，
  没有编译期依赖。对方缺失或改了签名时，对应功能静默关闭，模组照常加载。
- ⚠️ **本模组不用 Mixin 改 GTO 的类**（2026-10-14 查实：本实例的 `Selecting config` 只覆盖注册行号
  ≤L2551 的 120 个配置，本模组的 mixin 配置注册在 L2956、从未被 `select`，一次都没生效过；
  2026-10-15 已删除最后一个 Mixin `TravelHandlerMixin`）→ 要改 GTO 的类就用 CoreMod
  （`META-INF/coremods.json` + `coremods/*.js`，走 ModLauncher coremod 链），或者干脆不碰字节码
  （命令 / Forge 事件 + 反射）。

## 常用命令

```powershell
# 构建（-D 必须加引号，否则 PowerShell 会把它拆成任务名；JAVA_HOME 必须是 JDK17）
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat build '-Dnet.minecraftforge.gradle.check.certs=false'
# 产物 build\libs\gtominifix-1.0.5.jar（约 13 秒，jar → reobfJar）

# 部署（直接覆盖实例，重启游戏生效；先确认没有 java/javaw 进程在跑）
$m = "C:\Users\PC\AppData\Roaming\PrismLauncher\instances\GregTech.Odyssey-0.6.0\.minecraft\mods"
Copy-Item build\libs\gtominifix-1.0.5.jar $m -Force
# 该实例还有一份副本在 .minecraft\_mymods\mods\（不参与加载，只是备用堆放处）

# 验证（本机看不到日志，只能这样确认）
#   进游戏（需 OP）执行 /gtominifix config —— 逐功能打印状态（7 行）
```

## 游戏内排查（管道那套工具）

本模组的 SLF4J 在本机实例落不了盘，所以管道排查**不靠日志**，用自己写的文件 + 三个命令：

```text
/gtominifix pipetrace          准星对着机器 / 管道（10 格内）执行：机器订阅字段、自动 IO 监视器、
                               储罐每槽、正面；六向邻居缓存的原始槽值 vs 世界真值；handler 身份
                               与其 net；逐面重新寻路列出的每条路由 + 目标能不能收 1000 mB
                               （走 AE2 键层，与 FluidNetHandler.fill 同一条调用）；同时写文件
/gtominifix pipeprobe          Forge 能力层概览：自身流体能力、六向 fill(SIMULATE)、路由表条目数
/gtominifix pipewatch <秒>     逐秒摘要 + 每 5 秒完整探针 + 每 5 秒一次「有货+能出+未订阅」巡视
/gtominifix pipewatch stop
```

**注意**：探针读机器状态时要先下钻 `MetaMachineBlockEntity.metaMachine` —— `tank` / `io` /
`autoIOSubs` / `getFrontFacing` 都在机器对象上，从 BE 直接读只会得到 null。

日志文件：实例 `logs\gtominifix-debug.log`（超 4 MB 轮转 `.1`，受 `[pipe] debugFile` 控制）。
`/gtominifix config` 的 `pipe:` 行给出两个 CoreMod 补丁的存活计数（`PipeNetNotify.statusText()`：
邻居缓存检查次数 / 管网变动通知次数 / 已发方块更新数），探针的 `-- 本模组 --` 行同源。
计数不涨 = 补丁没进去。细节见 `docs/pipe.md`。

## 看 GTO / AE2 内部实现

```powershell
tools\decompile.cmd -List                       # 列出实例 mods 与 gtocore 内嵌的 jar
tools\decompile.cmd gtceu pipelike\fluid        # 反编译某包 → tools\out\gtceu\...
tools\decompile.cmd gtceu com.gregtechceu.gtceu.common.pipelike.fluid.FluidPipeNet
tools\decompile.cmd gtocore eio_travel          # 反编译 GTOCore 自己的包
```
细节与陷阱见 `docs/gto-internals.md`。**注意**：实例里没有独立的 gtceu jar，它是 GTOCore 的
jar-in-jar（脚本会自动抽出来并缓存到 `tools\out\_jars\`）。

## 硬性约定（违反会出 bug 或让排查失效）

1. **别靠日志**：本模组的 SLF4J 在本机实例从来落不了盘。诊断只用 `/gtominifix config`、聊天栏输出，
   或管道那套自己写文件的工具（`/gtominifix pipeprobe` / `pipewatch` → `logs\gtominifix-debug.log`）。
2. **反射 compat 类**（`Ae2Compat` / `LdlibCompat` / `FunctionalStorageCompat`）不要放进模组构造器；
   静态块要连 `LinkageError` 一起吞（对方可能只装了一半）。
3. **不要用 Mixin 改 GTO 的类**（2026-10-14 查实：本实例里我们的 Mixin 配置从未被 `select`、
   因此从未应用过 —— 日志里 `Selecting config` 只覆盖注册行号 ≤ L2551 的配置，我们的配置注册在
   L2956；2026-10-15 已删除最后一个 Mixin）。要改 GTO 的类就用 CoreMod
   （`META-INF/coremods.json` + `coremods/*.js`），或者干脆不碰字节码（命令 / Forge 事件 + 反射）；
   别把「改了没用」当成修法错了。证据链见 `docs/pipe.md` 的「历史」一节。
4. **事件优先级**：Forge 可取消事件里，排在更早的 `cancel` 之后的 handler 会被整个跳过（`crate` 必须 HIGHEST）。
5. 新增/修改网络消息要同步 `ModNetwork.PROTOCOL_VERSION`。
6. 新增配置键会自动写进玩家已有的 toml；**已有键保留玩家改过的值**，改默认值对老配置无效。
7. **静态分析只是假设**，结论必须实测；交付时区分「已验过」和「需要实测」。
8. 改用户环境（部署 jar、动配置）前先说一声；调试输出排查完清理干净。
9. `.gitignore` 忽略了 `temp/` 与 `.workbuddy-ai/` → 这两处的文件删了不可逆，也别指望进 git。
10. 本会话的 shell 是 **Windows PowerShell 5.1**（`pwsh` 不在 PATH）；`.ps1` 必须带 UTF-8 BOM，`.cmd` 必须纯 ASCII。
11. **不要在 CoreMod 里写日志来验证自己**：JS 里 `print()` 只进启动器控制台、不进日志文件，
    `java.io` 被 coremod 类过滤器挡住（异常还会被自己的 try/catch 吞掉）。验证补丁有没有生效，
    一律**从模组侧**判断：让补丁在方法头调一次我们自己的计数方法（如
    `PipeNetNotify.markCacheCheck()`），再读 `/gtominifix config` 的 `pipe:` 行计数涨不涨。
    改 JS 之后先跑离线验证器：`java '-Dfile.encoding=UTF-8' -cp <nashorn-core-15.4;asm-9.8;
    asm-tree;asm-analysis;asm-util;asm-commons> org.openjdk.nashorn.tools.Shell temp\coremod_test\driver.js`
    （读 `tools\out\_jars\gtceu-*.jar` 里的目标类、应用所有转换器、用 ASM `BasicVerifier` 逐方法校验）。
12. **订阅型机制（GTO 机器自动输出 / 管道自动抽取）退了不会自己回来**：它们的周期性任务正是被
    退订掉的那个，而订阅只在「机器自己身边有方块变化」时重建。所以修法是补触发链
    （管网变动 → 给相邻机器发一次方块更新，见 `PipeNetNotify`），再配上「不缓存否定结果」
    （`BlockEntityDirectionCache` 的 NULL 哨兵）。不要靠定时巡检去重订阅（那是绕法，见 `docs/pipe.md` 历史）。

## 改代码的标准流程

1. 先读对应的 `docs/<功能>.md`（一页，1 分钟），需要 GTO 内部细节再查 `docs/gto-internals.md`。
2. 新功能建包 `com.lai.gtominifix.<功能>`；反射 compat 照 `ae2\Ae2Compat.java` 的写法。
3. 配置项加进 `GtoMiniFixConfig`（分组 + 私有 `XxxValue` + 同名 `public static` 缓存字段 + `onConfigChanged` 里刷新）。
4. 状态加进 `/gtominifix config`（`command\GtoMiniFixCommand.java`）。
5. 构建 → 部署 → 让用户实测 → 更新 `docs/<功能>.md`、`changelog.txt`、必要时 `README.txt`。

## 文档地图

| 位置 | 内容 |
|---|---|
| `AGENTS.md` | 本文件：入口、命令、硬性约定 |
| `docs/<功能>.md` | 每个功能的症状 / 证据 / 实现 / 配置 / 验证 / 实测清单 |
| `docs/gto-internals.md` | 实例与 jar 布局、GTO 内部关键类、反编译流程 |
| `docs/conventions.md` | 反射 / CoreMod / 配置 / 事件 的完整规范与历史踩坑 |
| `changelog.txt` | 逐版本改了什么（技术细节都在这里） |
| `README.txt` | 给玩家的功能说明（保持简短） |
| `tools/` | `decompile.cmd` / `decompile.ps1` / `vineflower.jar` |
| `temp/` | 临时产物，可随时清空（不进 git） |
