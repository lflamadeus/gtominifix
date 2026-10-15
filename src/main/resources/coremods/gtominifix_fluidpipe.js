// gtominifix coremod: the two defects behind "a machine silently stops pushing into the pipe".
//
// Defect A -- a negative neighbour lookup is cached forever.
//   com.gregtechceu.gtceu.utils.cache.BlockEntityDirectionCache#getAdjacentBlockEntity
//   stores DirectionCache.NULL (a package private sentinel) whenever the block entity in that
//   direction is not loaded yet, and every later call returns null without ever asking the world
//   again. A machine that looked once while the neighbouring pipe's chunk was still loading stays
//   blind on that side for the rest of the session.
//   Patch: every use of that sentinel inside the method becomes a plain null. DirectionCache's
//   setCache stores the value as it is, so "no neighbour" then simply means "nothing cached" and
//   the next call looks at the world again. Only GTO's own members are referenced, no vanilla or
//   Forge method name has to be SRG mapped.
//
// Defect B -- the pipe network never tells the machines that it changed.
//   A machine drops its auto-output subscription when its neighbour momentarily cannot accept
//   (FluidHatchPartMachine#updateTankSubscription -> unsubscribe) and only re-evaluates on block
//   changes around itself. Pipe networks change all the time (pipes placed/broken, networks
//   split/merged, chunks (un)loaded) without touching the machines next to them.
//   Patch: at the head of FluidPipeNet#onPipeConnectionsUpdate call our own
//   PipeNetNotify.onNetworkChanged(this). It queues the network's node positions and, on the next
//   server tick, fires a neighbour update for each of them, which makes the adjacent machines
//   re-evaluate their subscription.
//
// Verifying it actually ran (do NOT rely on logging from inside a coremod: Nashorn's print() only
// reaches the launcher console, and file access from the coremod sandbox is unreliable):
//   in game:  /gtominifix config   -> "pipePatch: 缓存补丁(A)=... 网络通知(B)=..."
//   in game:  /gtominifix pipeprobe on any pipe -> the "-- 本模组 --" block
//
// The patch can be turned off without rebuilding the jar:
//   * put an empty file  config/gtominifix-pipe-patch.off  into the instance, or
//   * add the JVM argument  -Dgtominifix.pipePatch=off
// If reading the switch throws, the patch stays ENABLED: a broken switch must never silently
// disable the fix.

function initializeCoreMod() {
    var transformers = {};

    var disabled = false;
    var reason = '';
    try {
        disabled = gtominifixPatchDisabled();
        reason = gtominifixOffReason();
    } catch (err) {
        disabled = false;
        reason = 'switch check threw: ' + err + ' (patch stays ENABLED)';
    }

    if (disabled) {
        print('[gtominifix] coremod: pipe patch DISABLED (' + reason + ') -> neighbour cache and pipe network left untouched');
        return transformers;
    }

    print('[gtominifix] coremod: pipe patch ENABLED -> A neighbour cache (no permanent NULL) + B network change notify');

    transformers['gtominifix_neighbour_cache'] = {
        'target': {
            'type': 'CLASS',
            'name': 'com.gregtechceu.gtceu.utils.cache.BlockEntityDirectionCache'
        },
        'transformer': function (classNode) {
            try {
                return gtominifixPatchNeighbourCache(classNode);
            } catch (err) {
                print('[gtominifix] coremod: BlockEntityDirectionCache transform FAILED, class left untouched: ' + err);
                return classNode;
            }
        }
    };

    transformers['gtominifix_pipe_net_notify'] = {
        'target': {
            'type': 'CLASS',
            'name': 'com.gregtechceu.gtceu.common.pipelike.fluid.FluidPipeNet'
        },
        'transformer': function (classNode) {
            try {
                return gtominifixPatchPipeNetNotify(classNode);
            } catch (err) {
                print('[gtominifix] coremod: FluidPipeNet transform FAILED, class left untouched: ' + err);
                return classNode;
            }
        }
    };

    return transformers;
}

// ---------------------------------------------------------------- switch

function gtominifixGameDir() {
    try {
        var d = java.lang.System.getProperty('gtominifix.gameDir');
        if (d !== null && d.length > 0) {
            return new java.io.File(d);
        }
    } catch (e) { }
    try {
        return new java.io.File(java.lang.System.getProperty('user.dir'));
    } catch (e) { }
    return null;
}

