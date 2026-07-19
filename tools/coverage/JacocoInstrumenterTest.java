package rules_clojure.coverage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Unit tests for {@link JacocoInstrumenter}. Builds synthetic jars with known timestamps —
 * no nested Bazel / coverage runner required.
 *
 * <p>ASM is only used here to synthesize minimal class files for the tests; the instrumenter
 * itself does not depend on ASM.
 */
public class JacocoInstrumenterTest {

    // Match jar.clj's fixed timestamps: sources at T, classes at T+2000.
    private static final long SOURCE_TIME = 2_145_916_800_000L; // 2038-01-01T00:00:00Z
    private static final long CLASS_TIME = SOURCE_TIME + 2000L;

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void instrument_preservesTimestamps_writesUninstrumentedAndPaths() throws Exception {
        Path in = tmp.newFile("in.jar").toPath();
        Path out = tmp.newFile("out.jar").toPath();
        writeJar(
                in,
                entry("foo/core.clj", SOURCE_TIME, "(ns foo.core)".getBytes(StandardCharsets.UTF_8)),
                entry(
                        "foo/core.class",
                        CLASS_TIME,
                        classBytes("foo/core", "core.clj", /* body= */ true)));

        List<String> args =
                Arrays.asList(in.toString(), out.toString(), "workspace/foo/core.clj");
        JacocoInstrumenter.instrument(args, silent());

        Map<String, byte[]> zipped = JacocoInstrumenter.readZip(out);
        assertTrue(zipped.containsKey("foo/core.class"));
        assertTrue(zipped.containsKey("foo/core.class.uninstrumented"));
        assertTrue(zipped.containsKey("foo/core.clj"));
        // Paths entry is basename of out jar + suffix (not a full exec path with slashes).
        assertTrue(zipped.containsKey("out-paths-for-coverage.txt"));

        String paths =
                new String(zipped.get("out-paths-for-coverage.txt"), StandardCharsets.UTF_8);
        assertEquals("workspace/foo/core.clj\n", paths);

        // Instrumenting must change class bytes; uninstrumented keeps the original.
        assertFalse(
                Arrays.equals(
                        zipped.get("foo/core.class"), zipped.get("foo/core.class.uninstrumented")));
        assertTrue(
                Arrays.equals(
                        zipped.get("foo/core.class.uninstrumented"),
                        classBytes("foo/core", "core.clj", true)));

        // Class entry mtime preserved (zip stores DOS time; allow 2s resolution).
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(out.toFile())) {
            ZipEntry classEntry = zf.getEntry("foo/core.class");
            assertNotNull(classEntry);
            long mtime = classEntry.getLastModifiedTime().toMillis();
            assertTrue(
                    "class mtime should stay near CLASS_TIME, got " + mtime,
                    Math.abs(mtime - CLASS_TIME) < 3000);
        }
    }

    @Test
    public void instrument_abortsWhenSourcesNewerThanClasses() throws Exception {
        Path in = tmp.newFile("bad.jar").toPath();
        Path out = tmp.newFile("bad-out.jar").toPath();
        // Invert the jar.clj convention: sources newer than classes.
        writeJar(
                in,
                entry("foo/core.clj", CLASS_TIME + 10_000, "x".getBytes(StandardCharsets.UTF_8)),
                entry(
                        "foo/core.class",
                        CLASS_TIME,
                        classBytes("foo/core", "core.clj", true)));

        try {
            JacocoInstrumenter.instrument(
                    Arrays.asList(in.toString(), out.toString(), "foo/core.clj"), silent());
            fail("expected IOException for inverted timestamps");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("newer than .class"));
        }
    }

    @Test
    public void instrument_classesOnlyJar_skipsTimestampAssert() throws Exception {
        // Production AOT jars often contain only .class files (sources are workspace paths in the
        // paths file, not jar entries). Timestamp assert must not fire when no source entries exist.
        Path in = tmp.newFile("classes-only.jar").toPath();
        Path out = tmp.newFile("classes-only-out.jar").toPath();
        writeJar(
                in,
                entry(
                        "foo/core.class",
                        CLASS_TIME,
                        classBytes("foo/core", "core.clj", true)));

        JacocoInstrumenter.instrument(
                Arrays.asList(in.toString(), out.toString(), "src/foo/core.clj"), silent());

        Map<String, byte[]> zipped = JacocoInstrumenter.readZip(out);
        assertTrue(zipped.containsKey("foo/core.class.uninstrumented"));
        assertTrue(zipped.containsKey("classes-only-out-paths-for-coverage.txt"));
    }

    @Test
    public void expandArgsFile_readsParamFile() throws Exception {
        Path argsFile = tmp.newFile("args.txt").toPath();
        Files.write(argsFile, Arrays.asList("a.jar", "b.jar", "src/x.clj"));
        List<String> expanded =
                JacocoInstrumenter.expandArgsFile(new String[] {"@" + argsFile});
        assertEquals(Arrays.asList("a.jar", "b.jar", "src/x.clj"), expanded);
    }

    // ---- helpers ----

    private static PrintStream silent() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static final class Entry {
        final String name;
        final long mtime;
        final byte[] bytes;

        Entry(String name, long mtime, byte[] bytes) {
            this.name = name;
            this.mtime = mtime;
            this.bytes = bytes;
        }
    }

    private static Entry entry(String name, long mtime, byte[] bytes) {
        return new Entry(name, mtime, bytes);
    }

    private static void writeJar(Path jar, Entry... entries) throws IOException {
        try (ZipOutputStream zos =
                new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(jar)))) {
            for (Entry e : entries) {
                ZipEntry ze = new ZipEntry(e.name);
                ze.setLastModifiedTime(FileTime.fromMillis(e.mtime));
                zos.putNextEntry(ze);
                zos.write(e.bytes);
                zos.closeEntry();
            }
        }
    }

    /** Minimal public class with an empty void method so Jacoco has something to instrument. */
    private static byte[] classBytes(String internalName, String sourceFile, boolean withMethod) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitSource(sourceFile, null);
        MethodVisitor ctor =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        if (withMethod) {
            MethodVisitor mv =
                    cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "f", "()I", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
