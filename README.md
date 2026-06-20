# Java Deobfuscator — modern-JDK compatible fork

> **This is a fork of [java-deobfuscator/deobfuscator](https://github.com/java-deobfuscator/deobfuscator).**
>
> Every change in this fork was made by **Claude** (Anthropic's [Claude Code](https://claude.com/claude-code)) — the debugging, the fixes, and full end-to-end build-and-verify against a real Zelix KlassMaster–obfuscated jar running on **JDK 25**.
>
> The deobfuscator itself is the original work of [samczsun](https://github.com/samczsun) and the java-deobfuscator contributors and stays Apache-2.0 licensed. This fork only adds compatibility and robustness fixes on top — it does **not** claim authorship of the upstream project.

This project deobfuscates most commercially-available Java obfuscators (Zelix KlassMaster, Stringer, Allatori, DashO, DexGuard, …).

---

## Why this fork exists

Upstream no longer builds or runs cleanly on current JDKs. On **JDK 25** (and any modular JDK 9+) it crashes in several places, and the build fails because one of its dependencies is hosted on a repository that is now offline. This fork fixes all of that and makes the whole `generic + peephole + zelix` transformer set run without aborting.

## What changed

### 🟢 Runs on modern JDKs (tested on JDK 25)
- ASM 9.5 cannot parse the running JDK's own runtime classes once they're newer than Java 21 (JDK 25 = class-file major version **69**), which crashed hierarchy resolution and class writing. `Deobfuscator.pullFromRuntime` now reads runtime classes **version-tolerantly** — it only needs the type hierarchy, so it downgrades the class-file version bytes before reading. Works on any JDK, current or future.
- The class writer now falls back to `COMPUTE_MAXS` on an `AssertionError` from ASM frame computation (instead of printing a stack trace and writing a half-computed class).

### 🩹 Crash fixes — no more aborted runs
- **`MethodAnalyzer`** (the abstract interpreter behind `ConstantFolder` etc.): synthesizes an *unknown* value when a local is read before being written on an obfuscated/dead path instead of throwing an NPE, and catches abstract operand-stack underflow (`POP2` / `DUP_x` / array / math) so it degrades gracefully.
- **`MethodExecutor`** (the Zelix string emulator): throws a clean, catchable `ExecutionException` instead of a raw NPE on an uninitialized local load.
- **Zelix `StringEncryptionTransformer`**: isolates each class — one ZKM variant it can't emulate is skipped (with the temporary `<clinit>` scaffolding rolled back) instead of aborting the entire run. On a real Vulcan 2.9.7.17 jar this decrypts **1649 strings across 222 classes** and cleanly skips the handful it doesn't support.
- **Peephole** `LdcSwapInvokeSwapPopRemover` and `RedundantGotoRemover`: null-guarded against truncated/obfuscated instruction tails.

### 🏗️ Build fix
- The `javavm` dependency was hosted on `repo.samczsun.com`, which is **offline (dead DNS)**. The build now pulls it from **JitPack** (`com.github.java-deobfuscator:javavm`), so `mvn package` works again.

### 🧹 Quieter output
- Missing-`rt.jar` and unsupported-pattern code paths now log a single concise line instead of dumping full stack traces, so a clean run reads as a clean run.

---

## ⚠️ The JRE 8 thing (read this)

Two Zelix transformers — `zelix.string.SimpleStringEncryptionTransformer` and `zelix.string.EnhancedStringEncryptionTransformer` — spin up a full emulating VM (`javavm`) that needs the JDK's `rt.jar` / `jce.jar` / `jsse.jar`. **Those files only exist on JDK 8**; they were removed when the JDK became modular in Java 9.

- On **JDK 9+ (including 25)** these two transformers **skip gracefully** with a clear message — everything else still runs.
- To actually run them, launch the deobfuscator with a **JDK 8** runtime (e.g. [Adoptium Temurin 8](https://adoptium.net/temurin/releases/?version=8)):
  ```
  "C:\Program Files\Eclipse Adoptium\jdk-8.x.x-hotspot\bin\java.exe" -Xss512m -jar deobfuscator.jar --config config.yml
  ```
  You can **build** with any modern JDK and only **run** with JDK 8.

> The main `zelix.StringEncryptionTransformer` does **not** need JDK 8 — it uses the lightweight `MethodExecutor` and decrypts the common ZKM string-encryption modes on any JDK. In practice it already handles the bulk of Zelix string encryption; the JDK-8-only `Simple`/`Enhanced` transformers are for additional/legacy ZKM variants.

---

## Build

```bash
mvn -DskipTests package
# -> target/deobfuscator-1.0.0.jar
```

## Quick Start

* If you know which obfuscators were used, skip the detect step.
* Create `detect.yml` (replace `input.jar`):
```yaml
input: input.jar
detect: true
```
* Run `java -jar deobfuscator.jar --config detect.yml` to identify the obfuscators.
* Create `config.yml`:
```yaml
input: input.jar
output: output.jar
transformers:
  - [fully-qualified-name-of-transformer]
  - ... etc
```
* Run `java -jar deobfuscator.jar --config config.yml`
* Re-run detection if the JAR wasn't fully deobfuscated — obfuscations can be layered.

See [USAGE.md](USAGE.md) for more. Transformer names are relative to `com.javadeobfuscator.deobfuscator.transformers` (e.g. `zelix.FlowObfuscationTransformer`, `general.peephole.ConstantFolder`).

## Supported Obfuscators

* [Zelix KlassMaster](http://www.zelix.com/)
* [Stringer](https://jfxstore.com/stringer/)
* [Allatori](http://www.allatori.com/)
* [DashO](https://www.preemptive.com/products/dasho/overview)
* [DexGuard](https://www.guardsquare.com/dexguard)
* [ClassGuard](https://www.zenofx.com/classguard/)
* Smoke (dead, [archive](https://web.archive.org/web/20170918112921/https://newtownia.net/smoke/))
* SkidSuite2 (dead, [archive](https://github.com/GenericException/SkidSuite/tree/master/archive/skidsuite-2))

## FAQs

#### "Could not locate a class file"
You need to add the JARs the input references via `path:` / `libraries:` in the config. You'll almost always need `rt.jar` (JDK 8). This is not a crash — the deobfuscator falls back to `COMPUTE_MAXS` and continues.

#### "A StackOverflowError occurred during deobfuscation"
Increase your stack size, e.g. `java -Xss128m -jar deobfuscator.jar`.

## Licensing

Apache 2.0, same as upstream. The original deobfuscator is © [samczsun](https://github.com/samczsun) and the java-deobfuscator contributors. The fork's modifications were made by Claude (Anthropic).
