# teleport —— 旅行手杖落点

一句话：修「旅行手杖向下穿墙瞬移后每次掉 1 点血」；给玩 GTO 整合包、用旅行手杖的人，模组 id `gtominifix`。

## 症状 / 为什么需要它

- shift + 右键用旅行手杖向下穿墙，落地后稳定掉血，**每 20 tick 掉 1 点**，站着不动会一直掉。
- 不是摔落伤害（`TravelHandler.shortTeleport` 自己写了 `player.fallDistance = 0`，`TravelHandler.java:61`，混淆名 `f_19789_`），是原版窒息 `minecraft:in_wall`。
- 视觉上人半嵌在楼板里：脚落在空腔顶部，头和眼睛留在地板方块内。
- 1 格厚楼板 + 垂直向下瞬移几乎必现。

## 根因（证据）

1. `TravelHandler.teleportPosition` 在射线命中点距眼睛 < 3 格时，用 `BlockGetter.traverseBlocks` 重新找落点，回调里优先取「射线所在格的下面那一格」（`TravelHandler.java:149-166`：`:155` 先试 `traversePos.below()`，`:161` 再试 `traversePos`；判定函数 `traversalCheck` 在 `:180-185`）。
2. 向下射线穿过 1 格厚地板时，遍历到的第一格是那块地板，而它下面已经是空气 —— 于是落点被定成**地板正下方那一格**。
3. 最后那道校验 `isTeleportPositionClear(level, target.below())` 拦不住：它算的是 `target.below().above()`（也就是落点自己）的碰撞高度，落点是空气 → `height = 0 <= 0.2` → 直接早退，头顶上那块地板根本没被检查（`TravelHandler.java:234` 的 `isTeleportPositionClear`；它上面还有 `:169` 的 `target.below()` 校验与 `:177` 的 `distManhattan >= 2` 门槛）。那个 `<= 0.2` 早退是有意为之的（原文 javadoc：为了能站到地毯上），但它假定传进来的是地板格。
4. **为什么每次刚好 1 点**：`Player.tick()` 里 `super.tick()`（内部 `LivingEntity.baseTick` 会调 `isInWall()` 扣血）在前、`updatePlayerPose()` 在后（`Player.java:241` / `:284`，扣血点在 `LivingEntity.java:347`）。落地那一 tick 玩家的姿态还是落地前站在地板上的那个（站立眼高 1.62 / 下蹲 1.27），眼睛正好卡进上方地板 → 判定成立、扣 1 点。`in_wall` 固定 1.0 点伤害 + 原版 20 tick 无敌帧，所以表现为「每 20 tick 掉 1 点」。

## 实现

