package com.lai.gtominifix.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 安全落点计算。
 *
 * <p>纯函数工具类，不引用任何 GTOCore 类型，因此客户端（方案 B 的 Mixin）也能直接调用。
 *
 * <p>设计不变量（最重要的一条）：
 * <b>只要 GTO 算出来的落点本身装得下玩家，这里就原样返回，一个字节都不改。</b>
 * 这保证了穿墙、锚传送、电梯等一切正常场景的行为与不打模组时完全一致。
 *
 * <h2>为什么用完整碰撞箱判定，而不是复刻原版 {@code Entity#isInWall()}</h2>
 * {@code isInWall()} 只看眼睛处一个 0.48 × 1e-6 × 0.48 的扁平小盒。理论上「完整碰撞箱
 * 不与任何方块相交」是它的充分条件，反过来不成立。之所以刻意用更强的条件，是因为
 * <b>原版的窒息判定用的是「上一 tick 结束时定下来的姿态」</b>：
 * {@code Player#tick()} 里 {@code super.tick()}（内部 {@code LivingEntity#baseTick} 会调
 * {@code isInWall()} 扣血）在前、{@code updatePlayerPose()} 在后（Player.java:241 / 284）。
 * 也就是说瞬移落地那一 tick，姿态还是落地前站在地板上的那个（站立 1.62 眼高或下蹲 1.27），
 * 眼睛在旧姿态下可能正好卡在新位置上方的方块里 —— 于是每次向下瞬移都会白扣 1 点血。
 * 只按「当前姿态眼睛不卡住」来判定，就会把这个 bug 放过去。
 *
 * <h2>为什么不能无条件放宽到游泳姿态</h2>
 * 玩家 {@code Pose.SWIMMING} 的碰撞箱是 0.6 × 0.6（Player.java:130），比站立矮得多。
 * 但 bug 场景里那个空气格本来就只有 1 格高，0.6 高的游泳箱同样装得下 ——
 * 一旦无条件放宽，待修的落点会被判成合法，修复直接失效。所以只有落点确实在水里
 * （水里玩家本来就会变成游泳姿态）才放宽。
 */
public final class SafeLanding {

    private SafeLanding() {}

    /**
     * 实体以 {@code feet} 为脚底、站立姿态下的碰撞箱。
     *
     * <p>{@code EntityDimensions#makeBoundingBox} 的 x/z 是中心、y 是底面，
     * 正好和 GTO 的 {@code Vec3.atBottomCenterOf(target).add(0, floorHeight, 0)} 对齐。
     */
    public static AABB standingBox(Entity entity, Vec3 feet) {
        return entity.getDimensions(Pose.STANDING).makeBoundingBox(feet.x, feet.y, feet.z);
    }

    /**
     * 这个脚底位置能不能站（或游）得下该实体。
     *
     * <h2>为什么用 {@link #blockSpaceFree} 而不是 {@code level.noCollision(AABB)}</h2>
     * {@code noCollision(AABB)} 单参重载看起来只查方块，其实<b>会查实体碰撞</b>：
     * 它转发到 {@code noCollision(null, aabb)}（CollisionGetter 字节码），
     * 而 {@code CollisionGetter.noCollision(Entity, AABB)} 里方块之后紧接着就是
     * {@code getEntityCollisions(entity, aabb)}；entity 为 null 时谓词取
     * {@code EntitySelector.CAN_BE_COLLIDED_WITH}（= 非旁观 && 自身
     * {@code canBeCollidedWith()} 为真）。而 {@code Entity#canBeCollidedWith()} 默认返回
     * false，原版里只有 {@code Boat} 和 {@code Shulker} 覆写成 true。
     * 后果：船 / 潜影贝 / 任何把这个方法改写成 true 的模组实体压在落点上时，
     * 一个物理上完全合法的落点会被误判成非法，落点被挪走甚至传送被取消。
     * 而原版 {@code Entity#teleportTo} 根本不查实体碰撞，实体互相重叠是允许的，
     * 所以这里查它既不对也没用。
     *
     * <p>（顺便说明为什么也不用 {@code noCollision(entity, aabb)}：那个重载的谓词是
     * {@code NO_SPECTATORS.and(entity::canCollideWith)}，对船 / 潜影贝同样会命中，
     * 唯一区别是它还会额外查世界边界。这里保持「只查方块」，让世界边界的语义
     * 与不打模组时完全一致。）
     *
     * <p>另外这里刻意<b>不</b>用 {@code isOutsideBuildHeight(BlockPos)}：它把
     * {@code y == maxBuildHeight} 也算越界（LevelHeightAccessor 里是 {@code y >= maxBuildHeight}），
     * 但「脚底正好落在世界天花板」物理上是合法的 —— 世界顶格方块在 maxBuildHeight-1，
     * 站在它顶上脚底就等于 maxBuildHeight。GTO 自己的 {@code isTeleportPositionClear}
     * 只校验 {@code target.below()}，真的会算出这种落点，用 isOutsideBuildHeight
     * 会把合法的天花板站位误判成非法。
     */
    public static boolean fits(Level level, Entity entity, Vec3 feet) {
        // 只拒绝真正在世界外的落点：脚底低于世界底部（掉进虚空）或高于世界顶部。
        if (feet.y < level.getMinBuildHeight() || feet.y > level.getMaxBuildHeight()) {
            return false;
        }
        if (blockSpaceFree(level, entity, standingBox(entity, feet))) {
            return true;
        }
        // 只有落点确实在水里才放宽到游泳姿态（见类注释）。水里玩家会自己变成
        // 0.6 × 0.6 的游泳箱，一格高的水柱是合法落点，不该被误杀。
        return isWaterAt(level, feet)
                && blockSpaceFree(level, entity, entity.getDimensions(Pose.SWIMMING).makeBoundingBox(feet.x, feet.y, feet.z));
    }

    /**
     * 碰撞箱是否与任何<b>方块</b>碰撞箱都不相交。不含实体碰撞，理由见 {@link #fits}。
     *
     * <p>传 entity 是为了让 {@code CollisionContext} 正确（脚手架这类方块的碰撞箱
     * 依赖上下文），方块部分的行为与原 {@code noCollision(entity, aabb)} 完全一致。
     */
    private static boolean blockSpaceFree(Level level, Entity entity, AABB box) {
        for (VoxelShape shape : level.getBlockCollisions(entity, box)) {
            if (!shape.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 求一个安全的落脚点。
     *
     * <p>返回的落点保证<b>不是玩家自己正站着的那个方块</b>（见 {@link #accepts}）：
     * 救不了就返回 {@code null} 让调用方取消，而不是给一个「原地传送」的假成功。
     *
     * @param desired  GTO 原本算出来的落点（脚底坐标）
     * @return 安全落点；{@code null} 表示搜索范围内救不了，调用方应当取消本次传送
     */
    @Nullable
    public static Vec3 rescue(Level level, Entity entity, Vec3 desired,
                              int maxVertical, int maxHorizontal, boolean preferSolidGround) {
        if (fits(level, entity, desired)) {
            return desired; // ★ 不变量：合法就原样返回
        }
        if (preferSolidGround) {
            Vec3 grounded = search(level, entity, desired, maxVertical, maxHorizontal, true);
            if (grounded != null) {
                return grounded;
            }
        }
        return search(level, entity, desired, maxVertical, maxHorizontal, false);
    }

    /**
     * 按固定顺序枚举候选点，返回第一个合法的。
     *
     * <p>候选顺序是写死的，不做「按距离排序」——那会让落点随浮点误差变化、不可预测。
     *
     * <p>竖直偏移按 |dy| 递增枚举，同 |dy| 时<b>先向下再向上</b>。
     * 这一点与设计大纲里的 {@code {1, 2, -1, -2, -3}}（全上后下）不同，是有意改的：
     * 最常见的失败形态是「脚落在空腔顶部、头嵌进上方的天花板/地板」，
     * 此时向下挪 1 格正好是玩家真正想要的结果（继续穿到楼下），而向上挪 2 格
     * 等于把这次瞬移悄悄撤回。用大纲的顺序，楼板下是 2 格空腔时会退回原地，
     * 与大纲自己写的测试用例 2「正常穿到楼下」矛盾。
     */
    @Nullable
    private static Vec3 search(Level level, Entity entity, Vec3 desired,
                               int maxVertical, int maxHorizontal, boolean requireGround) {
        // 第 1 轮：同柱竖直微调
        Vec3 sameColumn = tryColumn(level, entity, desired, desired.y, maxVertical, requireGround);
        if (sameColumn != null) {
            return sameColumn;
        }
        // 第 2 轮：水平邻域（切比雪夫半径 1..maxHorizontal），每个格子再试一遍竖直偏移
        for (int r = 1; r <= maxHorizontal; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    Vec3 hit = tryColumn(level, entity, desired.add(dx, 0, dz), desired.y, maxVertical, requireGround);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 在 {@code base} 所在的那一列上，先试 base 本身，再按 |dy| 递增试上下偏移。
     *
     * @param desiredY 原始目标落点的 y，用来判断「这一侧算不算把瞬移撤回去」（见 {@link #accepts}）
     */
    @Nullable
    private static Vec3 tryColumn(Level level, Entity entity, Vec3 base, double desiredY,
                                  int maxVertical, boolean requireGround) {
        if (accepts(level, entity, base, desiredY, requireGround)) {
            return base;
        }
        for (int dy = 1; dy <= maxVertical; dy++) {
            Vec3 down = base.add(0, -dy, 0);
            if (accepts(level, entity, down, desiredY, requireGround)) return down;
            Vec3 up = base.add(0, dy, 0);
            if (accepts(level, entity, up, desiredY, requireGround)) return up;
        }
        return null;
    }

    private static boolean accepts(Level level, Entity entity, Vec3 feet, double desiredY, boolean requireGround) {
        if (!fits(level, entity, feet)) {
            return false;
        }
        // 两条「不许给出退化落点」的约束。它们保证 rescue 返回的位置要么是真把玩家挪走了，
        // 要么就返回 null 让调用方取消；绝不会出现「原地传送一次」——照扣冷却和电、
        // 照播音效，人却一步没动。
        //
        // (1) 修正到玩家本来站着的那个方块，等于这次瞬移根本没发生。
        //     必须判到方块级而不是 Vec3 级：玩家脚下的坐标是任意小数，候选点永远是方块中心，
        //     两者不会相等。反过来也不能放宽成「相邻方块」（distManhattan < 2），因为旅行锚 /
        //     电梯的落点本来就常常只离玩家 1 格（锚的落点是 anchorY+2，而玩家站在 anchorY+1），
        //     那属于正常传送，放宽会把锚传送整个废掉。
        //     只有候选点会走到这里：GTO 算出来的 desired 若就是玩家脚下那一格，
        //     rescue 第一行的 fits 判断已经原样放行（不变量），不会进搜索。
        if (BlockPos.containing(feet).equals(entity.blockPosition())) {
            return false;
        }
        // (2) 候选点必须落在「目标相对于玩家脚底的那一侧」，而且不许停在脚底那一层。
        //     向下瞬移却把人抬到脚底之上（或原地平移一格），同样是把这次瞬移撤回去，
        //     只是换了个方向 —— 玩家会看到「往下穿墙，结果人往上弹/往旁边挪」。
        //     目标高度与脚底齐平时（水平穿墙的常见情形）不做限制，只由 (1) 挡住自己那一格。
        double ownY = entity.getY();
        double side = desiredY - ownY;
        if (side != 0 && (feet.y - ownY) * side <= 0) {
            return false;
        }
        return !requireGround || hasGroundBelow(level, feet);
    }

    /** 脚底正下方那格是不是实心（给 {@code preferSolidGround} 用的软偏好）。 */
    private static boolean hasGroundBelow(Level level, Vec3 feet) {
        BlockPos below = BlockPos.containing(feet).below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    private static boolean isWaterAt(Level level, Vec3 feet) {
        return level.getFluidState(BlockPos.containing(feet)).is(FluidTags.WATER);
    }

    /**
     * 回退方案：放弃「带遍历」的落点，改用纯射线分支的结果，也就是
     * 「站在你瞄的那个方块顶上」。
     *
     * <p>复刻 {@code TravelHandler#teleportPosition} 里不含 {@code traverseBlocks} 的那一段
     * （GTOCore 的 {@code teleportPosition} 本身），不调用 GTOCore 的任何方法，
     * 所以 {@code blinkRange} 是反射读出来再传进来的。
     *
     * <p>末尾同样复刻了 GTO 的「落点离玩家自己的方块太近就当作没算出落点」
     * （{@code TravelHandler:198} 的 {@code distManhattan(target) < 2}）。
     * 不守这条的话，四面封闭的 1 格空腔会走到这里、算出「你脚下那块地板顶面」——
     * 也就是你自己的位置，于是变成「原地传送一次」：照扣 128 EU 和 5 tick 冷却、
     * 照播传送音效，人却只是被吸到方块中心。宁可返回 {@code null} 让调用方
     * 按 {@code cancelIfUnrescuable} 走「干脆不传」，语义更干净。
     *
     * @return 安全落点；{@code null} 表示连回退结果都装不下玩家，或者回退结果是个退化落点
     */
    @Nullable
    public static Vec3 raycastFallback(Level level, Player player, int blinkRange) {
        Vec3 from = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 to = from.add(look.scale(blinkRange));

        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, null));

        BlockPos target;
        if (hit.getType() == HitResult.Type.MISS) {
            target = hit.getBlockPos();
        } else if (hit.getType() == HitResult.Type.BLOCK) {
            Direction dir = hit.getDirection();
            if (dir == Direction.UP) {
                // 直接瞄到方块顶面：落点就是那个方块本身，稍后由 floorHeight 把人抬到它上面
                target = hit.getBlockPos();
            } else if (dir == Direction.DOWN) {
                target = hit.getBlockPos().below((int) Math.ceil(player.getBbHeight()));
            } else {
                target = hit.getBlockPos().offset(dir.getStepX(), 0, dir.getStepZ());
                if (level.getBlockState(target).getCollisionShape(level, target).isEmpty()) {
                    target = target.below();
                }
            }
        } else {
            return null;
        }

        // 与 GTO 一致：落到自己脚下那一格不算「传送」。
        if (player.blockPosition().distManhattan(target) < 2) {
            return null;
        }
        Optional<Double> floorHeight = isTeleportPositionClear(level, target.below());
        if (floorHeight.isEmpty()) {
            return null;
        }
        Vec3 landing = Vec3.atBottomCenterOf(target).add(0, floorHeight.get(), 0);
        // 回退结果同样要过一遍判定：宁可取消传送，也不能把人送进方块里。
        return fits(level, player, landing) ? landing : null;
    }

    /**
     * 复刻 GTOCore {@code TravelHandler#isTeleportPositionClear}（TravelHandler.java:270-288）。
     *
     * <p>返回的是「站在 {@code target.above()} 那格上时的地板高度」，
     * 里面那个 {@code height <= 0.2} 早退是有意为之的（原文 javadoc：为了能站到地毯上），
     * 但它假定传进来的是地板格 —— 遍历喂进来一个空气格时这层校验会退化成空操作，
     * 这正是 bug 的成因之一。这里只照抄计算，判定交给 {@link #fits}。
     *
     * <p>只给 {@link #raycastFallback} 用，所以是 private（GTOCore 那边是 public）。
     */
    private static Optional<Double> isTeleportPositionClear(BlockGetter level, BlockPos target) {
        if (level.isOutsideBuildHeight(target)) {
            return Optional.empty();
        }
        BlockPos above = target.above();
        double height = level.getBlockState(above).getCollisionShape(level, above).max(Direction.Axis.Y);
        if (height <= 0.2D) {
            return Optional.of(Math.max(height, 0));
        }
        above = above.above();
        BlockState state = level.getBlockState(above);
        VoxelShape shape = state.getCollisionShape(level, above);
        return shape.isEmpty() ? Optional.of(Math.max(height, 0)) : Optional.empty();
    }
}
