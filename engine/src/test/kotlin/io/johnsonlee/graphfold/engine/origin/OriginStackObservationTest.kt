package io.johnsonlee.graphfold.engine.origin

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OriginStackObservationTest {
    @TempDir lateinit var temporary: Path

    @Test fun `actual stack trace observes anonymous renumbering even when its source body is unchanged`() {
        val body = """
            public static String run() {
                return new java.util.function.Supplier<String>() {
                    public String get() { return new Throwable().getStackTrace()[0].getClassName(); }
                }.get();
            }
        """.trimIndent()
        val base = compile("base", body)
        val head = compile("head", "private void unused() { new Object() {}; }\n$body")
        val oldName = runtimeName(base)
        val newName = runtimeName(head)
        assertEquals("Example$1", oldName)
        assertEquals("Example$2", newName)
        assertNotEquals(oldName, newName)
        val normalized = normalizeOriginClasses(head, temporary.resolve("normalized"), mapOf(newName to oldName))
        assertTrue(normalized.methodDiagnostics.values.flatten().any { "Runtime binary name" in it.message },
            normalized.methodDiagnostics.toString())
        val stable = normalizeOriginClasses(base, temporary.resolve("stable"), mapOf(oldName to oldName))
        assertTrue(stable.diagnostics.isEmpty(), stable.diagnostics.toString())
    }

    @Test fun `stack capture accessors and method references block while unrelated calls remain safe`() {
        val classes = compile("matrix", """
            Object token = new Object() {};
            static Throwable captureThrowable() { return new Throwable(); }
            static StackTraceElement existingFrame() { return new StackTraceElement("Named", "run", "Named.java", 1); }
            static StackTraceElement[] captureThread() { return Thread.currentThread().getStackTrace(); }
            static Object captureAllThreads() { return Thread.getAllStackTraces(); }
            static void dumpThread() { Thread.dumpStack(); }
            static Throwable refresh(RuntimeException e) { return e.fillInStackTrace(); }
            static StackTraceElement[] inheritedCapture(Exception e) { return e.getStackTrace(); }
            static String elementName(StackTraceElement e) { return e.getClassName(); }
            static String elementRender(StackTraceElement e) { return e.toString(); }
            static Object walk() { return StackWalker.getInstance().walk(s -> s.findFirst().orElse(null)); }
            static java.util.function.Function<StackTraceElement, String> elementReference() {
                return StackTraceElement::getClassName;
            }
            static java.util.function.Function<StackWalker.StackFrame, String> frameReference() {
                return StackWalker.StackFrame::getClassName;
            }
            static java.util.function.Supplier<StackWalker> walkerReference() { return StackWalker::getInstance; }
            static java.util.function.Function<Object, String> objectReference() { return Object::toString; }
            static java.util.function.Supplier<String> boundReference(Object value) { return value::toString; }
            static java.util.function.Function<String, String> scalarReference() { return String::toString; }
            static java.util.function.Supplier<RuntimeException> exceptionReference() { return RuntimeException::new; }
            static Object safeConstructor() { return new Object(); }
            static String safeThread() { return Thread.currentThread().getName(); }
            static int getStackTrace() { return 0; }
            static int fillInStackTrace() { return 1; }
            static int safeNamesake() { return getStackTrace() + fillInStackTrace(); }
            static java.util.function.Function<String, Integer> safeReference() { return String::length; }
        """.trimIndent())
        val diagnostics = originNameMessages(nodes(classes), mapOf("Example$1" to "Example\$stable"))
        val positive = listOf("captureThrowable", "captureThread", "captureAllThreads", "dumpThread", "refresh", "inheritedCapture",
            "elementName", "elementRender", "existingFrame", "walk", "elementReference", "frameReference", "walkerReference",
            "objectReference", "boundReference", "exceptionReference")
        positive.forEach { method -> assertTrue(diagnostics.any { "Example.$method(" in it }, "$method: $diagnostics") }
        val observations = originNameObservability(nodes(classes), mapOf("Example$1" to "Example\$stable")).methods
        listOf("captureThrowable", "dumpThread", "refresh").forEach { name ->
            val gaps = observations.entries.single { it.key.startsWith("Example.$name(") }.value
            assertTrue(gaps.all { it.requiresObservedFrame }, name)
        }
        listOf("captureThread", "captureAllThreads", "inheritedCapture", "elementName", "existingFrame", "exceptionReference")
            .forEach { name ->
            val gaps = observations.entries.single { it.key.startsWith("Example.$name(") }.value
            assertTrue(gaps.none { it.requiresObservedFrame }, name)
        }
        listOf("safeThread", "safeNamesake", "safeReference", "scalarReference", "safeConstructor").forEach { method ->
            assertTrue(diagnostics.none { "Example.$method(" in it }, "$method: $diagnostics")
        }
    }

    @Test fun `returned exception objects preserve observable stack names without a local stack reader`() {
        listOf("new RuntimeException()", "new Capture()").forEachIndexed { index, allocation ->
            val body = """
                static class Capture extends RuntimeException {}
                public static Throwable run() {
                    return new java.util.function.Supplier<Throwable>() {
                        public Throwable get() { return $allocation; }
                    }.get();
                }
            """.trimIndent()
            val base = compile("exception-base-$index", body)
            val head = compile("exception-head-$index", "private void unused() { new Object() {}; }$body")
            val before = exceptionOrigin(base)
            val after = exceptionOrigin(head)
            assertEquals("Example$1", before)
            assertEquals("Example$2", after)
            val result = normalizeOriginClasses(head, temporary.resolve("exception-normalized-$index"), mapOf(after to before))
            assertTrue(result.methodDiagnostics.values.flatten().any { "Runtime binary name" in it.message }, result.diagnostics.toString())
        }
    }

    @Test fun `unavailable constructor ancestry cannot establish absence of stack capture`() {
        val classes = compile("unknown", """
            static class Parent {}
            static class Mystery extends Parent {}
            Object token = new Object() {};
            static Object constructUnknown() { return new Mystery(); }
        """.trimIndent())
        Files.delete(classes.resolve("Example\$Parent.class"))
        val diagnostics = originNameMessages(nodes(classes), mapOf("Example$1" to "Example\$stable"))
        assertTrue(diagnostics.any { "Example.constructUnknown(" in it }, diagnostics.toString())
    }

    private fun exceptionOrigin(classes: Path): String =
        URLClassLoader(arrayOf(classes.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val exception = loader.loadClass("Example").getMethod("run").invoke(null) as Throwable
            exception.stackTrace.first { it.methodName == "get" }.className
        }

    private fun compile(name: String, body: String): Path {
        val root = Files.createDirectories(temporary.resolve(name))
        val source = root.resolve("Example.java")
        Files.writeString(source, "public class Example { $body }")
        val output = Files.createDirectory(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", output.toString(), source.toString()))
        return output
    }

    private fun runtimeName(classes: Path): String =
        URLClassLoader(arrayOf(classes.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.loadClass("Example").getMethod("run").invoke(null) as String
        }

    private fun nodes(classes: Path): List<ClassNode> = Files.walk(classes).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.map { file ->
            ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
        }.toList()
    }
}
