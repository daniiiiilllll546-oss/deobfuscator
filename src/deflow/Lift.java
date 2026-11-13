package deflow;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * VM-flattening lifter.
 *
 * The protector rewrites each method into a stack-machine interpreter:
 *   driver:  Frame f = new Frame(L,S);
 *            while(!f.done) switch(f.pc/CHUNK){ __flow$G$C(f); }
 *            return f.result;
 *   chunk:   static void __flow$G$C(Frame f){ switch(f.pc){ case pc: ... f.pc=next; return; ... } }
 *
 * Each `case pc:` is ONE step of the original method. The VM operand stack
 * (f.push/f.pop, boxed as Object) is isomorphic to the real JVM operand stack;
 * f.locals[] is isomorphic to real locals. We symbolically re-execute the
 * interpreter, emit real bytecode, and let ClassWriter(COMPUTE_FRAMES) rebuild
 * frames. Unhandled shapes throw Bail so the method stays in interpreter form.
 *
 * Usage: Lift <in.jar> <out.jar> [internal/ClassName to dump]
 * Exit:  0 lifted, 3 no VM found (out = copy of in), 1 error.
 */
public final class Lift {
    /** Internal name of the VM frame class; auto-detected from the chunk methods' parameter type. */
    static String FF;

    static final class Bail extends RuntimeException { Bail(String m){ super(m); } }

    static final Pattern CHUNK = Pattern.compile("__flow\\$\\d+\\$\\d+");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: Lift <in.jar> <out.jar> [internal/ClassToDump]"); System.exit(1); }
        Path in = Path.of(args[0]), out = Path.of(args[1]);
        String only = args.length > 2 ? args[2] : null;

        Map<String,byte[]> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(in.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (e.isDirectory()) continue;
                try (var s = zip.getInputStream(e)) { entries.put(e.getName(), s.readAllBytes()); }
            }
        }

        FF = System.getenv("DEFLOW_VM");          // manual override, internal name
        if (FF == null) FF = detectVm(entries);
        if (FF == null) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("vm: none detected (no __flow$G$C chunk methods); jar copied unchanged");
            System.exit(3);
        }
        System.out.println("vm: " + FF);

        int lifted=0, bailed=0, skipped=0, groups=0, groupsSkipped=0;
        List<String> bails = new ArrayList<>();
        Map<String,Integer> skipHist = new HashMap<>();
        for (var en : new ArrayList<>(entries.entrySet())) {
            String name = en.getKey();
            if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
            if (only != null && !name.equals(only + ".class")) continue;
            ClassNode cn = new ClassNode();
            try { new ClassReader(en.getValue()).accept(cn, 0); } catch (Throwable t) { skipped++; continue; }
            if (cn.name.equals(FF) || cn.name.startsWith(FF + "$")) { skipped++; continue; }
            try {
                ClassLifter cl = new ClassLifter(cn);
                int g = cl.lift();
                groupsSkipped += cl.skippedGroups.size();
                if (only != null) for (String s : cl.skippedGroups) System.out.println("  group-skip " + s);
                for (String s : cl.skippedGroups) {
                    String key = s.replaceAll("^[0-9]+:[^ ]+ \\(","").replaceAll("@insn [0-9]+","@insn N")
                                  .replaceAll("instruction [0-9]+","instruction N").replaceAll("pc=?[0-9]+","pc=N");
                    skipHist.merge(key, 1, Integer::sum);
                }
                if (g == 0) { skipped++; continue; }
                if (only != null) dump(cn, Path.of(out + ".dump.txt"));
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES){
                    @Override protected String getCommonSuperClass(String a,String b){ return "java/lang/Object"; }
                };
                cn.accept(cw);
                groups += g;
                entries.put(name, cw.toByteArray());
                lifted++;
            } catch (Throwable t) {
                bailed++; bails.add(cn.name + ": " + (t instanceof Bail ? t.getMessage() : t.toString()));
            }
        }

        // Rewritten classes break any jar signature; drop it so the jar still loads.
        entries.keySet().removeIf(n -> n.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)"));
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            for (var e : entries.entrySet()) {
                ZipEntry z = new ZipEntry(e.getKey()); z.setTime(0);
                zip.putNextEntry(z); zip.write(e.getValue()); zip.closeEntry();
            }
        }
        System.out.println("lifted classes:   " + lifted);
        System.out.println("methods lifted:   " + groups);
        System.out.println("methods kept:     " + groupsSkipped + " (still interpreter form, still correct)");
        System.out.println("classes bailed:   " + bailed);
        System.out.println("classes no-vm:    " + skipped);
        if (!skipHist.isEmpty()) {
            System.out.println("--- why methods were kept (top) ---");
            skipHist.entrySet().stream().sorted((x,y)->y.getValue()-x.getValue()).limit(15)
                .forEach(e->System.out.println("  "+e.getValue()+"  "+e.getKey()));
        }
        int show = Math.min(bails.size(), 40);
        for (int i=0;i<show;i++) System.out.println("  BAIL " + bails.get(i));
        if (bails.size()>show) System.out.println("  ... +" + (bails.size()-show) + " more");
    }

    /** The VM frame class is the object parameter type shared by the __flow$G$C chunk methods. */
    static String detectVm(Map<String,byte[]> entries) {
        Map<String,Integer> votes = new HashMap<>();
        for (var en : entries.entrySet()) {
            if (!en.getKey().endsWith(".class")) continue;
            ClassNode cn = new ClassNode();
            try { new ClassReader(en.getValue()).accept(cn, ClassReader.SKIP_CODE); } catch (Throwable t) { continue; }
            for (MethodNode mn : cn.methods) {
                if (!CHUNK.matcher(mn.name).matches()) continue;
                for (Type t : Type.getArgumentTypes(mn.desc))
                    if (t.getSort() == Type.OBJECT) votes.merge(t.getInternalName(), 1, Integer::sum);
            }
        }
        return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    static void dump(ClassNode cn, Path to) throws Exception {
        var tr = new org.objectweb.asm.util.Textifier();
        var mv = new org.objectweb.asm.util.TraceMethodVisitor(tr);
        StringBuilder sb = new StringBuilder();
        for (MethodNode mn : cn.methods) {
            if (mn.name.startsWith("__flow$")) continue;
            if (mn.instructions==null || mn.instructions.size()==0) continue;
            sb.append("=== ").append(mn.name).append(mn.desc).append(" ===\n");
            tr.getText().clear();
            for (AbstractInsnNode in : mn.instructions) in.accept(mv);
            for (Object o : tr.getText()) sb.append(o);
            try {
                new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(cn.name, mn);
                sb.append("  [verify OK]\n");
            } catch (Throwable t) {
                sb.append("  [verify FAIL] ").append(t).append("\n");
            }
        }
        Files.writeString(to, sb.toString());
        System.out.println("dumped lifted bytecode -> " + to);
    }
}
