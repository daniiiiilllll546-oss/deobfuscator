package deflow;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

import static deflow.Lift.FF;
import static deflow.ClassLifter.*;
import deflow.Lift.Bail;

/**
 * Reconstructs ONE original method from its FlowFrame chunk cases.
 *
 * v1 strategy: strip only the VM-dispatch layer. The operand values ride the
 * real JVM operand stack exactly as the interpreter moved them through push/pop,
 * so box/unbox and the obfuscator's scratch locals are KEPT (types stay
 * consistent, no inference needed). We only:
 *   - drop `aload0` that feeds a FlowFrame op, and the push/pop/pc/done calls;
 *   - translate f.locals[n] <-> real local slot (LBASE + 2n);
 *   - translate f.rearrange(op) -> real DUP/SWAP/POP;
 *   - rebuild control flow: each case ends by setting pc (goto), or done (return).
 * Real ops, branches, field/array/invoke insns are copied verbatim.
 * Unhandled shapes throw Bail (class passes through untouched).
 */
final class GroupLifter {
    static final int LBASE = 200;            // real-slot base for f.locals[n] -> LBASE+2n
    final ClassNode cn;
    final MethodNode driver;                 // the real method to rebuild in place
    final List<MethodNode> chunkMethods;

    final TreeMap<Integer,Case> cases = new TreeMap<>();   // pc -> case
    final Map<Integer,LabelNode> labels = new HashMap<>(); // pc -> label

    GroupLifter(ClassNode cn, MethodNode driver, List<MethodNode> chunks){
        this.cn=cn; this.driver=driver; this.chunkMethods=chunks;
    }

    /** a single original instruction, as a slice of chunk bytecode */
    static final class Case {
        int pc;
        List<AbstractInsnNode> body = new ArrayList<>(); // insns between this case label and the next (excl. trailing control)
        String lastPendingPopped;                        // set during interp: pending type of the last Pending popped here
    }

    Type ret;
    List<Rule> rules;
    int fVar;   // FlowFrame receiver local in chunk methods: 0 if static, else 1 (instance: local0=this)

    final Map<AbstractInsnNode,Integer> rearrangeOp = new IdentityHashMap<>();

    void run(){
        fVar = (chunkMethods.get(0).access & Opcodes.ACC_STATIC)!=0 ? 0 : 1;
        rules = parseExceptionRules();   // parse BEFORE we overwrite the driver body
        collectCases();
        ret = Type.getReturnType(driver.desc);

        for(Case c : cases.values())
            for(AbstractInsnNode in : c.body) if(isConstruct(in)){ constructCases.add(c); break; }

        // 1) instruction-level width abstract-interp over the combined case CFG;
        //    fills rearrangeOp (DUP2/POP2/... choice) honoring internal branches.
        abstractInterp();

        TreeSet<Integer> boundaries = new TreeSet<>();
        for(Rule r : rules){ boundaries.add(r.lo); boundaries.add(r.hi); boundaries.add(r.handler); }

        // 2) emit
        InsnList out = new InsnList();
        emitPrologue(out);
        Iterator<Integer> bit = boundaries.iterator();
        Integer nextB = bit.hasNext()? bit.next(): null;
        for(Case c : cases.values()){
            while(nextB!=null && nextB <= c.pc){
                if(nextB < c.pc || !cases.containsKey(nextB)) out.add(label(nextB));
                nextB = bit.hasNext()? bit.next(): null;
            }
            out.add(label(c.pc));
            if(constructCases.contains(c)) translateConstructCase(c, out);
            else processCase(c, out);
        }
        while(nextB!=null){ out.add(label(nextB)); nextB = bit.hasNext()? bit.next(): null; }

        driver.instructions = out;
        driver.tryCatchBlocks = new ArrayList<>();
        for(Rule r : rules){
            driver.tryCatchBlocks.add(new TryCatchBlockNode(
                label(r.lo), label(r.hi), label(r.handler), r.type));
        }
        driver.maxStack = 0; driver.maxLocals = 0;
        driver.localVariables = null;
    }

    static final int MAXL = 512;
    static final int SBASE = LBASE + 2*MAXL;   // scratch ref-slot base for construct spill/reload

    // a VM-stack slot: width (1/2) + optional pending-class provenance (for construct)
    static final class V {
        final int w; final String pt;
        V(int w, String pt){ this.w=w; this.pt=pt; }
        public boolean equals(Object o){ return o instanceof V v && v.w==w && Objects.equals(v.pt,pt); }
        public int hashCode(){ return w*31 + (pt==null?0:pt.hashCode()); }
    }
    final Map<AbstractInsnNode,String> constructType = new IdentityHashMap<>();
    final Map<Case,Integer> entryHeight = new IdentityHashMap<>();                       // VM-stack height at case entry
    final Map<Case,V[]> constructWs = new IdentityHashMap<>();                           // VM-stack at the construct insn
    final Set<Case> constructCases = Collections.newSetFromMap(new IdentityHashMap<>()); // cases that call FlowFrame.construct

