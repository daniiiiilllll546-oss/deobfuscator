package deflow;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Load + link every class in a jar (JVM verification, no static init) against an
 * optional extra classpath. VerifyError/ClassFormatError are real bugs; a missing
 * dependency (NoClassDefFound) only means that class could not be checked.
 *
 * Usage: Verify <jar> [jar-or-dir-of-jars ...]
 */
public final class Verify {
    public static void main(String[] a) throws Exception {
        Path jar = Path.of(a[0]);
        String vm = System.getenv("DEFLOW_VM");
        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        for (int i=1;i<a.length;i++) for (Path p : jars(Path.of(a[i]))) urls.add(p.toUri().toURL());
        URLClassLoader ld = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());

        List<String> names = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) {
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class") || n.endsWith("package-info.class")) continue;
                String in = n.substring(0, n.length()-6);
                if (vm != null && (in.equals(vm) || in.startsWith(vm + "$"))) continue;
                names.add(in.replace('/','.'));
            }
        }
        int ok=0, gap=0; List<String> bad=new ArrayList<>(), other=new ArrayList<>();
        for (String n : names) {
            try { Class.forName(n, false, ld); ok++; }
            catch (VerifyError|ClassFormatError ve) { bad.add(n+": "+ve.getMessage()); }
            catch (NoClassDefFoundError|ClassNotFoundException miss) { gap++; }
            catch (Throwable t) { other.add(n+": "+t); }
        }
        System.out.println("verified OK:      " + ok + " / " + names.size());
        System.out.println("missing deps:     " + gap + " (not checkable without their libraries; pass --cp)");
        System.out.println("VERIFY ERRORS:    " + bad.size());
        for (String s : bad) System.out.println("  !! " + s);
        if (!other.isEmpty()) { System.out.println("other: " + other.size()); for (String s : other) System.out.println("  ? " + s); }
        System.exit(bad.isEmpty() ? 0 : 4);
    }
    static List<Path> jars(Path p) throws IOException {
        if (Files.isRegularFile(p)) return List.of(p);
        try (var s = Files.walk(p)) { return s.filter(x -> x.toString().endsWith(".jar")).sorted().toList(); }
    }
}