function gtominifixFlagFile() {
    try {
        var dir = gtominifixGameDir();
        if (dir !== null) {
            return new java.io.File(new java.io.File(dir, 'config'), 'gtominifix-pipe-patch.off');
        }
    } catch (e) { }
    return null;
}

function gtominifixOffReason() {
    var reasons = [];
    try {
        var p = java.lang.System.getProperty('gtominifix.pipePatch');
        if (p !== null) {
            reasons.push('-Dgtominifix.pipePatch=' + p);
        }
    } catch (e) { }
    try {
        var f = gtominifixFlagFile();
        if (f !== null) {
            reasons.push(f + (f.exists() ? ' (exists)' : ' (missing)'));
        }
    } catch (e) { }
    return reasons.join(' | ');
}

function gtominifixPatchDisabled() {
    try {
        var p = java.lang.System.getProperty('gtominifix.pipePatch');
        if (p !== null) {
            p = p.toLowerCase();
            if (p === 'off' || p === 'false' || p === '0' || p === 'no') {
                return true;
            }
        }
    } catch (e) { }
    try {
        var f = gtominifixFlagFile();
        if (f !== null && f.exists()) {
            return true;
        }
    } catch (e) { }
    return false;
}

// ---------------------------------------------------------------- A: never cache "no neighbour"

function gtominifixPatchNeighbourCache(classNode) {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

    var METHOD_DESC = '(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/level/block/entity/BlockEntity;';
    var SENTINEL = 'NULL';
    var NOTIFY = 'com/lai/gtominifix/pipe/PipeNetNotify';

    var method = null;
    for (var i = 0; i < classNode.methods.size(); i++) {
        var m = classNode.methods.get(i);
        if (m.name === 'getAdjacentBlockEntity' && m.desc === METHOD_DESC) {
            method = m;
            break;
        }
    }
    if (method === null) {
        print('[gtominifix] coremod: getAdjacentBlockEntity not found in BlockEntityDirectionCache, skipping');
        return classNode;
    }

    // Replace every use of the NULL sentinel with a plain null. DirectionCache#setCache stores the
    // value as it is, so "this direction has no neighbour" then simply means "nothing cached" and
    // the next call asks the world again. Stack neutral, so no frame has to be recomputed by hand.
    var insns = method.instructions;
    var replaced = 0;
    for (var i = 0; i < insns.size(); i++) {
        var node = insns.get(i);
        if (node.getOpcode() !== Opcodes.GETSTATIC || node.name !== SENTINEL) {
            continue;
        }
        if (node.owner === null || node.owner.indexOf('DirectionCache') < 0) {
            continue;
        }
        insns.set(node, new InsnNode(Opcodes.ACONST_NULL));
        replaced++;
    }

    // liveness marker: counts every neighbour lookup that went through the patched method
    insns.insertBefore(insns.getFirst(), new MethodInsnNode(
        Opcodes.INVOKESTATIC, NOTIFY, 'markCacheCheck', '()V', false));

    print('[gtominifix] coremod: BlockEntityDirectionCache.getAdjacentBlockEntity PATCHED -> '
        + replaced + ' NULL sentinel use(s) replaced by plain null (no permanent negative cache)');
    return classNode;
}

// ---------------------------------------------------------------- B: tell machines the network changed

function gtominifixPatchPipeNetNotify(classNode) {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

    var METHOD_NAME = 'onPipeConnectionsUpdate';
    var METHOD_DESC = '()V';
    var NOTIFY = 'com/lai/gtominifix/pipe/PipeNetNotify';

    var method = null;
    for (var i = 0; i < classNode.methods.size(); i++) {
        var m = classNode.methods.get(i);
        if (m.name === METHOD_NAME && m.desc === METHOD_DESC) {
            method = m;
            break;
        }
    }
    if (method === null) {
        print('[gtominifix] coremod: onPipeConnectionsUpdate not found in FluidPipeNet, skipping');
        return classNode;
    }

    var insns = method.instructions;
    var first = insns.getFirst();
    insns.insertBefore(first, new VarInsnNode(Opcodes.ALOAD, 0));
    insns.insertBefore(first, new MethodInsnNode(
        Opcodes.INVOKESTATIC, NOTIFY, 'onNetworkChanged', '(Ljava/lang/Object;)V', false));
    method.maxStack = Math.max(method.maxStack, 2);

    print('[gtominifix] coremod: FluidPipeNet.onPipeConnectionsUpdate PATCHED -> PipeNetNotify.onNetworkChanged(this)');
    return classNode;
}
