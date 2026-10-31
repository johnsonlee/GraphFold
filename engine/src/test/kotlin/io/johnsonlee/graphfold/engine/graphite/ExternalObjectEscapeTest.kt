package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalObjectEscapeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `application receivers arrays casts and branch merges remain observable at third party boundaries`() {
        val classes = compile("""
            public class Sample {
                public void direct() { Encoder.encode(this); }
                public void array() { Encoder.encode(new Sample[]{this}); }
                public void cast() { Object value = this; Encoder.encode((Sample)value); }
                public void classToken() { Encoder.encode(Sample.class); }
                public void merged(boolean choice) { Object value = choice ? this : "text"; Encoder.encode(value); }
            }
        """.trimIndent())
        val diagnostics = ExternalObjectEscape(classes).diagnostics()
        assertEquals(5, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.all { "Encoder.encode" in it })
    }

    @Test
    fun `primitive strings and modeled platform calls are not blanket third party gaps`() {
        val classes = compile("""
            public class Sample {
                public void number() { Encoder.number(1); }
                public void text() { Encoder.encode("text"); }
                public void boxed() { Encoder.encode(Integer.valueOf(1)); }
                public void platform() { System.out.println(this); }
                public void internal() { consume(this); }
                private void consume(Sample value) {}
            }
        """.trimIndent())
        assertTrue(ExternalObjectEscape(classes).diagnostics().isEmpty())
    }

    @Test
    fun `JDK serialization still observes application state`() {
        val classes = compile("""
            public class Sample {
                public void encode(java.io.ObjectOutputStream output) throws Exception { output.writeObject(this); }
            }
        """.trimIndent())
        assertTrue(ExternalObjectEscape(classes).diagnostics().single().contains("ObjectOutputStream.writeObject"))
    }

    @Test
    fun `erased helper and unknown parameter provenance block at third party boundary`() {
        val classes = compile("""
            public class Sample {
                public void helper() { Encoder.encode(identity(this)); }
                public void parameter(Object value) { Encoder.encode(value); }
                public void nullValue() { Encoder.encode(null); }
                private Object identity(Object value) { return value; }
            }
        """.trimIndent())
        val diagnostics = ExternalObjectEscape(classes).diagnostics()
        assertEquals(2, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.any { "Sample.helper" in it })
        assertTrue(diagnostics.any { "Sample.parameter" in it })
    }

    @Test
    fun `collections and optional cannot hide application state at third party boundaries`() {
        val classes = compile("""
            public class Sample {
                public void list() { Encoder.encode(java.util.List.of(this)); }
                public void map() { Encoder.encode(java.util.Map.of("self", this)); }
                public void optional() { Encoder.encode(java.util.Optional.of(this)); }
                public void valueList() { Encoder.encode(java.util.List.of("only strings")); }
            }
        """.trimIndent())
        val diagnostics = ExternalObjectEscape(classes).diagnostics()
        assertEquals(4, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.any { "Sample.list" in it })
        assertTrue(diagnostics.any { "Sample.map" in it })
        assertTrue(diagnostics.any { "Sample.optional" in it })
        // Explicit conservative gap: proving element contents requires a heap/escape model.
        assertTrue(diagnostics.any { "Sample.valueList" in it })
    }

    @Test
    fun `foreign receiver primitive arrays and merged unknown references remain observable`() {
        val classes = compile("""
            public class Sample {
                public void receiver(Encoder encoder) { encoder.inspect(); }
                public void numbers() { Encoder.encode(new int[]{1, 2}); }
                public void matrix() { Encoder.encode(new Sample[1][1]); }
                public void merged(boolean choice, StringBuilder left, StringBuffer right) {
                    Object value = choice ? left : right;
                    Encoder.encode(value);
                }
                public void cast(Object value) { Encoder.encode((StringBuilder) value); }
            }
        """.trimIndent())
        val diagnostics = ExternalObjectEscape(classes).diagnostics()
        assertEquals(5, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.any { "Sample.receiver" in it })
        assertTrue(diagnostics.any { "Sample.merged" in it })
    }

    @Test
    fun `scalar branch merges are safe but platform class tokens remain opaque`() {
        val classes = compile("""
            public class Sample {
                public void merged(boolean choice) { Encoder.encode(choice ? "one" : "two"); }
                public void constant() { Encoder.encode(String.class); }
                public void primitiveToken() { Encoder.encode(int.class); }
            }
        """.trimIndent())
        val diagnostics = ExternalObjectEscape(classes).diagnostics()
        assertEquals(2, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.none { "Sample.merged" in it })
    }

    @Test
    fun `joining scalars with opaque references retains the unsafe alternative in either order`() {
        val classes = compile("""
            public class Sample {
                public void left(boolean choice, String scalar, StringBuilder opaque) {
                    Encoder.encode(choice ? scalar : opaque);
                }
                public void right(boolean choice, String scalar, StringBuilder opaque) {
                    Encoder.encode(choice ? opaque : scalar);
                }
            }
        """.trimIndent())
        val diagnostics = ExternalObjectEscape(classes).diagnostics()
        assertEquals(2, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.any { "Sample.left" in it })
        assertTrue(diagnostics.any { "Sample.right" in it })
    }

    @Test
    fun `unreachable external invocation has no object escape`() {
        val method = org.objectweb.asm.tree.MethodNode(
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC, "dead", "()V", null, null,
        )
        method.visitCode()
        method.visitInsn(org.objectweb.asm.Opcodes.RETURN)
        method.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
        method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "Encoder", "encode", "(Ljava/lang/Object;)V", false)
        method.visitInsn(org.objectweb.asm.Opcodes.RETURN)
        method.visitMaxs(1, 0)
        method.visitEnd()
        val owner = ClassNode().also { it.name = "Dead"; it.methods.add(method) }
        assertTrue(ExternalObjectEscape(mapOf(owner.name to owner)).diagnostics().isEmpty())
    }

    @Test
    fun `malformed external call stack becomes explicit analysis failure`() {
        val method = org.objectweb.asm.tree.MethodNode(
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC, "broken", "()V", null, null,
        )
        method.visitCode()
        method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "Encoder", "encode", "(Ljava/lang/Object;)V", false)
        method.visitInsn(org.objectweb.asm.Opcodes.RETURN)
        method.visitMaxs(1, 0)
        method.visitEnd()
        val owner = ClassNode().also { it.name = "Broken"; it.methods.add(method) }
        assertEquals(listOf("Unable to verify external object escape: Broken.broken()V"),
            ExternalObjectEscape(mapOf(owner.name to owner)).diagnostics())
    }

    @Test
    fun `wide primitive arguments do not shift object escape argument indexing`() {
        val classes = compile("""
            public class Sample {
                public void safe() { Encoder.wide(1L, 2.0, "value"); }
                public void unsafe() { Encoder.wide(1L, 2.0, this); }
            }
        """.trimIndent())
        val diagnostics = ExternalObjectEscape(classes).diagnostics()
        assertEquals(1, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.single().contains("Sample.unsafe"))
    }

    @Test
    fun `external interface receiver and callback argument require explicit models`() {
        val classes = compile("""
            public class Sample {
                public void receiver(Foreign receiver) { receiver.dispatch("value"); }
                public void callback() { Encoder.encode((Runnable) () -> System.out.println("work")); }
            }
        """.trimIndent())
        val diagnostics = ExternalObjectEscape(classes).diagnostics()
        assertEquals(2, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.any { "Foreign.dispatch" in it })
        assertTrue(diagnostics.any { "Sample.callback" in it })
    }

    @Test
    fun `heterogeneous proven scalar branches do not become opaque object graph escapes`() {
        val classes = compile("""
            public class Sample {
                public void mixed(boolean choice) { Encoder.encode(choice ? "text" : Integer.valueOf(1)); }
                public void nullable(boolean choice) { Encoder.encode(choice ? null : Integer.valueOf(1)); }
                public void nested(int choice) {
                    Object value = choice == 0 ? "text" : choice == 1 ? Integer.valueOf(1) : null;
                    Encoder.encode(value);
                }
            }
        """.trimIndent())
        assertTrue(ExternalObjectEscape(classes).diagnostics().isEmpty())
    }

    private fun compile(body: String): Map<String, ClassNode> {
        val source = directory.resolve("Sample.java")
        val dependency = directory.resolve("Encoder.java")
        Files.writeString(source, body)
        Files.writeString(dependency, "public class Encoder { public static void encode(Object x) {} " +
            "public static void number(int x) {} public void inspect() {} " +
            "public static void wide(long a, double b, Object value) {} } " +
            "interface Foreign { void dispatch(Object value); }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", directory.toString(), source.toString(), dependency.toString()))
        val node = ClassNode()
        ClassReader(Files.readAllBytes(directory.resolve("Sample.class"))).accept(node, 0)
        return mapOf(node.name to node)
    }
}
