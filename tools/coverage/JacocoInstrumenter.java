package rules_clojure.coverage;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator;

/**
 * Offline-instruments a clojure_library jar for {@code bazel coverage}, matching the jar
 * shape JavaBuilder produces for {@code java_library}:
 *
 * <ul>
 *   <li>each {@code .class} is replaced by a Jacoco-instrumented copy
 *   <li>originals are kept as {@code .class.uninstrumented} (analyzed by JacocoCoverageRunner)
 *   <li>a {@code *-paths-for-coverage.txt} entry lists workspace source paths for LCOV {@code SF:}
 * </ul>
 *
 * <p><b>Timestamps.</b> Entry mtimes are preserved. {@code clojure.lang.RT.load} prefers the
 * newer of {@code foo__init.class} / {@code foo.clj}, and {@code rules-clojure.jar} stamps
 * classes newer than sources. Regenerating mtimes here would make Clojure recompile from
 * source at test time and report zero coverage with green tests. {@link
 * #assertClassesNewerThanSources} is a coarse jar-wide tripwire for that invariant.
 *
 * <p><b>Same-basename sources.</b> Clojure AOT sets {@code SourceFile} to the bare filename
 * ({@code core.clj}). Bazel's {@code CoverageAnalyzer} keys coverage by {@code package + "/" +
 * SourceFile} (e.g. {@code coverage/foo/core.clj}), so two namespaces both named {@code
 * core.clj} in different packages do not collide. The paths file must list workspace paths that
 * end with that package-relative path (normal {@code resource_strip_prefix} layout does).
 *
 * <p>Usage: {@code JacocoInstrumenter @argsfile} where the argsfile has one arg per line:
 * {@code in_jar}, {@code out_jar}, then workspace-relative source paths for the paths file.
 */
public final class JacocoInstrumenter {

    public static void main(String[] args) throws IOException {
        instrument(expandArgsFile(args), System.err);
    }

    /** Expand a single {@code @paramfile} argument into its lines, else return args unchanged. */
    static List<String> expandArgsFile(String[] args) throws IOException {
        if (args.length == 1 && args[0].startsWith("@")) {
            return Files.readAllLines(Paths.get(args[0].substring(1)), StandardCharsets.UTF_8);
        }
        List<String> out = new ArrayList<>();
        for (String a : args) {
            out.add(a);
        }
        return out;
    }

    /**
     * Instrument one jar. {@code args}: in_jar, out_jar, then source paths.
     *
     * <p>Package-private for tests.
     */
    static void instrument(List<String> args, PrintStream log) throws IOException {
        if (args.size() < 2) {
            throw new IOException("usage: JacocoInstrumenter in.jar out.jar [src ...]");
        }
        Path inJar = Paths.get(args.get(0));
        Path outJar = Paths.get(args.get(1));
        List<String> sourcePaths = args.subList(2, args.size());

        Instrumenter instrumenter = new Instrumenter(new OfflineInstrumentationAccessGenerator());

        // Coarse invariant: min .class mtime must be >= max .clj/.cljc mtime (jar.clj convention).
        long maxSourceMillis = Long.MIN_VALUE;
        long minClassMillis = Long.MAX_VALUE;
        boolean sawClass = false;
        boolean sawSource = false;

        try (ZipFile in = new ZipFile(inJar.toFile());
                ZipOutputStream out =
                        new ZipOutputStream(
                                new BufferedOutputStream(Files.newOutputStream(outJar)))) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                FileTime time = entry.getLastModifiedTime();
                long millis = time != null ? time.toMillis() : 0L;
                if (entry.isDirectory()) {
                    writeEntry(out, name, time, new byte[0]);
                    continue;
                }
                byte[] bytes = readAll(in.getInputStream(entry));
                if (name.endsWith(".class")) {
                    sawClass = true;
                    minClassMillis = Math.min(minClassMillis, millis);
                    byte[] instrumented;
                    try {
                        instrumented = instrumenter.instrument(bytes, name);
                    } catch (RuntimeException e) {
                        // Not instrumentable bytecode (e.g. module-info). Keep original;
                        // log so coverage gaps are visible rather than silent.
                        log.println(
                                "JacocoInstrumenter: skip "
                                        + name
                                        + " ("
                                        + e.getClass().getSimpleName()
                                        + ": "
                                        + e.getMessage()
                                        + ")");
                        writeEntry(out, name, time, bytes);
                        continue;
                    }
                    writeEntry(out, name, time, instrumented);
                    writeEntry(out, name + ".uninstrumented", time, bytes);
                    continue;
                } else if (name.endsWith(".clj") || name.endsWith(".cljc")) {
                    sawSource = true;
                    maxSourceMillis = Math.max(maxSourceMillis, millis);
                }
                writeEntry(out, name, time, bytes);
            }

            // Basename-only entry name; JacocoCoverageRunner matches endsWith("-paths-for-coverage.txt").
            String pathsEntry =
                    outJar.getFileName().toString().replaceAll("\\.jar$", "")
                            + "-paths-for-coverage.txt";
            StringBuilder sb = new StringBuilder();
            for (String src : sourcePaths) {
                sb.append(src).append('\n');
            }
            // Stable timestamp for reproducibility; jacoco never reads mtime on this entry.
            writeEntry(
                    out,
                    pathsEntry,
                    FileTime.fromMillis(0L),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        // Validate after the write attempt; non-zero exit makes Bazel discard the output.
        // Only enforce when the jar actually contains both class and source entries (AOT jars
        // often ship classes only — sources live in the workspace and are listed in the paths file).
        if (sawClass && sawSource) {
            assertClassesNewerThanSources(inJar, minClassMillis, maxSourceMillis);
        }
    }

    /**
     * Coarse jar-wide check that class entries are not older than source entries. Does not pair
     * per-namespace; {@code jar.clj} uses fixed global timestamps so min/max is enough to catch a
     * convention regression.
     */
    static void assertClassesNewerThanSources(
            Path inJar, long minClassMillis, long maxSourceMillis) throws IOException {
        if (minClassMillis != Long.MAX_VALUE
                && maxSourceMillis != Long.MIN_VALUE
                && minClassMillis < maxSourceMillis) {
            throw new IOException(
                    "coverage instrumentation aborted: "
                            + inJar
                            + " has .clj/.cljc source entries newer than .class entries ("
                            + maxSourceMillis
                            + " > "
                            + minClassMillis
                            + "). Clojure would load source over the instrumented class at test"
                            + " time and report zero coverage. Check the timestamp convention in"
                            + " src/rules_clojure/jar.clj.");
        }
    }

    private static void writeEntry(ZipOutputStream out, String name, FileTime time, byte[] bytes)
            throws IOException {
        ZipEntry e = new ZipEntry(name);
        if (time != null) {
            e.setLastModifiedTime(time);
        }
        out.putNextEntry(e);
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (is) {
            return is.readAllBytes();
        }
    }

    // Visible for tests that build synthetic jars.
    static Map<String, byte[]> readZip(Path jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) {
                    out.put(e.getName(), readAll(zf.getInputStream(e)));
                }
            }
        }
        return out;
    }

    private JacocoInstrumenter() {}
}
