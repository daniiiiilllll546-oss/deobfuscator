package deflow;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

import static deflow.Lift.FF;
import deflow.Lift.Bail;

/**
 * Lifts every FlowFrame VM method group in one class back to normal bytecode.
 *
 * Model: each `case pc:` in a __flow$G$C chunk is exactly ONE original JVM
 * instruction, wrapped in VM scaffolding. The VM operand stack (push/pop, boxed
 * as Object) mirrors the real operand stack; f.locals[] mirrors real locals.
 * We translate per case, skipping scaffolding (push/pop/box/unbox/pc), emitting
 * the one real op, and threading control flow through labels keyed by pc.
 */
final class ClassLifter {
    final ClassNode cn;
    final List<String> skippedGroups = new ArrayList<>();
    ClassLifter(ClassNode cn){ this.cn = cn; }

    // Reconstruct frames for this one method; throws if the bytecode is invalid
    // (stack under/overflow), which is our signal to roll the method back.
    void verify(MethodNode mn){
        // Analyzer allocates (maxLocals+maxStack) slots per instruction. Try ASM's exact
        // COMPUTE_MAXS bounds first (fast); if that fails, retry once with the old large
        // bounds so the accept/reject result is identical to the original check.
        try {
            int[] m = maxs(mn);
            mn.maxStack = m[0]; mn.maxLocals = m[1];
            new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicInterpreter()).analyze(cn.name, mn);
        } catch (Throwable fast) {
            mn.maxStack = 8000; mn.maxLocals = 2000;
            try {
                new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicInterpreter()).analyze(cn.name, mn);
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException ae){
                if(System.getenv("LIFT_DEBUG")!=null){
                    int idx = ae.node==null? -1 : mn.instructions.indexOf(ae.node);
                    if(idx<0){ var m=java.util.regex.Pattern.compile("instruction (\\d+)").matcher(ae.getMessage()); if(m.find()) idx=Integer.parseInt(m.group(1)); }
                    System.out.println("  VERIFY FAIL "+mn.name+" @insn "+idx+": "+ae.getMessage());
                    dumpWindow(mn, idx);
                }
                throw new RuntimeException(ae.getMessage());
            }
        }
        // final frame computation as the real writer will do it
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES){
            @Override protected String getCommonSuperClass(String a,String b){ return "java/lang/Object"; }
        };
        ClassNode tmp = new ClassNode();
        tmp.visit(cn.version, Opcodes.ACC_PUBLIC, cn.name, null, cn.superName, cn.interfaces.toArray(new String[0]));
        mn.accept(tmp);
        tmp.accept(cw);
    }
    int[] maxs(MethodNode mn){
        ClassWriter mw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassNode t = new ClassNode();
        t.visit(cn.version, Opcodes.ACC_PUBLIC, cn.name, null, cn.superName, cn.interfaces.toArray(new String[0]));
        mn.accept(t);
        t.accept(mw);
        ClassNode back = new ClassNode();
        new ClassReader(mw.toByteArray()).accept(back, ClassReader.SKIP_DEBUG);
        return new int[]{ back.methods.get(0).maxStack, back.methods.get(0).maxLocals };
    }
    void dumpWindow(MethodNode mn, int idx){
        var tr = new org.objectweb.asm.util.Textifier();
        var mv = new org.objectweb.asm.util.TraceMethodVisitor(tr);
        AbstractInsnNode[] arr = mn.instructions.toArray();
        int lo=Math.max(0,idx-12), hi=Math.min(arr.length, idx+6);
        for(int k=lo;k<hi;k++){ arr[k].accept(mv); System.out.print((k==idx?">> ":"   ")+k+": "+tr.getText().get(tr.getText().size()-1)); }
    }
    static String shortErr(Throwable t){
        String s = t.toString();
        return s.length()>80? s.substring(0,80): s;
    }

    // ---- recognition helpers on the FlowFrame API ----
    static boolean ffVirtual(AbstractInsnNode in, String name, String desc){
        return in instanceof MethodInsnNode m && m.getOpcode()==Opcodes.INVOKEVIRTUAL
            && m.owner.equals(FF) && m.name.equals(name) && m.desc.equals(desc);
    }
    static boolean ffStatic(AbstractInsnNode in, String name){
        return in instanceof MethodInsnNode m && m.getOpcode()==Opcodes.INVOKESTATIC
            && m.owner.equals(FF) && m.name.equals(name);
    }
    static boolean isPush(AbstractInsnNode in){ return ffVirtual(in,"push","(Ljava/lang/Object;I)V"); }
    static boolean isPop(AbstractInsnNode in){ return ffVirtual(in,"pop","()Ljava/lang/Object;"); }
    static boolean isUnbox(AbstractInsnNode in){
        return in instanceof MethodInsnNode m && m.getOpcode()==Opcodes.INVOKESTATIC && m.owner.equals(FF)
            && (m.name.equals("i")||m.name.equals("j")||m.name.equals("f")||m.name.equals("d"))
            && m.desc.startsWith("(Ljava/lang/Object;)");
    }
    static boolean isBox(AbstractInsnNode in){
        if(!(in instanceof MethodInsnNode m) || m.getOpcode()!=Opcodes.INVOKESTATIC || !m.name.equals("valueOf")) return false;
        switch(m.owner){
            case "java/lang/Integer": case "java/lang/Long": case "java/lang/Float":
            case "java/lang/Double": case "java/lang/Boolean": case "java/lang/Character":
            case "java/lang/Byte": case "java/lang/Short": return true;
            default: return false;
        }
    }
    static boolean ffGetField(AbstractInsnNode in, String name){
        return in instanceof FieldInsnNode f && f.getOpcode()==Opcodes.GETFIELD && f.owner.equals(FF) && f.name.equals(name);
    }
    static boolean ffPutField(AbstractInsnNode in, String name){
        return in instanceof FieldInsnNode f && f.getOpcode()==Opcodes.PUTFIELD && f.owner.equals(FF) && f.name.equals(name);
    }

    /** @return number of groups lifted */
    int lift(){
        // A chunk method is named __flow$G$C. Group G's driver is the real method that dispatches to it.
        Map<Integer,List<MethodNode>> chunks = new TreeMap<>();
        for(MethodNode mn : cn.methods){
            Integer g = chunkGroup(mn.name);
            if(g!=null) chunks.computeIfAbsent(g,k->new ArrayList<>()).add(mn);
        }
        if(chunks.isEmpty()) return 0;

        // map group -> driver method (the one whose body invokes __flow$G$*)
        Map<Integer,MethodNode> drivers = new TreeMap<>();
        for(MethodNode mn : cn.methods){
            if(chunkGroup(mn.name)!=null) continue;
            Integer g = driverGroup(mn);
            if(g!=null){
                if(drivers.put(g,mn)!=null) throw new Bail("two drivers for group "+g);
            }
        }
        int done=0;
        List<MethodNode> remove = new ArrayList<>();
        for(var e : chunks.entrySet()){
            int g = e.getKey();
            MethodNode driver = drivers.get(g);
            if(driver==null) throw new Bail("no driver for group "+g);
            // snapshot for rollback: methods whose reconstruction fails frame/stack
            // verification (e.g. exception-based control flow, not yet handled) are
            // left in interpreter form so the rest of the class still lifts cleanly.
            InsnList savedInsns = driver.instructions;
            List<TryCatchBlockNode> savedTcb = driver.tryCatchBlocks;
            int savedMaxS = driver.maxStack, savedMaxL = driver.maxLocals;
            List<LocalVariableNode> savedLv = driver.localVariables;
            try {
                new GroupLifter(cn, driver, e.getValue()).run();
                if(System.getenv("LIFT_NOVERIFY")==null) verify(driver);
                remove.addAll(e.getValue());
                done++;
            } catch (Throwable t) {
                if(System.getenv("LIFT_DEBUG")!=null){ System.out.println("--- group "+g+" "+driver.name+" ---"); t.printStackTrace(System.out); }
                driver.instructions = savedInsns;
                driver.tryCatchBlocks = savedTcb;
                driver.maxStack = savedMaxS; driver.maxLocals = savedMaxL;
                driver.localVariables = savedLv;
                skippedGroups.add(g + ":" + driver.name + " (" + shortErr(t) + ")");
                if(System.getenv("LIFT_WHERE")!=null) System.out.println("WHERE "+cn.name+" "+driver.name+driver.desc+" :: "+shortErr(t));
            }
        }
        cn.methods.removeAll(remove);
        return done;
    }

    static Integer chunkGroup(String name){
        if(!name.startsWith("__flow$")) return null;
        int a = name.indexOf('$'), b = name.indexOf('$', a+1);
        if(b<0) return null;
        try { return Integer.parseInt(name.substring(a+1,b)); } catch(NumberFormatException x){ return null; }
    }
    static Integer driverGroup(MethodNode mn){
        if(mn.instructions==null) return null;
        for(AbstractInsnNode in : mn.instructions){
            if(in instanceof MethodInsnNode m && m.name.startsWith("__flow$")){
                Integer g = chunkGroup(m.name);
                if(g!=null) return g;
            }
        }
        return null;
    }
}
