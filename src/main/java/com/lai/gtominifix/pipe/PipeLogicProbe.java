package com.lai.gtominifix.pipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管道「真实逻辑」探针：不猜、不推断，把机器和管道<b>此刻真正用来传输的那条调用链上的对象</b>抓出来。
 *
 * <p>和 {@link PipeProbe} 的分工：{@code PipeProbe} 打的是「状态快照」（缓存槽、路由表、
 * 储罐内容）；这里打的是「这条边到底通不通」，即机器输出时实际会走的那几行：
 * <pre>
 *   NotifiableInventory.exportToNearby(front)
 *     → m.holder.blockEntityDirectionCache.getAdjacentKeyHandler(level,pos,front,FLUIDS,INSERT)
 *       → BlockEntityDirectionCache.getAdjacentBlockEntity(level,pos,front)
 *       → KeyTarget.find(be, side, FLUIDS, INSERT)
 *     → KeyTransfer.transfer(inv, handler, MAX, filter)
 *       → handler.insert(key, amount, simulate)      ← FluidNetHandler.insert
 * </pre>
 * 所以这里做四件事：
 * <ol>
 *   <li>把这条链上每一环的<b>对象身份</b>打出来（{@code #identityHashCode} + 类名），
 *       尤其：机器拿到的 handler 是不是<b>当前世界那根管道</b>的 handler；</li>
 *   <li>handler 里那两个可疑字段：{@code pipe}（管道方块实体）与 {@code net}（网络），
 *       并和管道自己的 {@code currentFluidPipeNet} / {@code getFluidPipeNet()} 逐个比对身份；</li>
 *   <li>直接调用 {@code handler.insert(key, 1000, true)} —— 这是机器真正会走的那一次调用，
 *       返回 0 就是「这一面通不了」，比任何快照都直接；</li>
 *   <li>管道的每个面用 {@code FluidNetWalker.createNetData(...)} <b>当场重新寻路</b>
 *       （纯计算，不写 {@code netData} 缓存），看这一面「按现在的世界」能走出哪些路由。</li>
 * </ol>
 *
 * <p>全程只读：不写缓存、不改世界、不真的传流体（insert 一律 {@code simulate=true}）。
 * 每段都单独 try/catch，读不到只显示「读不到」，不会让命令或游戏出错。
 */
public final class PipeLogicProbe {

    private static final String HANDLER_MARK = "FluidNetHandler";
    private static final String WALKER = "com.gregtechceu.gtceu.common.pipelike.fluid.FluidNetWalker";

    /** 存在这个文件就表示「CoreMod 源头补丁被关掉」（放在实例的 {@code config/} 下）。 */
    public static final String FLAG_FILE = "gtominifix-pipe-patch.off";

    private PipeLogicProbe() {}

    /** CoreMod 补丁是否生效：直接看两个补丁各自的存活计数（比翻日志可靠，coremod 里写不了文件）。 */
    public static String coremodStatus() {
        return PipeNetNotify.statusText();
    }

    /** 开关文件状态（存在 = 补丁被关掉）。 */
    public static String flagState() {
        try {
            java.nio.file.Path flag = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("config").resolve(FLAG_FILE);
            return java.nio.file.Files.exists(flag) ? "补丁已关闭(" + flag + ")" : "补丁未关闭(无开关文件)";
        } catch (Throwable t) {
            return "读不到(" + t.getClass().getSimpleName() + ")";
        }
    }

    /** 完整逻辑探针。 */
    public static List<String> probe(Level level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        out.add("==== 逻辑探针 " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + "  gameTime=" + (level == null ? "?" : level.getGameTime()) + " ====");
        if (level == null || pos == null) {
            out.add("  level / pos 为空");
            return out;
        }
        try {
            BlockEntity be = level.getBlockEntity(pos);
            out.add("  方块=" + level.getBlockState(pos).getBlock()
                    + "  BE=" + (be == null ? "无" : cls(be) + "(#" + ident(be) + ")" + (be.isRemoved() ? " 【已移除!】" : "")));
            out.add("  维度=" + level.dimension().location());
            if (be == null) {
                return out;
            }
            Object machine = PipeProbe.machineOf(be);
            Object tank = machine == null ? null : PipeProbe.tankOf(machine);
            if (machine != null) {
                machineSection(level, be, machine, tank, pos, out);
            }
            if (isPipe(be)) {
                pipeSection(level, be, pos, out);
            } else {
                boolean any = false;
                for (Direction d : Direction.values()) {
                    BlockEntity n = level.getBlockEntity(pos.relative(d));
                    if (n != null && isPipe(n)) {
                        any = true;
                        out.add("  -- 相邻管道 [" + name(d) + "]（这台机器输出时会走这一面）--");
                        pipeSection(level, n, pos.relative(d), out);
                    }
                }
                if (!any) {
                    out.add("  这台方块实体六个方向都没有流体管道（探针到此为止）");
                }
            }
        } catch (Throwable t) {
            out.add("  探针自身出错（已忽略）：" + t);
        }
        return out;
    }

    /** 一行摘要，给逐秒观察用（只看这台方块实体本身，不看邻居）。 */
    public static String compactLine(Level level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                return "be=null";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("be=").append(simple(be)).append("#").append(ident(be)).append(be.isRemoved() ? "【已移除】" : "");
            Object machine = PipeProbe.machineOf(be);
            Object tank = machine == null ? null : PipeProbe.tankOf(machine);
            if (machine != null) {
                Object front = PipeProbe.call(machine, "getFrontFacing");
                sb.append(" | io=").append(PipeProbe.read(machine, "io"));
                sb.append(" work=").append(PipeProbe.call(machine, "isWorkingEnabled"));
                sb.append(" front=").append(front);
                sb.append(" subs=").append(identOrNull(PipeProbe.read(machine, "autoIOSubs")));
                sb.append(" tank=").append(tankText(tank));
                Object ownKey = tankKey(tank);
                boolean borrowedKey = false;
                if (ownKey == null) {
                    ownKey = keyAround(level, pos, true);
                    borrowedKey = ownKey != null;
                }
                if (ownKey != null && tank != null) {
                    sb.append(" 本机insert(").append(ownKey).append(borrowedKey ? "借" : "").append(")=").append(PipeProbe.call(tank, "insert", ownKey, 1000L, true));
                }
                Object cache = PipeProbe.read(be, "blockEntityDirectionCache");
                if (cache != null && front instanceof Direction d) {
                    Object slot = PipeProbe.call(cache, "getCache", d);
                    BlockEntity world = level.getBlockEntity(pos.relative(d));
                    sb.append(" | 世界=").append(world == null ? "无" : simple(world) + "#" + ident(world));
                    sb.append(" 缓存=").append(slotText(slot));
                    Object h = PipeProbe.call(cache, "getAdjacentKeyHandler", level, pos, d, fluidsType(), access("INSERT"));
                    sb.append(" handler=").append(identOrNull(h));
                    if (h != null && cls(h).contains(HANDLER_MARK)) {
                        Object pipe = PipeProbe.read(h, "pipe");
                        Object net = PipeProbe.read(h, "net");
                        if (pipe instanceof BlockEntity pb) {
                            sb.append(" hpipe.removed=").append(pb.isRemoved()).append(" hpipe==世界? ").append(pb == world);
                        }
                        sb.append(" hnet=").append(identOrNull(net)).append(" hnet==get? ").append(net != null && net == PipeProbe.call(pipe, "getFluidPipeNet"));
                    }
                    Object key = tankKey(tank);
                    if (key != null && h != null) {
                        sb.append(" insert=").append(PipeProbe.call(h, "insert", key, 1000L, true));
                    }
                }
                // 相邻的流体管道：每秒记一次「此刻这根管道收不收」——卡住的那一刻就落在这一行
                Object anyKey = tankKey(tank);
                if (anyKey == null) {
                    anyKey = pipeKey(level, pos);
                }
                if (anyKey != null) {
                    for (Direction d : Direction.values()) {
                        BlockEntity n = level.getBlockEntity(pos.relative(d));
                        if (n == null || !isPipe(n)) {
                            continue;
                        }
                        Object ph = PipeProbe.call(n, "getHandler", d.getOpposite(), false);
                        sb.append(" | 管道@").append(name(d)).append(" 本面insert(").append(anyKey).append(")=")
                                .append(ph == null ? "null" : PipeProbe.call(ph, "insert", anyKey, 1000L, true));
                    }
                }
            } else if (isPipe(be)) {
                Object cur = unwrapWeak(PipeProbe.read(be, "currentFluidPipeNet"));
                Object got = PipeProbe.call(be, "getFluidPipeNet");
                sb.append(" | net.cur=").append(identOrNull(cur)).append(" net.get=").append(identOrNull(got));
                sb.append(" defaultHandler=").append(identOrNull(PipeProbe.read(be, "defaultHandler")));
                sb.append(" handlers=").append(sizeOf(PipeProbe.read(be, "handlers")));
                sb.append(" transferred=").append(PipeProbe.read(be, "transferredFluids"));
                sb.append(" blockedSide=").append(PipeProbe.read(be, "blockedSide"));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "compact 出错（已忽略）：" + t;
        }
    }

    // ------------------------------------------------------------ 机器：真实取用路径

    private static void machineSection(Level level, BlockEntity be, Object machine, Object tank, BlockPos pos, List<String> out) {
        out.add("  -- 机器 #" + ident(machine) + " " + cls(machine) + " --");
        out.add("    io=" + PipeProbe.read(machine, "io")
                + "  isWorkingEnabled=" + PipeProbe.call(machine, "isWorkingEnabled")
                + "  getFrontFacing=" + PipeProbe.call(machine, "getFrontFacing"));
        Object subs = PipeProbe.read(machine, "autoIOSubs");
        Object monitor = PipeProbe.read(machine, "autoIOMonitor");
        out.add("    autoIOSubs=" + identOrNull(subs) + "  autoIOMonitor=" + identOrNull(monitor)
                + (monitor == null ? "" : "  monitor.task=" + identOrNull(PipeProbe.read(monitor, "task"))));
        out.add("    储罐：" + tankText(tank));
        Object key = tankKey(tank);
        if (key == null) {
            Object borrowed = keyAround(level, pos, true);
            if (borrowed != null) {
                key = borrowed;
                out.add("    （这台机器的储罐是空的，借了一个邻居的流体键：" + borrowed + "，只当参考）");
            }
        }
        out.add("    流体键=" + (key == null ? "（储罐空、邻居也借不到，只能测 Forge 能力）" : key + " 类=" + cls(key)));
        Object cache = PipeProbe.read(be, "blockEntityDirectionCache");
        out.add("    -- 真实取用路径：holder.blockEntityDirectionCache --");
        if (cache == null) {
            out.add("      读不到 blockEntityDirectionCache");
            return;
        }
        for (Direction d : Direction.values()) {
            faceTrace(level, be, pos, cache, d, key, out);
        }
    }

    private static void faceTrace(Level level, BlockEntity be, BlockPos pos, Object cache, Direction d, Object key, List<String> out) {
        try {
            Object slot = PipeProbe.call(cache, "getCache", d);
            BlockEntity world = level.getBlockEntity(pos.relative(d));
            Object has = PipeProbe.call(cache, "hasAdjacentTarget", level, pos, d, fluidsType(), access("INSERT"));
            Object h = PipeProbe.call(cache, "getAdjacentKeyHandler", level, pos, d, fluidsType(), access("INSERT"));
            out.add("      [" + name(d) + "] 世界=" + (world == null ? "无" : simple(world) + "(#" + ident(world) + ")")
                    + "  缓存槽=" + slotText(slot)
                    + "  hasTarget(INS)=" + boolText(has)
                    + "  handler=" + identOrNull(h));
            if (h == null) {
                return;
            }
            if (cls(h).contains(HANDLER_MARK)) {
                Object pipe = PipeProbe.read(h, "pipe");
                Object net = PipeProbe.read(h, "net");
                StringBuilder sb = new StringBuilder("        handler.pipe=");
                if (pipe instanceof BlockEntity pb) {
                    sb.append(simple(pb)).append("#").append(ident(pb))
                            .append("  isRemoved=").append(pb.isRemoved())
                            .append("  ==世界那格? ").append(pb == world)
                            .append("  ==缓存那格? ").append(pb == slot);
                } else {
                    sb.append(identOrNull(pipe));
                }
                out.add(sb.toString());
                String netLine = "        handler.net=" + identOrNull(net);
                if (pipe != null) {
                    Object cur = unwrapWeak(PipeProbe.read(pipe, "currentFluidPipeNet"));
                    Object got = PipeProbe.call(pipe, "getFluidPipeNet");
                    netLine += "  pipe.currentFluidPipeNet=" + identOrNull(cur)
                            + "  pipe.getFluidPipeNet()=" + identOrNull(got)
                            + "  net==get? " + (net != null && net == got)
                            + "  net==cur? " + (net != null && net == cur);
                }
                out.add(netLine);
            }
            if (key != null) {
                out.add("        handler.insert(key,1000,模拟)=" + PipeProbe.call(h, "insert", key, 1000L, true));
            }
        } catch (Throwable t) {
            out.add("      [" + name(d) + "] 读取出错（已忽略）：" + t);
        }
    }

    // ------------------------------------------------------------ 管道：网络 / handler / 寻路

    private static void pipeSection(Level level, BlockEntity be, BlockPos pos, List<String> out) {
        out.add("  -- 管道 #" + ident(be) + " " + cls(be) + " --");
        Object longPos = PipeProbe.call(be, "getPipeLongPos");
        Object cur = unwrapWeak(PipeProbe.read(be, "currentFluidPipeNet"));
        Object got = PipeProbe.call(be, "getFluidPipeNet");
        Object def = PipeProbe.read(be, "defaultHandler");
        out.add("    getPipeLongPos=" + longPos);
        out.add("    currentFluidPipeNet=" + identOrNull(cur) + "  getFluidPipeNet()=" + identOrNull(got)
                + "  两者同一个? " + (cur != null && cur == got));
        if (got != null) {
            out.add("    get 的网络: containsNode(longPos)=" + boolText(PipeProbe.call(got, "containsNode", longPos))
                    + "  isValid=" + boolText(PipeProbe.call(got, "isValid")));
        }
        if (cur != null) {
            out.add("    cur 的网络: containsNode(longPos)=" + boolText(PipeProbe.call(cur, "containsNode", longPos))
                    + "  isValid=" + boolText(PipeProbe.call(cur, "isValid")));
        }
        out.add("    defaultHandler=" + identOrNull(def) + (def == null ? "  【为 null → checkNetwork() 不会刷新 handler!】" : "  defaultHandler.net=" + identOrNull(PipeProbe.read(def, "net"))));
        Object handlers = PipeProbe.read(be, "handlers");
        out.add("    handlers=" + cls(handlers) + " 大小=" + sizeOf(handlers));
        try {
            if (handlers instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    Object h = e.getValue();
                    out.add("      handler[" + e.getKey() + "]=" + identOrNull(h) + "  net=" + identOrNull(h == null ? null : PipeProbe.read(h, "net")));
                }
            }
        } catch (Throwable ignored) {
        }
        out.add("    exposed非空=" + countNonNull(PipeProbe.read(be, "exposed"))
                + "  capabilityCache非空=" + countNonNull(PipeProbe.read(be, "capabilityCache"))
                + "  storageCache非空=" + countNonNull(PipeProbe.read(be, "storageCache"))
                + "  storageEpoch=" + PipeProbe.read(be, "storageEpoch"));
        out.add("    autoTransfer=" + PipeProbe.read(be, "autoTransfer")
                + "  blockedSide=" + PipeProbe.read(be, "blockedSide")
                + "  isInValid=" + boolText(PipeProbe.call(be, "isInValid"))
                + "  transferredFluids=" + PipeProbe.read(be, "transferredFluids")
                + "  timer=" + PipeProbe.read(be, "timer")
                + "  levelTime=" + PipeProbe.call(be, "getLevelTime"));
        Object netData = got == null ? null : PipeProbe.read(got, "netData");
        out.add("    netData(got 的缓存) 条目数=" + sizeOf(netData)
                + (netData == null ? "" : "  含本管道? " + boolText(PipeProbe.call(netData, "containsKey", longPos))));
        // 每个面：连接状态、缓存槽、handler、当场重新寻路
        // 注意：管道自己没储罐，本面邻居又常常是另一根管道/空面 → 按面借键会借不到，
        // 于是先在这根管道的机器邻居里借一个键，本面借不到时用它，免得 insert 模拟全被跳过。
        Object sharedKey = pipeKey(level, pos);
        if (sharedKey != null) {
            out.add("    管道邻居里借到的流体键=" + sharedKey + "（本面借不到键时用它做 insert 模拟）");
        }
        for (Direction d : Direction.values()) {
            try {
                Object slot = PipeProbe.read(be, "blockEntityDirectionCache");
                Object raw = slot == null ? null : PipeProbe.call(slot, "getCache", d);
                BlockEntity world = level.getBlockEntity(pos.relative(d));
                Object h = PipeProbe.call(be, "getHandler", d, false);
                out.add("      [" + name(d) + "] isConnected=" + boolText(PipeProbe.call(be, "isConnected", d))
                        + " isBlocked=" + boolText(PipeProbe.call(be, "isBlocked", d))
                        + " 世界=" + (world == null ? "无" : simple(world))
                        + " 缓存槽=" + slotText(raw)
                        + " getHandler=" + identOrNull(h));
                Object key = keyOfNeighbour(level, world);
                Object useKey = key != null ? key : sharedKey;
                if (got != null) {
                    Object routes = walk(got, pos, d);
                    out.add("        当场重新寻路(facing=" + name(d) + ")：路由数=" + sizeOf(routes)
                            + (useKey == null ? "" : "  测用的流体键=" + useKey + (key == null ? "（借的）" : "")));
                    listRoutes(routes, level, useKey, out, 6);
                }
                if (h != null && useKey != null) {
                    out.add("        pipe.getHandler(" + name(d) + ").insert(key,1000,模拟)=" + PipeProbe.call(h, "insert", useKey, 1000L, true));
                }
            } catch (Throwable t) {
                out.add("      [" + name(d) + "] 读取出错（已忽略）：" + t);
            }
        }
    }

    /** 纯计算地按某个入流面重新寻路（调 FluidNetWalker.createNetData，不写 netData 缓存）。 */
    private static Object walk(Object net, BlockPos pos, Direction facing) {
        try {
            Class<?> walker = Class.forName(WALKER);
            for (Method m : walker.getDeclaredMethods()) {
                if (m.getName().equals("createNetData") && m.getParameterCount() == 3) {
                    m.setAccessible(true);
                    return m.invoke(null, net, pos, facing);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 某个方块实体（机器）里的流体键，用来在探针里做 insert 模拟。 */
    private static Object keyOfNeighbour(Level level, BlockEntity world) {
        Object nm = world == null ? null : PipeProbe.machineOf(world);
        return nm == null ? null : tankKey(PipeProbe.tankOf(nm));
    }

    /**
     * 从周围借一个「现在真的有货」的流体键：先看六个方向的机器储罐，
     * throughPipes=true 时还会穿过管道再看一跳（管道自己没储罐，
     * 只扫邻居会永远借不到键，导致 insert 模拟全被跳过）。
     */
    private static Object keyAround(Level level, BlockPos pos, boolean throughPipes) {
        for (Direction d : Direction.values()) {
            BlockEntity n = level.getBlockEntity(pos.relative(d));
            if (n == null) {
                continue;
            }
            Object k = keyOfNeighbour(level, n);
            if (k != null) {
                return k;
            }
            if (throughPipes && isPipe(n)) {
                for (Direction d2 : Direction.values()) {
                    BlockEntity n2 = level.getBlockEntity(pos.relative(d).relative(d2));
                    if (n2 == null) {
                        continue;
                    }
                    Object k2 = keyOfNeighbour(level, n2);
                    if (k2 != null) {
                        return k2;
                    }
                }
            }
        }
        return null;
    }

    /** 管道六个方向的机器邻居里借一个流体键（给这个管道的每个面共用）。 */
    private static Object pipeKey(Level level, BlockPos pos) {
        return keyAround(level, pos, false);
    }

    /**
     * 巡视目标周围一片区域，专门找「<b>静默退订</b>」这种不会自己恢复的坏状态：
     * <ul>
     *   <li>机器：有 {@code autoIOMonitor}（说明它本来有自动输出）但 {@code autoIOSubs == null}，
     *       同时储罐里有货、正面邻居又肯收 —— 这台机器不会再往外推，而且只有邻居变化/区块重载
     *       才会重新订阅（{@code updateTankSubscription} 只在那些时机被调用）。</li>
     *   <li>管道：设了 {@code blockedSide}（说明开了自动抽取）但 {@code transferSubs == null}，
     *       而邻居里有货 —— 这根管道不会再抽。</li>
     * </ul>
     * 这两种状态和「跑一段时间后输出仓不推送」的症状完全吻合，而且是唯一「各层数字都健康、
     * 却整条链路不动」的可能来源，所以单独巡视、单独记一行。
     */
    public static List<String> audit(Level level, BlockPos center, int radius) {
        List<String> out = new ArrayList<>();
        int machines = 0;
        int machineBad = 0;
        int pipes = 0;
        int pipeBad = 0;
        try {
            for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
                try {
                    BlockPos pp = p.immutable();
                    BlockEntity be = level.getBlockEntity(pp);
                    if (be == null || be.isRemoved()) {
                        continue;
                    }
                    Object machine = PipeProbe.machineOf(be);
                    if (machine != null) {
                        Object monitor = PipeProbe.read(machine, "autoIOMonitor");
                        if (monitor == null) {
                            continue;
                        }
                        machines++;
                        Object subs = PipeProbe.read(machine, "autoIOSubs");
                        Object tank = PipeProbe.tankOf(machine);
                        boolean hasFluid = tankKey(tank) != null;
                        Object front = PipeProbe.call(machine, "getFrontFacing");
                        boolean canOut = false;
                        if (front instanceof Direction f) {
                            Object cache = PipeProbe.read(be, "blockEntityDirectionCache");
                            Object has = cache == null ? null
                                    : PipeProbe.call(cache, "hasAdjacentTarget", level, pp, f, fluidsType(), access("INSERT"));
                            canOut = Boolean.TRUE.equals(has);
                        }
                        if (subs == null && hasFluid && canOut) {
                            machineBad++;
                            out.add("⚠ 自动输出没订阅：" + coord(pp) + " " + simple(machine)
                                    + "  储罐=" + tankText(tank)
                                    + "  正面=" + name(front) + " 邻居能收=true"
                                    + "  → 它不会自己往外推（只有邻居变化/区块重载才会重新订阅）");
                        }
                    } else if (isPipe(be)) {
                        Object blocked = PipeProbe.read(be, "blockedSide");
                        if (blocked == null) {
                            continue;
                        }
                        pipes++;
                        Object subs = PipeProbe.read(be, "transferSubs");
                        boolean anyFluid = false;
                        for (Direction d : Direction.values()) {
                            if (keyOfNeighbour(level, level.getBlockEntity(pp.relative(d))) != null) {
                                anyFluid = true;
                                break;
                            }
                        }
                        if (subs == null && anyFluid) {
                            pipeBad++;
                            out.add("⚠ 管道自动抽取没订阅：" + coord(pp) + "  blockedSide=" + name(blocked)
                                    + "  邻居有货=true  → 这根管道不会自己抽");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            out.add("巡视出错（已忽略）：" + t);
        }
        out.add("巡视小结（半径 " + radius + "）：带自动输出的机器 " + machines + " 台，其中「有货+能出+未订阅」" + machineBad
                + " 台；设了 blockedSide 的管道 " + pipes + " 根，其中「邻居有货+未订阅」" + pipeBad + " 根");
        return out;
    }

    private static String coord(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static void listRoutes(Object routes, Level level, Object key, List<String> out, int max) {
        if (!(routes instanceof List<?> list)) {
            return;
        }
        int i = 0;
        for (Object r : list) {
            if (i++ >= max) {
                out.add("          … 还有 " + (list.size() - max) + " 条未列出");
                break;
            }
            try {
                Object targetPos = PipeProbe.call(r, "getTargetPipePos");
                Object targetFace = PipeProbe.call(r, "getTargetFacing");
                Object props = PipeProbe.call(r, "getProperties");
                Object handler = PipeProbe.call(r, "getHandler", level);
                String dest = "?";
                String extra = "";
                long accept = -1L;
                Object destTank = null;
                if (targetPos instanceof BlockPos tp && targetFace instanceof Direction f) {
                    BlockPos dp = tp.relative(f);
                    BlockEntity dbe = level.getBlockEntity(dp);
                    dest = dp.getX() + "," + dp.getY() + "," + dp.getZ() + " " + (dbe == null ? "无BE" : simple(dbe));
                    Object dm = dbe == null ? null : PipeProbe.machineOf(dbe);
                    if (dm != null) {
                        destTank = PipeProbe.tankOf(dm);
                        extra = " 目标io=" + PipeProbe.read(dm, "io") + " 目标储罐=" + tankText(destTank);
                    }
                }
                // 借不到键时就用目标自己的流体当键 —— 别让「目标收」这一列整片空着。
                Object ownKey = tankKey(destTank);
                Object useKey = key != null ? key : ownKey;
                if (useKey != null && handler != null) {
                    Object r2 = PipeProbe.call(handler, "insert", useKey, 1000L, true);
                    accept = r2 instanceof Number n ? n.longValue() : -1L;
                }
                // 目标储罐里装的是另一种流体时，「收 0」多半只是键不对，不是异常：
                // 用目标自己的流体再测一次，两个数字一对比就清楚了。
                String own = "";
                if (ownKey != null && handler != null && !ownKey.equals(useKey)) {
                    own = "  用目标自身流体(" + ownKey + ")再测=" + PipeProbe.call(handler, "insert", ownKey, 1000L, true) + "mB";
                }
                out.add("          #" + (i - 1) + " → " + dest
                        + " 面=" + name(targetFace)
                        + " 吞吐=" + PipeProbe.call(props, "getThroughput")
                        + " handler=" + (handler == null ? "null【这条路由送不进去】" : ident(handler))
                        + " 键=" + useKey + (key == null && useKey != null ? "(目标自身)" : "")
                        + (useKey == null ? " 目标收=?（没有流体键）" : " 目标收(key,1000,模拟)=" + accept + "mB")
                        + own
                        + extra);
            } catch (Throwable t) {
                out.add("          #" + (i - 1) + " 读取出错：" + t);
            }
        }
    }

    // ------------------------------------------------------------ 小工具

    private static boolean isPipe(BlockEntity be) {
        return cls(be).contains("FluidPipeBlockEntity") || cls(be).contains("PipeBlockEntity");
    }

    private static Object fluidsType() {
        try {
            Class<?> c = Class.forName("appeng.api.stacks.AEKeyTypes");
            return c.getField("FLUIDS").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object access(String name) {
        try {
            Class<?> c = Class.forName("appeng.api.storage.StorageAccess");
            return c.getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object tankKey(Object tank) {
        if (tank == null) {
            return null;
        }
        try {
            Object size = PipeProbe.call(tank, "size");
            int n = size instanceof Number num ? num.intValue() : 0;
            for (int i = 0; i < n; i++) {
                Object k = PipeProbe.call(tank, "keyAt", i);
                Object amount = PipeProbe.call(tank, "amountAt", i);
                if (k != null && amount instanceof Number a && a.longValue() > 0L) {
                    return k;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String tankText(Object tank) {
        if (tank == null) {
            return "读不到 tank 字段";
        }
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(cls(tank));
            Object size = PipeProbe.call(tank, "size");
            int n = size instanceof Number num ? num.intValue() : 0;
            sb.append(" 槽数=").append(n);
            for (int i = 0; i < n; i++) {
                Object k = PipeProbe.call(tank, "keyAt", i);
                Object amount = PipeProbe.call(tank, "amountAt", i);
                Object limit = PipeProbe.call(tank, "slotLimit", i);
                if (k != null || (amount instanceof Number a && a.longValue() > 0L)) {
                    sb.append(" [").append(i).append("]").append(k).append(' ')
                            .append(amount).append('/').append(limit);
                }
            }
            Object locked = PipeProbe.call(tank, "isLocked");
            if (Boolean.TRUE.equals(locked)) {
                sb.append("  已锁定=").append(PipeProbe.call(tank, "getLockedKey"));
            }
        } catch (Throwable t) {
            sb.append(" （读取出错：").append(t).append('）');
        }
        return sb.toString();
    }

    private static String slotText(Object slot) {
        if (slot == null) {
            return "未查过(null)";
        }
        if (slot instanceof BlockEntity be) {
            return simple(be) + "(#" + ident(be) + ")" + (be.isRemoved() ? "【已移除】" : "");
        }
        return "非BE(" + cls(slot) + ")【哨兵】";
    }

    private static String ident(Object o) {
        return Integer.toHexString(System.identityHashCode(o));
    }

    private static String identOrNull(Object o) {
        return o == null ? "null" : simple(o) + "#" + ident(o);
    }

    private static String cls(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static String simple(Object o) {
        if (o == null) {
            return "null";
        }
        String n = o.getClass().getSimpleName();
        return n.isEmpty() ? o.getClass().getName() : n;
    }

    private static String name(Object d) {
        return d == null ? "null" : String.valueOf(d).toLowerCase();
    }

    private static String boolText(Object o) {
        return o == null ? "?" : String.valueOf(o);
    }

    private static String sizeOf(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            if (o instanceof java.util.Collection<?> c) {
                return String.valueOf(c.size());
            }
            if (o instanceof Map<?, ?> m) {
                return String.valueOf(m.size());
            }
            Method size = o.getClass().getMethod("size");
            return String.valueOf(size.invoke(o));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String countNonNull(Object o) {
        if (o == null) {
            return "读不到";
        }
        try {
            int n = 0;
            if (o instanceof Object[] arr) {
                for (Object x : arr) {
                    if (x != null) {
                        n++;
                    }
                }
                return n + "/" + arr.length;
            }
            Method size = o.getClass().getMethod("size");
            int len = ((Number) size.invoke(o)).intValue();
            Method get = o.getClass().getMethod("get", int.class);
            for (int i = 0; i < len; i++) {
                if (get.invoke(o, i) != null) {
                    n++;
                }
            }
            return n + "/" + len;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static Object unwrapWeak(Object o) {
        if (o instanceof WeakReference<?> w) {
            return w.get();
        }
        return o;
    }
}