    static boolean isConstruct(AbstractInsnNode in){
        return in instanceof MethodInsnNode m && m.owner.equals(FF) && m.name.equals("construct");
    }

    // ---- instruction-level width+provenance abstract interpretation ----
    void abstractInterp(){
        Map<Integer,AbstractInsnNode> firstInsn = new HashMap<>();
        Map<LabelNode,AbstractInsnNode> labelTarget = new IdentityHashMap<>();
        Map<AbstractInsnNode,Case> ownerCase = new IdentityHashMap<>();
        for(Case c : cases.values()){
            if(c.body.isEmpty()) throw new Bail("empty case pc="+c.pc);
            firstInsn.put(c.pc, c.body.get(0));
            for(AbstractInsnNode in : c.body){
                ownerCase.put(in, c);
                if(in instanceof LabelNode ln) labelTarget.put(ln, in);
            }
        }
        Map<AbstractInsnNode,V[]> wsAt = new IdentityHashMap<>();
        Deque<AbstractInsnNode> work = new ArrayDeque<>();
        Deque<V[]> wsStk = new ArrayDeque<>();
        seed(firstInsn, 0, new V[0], work, wsStk);
        for(Rule r : rules) seed(firstInsn, r.handler, new V[]{new V(1,null)}, work, wsStk);
        int guard=0;
        while(!work.isEmpty()){
            if(guard++ > 5_000_000) throw new Bail("interp too large");
            AbstractInsnNode in = work.pop(); V[] wArr = wsStk.pop();
            V[] prev = wsAt.get(in);
            if(prev!=null){
                if(prev.length!=wArr.length) throw new Bail("ws height merge conflict @pc"+ownerCase.get(in).pc);
                for(int i=0;i<prev.length;i++) if(prev[i].w!=wArr[i].w) throw new Bail("ws width merge conflict @pc"+ownerCase.get(in).pc);
                // widths agree; merge provenance (differ -> null). re-process only if it changed.
                boolean changed=false; V[] merged=new V[prev.length];
                for(int i=0;i<prev.length;i++){ String pt = Objects.equals(prev[i].pt,wArr[i].pt)? prev[i].pt : null; if(!Objects.equals(pt,prev[i].pt)) changed=true; merged[i]=new V(prev[i].w,pt); }
                if(!changed) continue;
                wsAt.put(in, merged); wArr = merged;
            } else wsAt.put(in, wArr);
            Case oc = ownerCase.get(in);
            if(oc!=null && firstInsn.get(oc.pc)==in) entryHeight.put(oc, wArr.length);
            ArrayList<V> ws = new ArrayList<>(Arrays.asList(wArr));
            List<AbstractInsnNode> succ = step(in, ws, firstInsn, labelTarget, ownerCase.get(in));
            V[] ex = ws.toArray(new V[0]);
            for(AbstractInsnNode s : succ){ work.push(s); wsStk.push(ex); }
        }
    }
    void seed(Map<Integer,AbstractInsnNode> firstInsn, int pc, V[] ws, Deque<AbstractInsnNode> work, Deque<V[]> wsStk){
        AbstractInsnNode fi = firstInsn.get(pc);
        if(fi==null) throw new Bail("seed non-case pc="+pc);
        work.push(fi); wsStk.push(ws);
    }
    List<AbstractInsnNode> step(AbstractInsnNode in, ArrayList<V> ws,
            Map<Integer,AbstractInsnNode> firstInsn, Map<LabelNode,AbstractInsnNode> labelTarget, Case owner){
        List<AbstractInsnNode> out = new ArrayList<>();
        int op = in.getOpcode();
        if(ffPutField(in,"pc")){
            AbstractInsnNode p = in.getPrevious();
            if(!isIntConst(p)) throw new Bail("pc not const @pc"+owner.pc);
            AbstractInsnNode t = firstInsn.get(intVal(p));
            if(t==null) throw new Bail("pc->non-case "+intVal(p));
            out.add(t); return out;
        }
        if(ffPutField(in,"done")) return out;
        if(isPush(in)){
            AbstractInsnNode p=in.getPrevious(); if(!isIntConst(p)) throw new Bail("push w");
            ws.add(new V(intVal(p), pendingTypeBefore(p)));   // provenance if value came from FlowFrame.pending
            return fall(in,out);
        }
        if(isPop(in)){
            if(ws.isEmpty()) throw new Bail("pop empty @pc"+owner.pc);
            V v = ws.remove(ws.size()-1);
            if(v.pt!=null){
                // A Pending may only be popped as the receiver inside its own construct case.
                // Any other pop (e.g. storing it into a VM local) means the Pending escapes
                // our stack-only routing; null-substitution would verify but run wrong -> bail.
                if(!constructCases.contains(owner)) throw new Bail("pending escapes @pc"+owner.pc);
                owner.lastPendingPopped = v.pt;    // remember for the construct in this case
            }
            return fall(in,out);
        }
        if(ffVirtual(in,"rearrange","(I)V")){
            AbstractInsnNode p=in.getPrevious(); if(!isIntConst(p)) throw new Bail("rearrange op");
            rearrangeOp.put(in, rearrangePhysical(intVal(p), ws));
            return fall(in,out);
        }
        // construct: VM-stack effect is just the receiver pop (instance is placed into
        // f.locals/values in-place by the VM, not pushed); treat as ws-neutral here.
        // Emit still bails (needs full Pending dataflow) so the method stays interpreter.
        if(isConstruct(in)){
            String T = owner.lastPendingPopped;
            if(T!=null){
                constructType.put(in, T);
                constructWs.put(owner, ws.toArray(new V[0]));   // snapshot (survivor still has pt==T)
                // the surviving Pending slot now holds the real instance: clear its provenance
                for(int k=0;k<ws.size();k++) if(T.equals(ws.get(k).pt)) ws.set(k, new V(ws.get(k).w, null));
            }
            return fall(in,out);
        }
        if(in instanceof MethodInsnNode m && m.owner.equals(FF) && m.name.equals("clear")){ ws.clear(); return fall(in,out); }
        if(in instanceof JumpInsnNode j){
            AbstractInsnNode t = labelTarget.get(j.label);
            if(t==null) throw new Bail("jump->unknown label @pc"+owner.pc);
            if(op==Opcodes.GOTO){ out.add(t); return out; }
            if(op==Opcodes.JSR) throw new Bail("jsr");
            out.add(t); return fall(in,out);
        }
        if(in instanceof TableSwitchInsnNode ts){ out.add(labelTarget.get(ts.dflt)); for(LabelNode l: ts.labels) out.add(labelTarget.get(l)); return checkNull(out,owner); }
        if(in instanceof LookupSwitchInsnNode ls){ out.add(labelTarget.get(ls.dflt)); for(LabelNode l: ls.labels) out.add(labelTarget.get(l)); return checkNull(out,owner); }
        if(op==Opcodes.ATHROW || (op>=Opcodes.IRETURN && op<=Opcodes.RETURN)) return out;
        return fall(in, out);
    }
    List<AbstractInsnNode> fall(AbstractInsnNode in, List<AbstractInsnNode> out){
        AbstractInsnNode n = in.getNext();
        if(n!=null) out.add(n);
        return out;
    }
    List<AbstractInsnNode> checkNull(List<AbstractInsnNode> out, Case owner){
        for(AbstractInsnNode n: out) if(n==null) throw new Bail("switch->unknown label @pc"+owner.pc);
        return out;
    }

