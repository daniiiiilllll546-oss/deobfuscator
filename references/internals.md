# How the VM works and how deflow reverses it

## 1. What the obfuscator does to a method

Every real method is rewritten into an interpreter for a tiny stack VM.

**Driver** (the real method keeps its name and descriptor):

```java
RetType realMethod(args) {
    Frame f = new Frame(localsArray, stackSize);
    while (!f.done)
        switch (f.pc / CHUNK) {
            case 0: __flow$G$0(f); break;
            case 1: __flow$G$1(f); break;
        }
    return (RetType) f.result;
}
```

**Chunk** (`__flow$<group>$<chunk>`, takes the frame):

```java
static void __flow$G$C(Frame f) {
    switch (f.pc) {
        case 17: /* one real op, wrapped */  f.pc = 18; return;
        case 18: ...                          f.pc = 25; return;
    }
}
```

The two-level switch only splits the jump table so no method gets too big.
**Each `case pc:` is exactly one original JVM instruction**, wrapped in VM
bookkeeping.

## 2. The VM mirrors the real JVM

- `f.push(Object, int width)` / `f.pop()` move operands, boxed to `Object`. This
  stack is a 1:1 mirror of the real JVM operand stack.
- `f.locals[n]` mirrors real local slot `n`.
- `Frame.i/j/f/d(Object)` unboxing and `Xxx.valueOf(...)` boxing are type hints
  the obfuscator inserted; they carry no logic.
- `f.pc = const` is a `goto`; `f.done = true` followed by the driver returning
  `f.result` is a `return`.
- `f.rearrange(int)` encodes a stack shuffle (`DUP`, `DUP2`, `SWAP`, `POP`, …).
- `Frame.construct(...)` implements `new T(args)` through a placeholder object.

So reconstructing a method is mostly deletion: remove the bookkeeping, keep the
one real op per case, re-thread the jumps.

## 3. What the lifter does (`GroupLifter`)

1. **Collect cases** keyed by `pc` from all chunk methods of one group.
2. **Abstract-interpret** the combined case graph per instruction, tracking each
   VM-stack slot's width (1 or 2) and, for `construct`, where the placeholder
   came from. This picks the right physical stack op and catches height/width
   conflicts (→ bail).
3. **Emit** real bytecode: drop `push`/`pop`/`pc=`/`done=` and the frame loads;
   map `f.locals[n]` to real slot `200 + 2n` (high slots, never collide with the
   method's own); turn `rearrange` into real stack ops; turn `construct` into
   `NEW T; DUP; args; INVOKESPECIAL <init>`; copy real ops verbatim; rebuild
   try/catch ranges from the parsed exception table.
4. **Recompute** `maxStack`, `maxLocals` and stack-map frames with
   `ClassWriter(COMPUTE_FRAMES)`.

Boxing and the obfuscator's scratch locals are kept, so types stay consistent
without type inference. The decompiler cleans most of that up.

## 4. Rollback keeps the jar valid

Each rebuilt method is re-checked (ASM `Analyzer` + a real frame computation).
If that fails, the method's original interpreter body is restored and the
reason recorded. The rest of the class still lifts, so the output jar always
loads and runs.

## 5. Pointing it at a different VM

- **Frame class**: auto-detected (`Lift.detectVm`): the object parameter type
  most `__flow$G$C` methods share. Override: `DEFLOW_VM=internal/Name`.
- **Frame API names** (`push`, `pop`, `i/j/f/d`, `rearrange`, `construct`, fields
  `pc`, `done`, `locals`, `result`): the recognizers in `ClassLifter`
  (`isPush`, `isPop`, `isUnbox`, `isBox`, `ffGetField`, `ffPutField`) and
  `GroupLifter`.
- **Chunk naming** (`__flow$G$C`): `Lift.CHUNK`, `ClassLifter.chunkGroup`.

Same shape with different names: edit those. A fundamentally different VM
(register machine, no boxed stack) needs a new `step`/emit, but the
collect → interpret → emit → verify → roll back structure still holds.
