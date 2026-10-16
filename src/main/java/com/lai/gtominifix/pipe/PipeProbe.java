package com.lai.gtominifix.pipe;

import com.lai.gtominifix.GtoMiniFixConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 管道现场探针：把一台机器 / 一根管道<b>此刻</b>的关键内部状态打成可读文本。
 *
 * <p>动机是「跑一段时间才坏」这类问题没法靠猜：重启就好、过一会儿又坏，
 * 只有把坏掉那一刻的现场抓下来才知道是哪一层卡住。所以这里把可能卡住的三层
 * 一次性全打出来：
 * <ol>
 *   <li><b>邻居缓存</b>：{@code BlockEntityDirectionCache} 六个方向各自的槽
 *       （没查过 / 哨兵(失效) / 真正的方块实体），以及 {@code hasAdjacentTarget}、
 *       {@code getAdjacentKeyHandler} 的实时结果；</li>
 *   <li><b>管道</b>：网络对象、{@code netData} 里六个方向各自的路由表
 *       （目标管道坐标、目标面、过滤器、handler 是否拿得到）、限流计数器；</li>
 *   <li><b>机器</b>：{@code io}、工作状态、正面、储罐内容、自动输出订阅是否还在，
 *       以及六个邻居的流体能力与「塞 1000 mB 进去能收多少」（模拟，不真的塞）。</li>
 * </ol>
 *
 * <p><b>全部走反射</b>：本模组对 GT / AE2 没有编译期依赖，探针也不能有 ——
 * 对方改了签名只会让对应几行显示「读不到」，不会让命令报错、更不会崩游戏。
 */
public final class PipeProbe {

    private PipeProbe() {}

    /** 探针主入口：返回若干行文本（调用方负责显示 / 落盘）。 */
    public static List<String> probe(Level level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        out.add("---- 探针 " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " ----");
        if (level == null || pos == null) {
            out.add("  level / pos 为空");
            return out;
        }
        try {
            BlockEntity be = level.getBlockEntity(pos);
            out.add("  方块=" + level.getBlockState(pos).getBlock());
            out.add("  维度=" + level.dimension().location() + "  gameTime=" + level.getGameTime());
            if (be == null) {
                out.add("  该坐标没有方块实体");
            } else {
                out.add("  BE=" + be.getClass().getName() + (be.isRemoved() ? "  【已移除!】" : ""));
                dumpSelfHandler(be, out);
                dumpNeighbourCache(level, be, pos, out);
                dumpPipe(level, be, pos, out);
                dumpMachine(be, out);
            }
            dumpNeighbours(level, pos, be, out);
            dumpFixes(out);
        } catch (Throwable t) {
            out.add("  探针自身出错（已忽略）：" + t);
        }
        return out;
    }

    /** 把探针结果写进调试文件（配置关掉时什么都不做）。 */
    public static void logProbe(Level level, BlockPos pos) {
        PipeDebugLog.logLines(probe(level, pos));
    }

    // ---------------------------------------------------------------- 各段

    /** 这台方块实体自己对外提供的 Forge 流体能力（六个面各看一次）。 */
    private static void dumpSelfHandler(BlockEntity be, List<String> out) {
        out.add("  -- 自身 Forge 流体能力 --");
        boolean any = false;
        for (Direction d : Direction.values()) {
            IFluidHandler h = handler(be, d);
            if (h != null) {
                any = true;
                out.add("    [" + name(d) + "] " + describe(h));
            }
        }
        if (!any) {
            out.add("    六个面都拿不到 IFluidHandler（GTO 的机器可能只走 AE2 的 key 体系）");
        }
    }

