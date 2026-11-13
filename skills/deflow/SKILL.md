---
name: deflow
description: Deobfuscate VM-flattened Java in one command. Use when the user hands over a .jar, .zip, .rar, .7z, folder or .class whose decompiled methods are switch(pc) stack-machine interpreters (a frame object with push/pop, a while(!done) driver, __flow$G$C chunk methods) instead of normal if/while/for, or simply asks to deobfuscate, unflatten, lift, or decompile an obfuscated Java program, plugin, or Minecraft mod. Runs extraction, VM detection, lifting, JVM verification and decompilation automatically.
argument-hint: <jar, archive or folder>
metadata:
  version: "2.1"
---

# deflow

One command turns a VM-obfuscated Java program back into readable source:

```bash
"<this skill's base directory>/deflow" <file-or-folder>
```

Invoked as `/deflow <path>`: the path is the input. No path given: ask the user
for the file (or use the one they just mentioned or attached), then run.

Input can be a `.jar`/`.war`, an archive (`.zip`, `.rar`, `.7z`, `.tar.gz`, …),
a folder, or a `.class`. Archives inside archives are unpacked one level deep.
Output goes to `./<name>-deflow/`:

- `<jar>.deflow.jar` — the lifted jar (still runnable)
- `src/` — Vineflower-decompiled Java of the lifted jar
- `report.txt` — what was lifted, what was kept, verifier result

With several jars in the input, each gets its own subfolder.

## How to use it

1. Run the script on exactly what the user gave you. Don't unpack, rename or
   pre-process it yourself — the script handles every format above.
   First run downloads ASM, Vineflower and CFR (SHA-1 pinned) and compiles the lifter;
   later runs reuse the cache in `~/.cache/deflow`. A large jar can take 10+
   minutes; run it in the background and wait for it to finish.
2. If the user named the libraries the program runs against (a game jar, a
   framework, a server), pass them with `--cp <jar-or-folder>` so the verifier
   can check classes that depend on them. Without them those classes count as
   "missing deps", not errors.
3. Read `report.txt` and tell the user, plainly:
   - whether a VM was found (`vm:` line) and which class it is;
   - methods lifted vs. methods kept as interpreter (and the top reasons);
   - `VERIFY ERRORS` — must be 0; anything else is a lifter bug, quote it;
   - "methods no decompiler could rebuild" — those show as bytecode in `src/`;
   - where the source is.
4. If no VM was detected, say so: the jar was decompiled as-is. The protection
   is something else (string encryption, renaming, a different flattening
   scheme) and needs a different approach.

Requirements: JDK 17+ (`java` and `javac`) and `curl`. For `.rar`/`.7z`,
`7z` or `bsdtar` (built into macOS and most Linux).

## What it can and can't do

- **Detects the VM automatically.** The frame class is the parameter type shared
  by the `__flow$G$C` chunk methods. Override with `DEFLOW_VM=<internal/Name>`.
- **Never guesses.** A method whose shape the lifter doesn't model is left in
  interpreter form (correct, just unreadable) and listed under "why methods
  were kept". The output jar always runs.
- **Verified means well-formed, not identical.** The JVM verifier proves the
  bytecode is valid. Before calling a method's logic confirmed, compare it with
  its interpreter form.
- **Encrypted strings stay encrypted.** String decryption usually needs the
  program running; deflow doesn't do it.
- **Names don't come back.** Obfuscated class/method names stay as shipped.
- **Other VM schemes.** It targets the `__flow$G$C` boxed-stack VM. Same shape,
  different method/field names: edit the recognizers in `src/deflow/ClassLifter.java`.
  See [references/internals.md](references/internals.md).

Debugging the lifter itself (single-class dumps, env flags, running the steps
by hand): [references/manual.md](references/manual.md).
