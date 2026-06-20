/*
 * Fork addition (by Claude): restores Zelix KlassMaster enum obfuscation.
 *
 * ZKM strips the ACC_ENUM (0x4000) access flag from an enum's constant fields (and sometimes the
 * class). The bytecode still extends java/lang/Enum and has the synthetic (String name, int ordinal)
 * constructor + values()/valueOf(), but because the constant fields are no longer marked ACC_ENUM,
 * decompilers render them as ordinary fields initialized with `new TheEnum("X", 0)` -- which is
 * illegal Java (you cannot `new` an enum), so javac rejects the file.
 *
 * Re-adding ACC_ENUM to the class and to each static-final field whose type is the enum itself makes
 * decompilers emit proper `X, Y, Z;` enum constant syntax again. The synthetic $VALUES array field is
 * an array type (`[LTheEnum;`), so the self-type descriptor check naturally excludes it.
 */
package com.javadeobfuscator.deobfuscator.transformers.zelix;

import com.javadeobfuscator.deobfuscator.config.TransformerConfig;
import com.javadeobfuscator.deobfuscator.transformers.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

public class EnumObfuscationTransformer extends Transformer<TransformerConfig> {

    @Override
    public boolean transform() throws Throwable {
        int classesFixed = 0;
        int fieldsFixed = 0;

        for (ClassNode classNode : classNodes()) {
            // Only real enums extend java/lang/Enum directly (anonymous constant bodies extend the
            // enum type itself, so they are correctly skipped).
            if (!"java/lang/Enum".equals(classNode.superName)) {
                continue;
            }

            boolean changed = false;
            if ((classNode.access & Opcodes.ACC_ENUM) == 0) {
                classNode.access |= Opcodes.ACC_ENUM;
                changed = true;
            }

            String selfDesc = "L" + classNode.name + ";";
            for (FieldNode field : classNode.fields) {
                if ((field.access & Opcodes.ACC_STATIC) != 0
                    && (field.access & Opcodes.ACC_FINAL) != 0
                    && (field.access & Opcodes.ACC_ENUM) == 0
                    && selfDesc.equals(field.desc)) {
                    field.access |= Opcodes.ACC_ENUM;
                    fieldsFixed++;
                    changed = true;
                }
            }

            if (changed) {
                classesFixed++;
            }
        }

        System.out.println("[Zelix] [EnumObfuscationTransformer] Restored enum flags on "
            + classesFixed + " classes (" + fieldsFixed + " constant fields)");
        return fieldsFixed > 0;
    }
}
