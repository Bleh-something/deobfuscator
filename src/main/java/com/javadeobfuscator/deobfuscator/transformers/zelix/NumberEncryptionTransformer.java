/*
 * Fork addition (by Claude): reverses Zelix KlassMaster "long/number encryption".
 *
 * ZKM replaces a literal long constant with a decryptor call chain of the form
 *
 *     <decryptor>.a(seedA, seedB, null).a(encrypted)
 *
 * which at the bytecode level is the contiguous sequence
 *
 *     ldc2_w seedA
 *     ldc2_w seedB
 *     aconst_null
 *     invokestatic  <decryptor>.a (JJLjava/lang/Object;)L<decryptor-iface>;
 *     ldc2_w encrypted
 *     invokeinterface <decryptor-iface>.a (J)J
 *
 * We emulate each chain in the javavm VirtualMachine (which runs the decryptor's real
 * <clinit> and decryption math) and replace the whole sequence with the resulting
 * literal long. Like the other VM-based Zelix transformers this needs a JDK 8 runtime
 * (rt.jar); on a modern modular JDK it skips gracefully.
 */
package com.javadeobfuscator.deobfuscator.transformers.zelix;

import java.util.HashMap;

import com.javadeobfuscator.deobfuscator.config.TransformerConfig;
import com.javadeobfuscator.deobfuscator.transformers.Transformer;
import com.javadeobfuscator.deobfuscator.utils.TransformerHelper;
import com.javadeobfuscator.deobfuscator.utils.Utils;
import com.javadeobfuscator.javavm.MethodExecution;
import com.javadeobfuscator.javavm.VirtualMachine;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class NumberEncryptionTransformer extends Transformer<TransformerConfig> {

    @Override
    public boolean transform() throws Throwable {
        VirtualMachine vm;
        try {
            vm = TransformerHelper.newVirtualMachine(this);
        } catch (Throwable t) {
            System.out.println("[Zelix] [NumberEncryptionTransformer] Could not initialize the emulation VM "
                + "(needs a JDK 8 rt.jar; cannot run on a modern modular JDK) - skipping: " + t);
            return false;
        }

        int decrypted = 0;
        int found = 0;
        try {
            for (ClassNode classNode : classNodes()) {
                for (MethodNode methodNode : classNode.methods) {
                    if (methodNode.instructions == null || methodNode.instructions.size() == 0) {
                        continue;
                    }
                    for (AbstractInsnNode ain : methodNode.instructions.toArray()) {
                        if (ain.getOpcode() != Opcodes.INVOKESTATIC) {
                            continue;
                        }
                        MethodInsnNode entry = (MethodInsnNode) ain;
                        // entry: a(long, long, Object) -> <object> (the decryptor factory)
                        Type[] args = Type.getArgumentTypes(entry.desc);
                        if (args.length != 3
                            || args[0].getSort() != Type.LONG
                            || args[1].getSort() != Type.LONG
                            || !args[2].getDescriptor().equals("Ljava/lang/Object;")
                            || Type.getReturnType(entry.desc).getSort() != Type.OBJECT) {
                            continue;
                        }

                        AbstractInsnNode aNull = Utils.getPrevious(entry);                 // aconst_null
                        AbstractInsnNode seedB = aNull == null ? null : Utils.getPrevious(aNull);  // ldc2_w seedB
                        AbstractInsnNode seedA = seedB == null ? null : Utils.getPrevious(seedB);  // ldc2_w seedA
                        AbstractInsnNode enc = Utils.getNext(entry);                       // ldc2_w encrypted
                        AbstractInsnNode dec = enc == null ? null : Utils.getNext(enc);    // invoke*.a(J)J
                        if (aNull == null || seedB == null || seedA == null || enc == null || dec == null) {
                            continue;
                        }
                        if (aNull.getOpcode() != Opcodes.ACONST_NULL
                            || !isLongLdc(seedA) || !isLongLdc(seedB) || !isLongLdc(enc)) {
                            continue;
                        }
                        if (dec.getOpcode() != Opcodes.INVOKEINTERFACE && dec.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                            continue;
                        }
                        MethodInsnNode decrypt = (MethodInsnNode) dec;
                        if (!decrypt.desc.equals("(J)J")) {
                            continue;
                        }

                        found++;

                        // Build a synthetic ()J method that reproduces the chain and returns the long.
                        MethodNode synthetic = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            "decrypt" + decrypted, "()J", null, null);
                        HashMap<LabelNode, LabelNode> noLabels = new HashMap<>();
                        synthetic.instructions.add(seedA.clone(noLabels));
                        synthetic.instructions.add(seedB.clone(noLabels));
                        synthetic.instructions.add(aNull.clone(noLabels));
                        synthetic.instructions.add(entry.clone(noLabels));
                        synthetic.instructions.add(enc.clone(noLabels));
                        synthetic.instructions.add(decrypt.clone(noLabels));
                        synthetic.instructions.add(new InsnNode(Opcodes.LRETURN));

                        ClassNode host = new ClassNode();
                        host.visit(49, Opcodes.ACC_PUBLIC, "zkm_number_decryptor_" + decrypted, null, "java/lang/Object", null);
                        host.methods.add(synthetic);

                        long value;
                        try {
                            MethodExecution execution = vm.execute(host, synthetic);
                            value = execution.getReturnValue().asLong();
                        } catch (Throwable t) {
                            // Couldn't emulate this one - leave it untouched rather than guess.
                            continue;
                        }

                        methodNode.instructions.insertBefore(seedA, new LdcInsnNode(value));
                        methodNode.instructions.remove(seedA);
                        methodNode.instructions.remove(seedB);
                        methodNode.instructions.remove(aNull);
                        methodNode.instructions.remove(entry);
                        methodNode.instructions.remove(enc);
                        methodNode.instructions.remove(decrypt);
                        decrypted++;
                    }
                }
            }
        } finally {
            vm.shutdown();
        }

        System.out.println("[Zelix] [NumberEncryptionTransformer] Decrypted " + decrypted + " of " + found + " encrypted longs");
        return decrypted > 0;
    }

    private static boolean isLongLdc(AbstractInsnNode insn) {
        return insn.getOpcode() == Opcodes.LDC && ((LdcInsnNode) insn).cst instanceof Long;
    }
}
