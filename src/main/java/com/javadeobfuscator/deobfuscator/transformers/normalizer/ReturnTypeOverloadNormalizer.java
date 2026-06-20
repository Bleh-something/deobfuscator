/*
 * Fork addition (by Claude): resolves return-type method overloading.
 *
 * The JVM allows a class to declare several methods with the same name and parameter types that
 * differ only in return type (the descriptor includes the return type, so they are distinct).
 * Java source does not, so a decompiler emits two methods javac then rejects.
 *
 * This renames the offending variants. Unlike DuplicateRenamer it needs no class hierarchy (so it
 * works without the program's libraries on the classpath): it only renames a (name, params) group
 * that actually collides inside some single class, and it applies each rename to *every* declaration
 * and call site of that exact (name, descriptor) across the whole jar. Because the rename is keyed by
 * the full descriptor and applied globally, real override chains stay linked and inherited call sites
 * are updated too. One variant per group keeps the original name.
 */
package com.javadeobfuscator.deobfuscator.transformers.normalizer;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.javadeobfuscator.deobfuscator.config.TransformerConfig;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

@TransformerConfig.ConfigOptions(configClass = ReturnTypeOverloadNormalizer.Config.class)
public class ReturnTypeOverloadNormalizer extends AbstractNormalizer<ReturnTypeOverloadNormalizer.Config> {

    @Override
    public void remap(CustomRemapper remapper) {
        // (name + params) signatures that have >1 method within a single class = real collisions.
        Set<String> collidingKeys = new HashSet<>();
        for (ClassNode classNode : classNodes()) {
            Set<String> seenInClass = new HashSet<>();
            for (MethodNode methodNode : classNode.methods) {
                if (methodNode.name.startsWith("<")) {
                    continue;
                }
                String key = key(methodNode);
                if (!seenInClass.add(key)) {
                    collidingKeys.add(key);
                }
            }
        }

        // For each colliding signature collect its distinct full descriptors across the whole jar
        // (so the rename is consistent everywhere the signature appears).
        Map<String, LinkedHashSet<String>> descsByKey = new LinkedHashMap<>();
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                if (methodNode.name.startsWith("<")) {
                    continue;
                }
                String key = key(methodNode);
                if (collidingKeys.contains(key)) {
                    descsByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(methodNode.desc);
                }
            }
        }

        int renamed = 0;
        for (Map.Entry<String, LinkedHashSet<String>> entry : descsByKey.entrySet()) {
            String name = entry.getKey().substring(0, entry.getKey().indexOf(' '));
            int idx = 0;
            for (String desc : entry.getValue()) {
                idx++;
                if (idx == 1) {
                    continue; // first variant keeps the original name
                }
                String newName = name + "_ret" + idx;
                // Map this exact (name, desc) for every class so declarations and all call sites
                // (including inherited ones, whatever the call-site owner is) are renamed together.
                for (ClassNode classNode : classNodes()) {
                    remapper.mapMethodName(classNode.name, name, desc, newName, true);
                }
                renamed++;
            }
        }

        System.out.println("[Normalizer] [ReturnTypeOverloadNormalizer] Renamed " + renamed
            + " return-type-overloaded method variant(s)");
    }

    private static String key(MethodNode methodNode) {
        return methodNode.name + ' ' + methodNode.desc.substring(0, methodNode.desc.indexOf(')') + 1);
    }

    public static class Config extends AbstractNormalizer.Config {
        public Config() {
            super(ReturnTypeOverloadNormalizer.class);
        }
    }
}