    /** 邻居缓存：每个方向「世界真值」对比「缓存里存了什么」。 */
    private static void dumpNeighbourCache(Level level, BlockEntity be, BlockPos pos, List<String> out) {
        Object cache = read(be, "blockEntityDirectionCache");
        out.add("  -- 邻居缓存 --");
        if (cache == null) {
            out.add("    该方块实体没有 blockEntityDirectionCache 字段（不是 GTO 机器 / 管道）");
            return;
        }
        out.add("    " + cache.getClass().getName());
        Object sentinel = readStatic(cache.getClass(), "NULL");
        Object[] slots = asArray(read(cache, "array"));
        Object fluids = aeKeyTypeFluids();
        Object insert = storageAccess("INSERT");
        Object extract = storageAccess("EXTRACT");
        for (Direction d : Direction.values()) {
            Object slot = slots != null && d.ordinal() < slots.length ? slots[d.ordinal()] : null;
            String cacheText;
            if (slot == null) {
                cacheText = "未查过(null)";
            } else if (slot == sentinel) {
                cacheText = "哨兵→该方向被判为无邻居!!";
            } else if (slot instanceof BlockEntity cached) {
                cacheText = cached.getClass().getSimpleName() + (cached.isRemoved() ? "(已移除!)" : "");
            } else {
                cacheText = slot.getClass().getName() + "（非 BE 对象）";
            }
            BlockEntity truth = level.getBlockEntity(pos.relative(d));
            String truthText = truth == null ? "空" : truth.getClass().getSimpleName();
            out.add("    [" + name(d) + "] 世界=" + truthText + "  缓存=" + cacheText
                    + "  hasAdjacentTarget(INS/EXT)=" + boolText(call(cache, "hasAdjacentTarget", level, pos, d, fluids, insert))
                    + "/" + boolText(call(cache, "hasAdjacentTarget", level, pos, d, fluids, extract))
                    + "  keyHandler(INS)=" + (call(cache, "getAdjacentKeyHandler", level, pos, d, fluids, insert) != null ? "有" : "无")
                    + "  keyHandler(EXT)=" + (call(cache, "getAdjacentKeyHandler", level, pos, d, fluids, extract) != null ? "有" : "无"));
        }
    }

    /** 管道：网络对象 + 六个方向各自 getNetData 拿到的路由表 + 限流计数器。 */
    private static void dumpPipe(Level level, BlockEntity be, BlockPos pos, List<String> out) {
        Object net = unwrap(read(be, "net"));
        if (net == null) {
            net = unwrap(read(be, "currentFluidPipeNet"));
        }
        Object netData = read(be, "netData");
        if (net == null && netData == null) {
            return; // 不是管道
        }
        out.add("  -- 管道 --");
        out.add("    net=" + (net == null ? "null（网络没建起来 / 不在网络里）" : net.getClass().getName())
                + "  autoTransfer=" + read(be, "autoTransfer")
                + "  blockedSide=" + read(be, "blockedSide")
                + "  isInValid=" + call(be, "isInValid"));
        out.add("    transferredFluids=" + read(be, "transferredFluids")
                + "  timer=" + read(be, "timer")
                + "  levelTime=" + call(be, "getLevelTime"));
        Object nodeData = call(be, "getNodeData");
        if (nodeData != null) {
            out.add("    nodeData.throughput=" + call(nodeData, "getThroughput")
                    + "（每 20 tick 的预算 = throughput * 20）");
        }
        if (net == null) {
            return;
        }
        Object map = read(net, "netData");
        out.add("    net.netData 条目数=" + sizeOf(map));
        Object pipeLongPos = call(be, "getPipeLongPos");
        Object pipePos = call(be, "getPipePos");
        if (pipeLongPos == null || pipePos == null) {
            out.add("    读不到 getPipeLongPos / getPipePos，跳过路由表");
            return;
        }
        StringBuilder blocked = new StringBuilder();
        for (Direction d : Direction.values()) {
            if (Boolean.TRUE.equals(call(be, "isBlocked", d))) {
                blocked.append(name(d)).append(' ');
            }
        }
        out.add("    isBlocked(逐面)=" + (blocked.length() == 0 ? "六个面都没被扳手阻塞" : blocked.toString()));
        // 只读：缓存里那张表（键只有管道坐标，六面共用同一张）。
        // 绝不能再调 getNetData —— 那会在 miss 时建表并写进缓存，把现场毁掉。
        Object cachedTable = map == null ? null : call(map, "get", pipeLongPos);
        Object[] cachedRoutes = cachedTable == null ? null : asArray(read(cachedTable, "array"));
        out.add("    缓存里本管道的表=" + (cachedRoutes == null
                ? "无（没人 insert 过，或刚被清过）"
                : cachedRoutes.length + " 条路由（六面共用这一张：键只有管道坐标）"));
        dumpInsertDiagnosis(level, be, pos, out);
    }

