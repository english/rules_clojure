package rules_clojure.coverage;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
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
 * src/rules_clojure/jar.clj deliberately stamps .class entries newer than .clj entries.
 * If a future change to jar.clj inverted that, Clojure would silently recompile from
 * source at test time, bypassing the instrumented classes and reporting zero coverage
 * with green tests; assertClassesNewerThanSources() turns that into a loud failure.
 *
 * Runs either as a Bazel persistent worker (--persistent_worker, JSON protocol) or as a
 * one-shot tool: JacocoInstrumenter @argsfile, where argsfile holds one arg per line:
 * in_jar, out_jar, then workspace-relative source paths for the coverage paths file.
 */
public final class JacocoInstrumenter {

    public static void main(String[] args) throws IOException {
        boolean persistent = false;
        for (String a : args) {
            if (a.equals("--persistent_worker")) {
                persistent = true;
            }
        }
        if (persistent) {
            runWorker();
        } else {
            instrument(expandArgsFile(args), System.err);
        }
    }

    /** Expand a single `@paramfile` argument into its lines, else return args unchanged. */
    private static List<String> expandArgsFile(String[] args) throws IOException {
        if (args.length == 1 && args[0].startsWith("@")) {
            return Files.readAllLines(Paths.get(args[0].substring(1)), StandardCharsets.UTF_8);
        }
        List<String> out = new ArrayList<>();
        for (String a : args) {
            out.add(a);
        }
        return out;
    }

    /** Instrument one jar. args: in_jar, out_jar, then source paths. */
    private static void instrument(List<String> args, PrintStream log) throws IOException {
        if (args.size() < 2) {
            throw new IOException("usage: JacocoInstrumenter in.jar out.jar [src ...]");
        }
        Path inJar = Paths.get(args.get(0));
        Path outJar = Paths.get(args.get(1));

        Instrumenter instrumenter = new Instrumenter(new OfflineInstrumentationAccessGenerator());

        long maxSourceMillis = Long.MIN_VALUE;
        long minClassMillis = Long.MAX_VALUE;

        try (ZipFile in = new ZipFile(inJar.toFile());
             ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outJar)))) {
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
                    byte[] instrumented = null;
                    try {
                        instrumented = instrumenter.instrument(bytes, name);
                    } catch (Exception e) {
                        // not instrumentable (e.g. module-info); ship it unmodified
                    }
                    if (instrumented != null) {
                        minClassMillis = Math.min(minClassMillis, millis);
                        writeEntry(out, name, time, instrumented);
                        writeEntry(out, name + ".uninstrumented", time, bytes);
                        continue;
                    }
                } else if (name.endsWith(".clj") || name.endsWith(".cljc")) {
                    maxSourceMillis = Math.max(maxSourceMillis, millis);
                }
                writeEntry(out, name, time, bytes);
            }

            // Entry name only needs to end with -paths-for-coverage.txt; deriving it from
            // the output jar's exec path keeps it unique when classpath jars are merged.
            String pathsEntry = args.get(1).replaceAll("\\.jar$", "") + "-paths-for-coverage.txt";
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < args.size(); i++) {
                sb.append(args.get(i)).append('\n');
            }
            // jacoco never reads this file; give it a stable timestamp for reproducibility.
            writeEntry(out, pathsEntry, FileTime.fromMillis(0L),
                       sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        assertClassesNewerThanSources(inJar, minClassMillis, maxSourceMillis);
    }

    /**
     * Clojure loads the newer of the .class / .clj for a namespace. If an instrumented
     * class were older than its source, the source would win at test time and coverage
     * would read zero with no error. jar.clj guarantees classes are stamped newer; if
     * that ever regresses, fail loudly here instead.
     */
    private static void assertClassesNewerThanSources(Path inJar, long minClassMillis, long maxSourceMillis)
            throws IOException {
        if (minClassMillis != Long.MAX_VALUE && maxSourceMillis != Long.MIN_VALUE
                && minClassMillis < maxSourceMillis) {
            throw new IOException(
                "coverage instrumentation aborted: " + inJar + " has .clj source entries newer than"
                + " .class entries (" + maxSourceMillis + " > " + minClassMillis + "). Clojure would"
                + " load source over the instrumented class at test time and report zero coverage."
                + " Check the timestamp convention in src/rules_clojure/jar.clj.");
        }
    }

    private static void writeEntry(ZipOutputStream out, String name, FileTime time, byte[] bytes) throws IOException {
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

    // ---- Bazel persistent worker (JSON protocol) ----

    private static void runWorker() throws IOException {
        Reader stdin = new InputStreamReader(System.in, StandardCharsets.UTF_8);
        PrintStream stdout = System.out;
        String obj;
        while ((obj = readJsonObject(stdin)) != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> req = (Map<String, Object>) Json.parse(obj);
            int requestId = req.containsKey("requestId") ? ((Number) req.get("requestId")).intValue() : 0;
            @SuppressWarnings("unchecked")
            List<Object> rawArgs = (List<Object>) req.getOrDefault("arguments", new ArrayList<>());
            List<String> reqArgs = new ArrayList<>();
            for (Object a : rawArgs) {
                reqArgs.add((String) a);
            }
            StringWriter captured = new StringWriter();
            int exitCode = 0;
            try {
                instrument(expandArgsFile(reqArgs.toArray(new String[0])), System.err);
            } catch (Throwable t) {
                exitCode = 1;
                captured.write(t.toString());
                captured.write('\n');
            }
            String resp = "{\"exitCode\":" + exitCode
                + ",\"output\":" + Json.quote(captured.toString())
                + ",\"requestId\":" + requestId + "}";
            synchronized (stdout) {
                stdout.print(resp);
                stdout.flush();
            }
        }
    }

    /**
     * Read one balanced top-level JSON object from the stream (skipping leading
     * whitespace), respecting strings and escapes. Returns the raw object text, or null
     * at end of stream.
     */
    private static String readJsonObject(Reader in) throws IOException {
        int c;
        // skip whitespace
        do {
            c = in.read();
        } while (c == ' ' || c == '\n' || c == '\r' || c == '\t');
        if (c == -1) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        while (c != -1) {
            char ch = (char) c;
            sb.append(ch);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (ch == '\\') {
                    escape = true;
                } else if (ch == '"') {
                    inString = false;
                }
            } else {
                if (ch == '"') {
                    inString = true;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        return sb.toString();
                    }
                }
            }
            c = in.read();
        }
        // truncated object at EOF
        return null;
    }

    /** Minimal JSON parser sufficient for Bazel WorkRequest objects. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json p = new Json(s);
            p.ws();
            Object v = p.value();
            p.ws();
            return v;
        }

        private Object value() {
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    i += 4;
                    return Boolean.TRUE;
                case 'f':
                    i += 5;
                    return Boolean.FALSE;
                case 'n':
                    i += 4;
                    return null;
                default:
                    return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            i++; // {
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                i++; // :
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') {
                    break;
                }
                // c == ','
            }
            return m;
        }

        private List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++; // [
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return a;
            }
            while (true) {
                ws();
                a.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') {
                    break;
                }
                // c == ','
            }
            return a;
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Number number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String n = s.substring(start, i);
            if (n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0) {
                return Double.parseDouble(n);
            }
            return Long.parseLong(n);
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else {
                    break;
                }
            }
        }

        static String quote(String v) {
            StringBuilder sb = new StringBuilder("\"");
            for (int j = 0; j < v.length(); j++) {
                char c = v.charAt(j);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            return sb.append('"').toString();
        }
    }
}