- 挂载点：`TeleportFix.onEntityTeleport(EntityTeleportEvent)`。GTOCore 的所有旅行传送都汇聚到这个事件：手杖 shift+右键 `TravelStaffBehavior#performAction` → `TravelHandler.shortTeleport` → `teleportEvent`（调用点 `TravelHandler.java:57`，方法体 `:250`）；旅行锚传送 / 锚上跳蹲电梯 `blockTeleport` / `blockElevatorTeleport` → `blockTeleportTo` → 同一个 `teleportEvent`（`TravelHandler.java:106`）。好处是零字节码注入、零编译期依赖，还顺带覆盖旅行锚；代价是只在服务端触发。
- **核心不变量**（`SafeLanding.fits` 就是第一道判断）：落点本身装得下玩家就原样返回，一个字节都不改 —— 穿墙 / 锚传送 / 电梯 / 落半空 / 落水里行为与不打模组时完全一致。
- 判定用完整 `Pose.STANDING` 碰撞箱（0.6 × 1.8）与方块碰撞箱不相交，比原版 `isInWall()` 的眼睛小盒更严格，正好免疫上面那个「姿态滞后一 tick」的坑。`blockSpaceFree` 走 `level.getBlockCollisions(entity, box)`：**只查方块，不查实体碰撞**（否则船 / 潜影贝 / 任何把 `canBeCollidedWith()` 改写成 true 的模组实体会让合法落点被误判）。只有落点确实在水里才放宽到 `Pose.SWIMMING`（0.6 × 0.6）。
- `SafeLanding.rescue(...)`：先同柱上下各 `maxVerticalSearch` 格，再按切比雪夫半径 `1..maxHorizontalSearch` 逐列重试；搜不到返回 `null`，调用方取消。竖直候选按 |dy| 递增、同 |dy| **先下后上**（有意不同于设计大纲的 `{1,2,-1,-2,-3}`：最常见失败形态是头嵌天花板，向下挪 1 格才是玩家真正想要的）。
- `accepts` 两条硬约束，保证不给出退化落点：① 候选点不许是玩家自己站着的那个方块（`BlockPos.containing(feet).equals(entity.blockPosition())`，必须判到方块级；不能放宽成「相邻方块」，否则旅行锚 `anchorY+2` / 玩家 `anchorY+1` 的正常落点会被废掉）；② 必须落在目标相对玩家脚底的那一侧，且不许停在脚底那一层。合起来 = 救不了就取消，不会「原地传送一次」也不会「反向弹一下」。
- 回退：`SafeLanding.raycastFallback(level, player, blinkRange)` 复刻 `teleportPosition` 里不含 `traverseBlocks` 的那段（站在你瞄的方块顶上），并守 GTO 自己的 `TravelHandler.java:177` `distManhattan(target) < 2 → 返回空`；`blinkRange` 由 `GtoCompat.blinkRange` 反射读 `GTOConfig.INSTANCE.travelConfig.blinkRange`，读不到用 `DEFAULT_BLINK_RANGE = 24`。
- （2026-10-15 已删除）曾经有一个可选的 Mixin 增强层 `TravelHandlerMixin`（方案 B），直接改 `TravelHandler#teleportPosition` 的返回值，额外买到两件事：客户端粒子预览与真实落点一致（`TravelParticleHandler#clientTick` 每 3 tick 直接调这个方法，不走事件）；取消时返回 `Optional.empty()` 让 `shortTeleport` 走 `return false`，不加 5 tick 冷却、不扣 128 EU。删掉的原因：本实例的 mixin 配置从未被 `select`、一次都没生效过（证据链见 `docs/pipe.md` 的「历史」一节），留着只会误导后来者以为这条路能用。现在只做方案 A。
- 兜底层：`TeleportGuard` 在 `LivingHurtEvent` 上，只吞「刚被本模组标记过一次非法落点的传送」后 `inWallGraceTicks` 内、且玩家确实还嵌在方块里（`noCollision(standingBox)` 为 false）的 `DamageTypes.IN_WALL`。刻意**不**无条件取消 `in_wall` —— 那是原版正常的「被活塞挤进墙里」反馈。玩家登出时清表。

## 配置

`config/gtominifix-common.toml` → `[teleport]`：

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关，关掉后不再介入任何传送 |
| `onlyPlainTeleportEvent` | true | 只处理直接 post 基类事件的传送；原版 /tp、末影珍珠、紫颂果走子类，默认不受影响 |
| `maxVerticalSearch` | 3 | 同柱竖直搜索范围（上下各几格，范围 0-16） |
| `maxHorizontalSearch` | 2 | 水平邻域切比雪夫半径（范围 0-8），0 = 只做同柱 |
| `preferSolidGround` | false | 是否优先选脚下有实心方块的候选点；默认关，穿墙本来就常落半空 |
| `enableRaycastFallback` | true | 救不了时是否回退到「纯射线落点」 |
| `cancelIfUnrescuable` | true | 彻底救不了时是否取消传送；false = 放行原始落点，退化成现状 |
| `suppressInWallAfterTeleport` | true | 是否启用兜底层的伤害抑制 |
| `inWallGraceTicks` | 20 | 兜底层的保护窗口（范围 0-200），默认正好覆盖原版无敌帧第一次命中 |