    /** a reconstructed try-catch: protected [lo,hi) pc range, catch `type` -> handler pc */
    static final class Rule { int lo, hi, handler; String type; }

    /**
     * The driver wraps the whole dispatch in try{...}catch(Throwable error){ rules; athrow }.
     * Each rule is  if(pc>=LO && pc<HI && error instanceof TYPE){ ...; f.pc=HANDLER; continue }.
     * We recover (LO,HI,TYPE,HANDLER) tuples by scanning the catch handler block.
     */
    List<Rule> parseExceptionRules(){
        List<Rule> rules = new ArrayList<>();
        if(driver.tryCatchBlocks==null || driver.tryCatchBlocks.isEmpty()) return rules;
        // handler label of the outer Throwable catch
        LabelNode handlerLbl = driver.tryCatchBlocks.get(0).handler;
        AbstractInsnNode in = handlerLbl;
        int lo=Integer.MIN_VALUE, hi=Integer.MIN_VALUE; String type=null;
        for(; in!=null; in=in.getNext()){
            if(ffGetField(in,"pc")){
                AbstractInsnNode c = in.getNext(), jmp = c==null?null:c.getNext();
                if(isIntConst(c) && jmp instanceof JumpInsnNode j){
                    if(j.getOpcode()==Opcodes.IF_ICMPLT) lo=intVal(c);
                    else if(j.getOpcode()==Opcodes.IF_ICMPGE) hi=intVal(c);
                }
                continue;
            }
            if(in instanceof TypeInsnNode t && t.getOpcode()==Opcodes.INSTANCEOF) type=t.desc;
            if(ffPutField(in,"pc")){
                AbstractInsnNode prev = in.getPrevious();
                if(isIntConst(prev) && lo!=Integer.MIN_VALUE && hi!=Integer.MIN_VALUE){
                    Rule r=new Rule(); r.lo=lo; r.hi=hi; r.handler=intVal(prev); r.type=type;
                    rules.add(r);
                }
                type=null; // reset for next rule (lo/hi re-read)
            }
            if(in.getOpcode()==Opcodes.ATHROW) break; // end of catch (rethrow)
        }
        return rules;
    }