    /**
     * 插入诊断：回答「这根管道为什么收不下流体」。
     *
     * <p>做法：取一份源流体 → 量一次管道收多少 → 清空该网络的路由表缓存 → 再量一次。
     * 两次之差直接说明「缓存路由表」是不是元凶；再逐条看新表里每个目标能不能收，
     * 用来区分「目标都满」与「过滤谓词把路由挡了」。
     */
    private static void dumpInsertDiagnosis(Level level, BlockEntity be, BlockPos pos, List<String> out) {
        out.add("  -- 插入诊断（源机器 → 相邻管道） --");
        // 1) 找一份源流体：优先用探针方块自己的储罐，否则找第一个有流体的邻居
        FluidStack source = firstFluid(be);
        BlockPos sourcePos = source.isEmpty() ? null : pos;
        if (source.isEmpty()) {
            for (Direction d : Direction.values()) {
                BlockPos np = pos.relative(d);
                BlockEntity nbe = level.getBlockEntity(np);
                if (nbe == null) {
                    continue;
                }
                FluidStack fs = firstFluid(nbe);
                if (!fs.isEmpty()) {
                    source = fs;
                    sourcePos = np;
                    break;
                }
            }
        }
        if (source.isEmpty() || sourcePos == null) {
            out.add("    探针方块与六向邻居的储罐都是空的，拿不到源流体，跳过");
            return;
        }
        // 2) 从源方块出发找相邻的 GT 流体管道（优先按类名，退化按「有流体能力」）
        BlockEntity pipe = null;
        Direction srcToPipe = null;
        for (Direction d : Direction.values()) {
            BlockEntity nbe = level.getBlockEntity(sourcePos.relative(d));
            if (nbe != null && nbe.getClass().getName().contains("FluidPipeBlockEntity")) {
                pipe = nbe;
                srcToPipe = d;
                break;
            }
        }
        if (pipe == null) {
            for (Direction d : Direction.values()) {
                BlockEntity nbe = level.getBlockEntity(sourcePos.relative(d));
                if (nbe != null && tryFill(nbe, d.getOpposite(), source) >= 0L) {
                    pipe = nbe;
                    srcToPipe = d;
                    break;
                }
            }
        }
        out.add("    源流体=" + fluidName(source) + "  源方块=" + sourcePos.toShortString()
                + "  源方块类=" + (level.getBlockEntity(sourcePos) == null ? "?" : level.getBlockEntity(sourcePos).getClass().getSimpleName()));
        if (pipe == null) {
            out.add("    源方块六向都没有流体管道/流体能力，流体无处可去（机器正面没对管道？）");
            return;
        }
        Direction pipeSide = srcToPipe.getOpposite();
        BlockPos pipePos = sourcePos.relative(srcToPipe);
        out.add("    相邻管道=" + pipePos.toShortString() + "  类=" + pipe.getClass().getSimpleName()
                + "  从管道看源方块的入流面=" + name(pipeSide));
        out.add("    源方块朝管道那面的盖板=" + coverText(coverAt(level, sourcePos, srcToPipe))
                + "（GTO insert 用它判 checkImportCover：EXTRACT 模式且流体不匹配就会直接拒收）");
        // 管道上装了哪些盖板（过滤盖/闸门会进路由谓词，是最可疑的元凶）
        Object coverContainer = call(pipe, "getCoverContainer");
        if (coverContainer != null) {
            for (Direction d : Direction.values()) {
                Object cov = call(coverContainer, "getCoverAtSide", d);
                if (cov != null) {
                    Object mode = call(cov, "getFilterMode");
                    out.add("      盖板[" + name(d) + "] " + cov.getClass().getSimpleName()
                            + (mode == null ? "" : " 过滤模式=" + mode));
                }
            }
        }
        // 3) 只读：量一次「这根管道此刻收不收」，并列出缓存里那张表的内容。
        //    绝不写表（不调 getNetData / onPipeConnectionsUpdate / netData.clear），否则现场被毁。
        long pipeGot = tryFill(pipe, pipeSide, source);
        out.add("    管道此刻收 " + pipeGot + " mB / 1000 mB" + (pipeGot == 0L ? "（拒收）" : "") + "   ← Forge 能力层，仅供参考");
        Object net = unwrap(read(pipe, "net"));
        if (net == null) {
            net = unwrap(read(pipe, "currentFluidPipeNet"));
        }
        if (net == null) {
            out.add("    拿不到网络对象（net=null）→ 管道 checkNetwork 没连上网络，insert 会直接返回 0");
            return;
        }
        Object map = read(net, "netData");
        Object pipeLongPos = call(pipe, "getPipeLongPos");
        out.add("    路由表条目数=" + sizeOf(map) + "（只读）");
        Object cached = map == null || pipeLongPos == null ? null : call(map, "get", pipeLongPos);
        if (cached == null) {
            out.add("    缓存表：本管道没有条目（还没人 insert 过，或被清过）→ 下一个 insert 的机器将决定这张表");
            return;
        }
        Object[] routes = asArray(read(cached, "array"));
        if (routes == null) {
            out.add("    缓存表：读不到 array");
            return;
        }
        out.add("    缓存表内容（只读，" + routes.length + " 条；键只有管道坐标 → 六面共用这一张）：");
        int accept = 0;
        int backflow = 0;
        int shown = 0;
        int filtered = 0;
        for (int idx = 0; idx < routes.length; idx++) {
            Object r = routes[idx];
            if (r == null) {
                continue;
            }
            Object tp = call(r, "getTargetPipePos");
            Object tf = call(r, "getTargetFacing");
            if (!(tp instanceof BlockPos tpBp) || !(tf instanceof Direction dir)) {
                continue;
            }
            BlockPos targetBp = tpBp.relative(dir);
            BlockEntity target = level.getBlockEntity(targetBp);
            long got = target == null ? -1L : tryFill(target, dir.getOpposite(), source);
            if (got > 0L) {
                accept++;
            }
            if (targetBp.equals(sourcePos)) {
                backflow++;
            }
            Object filters = read(r, "filters");
            Object verdict = filters == null ? null : call(filters, "test", source);
            boolean pass = Boolean.TRUE.equals(verdict);
            if (filters != null && !pass) {
                filtered++;
            }
            if (shown < 12) {
                out.add("      [" + idx + "] → " + targetBp.toShortString() + " 面=" + name(dir)
                        + " 类=" + (target == null ? "无方块实体" : target.getClass().getSimpleName())
                        + (targetBp.equals(sourcePos) ? "  ★回流：指向源方块自己" : "")
                        + " 过滤=" + (filters == null ? "无" : (verdict == null ? "?" : (pass ? "通过" : "拒绝")))
                        + " 目标盖板=" + coverText(coverAt(level, targetBp, dir.getOpposite()))
                        + " 收=" + (got < 0L ? "无能力" : got + "mB"));
                shown++;
            }
        }
        out.add("    缓存表小结：" + routes.length + " 条路由，目标能收 1000mB 的 " + accept
                + " 条，过滤谓词拒绝的 " + filtered + " 条，指向源方块自己的（回流）" + backflow + " 条");
        out.add("    判读（「能收」在 Forge 能力层量的；GTO 机器真正的路径是 AE2 键层，以 /gtominifix pipetrace 的 handler.insert 数字为准）："
                + "缓存表里出现源方块自己且排在前几条 ＝ 自吃（源机器的流体出门又回自己缸、把配额吃满）；"
                + "缓存表里没有源方块 ＝ 这张表是它自己建的（正常）；可收=0 ＝ 目标都满/没能力");
    }

