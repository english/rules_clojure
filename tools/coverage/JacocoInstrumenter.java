package rules_clojure.coverage;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator;

/**
 * Offline-instruments a clojure_library jar for `bazel coverage`, the same way
 * JavaBuilder post-processes java_library jars: each .class entry is replaced by a
 * jacoco-instrumented copy, the original bytes are kept alongside as
 * .class.uninstrumented (JacocoCoverageRunner analyzes those at test time), and a
 * *-paths-for-coverage.txt entry maps class SourceFile attributes back to
 * workspace source paths.
 *
 * Entry timestamps are preserved from the input jar. This matters: clojure.lang.RT.load
 * loads whichever of foo__init.class / foo.clj has the newer modification time, and
 * src/rules_clojure/jar.clj deliberately stamps .class entries 2s newer than .clj
 * entries. Fresh timestamps here would make Clojure silently recompile from source at
 * test time, bypassing the instrumented classes and reporting zero coverage.
 *
 * Usage: JacocoInstrumenter @argsfile, where argsfile contains one arg per line:
 * in_jar, out_jar, then workspace-relative source paths for the coverage paths file.
 */
public final class JacocoInstrumenter {

    // 2038-01-01T00:00:00Z, matching default-file-modified-time-millis in jar.clj
    private static final FileTime DEFAULT_TIME = FileTime.fromMillis(2145916800000L);

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].startsWith("@")) {
            List<String> lines = Files.readAllLines(Paths.get(args[0].substring(1)), StandardCharsets.UTF_8);
            args = lines.toArray(new String[0]);
        }
        if (args.length < 2) {
            System.err.println("usage: JacocoInstrumenter in.jar out.jar [src ...]");
            System.exit(1);
        }
        Path inJar = Paths.get(args[0]);
        Path outJar = Paths.get(args[1]);

        Instrumenter instrumenter = new Instrumenter(new OfflineInstrumentationAccessGenerator());

        try (ZipFile in = new ZipFile(inJar.toFile());
             ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outJar)))) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    writeEntry(out, entry.getName(), entry.getLastModifiedTime(), new byte[0]);
                    continue;
                }
                byte[] bytes = readAll(in.getInputStream(entry));
                if (entry.getName().endsWith(".class")) {
                    byte[] instrumented = null;
                    try {
                        instrumented = instrumenter.instrument(bytes, entry.getName());
                    } catch (Exception e) {
                        // not instrumentable (e.g. module-info); ship it unmodified
                    }
                    if (instrumented != null) {
                        writeEntry(out, entry.getName(), entry.getLastModifiedTime(), instrumented);
                        writeEntry(out, entry.getName() + ".uninstrumented", entry.getLastModifiedTime(), bytes);
                        continue;
                    }
                }
                writeEntry(out, entry.getName(), entry.getLastModifiedTime(), bytes);
            }

            // Entry name only needs to end with -paths-for-coverage.txt; deriving it from
            // the output jar's exec path keeps it unique when classpath jars are merged.
            String pathsEntry = args[1].replaceAll("\\.jar$", "") + "-paths-for-coverage.txt";
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                sb.append(args[i]).append('\n');
            }
            writeEntry(out, pathsEntry, DEFAULT_TIME, sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeEntry(ZipOutputStream out, String name, FileTime time, byte[] bytes) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setLastModifiedTime(time != null ? time : DEFAULT_TIME);
        out.putNextEntry(e);
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (is) {
            return is.readAllBytes();
        }
    }
}
