# deflow

Deobfuscate VM-flattened Java in one command.

Some Java obfuscators rewrite every method into a tiny stack-machine
interpreter: a frame object, a `while (!f.done) switch (f.pc)` driver, and
`__flow$G$C` chunk methods with one instruction per `case`. A normal decompiler
shows you walls of `switch(pc)`. deflow lifts the interpreter back out and
gives you real `if` / `while` / `for`, checked by the JVM verifier.

```bash
deflow obfuscated.jar
```

That's the whole workflow. deflow unpacks the input, finds every jar, detects
the VM, lifts each method, verifies the result, decompiles it, and writes:

```
obfuscated-deflow/
  obfuscated.deflow.jar   lifted jar, still runnable
  src/                    decompiled Java
  report.txt              lifted / kept / verifier result
```

Accepts `.jar`, `.war`, `.zip`, `.rar`, `.7z`, `.tar.gz`, a folder, or a
`.class`. Archives inside archives are unpacked too.

## Install

Needs a JDK 17+ and `curl`. ASM, Vineflower and CFR download on first run (checksums
pinned) into `~/.cache/deflow`.

```bash
curl -fsSL https://raw.githubusercontent.com/daniiiiilllll546-oss/deobfuscator/main/install.sh | bash
```

This clones deflow into `~/.claude/skills/deflow`, which makes it a **Claude
Code skill** (hand Claude an obfuscated jar, or say `/deflow path/to/file`),
and links the `deflow` command into a writable folder on your PATH, no sudo.
Run it again to update.

## Options

```
deflow <file-or-folder> [-o outdir] [--cp jar-or-dir]...
```

- `-o outdir` — output folder (default `./<name>-deflow`).
- `--cp path` — libraries the program was built against (a game jar, a
  framework). The verifier can only check classes whose dependencies it can
  load; without them they count as "missing deps", not errors.
- `DEFLOW_VM=internal/Name` — force the VM frame class instead of
  auto-detecting it.

## Limits

- Methods with shapes the lifter doesn't model stay in interpreter form —
  correct, just unreadable. The report lists how many and why. It never guesses.
- Verified means the bytecode is valid, not that it's proven identical.
- Decompiling is the weak link. deflow runs Vineflower, then CFR on classes
  Vineflower gives up on. Methods neither can rebuild show as bytecode in
  `src/`; the report counts them. Big jars take several minutes.
- Encrypted strings stay encrypted; decryption usually needs the program running.
- Obfuscated names stay as shipped.
- Built for the `__flow$G$C` boxed-stack VM. Other names with the same shape:
  edit the recognizers. See [references/internals.md](references/internals.md).
- macOS and Linux (bash). On Windows, use WSL.

## Layout

```
deflow                 the one command (bash)
src/deflow/            the lifter (Java + ASM)
  Lift.java            unzip, detect VM, lift each class, repack, report
  ClassLifter.java     per class: pair chunks with drivers, re-check, roll back
  GroupLifter.java     rebuild one method from its VM cases
  Verify.java          load + link every class with the JVM verifier
SKILL.md               Claude Code skill
references/            internals.md (how it works), manual.md (debugging)
docs/                  website
```

## License

MIT