    /**
     * 机器：io / 工作状态 / 正面 / 储罐内容 / 自动输出订阅。
     *
     * <p>关键：GTO 的机器状态（tank / io / autoIOSubs / getFrontFacing）挂在
     * {@code MetaMachineBlockEntity.metaMachine} 指的那台机器对象上，**不在 BE 上**；
     * 从 BE 直接读只会读到 null（早期探针就吃过这个亏）。这里统一下钻。
     */
    private static void dumpMachine(BlockEntity be, List<String> out) {
        Object machine = machineOf(be);
        Object host = machine != null ? machine : be;
        Object tank = tankOf(host);
        Object io = read(host, "io");
        Object subs = read(host, "autoIOSubs");
        Object monitor = read(host, "autoIOMonitor");
        Object tankSubs = read(host, "tankSubs");
        if (tank == null && io == null && subs == null) {
            return; // 不是机器
        }
        out.add("  -- 机器 --" + (machine != null
                ? "（metaMachine=" + machine.getClass().getSimpleName() + "）"
                : "（BE 上没有 metaMachine 字段，直接读的 BE）"));
        out.add("    io=" + io
                + "  workingEnabled=" + call(host, "isWorkingEnabled")
                + "  frontFacing=" + call(host, "getFrontFacing")
                + "  autoIOSubs=" + (subs == null ? "null（自动输出没在订阅!!）" : "在订阅")
                + "  autoIOMonitor=" + (monitor == null ? "null" : "有")
                + "  tankSubs=" + (tankSubs == null ? "null" : "有"));
        if (tank == null) {
            return;
        }
        out.add("    tank=" + tank.getClass().getSimpleName()
                + "  capabilityIO=" + call(tank, "getCapabilityIO")
                + "  locked=" + call(tank, "isLocked")
                + "  lockedKey=" + call(tank, "getLockedKey"));
        Object size = call(tank, "size");
        if (size instanceof Integer n) {
            for (int i = 0; i < n; i++) {
                out.add("      槽" + i + ": " + call(tank, "keyAt", i) + " " + call(tank, "amountAt", i)
                        + "/" + call(tank, "slotLimit", i) + "mB");
            }
        } else {
            out.add("      读不到储罐内容（size() 不可用）");
        }
    }