    LabelNode label(int pc){ return labels.computeIfAbsent(pc, k->new LabelNode()); }

    // ---- gather pc -> case across all chunk methods ----
    void collectCases(){
        for(MethodNode ch : chunkMethods){
            AbstractInsnNode p = ch.instructions.getFirst();
            // chunk body: aload0; getfield pc; (tableswitch|lookupswitch)
            SwitchHolder sw = findPcSwitch(ch);
            if(sw==null) throw new Bail("no pc switch in "+ch.name);
            // Map each case label to its pc key, then slice insns.
            // Build ordered list of (pcKey,label).
            List<int[]> order = new ArrayList<>(); // [pcKey, labelInsnIndex via identity]
            Map<LabelNode,Integer> labelPc = new IdentityHashMap<>();
            for(int i=0;i<sw.keys.size();i++) labelPc.put(sw.labels.get(i), sw.keys.get(i));
            // default label -> typically "throw VerifyError", ignore
            // Walk the whole chunk instruction list; when we hit a case label, start a slice.
            AbstractInsnNode cur = ch.instructions.getFirst();
            Case open=null;
            for(AbstractInsnNode in = ch.instructions.getFirst(); in!=null; in=in.getNext()){
                if(in instanceof LabelNode ln && labelPc.containsKey(ln)){
                    open = new Case(); open.pc = labelPc.get(ln);
                    if(cases.containsKey(open.pc)) throw new Bail("dup pc "+open.pc);
                    cases.put(open.pc, open);
                    continue;
                }
                if(open!=null) open.body.add(in);
            }
        }
        if(cases.isEmpty()) throw new Bail("no cases");
    }

    static final class SwitchHolder { List<Integer> keys=new ArrayList<>(); List<LabelNode> labels=new ArrayList<>(); }
    SwitchHolder findPcSwitch(MethodNode ch){
        for(AbstractInsnNode in : ch.instructions){
            if(in instanceof TableSwitchInsnNode t){
                SwitchHolder h=new SwitchHolder();
                for(int k=t.min;k<=t.max;k++){ h.keys.add(k); }
                h.labels.addAll(t.labels);
                return h;
            }
            if(in instanceof LookupSwitchInsnNode l){
                SwitchHolder h=new SwitchHolder();
                h.keys.addAll(l.keys); h.labels.addAll(l.labels);
                return h;
            }
        }
        return null;
    }

