package io.johnsonlee.graphfold.engine.bytecode.asm

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyObservationProofTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `exact getters static helpers and their class initializers have independent observation proofs`() {
        val classes = compile("""
            final class Library {
                private String value;
                static int count = initialize();
                private static int initialize() { return 1; }
                public String get() { return value; }
                public static String pure() { return helper(); }
                private static String helper() { count = count + 1; return "value"; }
            }
        """.trimIndent())
        DependencyObservationProof(listOf(classes), emptySet()).use { proof ->
            assertTrue(proof.proves(call("Library", "get", Opcodes.INVOKEVIRTUAL)))
            assertTrue(proof.proves(call("Library", "pure")))
            assertTrue(proof.proves(call("Library", "pure")), "Repeated calls reuse an independently closed proof")
            assertTrue(proof.proves(call("java/lang/String", "length", Opcodes.INVOKEVIRTUAL, "()I")))
        }
    }

    @Test
    fun `reflection native callbacks unknown dispatch and dependency initializers remain guarded`() {
        val classes = compile("""
            final class Library {
                public static String reflect() throws Exception {
                    return Class.forName("Example").getDeclaredMethods()[0].getName();
                }
                public static native String nativeCall();
                public static String callback() { return Application.value(); }
                public static String unknown(Unknown target) { return target.value(); }
                public static String recursive() { return recursive(); }
                public static String dynamic(int value) { return "value=" + value; }
            }
            class Unknown { public String value() { return "base"; } }
            final class Application { public static String value() { return "application"; } }
            final class Initialized {
                static { System.loadLibrary("unmodeled"); }
                public static String value() { return "text"; }
            }
        """.trimIndent())
        DependencyObservationProof(listOf(classes), setOf("Application")).use { proof ->
            for (name in listOf("reflect", "nativeCall", "callback", "recursive")) {
                assertFalse(proof.proves(call("Library", name)), name)
            }
            assertFalse(proof.proves(call("Library", "unknown", descriptor = "(LUnknown;)Ljava/lang/String;")))
            assertFalse(proof.proves(call("Library", "dynamic", descriptor = "(I)Ljava/lang/String;")))
            assertFalse(proof.proves(call("Unknown", "value", Opcodes.INVOKEVIRTUAL)))
            assertFalse(proof.proves(call("Initialized", "value")))
        }
    }

    @Test
    fun `missing ambiguous malformed and unreadable dependency bodies do not become proofs`() {
        val classes = compile("final class Library { public static String value() { return \"text\"; } }")
        val duplicate = Files.createDirectories(temporary.resolve("duplicate"))
        Files.copy(classes.resolve("Library.class"), duplicate.resolve("Library.class"))
        DependencyObservationProof(listOf(classes, duplicate), emptySet()).use {
            assertFalse(it.proves(call("Library", "value")))
        }
        DependencyObservationProof(listOf(classes), emptySet()).use {
            assertFalse(it.proves(call("Missing", "value")))
            assertFalse(it.proves(call("Library", "missing")))
            assertFalse(it.proves(call("Library", "value", descriptor = "()I")))
        }
        Files.write(duplicate.resolve("Broken.class"), byteArrayOf(1))
        DependencyObservationProof(listOf(duplicate), emptySet()).use {
            assertFalse(it.proves(call("Broken", "value")))
        }
        DependencyObservationProof(listOf(temporary.resolve("missing.jar")), emptySet()).use {
            assertFalse(it.proves(call("Missing", "value")))
        }
    }

    @Test
    fun `jar bodies are verified and depth exhaustion remains unknown`() {
        val depth = 34
        val methods = (0 until depth).joinToString("\n") {
            "public static String value$it() { return value${it + 1}(); }"
        }
        val classes = compile("final class Library { $methods public static String value$depth() { return \"text\"; } }")
        val jar = temporary.resolve("library.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(ZipEntry("Library.class"))
            Files.copy(classes.resolve("Library.class"), it)
            it.closeEntry()
        }
        DependencyObservationProof(listOf(jar), emptySet()).use {
            assertTrue(it.proves(call("Library", "value$depth")))
            assertFalse(it.proves(call("Library", "value0")))
            assertFalse(it.proves(call("Missing", "value")))
        }
    }

    @Test
    fun `failed initializer cycles never publish provisional proofs in either query order`() {
        for (access in listOf("B.y", "B.read()")) {
            val classes = compile("""
                final class A {
                    static int x = $access;
                    static { System.loadLibrary("unmodeled"); }
                    public static String value() { return "a"; }
                }
                final class B {
                    static int y = A.x;
                    public static String value() { return "b"; }
                    public static int read() { return y; }
                }
            """.trimIndent())
            rejectsBothOrders(classes)
        }
    }

    @Test
    fun `fully verified initializer cycles remain supported in either query order`() {
        val classes = compile("""
            final class A {
                static int x = B.y;
                public static String value() { return "a"; }
            }
            final class B {
                static int y = A.x;
                public static String value() { return "b"; }
            }
        """.trimIndent())
        for (order in listOf(listOf("A", "B"), listOf("B", "A"))) {
            DependencyObservationProof(listOf(classes), emptySet()).use { proof ->
                (order + order).forEach { assertTrue(proof.proves(call(it, "value")), it) }
            }
        }
    }

    @Test
    fun `non retention proof distinguishes no op payload consumers from reference stores and returns`() {
        val classes = compile("""
            final class Library {
                Object field; static Object shared; int number;
                public static void ignore(Object value) { helper(value); }
                private static void helper(Object value) { }
                public void instance(Object value) { field = value; }
                public static void global(Object value) { shared = value; }
                public static void array(Object[] values, Object value) { values[0] = value; }
                public static Object identity(Object value) { return value; }
                public static void thrown(RuntimeException value) { throw value; }
                public void primitive(int value) { number = value; }
            }
        """.trimIndent())
        DependencyObservationProof(listOf(classes), emptySet()).use { proof ->
            val ignored = call("Library", "ignore", descriptor = "(Ljava/lang/Object;)V")
            assertTrue(proof.proves(ignored))
            assertTrue(proof.provesNoRetention(ignored))
            assertTrue(proof.provesNoRetention(ignored))
            assertTrue(proof.proves(ignored))
            val retaining = listOf(
                call("Library", "instance", Opcodes.INVOKEVIRTUAL, "(Ljava/lang/Object;)V"),
                call("Library", "global", descriptor = "(Ljava/lang/Object;)V"),
                call("Library", "array", descriptor = "([Ljava/lang/Object;Ljava/lang/Object;)V"),
                call("Library", "identity", descriptor = "(Ljava/lang/Object;)Ljava/lang/Object;"),
                call("Library", "thrown", descriptor = "(Ljava/lang/RuntimeException;)V"),
            )
            retaining.forEach {
                assertTrue(proof.proves(it))
                assertFalse(proof.provesNoRetention(it))
            }
            assertTrue(proof.provesNoRetention(call("Library", "primitive", Opcodes.INVOKEVIRTUAL, "(I)V")))
        }
    }

    @Test
    fun `proof modes preserve independent committed work across failures and mode switches`() {
        val classes = compile("""
            final class Library {
                public static void ignore(Object value) { }
                public static Object identity(Object value) { return value; }
                public static native void opaque();
            }
        """.trimIndent())
        DependencyObservationProof(listOf(classes), emptySet()).use { proof ->
            val ignored = call("Library", "ignore", descriptor = "(Ljava/lang/Object;)V")
            val identity = call("Library", "identity", descriptor = "(Ljava/lang/Object;)Ljava/lang/Object;")
            assertTrue(proof.proves(ignored))
            assertTrue(proof.proves(identity))
            val observed = memo(proof, "observationProofs", "methods").toMap()
            assertTrue(proof.provesNoRetention(ignored))
            val retained = memo(proof, "retentionProofs", "methods").toMap()
            assertTrue(observed.isNotEmpty())
            assertTrue(retained.isNotEmpty())
            assertEquals(observed, memo(proof, "observationProofs", "methods"))
            assertFalse(proof.provesNoRetention(identity))
            assertFalse(proof.proves(call("Library", "opaque", descriptor = "()V")))
            assertEquals(observed, memo(proof, "observationProofs", "methods"))
            assertEquals(retained, memo(proof, "retentionProofs", "methods"))
            assertTrue(proof.proves(identity))
            assertTrue(proof.provesNoRetention(ignored))
        }
    }

    @Test
    fun `exceptional initializer root discards cycle assumptions but preserves earlier successful roots in both modes`() {
        val classes = compile("""
            final class Safe { public static void run() {} }
            final class A {
                static int value = B.read();
                static { Broken.run(); }
                public static void run() {}
            }
            final class B {
                static int value = A.value;
                public static int read() { return value; }
            }
            final class Broken { public static void run() {} }
        """.trimIndent())
        Files.write(classes.resolve("Broken.class"), byteArrayOf(1))
        DependencyObservationProof(listOf(classes), emptySet()).use { proof ->
            val queries = listOf(proof::proves, proof::provesNoRetention)
            queries.forEach { assertTrue(it(call("Safe", "run", descriptor = "()V"))) }
            val modes = listOf("observationProofs", "retentionProofs")
            val before = modes.associateWith { memo(proof, it, "initializers").toMap() }
            queries.forEach { query ->
                assertFalse(query(call("A", "run", descriptor = "()V")))
                assertFalse(query(call("B", "read", descriptor = "()I")))
            }
            modes.forEach { mode -> assertEquals(before.getValue(mode), memo(proof, mode, "initializers")) }
            assertTrue(memo(proof, "provisional", "methods").isEmpty())
            assertTrue(memo(proof, "provisional", "initializers").isEmpty())
        }
    }

    /** Inspect memo survival, not elapsed time; this is a cache contract assertion, not a performance benchmark. */
    private fun memo(proof: DependencyObservationProof, mode: String, member: String): Map<*, *> {
        val cache = requireNotNull(proof.javaClass.getDeclaredField(mode).also { it.isAccessible = true }.get(proof))
        return requireNotNull(cache.javaClass.getDeclaredField(member).also { it.isAccessible = true }.get(cache)) as Map<*, *>
    }

    private fun rejectsBothOrders(classes: Path) {
        for (order in listOf(listOf("A", "B"), listOf("B", "A"))) {
            DependencyObservationProof(listOf(classes), emptySet()).use { proof ->
                (order + order).forEach { assertFalse(proof.proves(call(it, "value")), it) }
                assertFalse(proof.proves(call("B", "read", descriptor = "()I")))
            }
        }
    }

    private fun compile(source: String): Path {
        val root = Files.createDirectories(temporary.resolve("sources"))
        val output = Files.createDirectories(temporary.resolve("classes"))
        val input = root.resolve("Library.java")
        Files.writeString(input, source)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "17", "-d", output.toString(), input.toString()))
        return output
    }

    private fun call(owner: String, name: String, opcode: Int = Opcodes.INVOKESTATIC,
        descriptor: String = "()Ljava/lang/String;"): MethodInsnNode = MethodInsnNode(opcode, owner, name, descriptor, false)
}
