# Running the steps by hand

`deflow` does all of this for you. Use these when debugging the lifter.

After one run of `deflow`, the dependencies are in `~/.cache/deflow/lib` and
the compiled lifter in `~/.cache/deflow/classes-*`.

```bash
L=~/.cache/deflow/lib
CP="$(ls -d ~/.cache/deflow/classes-* | head -1):$L/asm-9.9.1.jar:$L/asm-tree-9.9.1.jar:$L/asm-analysis-9.9.1.jar:$L/asm-util-9.9.1.jar"
```

## Lift

```bash
java -cp "$CP" deflow.Lift in.jar lifted.jar
```

Exit 0 = lifted, 3 = no VM found (output is a copy), 1 = error.

One class only, with its rebuilt bytecode and per-method verify result written
to `lifted.jar.dump.txt`:

```bash
java -cp "$CP" deflow.Lift in.jar lifted.jar com/example/SomeClass
```

### Report

```
vm: a/b/Frame
lifted classes:   N
methods lifted:   N
methods kept:     N   <- still interpreter form (unhandled shape)
classes bailed:   N   <- whole class untouched; should be 0
classes no-vm:    N
--- why methods were kept (top) ---
```

The "why" histogram is the to-do list for the lifter.

### Env flags

| Flag | Effect |
|---|---|
| `DEFLOW_VM=a/b/Frame` | Force the frame class instead of auto-detecting |
| `LIFT_DEBUG=1` | Stack traces for kept methods + an instruction window around each verify failure |
| `LIFT_WHERE=1` | One line per kept method: class, method, reason |
| `LIFT_NOVERIFY=1` | Skip the per-method re-check (triage only, may emit invalid code) |

## Verify

```bash
DEFLOW_VM=a/b/Frame java -cp "$CP" deflow.Verify lifted.jar libs/ other.jar
```

Loads and links every class (no static init). `VERIFY ERRORS: 0` is the bar.
"missing deps" are classes whose libraries weren't on the classpath.

## Decompile

```bash
java -jar ~/.cache/deflow/lib/vineflower-1.11.2.jar -mpm=60 lifted.jar src/
```
