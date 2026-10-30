package io.johnsonlee.graphfold.engine.origin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real compiler output exercises verifier stack slots and control-flow joins, not hand-built instruction fragments. */
class OriginObjectFlowTest {
    @TempDir lateinit var temporary: Path

    @Test fun `Java fixtures separate safe allocations from unknown or observable object flows`() {
        val classes = compileJava("java", fixture("java/FlowCases.java"))
        assertFixture(classes, "FlowCases")
    }

    @Test fun `Kotlin fixtures preserve object provenance through equivalent language constructs`() {
        val classes = Files.createDirectory(temporary.resolve("kotlin"))
        val libraries = listOf(Unit::class.java, org.jetbrains.annotations.NotNull::class.java).map {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }
        val log = ByteArrayOutputStream()
        val exit = PrintStream(log).use { stream ->
            K2JVMCompiler().exec(stream, "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                "-classpath", libraries.joinToString(File.pathSeparator), "-d", classes.toString(),
                fixture("kotlin/FlowCases.kt").toString())
        }
        assertEquals(ExitCode.OK, exit, log.toString())
        assertFixture(classes, "FlowCases\$Companion")
    }

    @Test fun `rendered anonymous allocation actually reveals changed runtime binary name`() {
        val body = "public static String run() { Object value = new Object() {}; Object alias = value; " +
            "return alias.toString().split(\"@\")[0]; }"
        val base = compileExample("base", body)
        val head = compileExample("head", "private void unused() { new Object() {}; } $body")
        val before = invoke(base)
        val after = invoke(head)
        assertEquals("Example$1", before)
        assertEquals("Example$2", after)
        assertNotEquals(before, after)
        val diagnostics = originNameObservability(nodes(head), mapOf(after to before))
        assertTrue(diagnostics.any { "Example.run(" in it }, diagnostics.toString())
    }

    @Test fun `null virtual rendering exposes renamed anonymous caller through escaping exception stack`() {
        val body = """
            public static Throwable run() {
                try {
                    new Runnable() { public void run() { Object value = null; value.toString(); } }.run();
                    return null;
                } catch (Throwable failure) { return failure; }
            }
        """.trimIndent()
        val base = compileExample("null-base", body)
        val head = compileExample("null-head", "private void unused() { new Object() {}; } $body")
        val before = exceptionOwner(base)
        val after = exceptionOwner(head)
        assertEquals("Example$1", before)
        assertEquals("Example$2", after)
        assertNotEquals(before, after)
        val diagnostics = originNameObservability(nodes(head), mapOf(after to before))
        assertTrue(diagnostics.any { "$after.run(" in it }, diagnostics.toString())
    }

    private fun assertFixture(classes: Path, owner: String) {
        val definitions = nodes(classes)
        val renamed = definitions.filter { node ->
            node.innerClasses.any { it.name == node.name && it.innerName == null }
        }.associate { it.name.replace('/', '.') to "normalized.${it.name.replace('/', '.')}" }
        assertTrue(renamed.isNotEmpty(), "Fixture must contain actual anonymous allocations")
        val diagnostics = originNameObservability(definitions, renamed)
        val methods = definitions.single { it.name == owner }.methods
        val safe = methods.filter { it.name.startsWith("safe") }
        val blocked = methods.filter { it.name.startsWith("blocked") }
        assertTrue(safe.size >= 8, "Expected explicit negative fixture coverage")
        assertTrue(blocked.size >= 14, "Expected explicit positive fixture coverage")
        safe.forEach { method ->
            assertTrue(diagnostics.none { "$owner.${method.name}(" in it }, "${method.name}: $diagnostics")
        }
        blocked.forEach { method ->
            assertTrue(diagnostics.any { "$owner.${method.name}(" in it }, "${method.name}: $diagnostics")
        }
    }

    private fun compileExample(name: String, body: String): Path {
        val root = Files.createDirectory(temporary.resolve(name))
        val source = root.resolve("Example.java")
        Files.writeString(source, "public class Example { $body }")
        return compileJava("$name-classes", source)
    }

    private fun compileJava(name: String, source: Path): Path {
        val classes = Files.createDirectory(temporary.resolve(name))
        val log = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, log, log,
            "-g", "--release", "17", "-d", classes.toString(), source.toString()), log.toString())
        return classes
    }

    private fun fixture(relative: String): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("fixtures/origin-object-flow/$relative") }.first(Files::isRegularFile)

    private fun nodes(classes: Path): List<ClassNode> = Files.walk(classes).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.map { file ->
            ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
        }.toList()
    }

    private fun invoke(classes: Path): String =
        URLClassLoader(arrayOf(classes.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.loadClass("Example").getMethod("run").invoke(null) as String
        }

    private fun exceptionOwner(classes: Path): String =
        URLClassLoader(arrayOf(classes.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val failure = loader.loadClass("Example").getMethod("run").invoke(null) as Throwable
            assertTrue(failure is NullPointerException)
            failure.stackTrace.first { it.className.startsWith("Example$") }.className
        }
}
