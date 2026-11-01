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
        assertEquals(3, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.any { "Sample.list" in it })
        assertTrue(diagnostics.any { "Sample.map" in it })
        assertTrue(diagnostics.any { "Sample.optional" in it })
        assertTrue(diagnostics.none { "Sample.valueList" in it })
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

    @Test
    fun `fixed arity immutable factories prove their complete scalar object graph`() {
        val collections = listOf("List", "Set", "Map").flatMap { collection -> (0..10).map { count ->
            val values = (1..(count * if (collection == "Map") 2 else 1)).joinToString { "\"value$it\"" }
            "public void ${collection.lowercase()}$count() { Encoder.encode(java.util.$collection.of($values)); }"
        } }
        val classes = compile("public class Sample { " + collections.joinToString("\n") + """
            public void emptyList() { Encoder.encode(java.util.Collections.emptyList()); }
            public void emptySet() { Encoder.encode(java.util.Collections.emptySet()); }
            public void emptyMap() { Encoder.encode(java.util.Collections.emptyMap()); }
            public void singletonList() { Encoder.encode(java.util.Collections.singletonList("one")); }
            public void singletonSet() { Encoder.encode(java.util.Collections.singleton(1)); }
            public void singletonMap() { Encoder.encode(java.util.Collections.singletonMap("one", 1)); }
            public void optionalEmpty() { Encoder.encode(java.util.Optional.empty()); }
            public void optionalValue() { Encoder.encode(java.util.Optional.of("one")); }
            public void optionalNullable() { Encoder.encode(java.util.Optional.ofNullable(null)); }
            public void nested() { Encoder.encode(java.util.List.of(java.util.Optional.of("one"))); }
            public void left(boolean condition) { Encoder.encode(condition ? java.util.List.of("one") : "two"); }
            public void right(boolean condition) { Encoder.encode(condition ? "two" : java.util.List.of("one")); }
            public void collections(boolean condition) {
                Encoder.encode(condition ? java.util.List.of("one") : java.util.Map.of("two", 2));
            }
            }
        """.trimIndent())
        assertTrue(ExternalObjectEscape(classes).methodDiagnostics().isEmpty())
    }

    @Test
    fun `varargs mutable collections unknown contents and unsafe joins remain opaque`() {
        val classes = compile("""
            public class Sample {
                public void varargs() { Encoder.encode(java.util.List.of(new String[]{"one", "two"})); }
                public void mutable() { Encoder.encode(new java.util.ArrayList<String>()); }
                public void unknown(java.util.List<String> value) { Encoder.encode(value); }
                public void copy(java.util.List<String> value) { Encoder.encode(java.util.List.copyOf(value)); }
                public void payload() { Encoder.encode(java.util.Collections.singleton(this)); }
                public void nullable(Object value) { Encoder.encode(java.util.Optional.ofNullable(value)); }
                public void left(boolean condition) { Encoder.encode(condition ? java.util.List.of("one") : this); }
                public void right(boolean condition) { Encoder.encode(condition ? this : java.util.List.of("one")); }
                public void unknownJoin(boolean condition, Object value) {
                    Encoder.encode(condition ? java.util.List.of("one") : value);
                }
            }
        """.trimIndent())
        val diagnostics = ExternalObjectEscape(classes).methodDiagnostics()
        assertEquals(9, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.values.flatten().all { it.callee == "Encoder.encode(Ljava/lang/Object;)V" })
    }

    @Test
    fun `logging facade scalar arguments are safe while application unknown and varargs payloads block`() {
        val logger = directory.resolve("Logger.java")
        Files.writeString(logger, """
            package org.slf4j;
            public interface Logger {
                void trace(String message);
                void debug(String message, Object value);
                void info(String message, Object left, Object right);
                void warn(String message);
                void warn(String message, Object... values);
                void error(String message, Object value);
                void custom(String message);
                boolean info(String message, int value);
            }
        """.trimIndent())
        val classes = compile("""
            public abstract class Sample implements org.slf4j.Logger {
                public void scalar(org.slf4j.Logger log) {
                    log.trace("message"); log.debug("number {}", 1); log.info("{} {}", "one", null);
                    log.error("value {}", "two"); log.warn("message");
                }
                public void payload(org.slf4j.Logger log) { log.debug("{}", this); }
                public void unknown(org.slf4j.Logger log, Object value) { log.error("{}", value); }
                public void varargs(org.slf4j.Logger log) { log.warn("{}", new Object[]{"one"}); }
                public void collection(org.slf4j.Logger log) { log.debug("{}", java.util.List.of("one")); }
                public void custom(org.slf4j.Logger log) { log.custom("message"); }
                public void signature(org.slf4j.Logger log) { log.info("message", 1); }
                public void self() { ((org.slf4j.Logger)this).trace("message"); }
            }
        """.trimIndent(), listOf(logger))
        val diagnostics = ExternalObjectEscape(classes).methodDiagnostics()
        assertEquals(7, diagnostics.size, diagnostics.toString())
        assertTrue(diagnostics.keys.none { it.startsWith("Sample.scalar") })
    }

    @Test
    fun `final DTO observations include all scalar field and behavior owners without becoming global`() {
        val classes = compile("""
            public class Sample {
                public void expose() { Encoder.encode(new Payload()); }
                public static final class Payload {
                    private int number;
                    private String text;
                    private Leaf child;
                    private static Leaf shared;
                    private Payload next;
                    public int size() { return text.length(); }
                    public int childValue() { return child.value(); }
                    public Leaf create() { return new Leaf(); }
                    public String literal() { return "safe literal"; }
                }
                public static final class Leaf {
                    private boolean flag;
                    public int value() { return flag ? 1 : 0; }
                }
            }
        """.trimIndent())
        val gap = ExternalObjectEscape(classes).methodDiagnostics().getValue("Sample.expose()V").single()
        assertTrue(!gap.globalObservation)
        assertEquals(setOf("Sample${'$'}Payload", "Sample${'$'}Leaf"), gap.observedOwners)
    }

    @Test
    fun `erased container inherited native and opaque behavior observations keep global uncertainty`() {
        val classes = compile("""
            public class Sample {
                public void erased(Object value) { Encoder.encode(value); }
                public void array() { Encoder.encode(new Payload[]{new Payload()}); }
                public void container() { Encoder.encode(java.util.List.of(new Payload())); }
                public void nonfinal() { Encoder.encode(new Extensible()); }
                public void inherited() { Encoder.encode(new Inherited()); }
                public void opaque() { Encoder.encode(new Opaque()); }
                public void callback() { Encoder.encode(new Callback()); }
                public void nativeMethod() { Encoder.encode(new Native()); }
                public void unknownMethod() { Encoder.encode(new UnknownMethod()); }
                public void token() { Encoder.encode(new Token()); }
                public void dynamic() { Encoder.encode(new Dynamic()); }
                public void arrayField() { Encoder.encode(new ArrayField()); }
                public void unknownParameter() { Encoder.encode(new UnknownParameter()); }
                public void opaqueScalarCall() { Encoder.encode(new OpaqueScalarCall()); }
                public void foreignStaticField() { Encoder.encode(new ForeignStaticField()); }
                public void foreignAllocation() { Encoder.encode(new ForeignAllocation()); }
                public static final class Payload { private String text; }
                public static class Extensible { private int number; }
                public static final class Inherited extends Extensible {}
                public static final class Opaque { private Object hidden; }
                public static final class Callback { public void run() { Encoder.encode(this); } }
                public static final class Native { public native int value(); }
                public static final class UnknownMethod { public Object value() { return null; } }
                public static final class Token { public void run() { Encoder.encode(Payload.class); } }
                public static final class Dynamic { public void run() { Runnable work = () -> {}; work.run(); } }
                public static final class ArrayField { private String[] values; }
                public static final class UnknownParameter { public void accept(Object value) {} }
                public static final class OpaqueScalarCall { public String text() { return String.valueOf(this); } }
                public static final class ForeignStaticField { public void read() { Object value = System.out; } }
                public static final class ForeignAllocation { public void allocate() { new StringBuilder(); } }
            }
        """.trimIndent())
        val gaps = ExternalObjectEscape(classes).methodDiagnostics().filterKeys { it.startsWith("Sample.") }.values.flatten()
        assertEquals(16, gaps.size, gaps.toString())
        assertTrue(gaps.all { it.globalObservation && it.observedOwners.isEmpty() })
    }

    @Test
    fun `aliases and checked final types preserve bounds while erased joins and collection casts stay conservative`() {
        val classes = compile("""
            public class Sample {
                public void alias() { Object first = new Payload(); Object second = first; Encoder.encode(second); }
                public void checked(Object value) { Encoder.encode((Payload) value); }
                public void immutableAlias() {
                    Object first = java.util.List.of(java.util.Optional.of("one")); Object second = first;
                    Encoder.encode(second);
                }
                public void collectionCast() {
                    Object value = java.util.List.of("one"); Encoder.encode((java.util.List<?>) value);
                }
                public void scalarCast(Object value) { Encoder.encode((String) value); }
                public void nullable(boolean choose) { Encoder.encode(choose ? new Payload() : null); }
                public void unknownJoin(boolean choose, Object value) { Encoder.encode(choose ? new Payload() : value); }
                public static final class Payload { private String value; }
            }
        """.trimIndent())
        val diagnostics = ExternalObjectEscape(classes).methodDiagnostics()
        assertEquals(5, diagnostics.size, diagnostics.toString())
        listOf("Sample.alias()V", "Sample.checked(Ljava/lang/Object;)V").forEach { method ->
            val gap = diagnostics.getValue(method).single()
            assertTrue(!gap.globalObservation)
            assertEquals(setOf("Sample${'$'}Payload"), gap.observedOwners)
        }
        listOf("Sample.collectionCast()V", "Sample.nullable(Z)V", "Sample.unknownJoin(ZLjava/lang/Object;)V").forEach { method ->
            assertTrue(diagnostics.getValue(method).single().globalObservation)
        }
    }

    private fun compile(body: String, additionalSources: List<Path> = emptyList()): Map<String, ClassNode> {
        val source = directory.resolve("Sample.java")
        val dependency = directory.resolve("Encoder.java")
        Files.writeString(source, body)
        Files.writeString(dependency, "public class Encoder { public static void encode(Object x) {} " +
            "public static void number(int x) {} public void inspect() {} " +
            "public static void wide(long a, double b, Object value) {} } " +
            "interface Foreign { void dispatch(Object value); }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            *(listOf("-d", directory.toString(), source.toString(), dependency.toString()) +
                additionalSources.map(Path::toString)).toTypedArray()))
        return Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().matches(Regex("Sample(?:\\$.*)?\\.class")) }.toList().associate { path ->
                val node = ClassNode()
                ClassReader(Files.readAllBytes(path)).accept(node, 0)
                node.name to node
            }
        }
    }
}
