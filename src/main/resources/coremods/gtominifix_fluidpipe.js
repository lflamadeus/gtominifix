// gtominifix coremod: observation only. Two call sites that let the in-game pipe tools see
// FluidHatchPartMachine's auto-IO subscription decisions and FluidPipeBlockEntity's per-second
// quota credits. Nothing is patched or rewritten here any more: GTO 26.10.53 (gtceu) already fixes
// the route-table defect upstream, following the two-part suggestion we filed (route table
// independent of the caller + drop the route that points back at the caller), so the previous
// D/E transformers were removed on 2026-10-16.
//
// Verifying the injection ran (never rely on logging from inside a coremod: Nashorn print() only
// reaches the launcher console, and file access from the coremod sandbox is unreliable):
//   in game:  /gtominifix pipewatch <seconds>  -> [订阅] / [配额] lines in the debug log
//   in game:  /gtominifix config               -> the "pipe:" line reports the listening state
//
// There are no switches in this file. The coremod sandbox cannot read files reliably, so the
// listening switch lives on the Java side: PipeNetNotify.setTracing, driven by /gtominifix
// pipewatch. While it is off, both injected calls return on their first line (one static call +
// one boolean read: no reflection, no allocation, no disk).
function initializeCoreMod() {
    var transformers = {};

    print('[gtominifix] coremod: injecting 2 observation call sites (subscription decisions + quota '
        + 'credits); they are inert unless /gtominifix pipewatch is running');

    transformers['gtominifix_subscription_trace'] = {
        'target': {
            'type': 'CLASS',
            'name': 'com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine'
        },
        'transformer': function (classNode) {
            try {
                return gtominifixPatchSubscriptionTrace(classNode);
            } catch (err) {
                print('[gtominifix] coremod: FluidHatchPartMachine trace FAILED, class left untouched: ' + err);
                return classNode;
            }
        }
    };

    transformers['gtominifix_transfer_trace'] = {
        'target': {
            'type': 'CLASS',
            'name': 'com.gregtechceu.gtceu.common.blockentity.FluidPipeBlockEntity'
        },
        'transformer': function (classNode) {
            try {
                return gtominifixPatchTransferTrace(classNode);
            } catch (err) {
                print('[gtominifix] coremod: FluidPipeBlockEntity trace FAILED, class left untouched: ' + err);
                return classNode;
            }
        }
    };

    return transformers;
}

// ------------------------------------------------- 观察用：订阅判定（FluidHatchPartMachine）

function gtominifixPatchSubscriptionTrace(classNode) {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var NOTIFY = 'com/lai/gtominifix/pipe/PipeNetNotify';

    var METHOD_NAME = 'updateTankSubscription';
    var METHOD_DESC = '(Lnet/minecraft/core/Direction;)V';

    var method = null;
    for (var i = 0; i < classNode.methods.size(); i++) {
        var m = classNode.methods.get(i);
        if (m.name === METHOD_NAME && m.desc === METHOD_DESC) {
            method = m;
            break;
        }
    }
    if (method === null) {
        print('[gtominifix] coremod: updateTankSubscription(Direction) not found, skipping');
        return classNode;
    }

    var insns = method.instructions;
    var first = insns.getFirst();
    insns.insertBefore(first, new VarInsnNode(Opcodes.ALOAD, 0));
    insns.insertBefore(first, new VarInsnNode(Opcodes.ALOAD, 1));
    insns.insertBefore(first, new MethodInsnNode(
        Opcodes.INVOKESTATIC, NOTIFY, 'onSubscriptionCheck', '(Ljava/lang/Object;Ljava/lang/Object;)V', false));
    method.maxStack = Math.max(method.maxStack, 3);

    print('[gtominifix] coremod: FluidHatchPartMachine.updateTankSubscription PATCHED (observe only)');
    return classNode;
}

// ------------------------------------------------- 观察用：配额记入（FluidPipeBlockEntity）

function gtominifixPatchTransferTrace(classNode) {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var NOTIFY = 'com/lai/gtominifix/pipe/PipeNetNotify';

    var METHOD_NAME = 'addTransferredFluids';
    var METHOD_DESC = '(I)V';

    var method = null;
    for (var i = 0; i < classNode.methods.size(); i++) {
        var m = classNode.methods.get(i);
        if (m.name === METHOD_NAME && m.desc === METHOD_DESC) {
            method = m;
            break;
        }
    }
    if (method === null) {
        print('[gtominifix] coremod: addTransferredFluids(I)V not found, skipping');
        return classNode;
    }

    var insns = method.instructions;
    var first = insns.getFirst();
    insns.insertBefore(first, new VarInsnNode(Opcodes.ALOAD, 0));
    insns.insertBefore(first, new VarInsnNode(Opcodes.ILOAD, 1));
    insns.insertBefore(first, new MethodInsnNode(
        Opcodes.INVOKESTATIC, NOTIFY, 'onTransferredAdded', '(Ljava/lang/Object;I)V', false));
    method.maxStack = Math.max(method.maxStack, 3);

    print('[gtominifix] coremod: FluidPipeBlockEntity.addTransferredFluids PATCHED (observe only)');
    return classNode;
}
