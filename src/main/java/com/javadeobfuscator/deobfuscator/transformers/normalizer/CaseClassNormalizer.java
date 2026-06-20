/*
 * Fork addition (by Claude): resolves class names that differ only by case.
 *
 * Obfuscators happily emit `Foo` and `foo` in the same package. A jar (zip) stores both
 * fine, but on a case-insensitive filesystem (Windows/macOS) extracting the jar -- or a decompiler
 * writing `Foo.java` and `foo.java` -- makes one silently overwrite the other, so classes
 * go missing. This normalizer renames the colliding classes (keeping the first of each
 * case-insensitive group, suffixing the rest with `_0`, `_1`, ...) and the AbstractNormalizer base
 * rewrites every reference via ASM's ClassRemapper. It only touches names that actually collide.
 *
 * Run it LAST, after all other transformers (renaming is a normalization step).
 */
package com.javadeobfuscator.deobfuscator.transformers.normalizer;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.javadeobfuscator.deobfuscator.config.TransformerConfig;
import org.objectweb.asm.tree.ClassNode;

@TransformerConfig.ConfigOptions(configClass = CaseClassNormalizer.Config.class)
public class CaseClassNormalizer extends AbstractNormalizer<CaseClassNormalizer.Config> {

    @Override
    public void remap(CustomRemapper remapper) {
        // Every existing class name, lower-cased, so a generated name never re-introduces a collision.
        Set<String> usedLower = new HashSet<>();
        for (ClassNode classNode : classNodes()) {
            usedLower.add(classNode.name.toLowerCase(Locale.ROOT));
        }

        Set<String> seenLower = new HashSet<>();
        int renamed = 0;
        for (ClassNode classNode : classNodes()) {
            String lower = classNode.name.toLowerCase(Locale.ROOT);
            if (seenLower.add(lower)) {
                // first class with this case-insensitive name keeps it
                continue;
            }
            int n = 0;
            String newName;
            do {
                newName = classNode.name + "_" + (n++);
            } while (usedLower.contains(newName.toLowerCase(Locale.ROOT)) || !remapper.map(classNode.name, newName));
            usedLower.add(newName.toLowerCase(Locale.ROOT));
            seenLower.add(newName.toLowerCase(Locale.ROOT));
            renamed++;
        }

        System.out.println("[Normalizer] [CaseClassNormalizer] Renamed " + renamed + " case-colliding classes");
    }

    public static class Config extends AbstractNormalizer.Config {
        public Config() {
            super(CaseClassNormalizer.class);
        }
    }
}