    /** 六个邻居：类别 + 流体能力 + 「塞 1000 mB 进去能收多少」（模拟）。 */
    private static void dumpNeighbours(Level level, BlockPos pos, BlockEntity self, List<String> out) {
        out.add("  -- 六向邻居 --");
        FluidStack probeFluid = self == null ? FluidStack.EMPTY : firstFluid(self);
        for (Direction d : Direction.values()) {
            BlockPos np = pos.relative(d);
            BlockEntity nbe = level.getBlockEntity(np);
            if (nbe == null) {
                out.add("    [" + name(d) + "] 空（" + level.getBlockState(np).getBlock() + "）");
                continue;
            }
            StringBuilder sb = new StringBuilder("    [" + name(d) + "] " + nbe.getClass().getSimpleName()
                    + " " + np.getX() + "," + np.getY() + "," + np.getZ());
            IFluidHandler h = handler(nbe, d.getOpposite());
            if (h == null) {
                sb.append("  流体能力=无");
            } else {
                sb.append("  ").append(describe(h));
                if (!probeFluid.isEmpty()) {
                    try {
                        FluidStack probe = probeFluid.copy();
                        probe.setAmount(1000);
                        sb.append("  fill(1000mB ").append(probeFluid.getFluid()).append(" 模拟)=")
                                .append(h.fill(probe, IFluidHandler.FluidAction.SIMULATE)).append("mB");
                    } catch (Throwable t) {
                        sb.append("  fill 试算失败(").append(t.getClass().getSimpleName()).append(')');
                    }
                }
            }
            out.add(sb.toString());
        }
    }

    private static void dumpFixes(List<String> out) {
        out.add("  -- 本模组 --");
        out.add("    " + coremodStatus());
        out.add("    debugFile=" + GtoMiniFixConfig.pipeDebugFile + "  日志=" + PipeDebugLog.pathText());
    }

    /**
     * 本模组管道侧的状态：只剩两个观察点，监听默认关（{@code /gtominifix pipewatch} 打开）。
     * 不能靠 CoreMod 自己的日志确认：JS 里写不了文件，{@code print()} 也只进启动器控制台。
     */
    private static String coremodStatus() {
        return "监听: " + PipeNetNotify.statusText();
    }