    // ---- prologue: how the driver seeded f.locals from args ----
    // VM locals are an Object[]; args were boxed in. Mirror: realslot LBASE+2k holds
    // the (boxed) arg k, so body locals load/store are plain ALOAD/ASTORE of Object.
    void emitPrologue(InsnList out){
        Type[] args = Type.getArgumentTypes(driver.desc);
        boolean isStatic = (driver.access & Opcodes.ACC_STATIC)!=0;
        int slot = 0, vmIdx = 0;
        if(!isStatic){
            out.add(new VarInsnNode(Opcodes.ALOAD, 0));
            out.add(new VarInsnNode(Opcodes.ASTORE, LBASE + 2*vmIdx));
            vmIdx++; slot=1;
        }
        for(Type a : args){
            out.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot));
            box(out, a);
            out.add(new VarInsnNode(Opcodes.ASTORE, LBASE + 2*vmIdx));
            slot += a.getSize(); vmIdx++;
        }
    }
    static void box(InsnList out, Type t){
        String owner=null, desc=null;
        switch(t.getSort()){
            case Type.BOOLEAN: owner="java/lang/Boolean"; desc="(Z)Ljava/lang/Boolean;"; break;
            case Type.CHAR: owner="java/lang/Character"; desc="(C)Ljava/lang/Character;"; break;
            case Type.BYTE: owner="java/lang/Byte"; desc="(B)Ljava/lang/Byte;"; break;
            case Type.SHORT: owner="java/lang/Short"; desc="(S)Ljava/lang/Short;"; break;
            case Type.INT: owner="java/lang/Integer"; desc="(I)Ljava/lang/Integer;"; break;
            case Type.LONG: owner="java/lang/Long"; desc="(J)Ljava/lang/Long;"; break;
            case Type.FLOAT: owner="java/lang/Float"; desc="(F)Ljava/lang/Float;"; break;
            case Type.DOUBLE: owner="java/lang/Double"; desc="(D)Ljava/lang/Double;"; break;
            default: return; // reference: no box
        }
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", desc, false));
    }

    // ---- emit one case (one original instruction) ----
    // Strips VM dispatch, translates locals/rearrange/pc-control; rearrange uses the
    // physical opcode precomputed by abstractInterp. Real ops/branches copied verbatim.
    void processCase(Case c, InsnList out){
        List<AbstractInsnNode> b = c.body;
        Map<LabelNode,LabelNode> lm = new IdentityHashMap<>(){
            @Override public LabelNode get(Object k){
                LabelNode v=super.get(k); if(v==null){ v=new LabelNode(); put((LabelNode)k,v);} return v; }
        };
        int pendingStoreSlot=-1;
        for(int i=0;i<b.size();i++){
            AbstractInsnNode in = b.get(i);
            int op = in.getOpcode();

            if(op==Opcodes.ALOAD && ((VarInsnNode)in).var==fVar) continue; // FlowFrame receiver

            if(ffPutField(in,"pc")){
                AbstractInsnNode prev=b.get(i-1);
                dropLastIfConst(out);
                out.add(new JumpInsnNode(Opcodes.GOTO, label(intVal(prev))));
                continue;
            }
            if(ffPutField(in,"done")){ dropLastIfConst(out); emitReturn(out, ret, false); continue; }
            if(ffPutField(in,"result")) continue;
            if(ffGetField(in,"pc")||ffGetField(in,"done")||ffGetField(in,"result")) continue;

            if(ffGetField(in,"locals")){
                AbstractInsnNode idxIn = next(b,i+1);
                int idx=intVal(idxIn);
                AbstractInsnNode after = next(b,i+2);
                if(after!=null && after.getOpcode()==Opcodes.AALOAD){
                    out.add(new VarInsnNode(Opcodes.ALOAD, LBASE+2*idx)); i+=2; continue;
                }
                pendingStoreSlot=idx; i+=1; continue;
            }
            if(op==Opcodes.AASTORE && pendingStoreSlot>=0){
                out.add(new VarInsnNode(Opcodes.ASTORE, LBASE+2*pendingStoreSlot));
                pendingStoreSlot=-1; continue;
            }

            if(isPush(in)){ dropLastIfConst(out); continue; }
            if(isPop(in)) continue;

            if(ffVirtual(in,"rearrange","(I)V")){
                Integer phys = rearrangeOp.get(in);
                if(phys==null) throw new Bail("rearrange not interp'd pc="+c.pc);
                dropLastIfConst(out);
                out.add(new InsnNode(phys));
                continue;
            }

            if(isBox(in) || isUnbox(in)){ out.add(in.clone(lm)); continue; }

            if(in instanceof MethodInsnNode m && m.owner.equals(FF)){
                if(m.name.equals("clear")) throw new Bail("clear emit pc="+c.pc);
                if(m.name.equals("construct")) throw new Bail("construct pc="+c.pc); // needs NEW/DUP/INVOKESPECIAL (phase 2)
                out.add(in.clone(lm)); continue;   // staticField/pending/monitor/compare kept reflective
            }

            if(op==Opcodes.RETURN) continue;
            if(in instanceof LineNumberNode || in instanceof FrameNode) continue;

            out.add(in.clone(lm));
        }
    }

    // Translate f.rearrange(opcode) to a real stack op, mirroring FlowFrame.rearrange's
    // width-aware semantics on the width list `ws` (top = last). All VM values are one
    // physical slot (boxed), so the real op is chosen by how many values move.
    int rearrangePhysical(int op, ArrayList<V> ws){
        switch(op){
            case 87: pop(ws); return Opcodes.POP;
            case 88: { V a=pop(ws); if(a.w==2) return Opcodes.POP; pop(ws); return Opcodes.POP2; }
            case 89: { V a=top(ws); ws.add(a); return Opcodes.DUP; }
            case 90: { V a=pop(ws), b=pop(ws); ws.add(a); ws.add(b); ws.add(a); return Opcodes.DUP_X1; }
            case 91: { V a=pop(ws), b=pop(ws);
                       if(b.w==2){ ws.add(a); ws.add(b); ws.add(a); return Opcodes.DUP_X1; }
                       V cc=pop(ws); ws.add(a); ws.add(cc); ws.add(b); ws.add(a); return Opcodes.DUP_X2; }
            case 92: { V a=top(ws); if(a.w==2){ ws.add(a); return Opcodes.DUP; }
                       V t=pop(ws), s=pop(ws); ws.add(s); ws.add(t); ws.add(s); ws.add(t); return Opcodes.DUP2; }
            case 93: { V a=top(ws);
                       if(a.w==2){ V t=pop(ws), b=pop(ws); ws.add(t); ws.add(b); ws.add(t); return Opcodes.DUP_X1; }
                       V t=pop(ws), b=pop(ws), cc=pop(ws); ws.add(b); ws.add(t); ws.add(cc); ws.add(b); ws.add(t); return Opcodes.DUP2_X1; }
            case 94: { V a=pop(ws);
                       if(a.w==2){ V b=pop(ws);
                           if(b.w==2){ ws.add(a); ws.add(b); ws.add(a); return Opcodes.DUP_X1; }
                           V cc=pop(ws); ws.add(a); ws.add(cc); ws.add(b); ws.add(a); return Opcodes.DUP_X2; }
                       V b=pop(ws), cc=pop(ws);
                       if(cc.w==2){ ws.add(b); ws.add(a); ws.add(cc); ws.add(b); ws.add(a); return Opcodes.DUP2_X1; }
                       V e=pop(ws); ws.add(b); ws.add(a); ws.add(e); ws.add(cc); ws.add(b); ws.add(a); return Opcodes.DUP2_X2; }
            case 95: { V a=pop(ws), b=pop(ws); ws.add(a); ws.add(b); return Opcodes.SWAP; }
            default: throw new Bail("unknown rearrange "+op);
        }
    }
    static V pop(ArrayList<V> ws){ if(ws.isEmpty()) throw new Bail("rearrange underflow"); return ws.remove(ws.size()-1); }
    static V top(ArrayList<V> ws){ if(ws.isEmpty()) throw new Bail("rearrange underflow"); return ws.get(ws.size()-1); }

    // If the push value was produced by FlowFrame.pending(T.class), return T's internal name.
    static String pendingTypeBefore(AbstractInsnNode widthConst){
        AbstractInsnNode producer = widthConst.getPrevious();
        if(producer instanceof MethodInsnNode m && m.getOpcode()==Opcodes.INVOKESTATIC
                && m.owner.equals(FF) && m.name.equals("pending")){
            AbstractInsnNode ldc = m.getPrevious();
            if(ldc instanceof LdcInsnNode l && l.cst instanceof Type t && t.getSort()==Type.OBJECT)
                return t.getInternalName();
        }
        return null;
    }

    static void dropLastIfConst(InsnList out){
        AbstractInsnNode last = out.getLast();
        if(last!=null && isIntConst(last)) out.remove(last);
    }

    // Translate a `new T(args)` case: pending(T) placeholder was pushed earlier; this
    // case pops it (receiver), builds Class[] types + Object[] args reflectively, calls
    // FlowFrame.construct. We emit: POP (drop the Pending) ; NEW T; DUP; <spread args>;
    // INVOKESPECIAL T.<init>(desc)V, leaving the real object where the Pending was.
    // Rewrite a `new T(args)` case. Shape (one case = the INVOKESPECIAL step):
    //   (aload f; f.pop(); astore t)*            -- drain receiver + args into real temps
    //   aload f; aload recvTemp;                 -- receiver load (unused by rebuild)
    //   iconst N; anewarray Class;  (dup; iconst k; ldc Tk.class|getstatic W.TYPE; aastore)*
    //   iconst N; anewarray Object; (dup; iconst k; <argExpr>; aastore)*
    //   f.construct(recv, types, args)
    //   aload f; iconst nextpc; putfield pc; return   (or putfield done)
    // We discard the reflective array-building: exactly ONE Pending survives on the VM
    // stack (NEW;DUP;INVOKESPECIAL always leaves one ref). Drain the temps, POP the
    // surviving placeholder, then emit NEW T; DUP; <args>; INVOKESPECIAL in one block so
    // the uninitialized ref never crosses a frame boundary.
    void translateConstructCase(Case c, InsnList out){
        List<AbstractInsnNode> b = c.body;
        int ci=-1;
        for(int i=0;i<b.size();i++) if(isConstruct(b.get(i))){ if(ci>=0) throw new Bail("two constructs pc="+c.pc); ci=i; }
        if(ci<0) throw new Bail("no construct pc="+c.pc);
        String T = constructType.get(b.get(ci));
        if(T==null) throw new Bail("construct T null pc="+c.pc);

        // locate the two anewarrays (Class[] types, Object[] args)
        int typesAt=-1, argsAt=-1;
        for(int i=0;i<ci;i++){
            if(b.get(i) instanceof TypeInsnNode t && t.getOpcode()==Opcodes.ANEWARRAY){
                if(t.desc.equals("java/lang/Class") && typesAt<0) typesAt=i;
                else if(t.desc.equals("java/lang/Object") && argsAt<0) argsAt=i;
            }
        }
        if(typesAt<0 || argsAt<0 || argsAt<typesAt) throw new Bail("construct arrays pc="+c.pc);

        // param types from the Class[] builder
        List<Type> params = new ArrayList<>();
        for(int i=typesAt+1;i<argsAt;i++){
            if(b.get(i) instanceof LdcInsnNode l && l.cst instanceof Type ty) params.add(ty);
            else if(b.get(i) instanceof FieldInsnNode f && f.getOpcode()==Opcodes.GETSTATIC && f.name.equals("TYPE"))
                params.add(primForWrapper(f.owner));
        }

        // arg value exprs from the Object[] builder: DUP; iconst k; <expr>; AASTORE
        List<List<AbstractInsnNode>> argExprs = new ArrayList<>();
        int i=argsAt+1;
        while(i<ci){
            if(b.get(i).getOpcode()==Opcodes.DUP){
                if(!(i+1<ci && isIntConst(b.get(i+1)))) throw new Bail("construct arg idx pc="+c.pc);
                int j=i+2; List<AbstractInsnNode> expr=new ArrayList<>();
                while(j<ci && b.get(j).getOpcode()!=Opcodes.AASTORE){
                    int opj=b.get(j).getOpcode();
                    if(opj==Opcodes.ANEWARRAY||opj==Opcodes.NEWARRAY) throw new Bail("construct nested arg pc="+c.pc);
                    expr.add(b.get(j)); j++;
                }
                if(j>=ci) throw new Bail("construct arg unterminated pc="+c.pc);
                argExprs.add(expr); i=j+1;
            } else i++;
        }
        if(argExprs.size()!=params.size()) throw new Bail("construct arity "+argExprs.size()+"/"+params.size()+" pc="+c.pc);

        // locate the surviving Pending on the VM stack (exactly one slot with pt==T).
        // Every VM value is one physical ref slot (boxed), so depth = slots above it.
        V[] cws = constructWs.get(c);
        if(cws==null) throw new Bail("construct no ws pc="+c.pc);
        int idx=-1, cnt=0;
        for(int k=0;k<cws.length;k++) if(T.equals(cws[k].pt)){ idx=k; cnt++; }
        if(cnt!=1) throw new Bail("construct survivors="+cnt+" pc="+c.pc);
        int depth = cws.length-1-idx;   // ref slots sitting above the survivor

        // 1) drain the leading `f.pop(); astore t` into the obfuscator's temps.
        //    Strict: the prelude may only be receiver/temp loads, the drain pops, and the
        //    Class[]/Object[] array-size consts. Anything else means a value is computed in
        //    this case (not just drained) -> bail rather than silently drop it.
        for(int k=0;k<typesAt;k++){
            AbstractInsnNode in=b.get(k);
            if(in instanceof LineNumberNode || in instanceof FrameNode || in instanceof LabelNode) continue;
            if(in.getOpcode()==Opcodes.ALOAD || isIntConst(in)) continue;
            if(isPop(in)){
                AbstractInsnNode st = next(b,k+1);
                if(st==null || st.getOpcode()!=Opcodes.ASTORE) throw new Bail("construct drain not astore pc="+c.pc);
                out.add(st.clone(EMPTY)); k++; continue;   // consume the astore
            }
            throw new Bail("construct prelude op "+in.getOpcode()+" pc="+c.pc);
        }
        // 2) spill the `depth` ref slots above the survivor to scratch locals (top-first)
        for(int k=0;k<depth;k++) out.add(new VarInsnNode(Opcodes.ASTORE, SBASE+k));
        // 3) discard the surviving placeholder, build the real object in one block
        out.add(new InsnNode(Opcodes.POP));
        out.add(new TypeInsnNode(Opcodes.NEW, T));
        out.add(new InsnNode(Opcodes.DUP));
        for(int k=0;k<argExprs.size();k++){ emitArgExpr(argExprs.get(k), out); coerceTo(out, params.get(k)); }
        StringBuilder desc=new StringBuilder("(");
        for(Type p: params) desc.append(p.getDescriptor());
        desc.append(")V");
        out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, T, "<init>", desc.toString(), false));
        // 4) restore the spilled slots above the new instance (reverse order)
        for(int k=depth-1;k>=0;k--) out.add(new VarInsnNode(Opcodes.ALOAD, SBASE+k));

        // 5) trailing control after the construct call. Strict: only receiver loads, the
        //    next-pc/done const, and the pc/done store may follow -- a dropped pop/store here
        //    would pass the guard (interp allows pops in a construct case) and lose data.
        for(int k=ci+1;k<b.size();k++){
            AbstractInsnNode in=b.get(k);
            if(in instanceof LineNumberNode || in instanceof FrameNode || in instanceof LabelNode) continue;
            if(in.getOpcode()==Opcodes.ALOAD || isIntConst(in)) continue;
            if(ffPutField(in,"pc")){ out.add(new JumpInsnNode(Opcodes.GOTO, label(intVal(b.get(k-1))))); return; }
            if(ffPutField(in,"done")){ emitReturn(out, ret, false); return; }
            throw new Bail("construct trailing op "+in.getOpcode()+" pc="+c.pc);
        }
        throw new Bail("construct no pc-control pc="+c.pc);
    }

    void emitArgExpr(List<AbstractInsnNode> expr, InsnList out){
        Map<LabelNode,LabelNode> lm = new IdentityHashMap<>(){
            @Override public LabelNode get(Object k){ LabelNode v=super.get(k); if(v==null){v=new LabelNode();put((LabelNode)k,v);} return v; } };
        for(int i=0;i<expr.size();i++){
            AbstractInsnNode in=expr.get(i); int op=in.getOpcode();
            if(op==Opcodes.ALOAD && ((VarInsnNode)in).var==fVar) continue;
            if(ffGetField(in,"locals")){
                AbstractInsnNode idxIn=next(expr,i+1); int idx=intVal(idxIn);
                AbstractInsnNode after=next(expr,i+2);
                if(after!=null && after.getOpcode()==Opcodes.AALOAD){ out.add(new VarInsnNode(Opcodes.ALOAD, LBASE+2*idx)); i+=2; continue; }
                throw new Bail("construct arg locals-store");
            }
            if(isBox(in)||isUnbox(in)){ out.add(in.clone(lm)); continue; }
            if(in instanceof MethodInsnNode m && m.owner.equals(FF)) throw new Bail("construct arg FF op "+m.name);
            if(isPush(in)||isPop(in)) throw new Bail("construct arg vm-stack");
            if(in instanceof LineNumberNode||in instanceof FrameNode) continue;
            out.add(in.clone(lm));
        }
    }
    // coerce the just-emitted value (as it sits in Object[]: boxed for primitives) to param type
    void coerceTo(InsnList out, Type p){
        if(p.getSort()==Type.OBJECT || p.getSort()==Type.ARRAY){
            if(!p.getInternalName().equals("java/lang/Object")) out.add(new TypeInsnNode(Opcodes.CHECKCAST, p.getInternalName()));
        } else {
            unbox(out, p); // boxed wrapper -> primitive
        }
    }
    static Type primForWrapper(String owner){
        switch(owner){
            case "java/lang/Integer": return Type.INT_TYPE;
            case "java/lang/Long": return Type.LONG_TYPE;
            case "java/lang/Short": return Type.SHORT_TYPE;
            case "java/lang/Byte": return Type.BYTE_TYPE;
            case "java/lang/Character": return Type.CHAR_TYPE;
            case "java/lang/Boolean": return Type.BOOLEAN_TYPE;
            case "java/lang/Float": return Type.FLOAT_TYPE;
            case "java/lang/Double": return Type.DOUBLE_TYPE;
            case "java/lang/Void": return Type.VOID_TYPE;
            default: throw new Bail("unknown TYPE wrapper "+owner);
        }
    }

    void emitReturn(InsnList out, Type ret, boolean hasResult){
        if(ret.getSort()==Type.VOID){ out.add(new InsnNode(Opcodes.RETURN)); }
        else {
            // result value is on the real stack (we skipped putfield result); it is boxed
            // (VM result is Object). Unbox to the real return type if primitive.
            if(ret.getSort()!=Type.OBJECT && ret.getSort()!=Type.ARRAY) unbox(out, ret);
            else if(!ret.getInternalName().equals("java/lang/Object"))
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, ret.getInternalName()));
            out.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        }
    }
    static void unbox(InsnList out, Type t){
        String owner,m,d;
        switch(t.getSort()){
            case Type.BOOLEAN: owner="java/lang/Boolean"; m="booleanValue"; d="()Z"; break;
            case Type.CHAR: owner="java/lang/Character"; m="charValue"; d="()C"; break;
            case Type.BYTE: owner="java/lang/Byte"; m="byteValue"; d="()B"; break;
            case Type.SHORT: owner="java/lang/Short"; m="shortValue"; d="()S"; break;
            case Type.INT: owner="java/lang/Integer"; m="intValue"; d="()I"; break;
            case Type.LONG: owner="java/lang/Long"; m="longValue"; d="()J"; break;
            case Type.FLOAT: owner="java/lang/Float"; m="floatValue"; d="()F"; break;
            case Type.DOUBLE: owner="java/lang/Double"; m="doubleValue"; d="()D"; break;
            default: return;
        }
        out.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, m, d, false));
    }

    // ---- helpers ----
    static final Map<LabelNode,LabelNode> EMPTY = new IdentityHashMap<>(){
        @Override public LabelNode get(Object k){ return (LabelNode)k; } // clone keeps same labels
    };
    static AbstractInsnNode next(List<AbstractInsnNode> b, int i){ return i<b.size()? b.get(i): null; }
    static boolean isIntConst(AbstractInsnNode in){
        if(in==null) return false;
        int op=in.getOpcode();
        if(op>=Opcodes.ICONST_M1 && op<=Opcodes.ICONST_5) return true;
        if(op==Opcodes.BIPUSH || op==Opcodes.SIPUSH) return true;
        if(in instanceof LdcInsnNode l && l.cst instanceof Integer) return true;
        return false;
    }
    static int intVal(AbstractInsnNode in){
        int op=in.getOpcode();
        if(op>=Opcodes.ICONST_M1 && op<=Opcodes.ICONST_5) return op-Opcodes.ICONST_0;
        if(in instanceof IntInsnNode ii) return ii.operand;
        if(in instanceof LdcInsnNode l && l.cst instanceof Integer i) return i;
        throw new Bail("not int const");
    }
}