`debugLog`（根层级，默认 false）打开后会打印「落点修正 X -> Y」等日志。

## 怎么验证它在工作

1. `/gtominifix config`（需 OP）：看 `teleport:` 那一行，九个键都要和上表对得上；最后一行 `GTOCore.blinkRange=` 是反射读值，装没装 GTOCore 一眼可见。
2. `/gtominifix probe`（需 OP）：**不实际传送**，直接打印当前视角下 GTO 的原始落点、`装得下玩家=是/否`、会被修正到哪 / 回退落点 / 会被取消。调参不用真去卡一次墙。
3. 游戏内实试：站在 1 格厚楼板上 shift + 右键向下穿墙，血条不掉、人不嵌墙。
4. 回归对照：在自由空间（落点本来就合法）对比打模组前后的落点坐标，必须完全一致 —— 这是那条不变量的直接验证，`/gtominifix probe` 能把两组数字直接打出来对比。

## 实测清单

- [ ] 1 格厚楼板垂直向下瞬移（bug 复现）→ 不掉血、不卡墙；落到合法点或取消传送
- [ ] 楼板下是 2 格以上空腔 → 落到楼下地板上（比原来低 1 格），不掉血
- [ ] 站在实心地面上向下瞬移 → 与不打模组一致（GTO 自己会因为距离太近不传送）
- [ ] 对着完整方块水平穿墙 → 落点、粒子、耗电与原来一致
- [ ] 向上穿天花板 → 与原来一致
- [ ] 落在台阶 / 地毯 / 雪层上 → 仍然能站上去
- [ ] 落点在水里 → 允许
- [ ] 旅行锚传送、锚上跳 / 蹲电梯 → 与原来一致
- [ ] 旁观者模式、坐在船 / 马上 → 模组完全不介入
- [ ] `/tp` 进墙里、末影珍珠、紫颂果 → 默认不受影响
- [ ] 把 GTO 配置里的 `blinkRange` 改成非 24 再 `/gtominifix probe` → 回退落点跟着变（说明反射读值成功）
- [ ] 3 格厚楼板、楼板下只有 1 格空腔、四周封闭，垂直向下瞬移 → 取消传送：人不动、播 `DISPENSER_FAIL`，**不会**再「原地传送」或往旁边挪
- [ ] 同上，但空腔旁边 2 格内有能站人的空位 → 挪到那个空位，不掉血（这就是「会向旁边传送」的正常形态）
- [ ] 1 格厚楼板、下面 2 格空腔，垂直向下瞬移 → 落到楼下地板上（比原来低 1 格），不掉血
- [ ] 落点上正好压着一条船 / 一只潜影贝 → 行为与没有那个实体时**一致**（不再被误判成非法落点）

## 已知边界与坑

- 玩家被瞬移进「四面封闭的 1 格空腔」时救不了 → **取消传送**（播 `DISPENSER_FAIL`，人不移动）。这是正确行为：那个位置本来就装不下玩家，「原地传送一次」只会白扣冷却和电、还播一个传送音效骗人。
- 判定用的是完整碰撞箱，比原版眼睛小盒严格。极端情况（只有贴着头顶边缘的薄碰撞形状与身体相交）下，原版不会扣血但本模组仍会挪动落点 —— 刻意的取舍，换来「一定不会卡进方块」。
- 判定**只看方块碰撞，不看实体碰撞**。代价是可能把玩家传送到与某个实体重叠的位置上，这和原版 `Entity#teleportTo` 的行为一致。
- 只在服务端修正位置，客户端粒子预览显示的仍是原始落点；取消传送时会白吃 128 EU + 5 tick 冷却（这两样本来由已删除的方案 B 免掉）。
- 配置是 COMMON 类型，客户端和服务端各读自己那份文件。多人游戏里两边配置不同时，服务端决定真实落点、客户端决定粒子预览，可能对不上（单人游戏没有这个问题）。