    /** 一行摘要，给 {@link PipeWatch} 逐秒记录用：只保留「卡住时会变」的那几个量。 */
    public static String compact(Level level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                return "无方块实体";
            }
            StringBuilder sb = new StringBuilder(be.getClass().getSimpleName());
            Object cache = read(be, "blockEntityDirectionCache");
            Object sentinel = cache == null ? null : readStatic(cache.getClass(), "NULL");
            Object[] slots = cache == null ? null : asArray(read(cache, "array"));
            int known = 0;
            int poison = 0;
            if (slots != null) {
                for (Object s : slots) {
                    if (s != null) {
                        known++;
                        if (s == sentinel) {
                            poison++;
                        }
                    }
                }
            }
            Object machine = machineOf(be);
            Object host = machine != null ? machine : be;
            Object front = call(host, "getFrontFacing");
            Object fluids = aeKeyTypeFluids();
            Object insert = storageAccess("INSERT");
            sb.append(" 缓存:已查=").append(known).append(" 哨兵=").append(poison)
                    .append(" front=").append(front)
                    .append(" hasTarget(INS)=").append(boolText(call(cache, "hasAdjacentTarget", level, pos, front, fluids, insert)));
            Object tank = tankOf(host);
            if (tank != null) {
                sb.append(" tank0=").append(call(tank, "keyAt", 0)).append(' ').append(call(tank, "amountAt", 0)).append("mB");
            }
            sb.append(" autoIOSubs=").append(read(host, "autoIOSubs") == null ? "null!" : "on");
            Object net = unwrap(read(be, "net"));
            if (net != null) {
                // 只读缓存里那张表：绝不调 getNetData（那会建表写缓存，把现场毁掉）
                Object plp = call(be, "getPipeLongPos");
                Object map = read(net, "netData");
                Object table = map == null || plp == null ? null : call(map, "get", plp);
                Object[] arr = table == null ? null : asArray(read(table, "array"));
                int routes = 0;
                int dead = 0;
                if (arr != null) {
                    for (Object r : arr) {
                        routes++;
                        if (call(r, "getHandler", level) == null) {
                            dead++;
                        }
                    }
                }
                sb.append(" 缓存表=").append(arr == null ? "无" : routes + "条")
                        .append(" handler为null=").append(dead)
                        .append(" transferred=").append(read(be, "transferredFluids"));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "compact 出错（已忽略）：" + t;
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * GTO 的机器对象：{@code MetaMachineBlockEntity.metaMachine}（public final 字段）。
     * 读不到就退回 BE 自己，保证探针在任何方块上都不会抛。
     */
    static Object machineOf(BlockEntity be) {
        if (be == null) {
            return null;
        }
        Object m = read(be, "metaMachine");
        if (m == null) {
            m = call(be, "getMetaMachine");
        }
        return m;
    }

    /**
     * 机器对象上的流体储罐。各机器的字段名不统一（流体仓叫 {@code tank}，
     * 蒸馏室之类叫 {@code inputTank} / {@code outputTank}…），只认 "tank"
     * 会让探针对一半机器失效，所以先按名字找，再扫一遍所有字段。
     */
    static Object tankOf(Object machine) {
        if (machine == null) {
            return null;
        }
        Object direct = read(machine, "tank");
        if (isTank(direct)) {
            return direct;
        }
        for (Class<?> c = machine.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(machine);
                    if (isTank(v)) {
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 看起来像 GTO 的流体储罐（NotifiableInventory / 有 keyAt+amountAt 的容器）。 */
    private static boolean isTank(Object o) {
        if (o == null) {
            return false;
        }
        String n = o.getClass().getName();
        if (n.contains("NotifiableInventory") || n.contains("NotifiableFluidTank")) {
            return true;
        }
        try {
            o.getClass().getMethod("keyAt", int.class);
            o.getClass().getMethod("amountAt", int.class);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static IFluidHandler handler(BlockEntity be, Direction side) {
        try {
            return be.getCapability(ForgeCapabilities.FLUID_HANDLER, side).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describe(IFluidHandler h) {
        StringBuilder sb = new StringBuilder();
        try {
            int n = h.getTanks();
            sb.append(n).append(" 槽");
            for (int i = 0; i < n; i++) {
                FluidStack fs = h.getFluidInTank(i);
                sb.append("  [").append(i).append(']')
                        .append(fs.isEmpty() ? "空" : fluidName(fs) + " " + fs.getAmount())
                        .append('/').append(h.getTankCapacity(i)).append("mB");
            }
        } catch (Throwable t) {
            sb.append(" 读取失败(").append(t.getClass().getSimpleName()).append(')');
        }
        return sb.toString();
    }

    /** 某个方块实体某一面装的盖板（过滤盖/闸门/泵等），没有就返回 null。 */
    private static Object coverAt(Level level, BlockPos pos, Direction side) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        Object container = call(be, "getCoverContainer");
        return container == null ? null : call(container, "getCoverAtSide", side);
    }

    private static String coverText(Object cover) {
        if (cover == null) {
            return "无";
        }
        Object mode = call(cover, "getFilterMode");
        return cover.getClass().getSimpleName() + (mode == null ? "" : "/" + mode);
    }

    private static FluidStack firstFluid(BlockEntity be) {
        for (Direction d : Direction.values()) {
            IFluidHandler h = handler(be, d);
            if (h == null) {
                continue;
            }
            try {
                for (int i = 0; i < h.getTanks(); i++) {
                    FluidStack fs = h.getFluidInTank(i);
                    if (!fs.isEmpty()) {
                        return fs;
                    }
                }
            } catch (Throwable ignored) {
                // 换下一个面
            }
        }
        return FluidStack.EMPTY;
    }

    /** 流体注册名（例如 {@code gtceu:light_fuel}），比对象哈希好认得多。 */
    static String fluidName(FluidStack fs) {
        if (fs == null || fs.isEmpty()) {
            return "空";
        }
        try {
            net.minecraft.resources.ResourceLocation id = BuiltInRegistries.FLUID.getKey(fs.getFluid());
            return id == null ? String.valueOf(fs.getFluid()) : id.toString();
        } catch (Throwable t) {
            return String.valueOf(fs.getFluid());
        }
    }

    /** 用 Forge 流体能力从这个面试塞 1000 mB（模拟）；-1 = 这个面没有流体能力。 */
    private static long tryFill(BlockEntity be, Direction side, FluidStack source) {
        IFluidHandler h = handler(be, side);
        if (h == null) {
            return -1L;
        }
        try {
            return h.fill(new FluidStack(source.getFluid(), 1000), IFluidHandler.FluidAction.SIMULATE);
        } catch (Throwable t) {
            return -1L;
        }
    }

    static Object aeKeyTypeFluids() {
        try {
            return Class.forName("appeng.api.stacks.AEKeyTypes").getField("FLUIDS").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object storageAccess(String constant) {
        try {
            Class<?> c = Class.forName("appeng.api.storage.StorageAccess");
            if (c.isEnum()) {
                for (Object o : c.getEnumConstants()) {
                    if (o instanceof Enum<?> e && e.name().equals(constant)) {
                        return o;
                    }
                }
            }
            return c.getField(constant).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String name(Direction d) {
        return d.getName().toLowerCase(java.util.Locale.ROOT);
    }

    private static String cls(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    private static String boolText(Object o) {
        return o instanceof Boolean b ? (b ? "true" : "false") : "?";
    }

    private static String sizeOf(Object map) {
        if (map == null) {
            return "?";
        }
        try {
            Method m = map.getClass().getMethod("size");
            return String.valueOf(m.invoke(map));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static Object unwrap(Object o) {
        return o instanceof WeakReference<?> ref ? ref.get() : o;
    }

    static Object[] asArray(Object o) {
        return o instanceof Object[] arr ? arr : null;
    }

    private static Field findField(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    static Object read(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field f = findField(target.getClass(), name);
            return f == null ? null : f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object readStatic(Class<?> start, String name) {
        try {
            Field f = findField(start, name);
            return f == null ? null : f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> start, String name, int params) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == params) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** 按「方法名 + 参数个数」找并调用；任何失败都返回 null（探针不许抛异常）。 */
    static Object call(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method m = findMethod(target.getClass(), name, args.length);
            if (m == null) {
                return null;
            }
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
