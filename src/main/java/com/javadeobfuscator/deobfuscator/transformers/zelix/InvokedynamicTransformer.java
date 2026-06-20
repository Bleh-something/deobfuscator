/*
 * Fork addition (by Claude): devirtualizes Zelix KlassMaster invokedynamic obfuscation.
 *
 * ZKM hides calls (notably its string-decrypt calls) behind invokedynamic. Each indy uses a
 * per-class bootstrap of the form
 *
 *     <C>.a(MethodHandles$Lookup, String, MethodType) -> CallSite
 *
 * which installs a lazy "linker"
 *
 *     <C>.a(MethodHandles$Lookup, MutableCallSite, String, Object[]) -> Object
 *
 * The linker simply unpacks the real arguments out of the Object[] and calls a same-class target
 * method whose descriptor equals the indy descriptor (e.g. a(I,J)String), memoising the result.
 * So an `invokedynamic name:(IJ)String` is exactly an obfuscated call to that target method.
 *
 * This transformer resolves the target statically (no VM needed, works on any JDK) by reading the
 * bootstrap -> linker -> the same-class invoke whose descriptor matches the indy, and replaces the
 * invokedynamic with a plain invokestatic. Real JDK lambda metafactory indys are left untouched.
 */
package com.javadeobfuscator.deobfuscator.transformers.zelix;

import java.util.HashMap;
import java.util.Map;

import com.javadeobfuscator.deobfuscator.config.TransformerConfig;
import com.javadeobfuscator.deobfuscator.transformers.Transformer;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class InvokedynamicTransformer extends Transformer<TransformerConfig> {

    private static final String ZKM_BOOTSTRAP_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    private static final String ZKM_LINKER_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;";

    @Override
    public boolean transform() throws Throwable {
        Map<String, MethodInsnNode> cache = new HashMap<>();
        int resolved = 0;
        int candidates = 0;

        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                if (methodNode.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode ain : methodNode.instructions.toArray()) {
                    if (!(ain instanceof InvokeDynamicInsnNode)) {
                        continue;
                    }
                    InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) ain;
                    Handle bsm = indy.bsm;
                    // Leave real JDK lambdas (LambdaMetafactory etc.) alone; only ZKM bootstraps.
                    if (bsm == null || bsm.getOwner().startsWith("java/") || bsm.getOwner().startsWith("javax/")) {
                        continue;
                    }
                    if (!bsm.getDesc().equals(ZKM_BOOTSTRAP_DESC)) {
                        continue;
                    }
                    candidates++;

                    String key = bsm.getOwner() + ' ' + bsm.getName() + ' ' + indy.name + ' ' + indy.desc;
                    MethodInsnNode target;
                    if (cache.containsKey(key)) {
                        target = cache.get(key);
                    } else {
                        target = resolveTarget(bsm, indy.desc);
                        cache.put(key, target); // cache negatives too
                    }
                    if (target == null) {
                        continue;
                    }
                    methodNode.instructions.set(indy,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, target.owner, target.name, target.desc, false));
                    resolved++;
                }
            }
        }

        System.out.println("[Zelix] [InvokedynamicTransformer] Devirtualized " + resolved
            + " of " + candidates + " Zelix invokedynamic calls");
        return resolved > 0;
    }

    private MethodInsnNode resolveTarget(Handle bsm, String indyDesc) {
        ClassNode bootClass = classes.get(bsm.getOwner());
        if (bootClass == null) {
            return null;
        }
        MethodNode bootstrap = findMethod(bootClass, bsm.getName(), bsm.getDesc());
        if (bootstrap == null || bootstrap.instructions == null) {
            return null;
        }
        // The bootstrap loads the linker as a MethodHandle constant.
        Handle linkerHandle = null;
        for (AbstractInsnNode in : bootstrap.instructions.toArray()) {
            if (in instanceof LdcInsnNode && ((LdcInsnNode) in).cst instanceof Handle) {
                Handle h = (Handle) ((LdcInsnNode) in).cst;
                if (h.getDesc().equals(ZKM_LINKER_DESC)) {
                    linkerHandle = h;
                    break;
                }
            }
        }
        if (linkerHandle == null) {
            return null;
        }
        ClassNode linkerClass = classes.get(linkerHandle.getOwner());
        if (linkerClass == null) {
            return null;
        }
        MethodNode linker = findMethod(linkerClass, linkerHandle.getName(), linkerHandle.getDesc());
        if (linker == null || linker.instructions == null) {
            return null;
        }
        // Inside the linker, the real target is the same-class invoke whose descriptor equals the indy.
        MethodInsnNode found = null;
        for (AbstractInsnNode in : linker.instructions.toArray()) {
            if (!(in instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode m = (MethodInsnNode) in;
            if (m.owner.equals(bsm.getOwner()) && m.desc.equals(indyDesc)) {
                if (found != null && !found.name.equals(m.name)) {
                    return null; // ambiguous - don't guess
                }
                found = m;
            }
        }
        if (found == null) {
            return null;
        }
        // Only rewrite to invokestatic if the target really is static.
        ClassNode targetOwner = classes.get(found.owner);
        MethodNode targetMethod = targetOwner == null ? null : findMethod(targetOwner, found.name, found.desc);
        if (targetMethod == null || (targetMethod.access & Opcodes.ACC_STATIC) == 0) {
            return null;
        }
        return found;
    }

    private static MethodNode findMethod(ClassNode classNode, String name, String desc) {
        for (MethodNode methodNode : classNode.methods) {
            if (methodNode.name.equals(name) && methodNode.desc.equals(desc)) {
                return methodNode;
            }
        }
        return null;
    }
}
