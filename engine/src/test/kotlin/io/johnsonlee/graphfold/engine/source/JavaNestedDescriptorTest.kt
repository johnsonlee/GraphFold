package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaNestedDescriptorTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @EnumSource(Resolution::class)
    fun `resolved nested signatures match actual javac descriptors for every dependency source`(resolution: Resolution) {
        val dependencyRoot = Files.createDirectories(temporary.resolve("dependency"))
        val dependency = dependencyRoot.resolve("library/Outer.java")
        Files.createDirectories(dependency.parent)
        Files.writeString(dependency, """
            package library;
            public class Outer {
                public enum Mode { FIRST, SECOND }
                public static class Nested { public enum Kind { VALUE } }
                public static class Dollar${'$'}Name {}
            }
        """.trimIndent())
        val classes = Files.createDirectories(temporary.resolve("dependency-classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), dependency.toString()))
        val sourceRoot = Files.createDirectories(temporary.resolve("source"))
        val source = sourceRoot.resolve("Example.java")
        Files.writeString(source, """
            import library.Outer;
            public class Example<T extends Outer.Nested> {
                public Example(Outer.Mode mode) {}
                public Outer.Mode echo(Outer.Mode mode) { return mode; }
                public Outer.Mode[] array(Outer.Mode... modes) { return modes; }
                public T generic(T value) { return value; }
                public Outer.Nested.Kind deep(Outer.Nested.Kind kind) { return kind; }
                public Outer.Dollar${'$'}Name literal(Outer.Dollar${'$'}Name value) { return value; }
                public java.util.Map.Entry<String, String> entry(java.util.Map.Entry<String, String> value) { return value; }
                public int[] primitives(int[] values) { return values; }
                public void noReturn() {}
            }
        """.trimIndent())
        val output = Files.createDirectories(temporary.resolve("output"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-classpath", classes.toString(), "-d", output.toString(), source.toString()))
        val dependencies = when (resolution) {
            Resolution.SOURCE -> emptyList()
            Resolution.DIRECTORY -> listOf(classes)
            Resolution.JAR -> listOf(archive(classes))
        }
        val roots = listOf(sourceRoot) + if (resolution == Resolution.SOURCE) listOf(dependencyRoot) else emptyList()
        val indexed = JavaSourceIndex(roots, dependencies).use { it.index(sourceRoot, "Example.java", Revision.HEAD) }
        if (resolution == Resolution.JAR) {
            // The pinned JarTypeSolver cannot resolve literal dollars in nested source identifiers.
            // Keep that limitation blocking and omit the method instead of inventing a descriptor.
            assertEquals(1, indexed.diagnostics.size, indexed.diagnostics.toString())
            assertTrue(indexed.diagnostics.single().contains("Unsolved symbol : Outer.Dollar${'$'}Name"))
        } else {
            assertTrue(indexed.diagnostics.isEmpty(), indexed.diagnostics.toString())
        }
        val methods = indexed.symbols.filter { it.kind == SymbolKind.METHOD }.map { it.identity }.toSet()
        val emitted = ClassNode().also { ClassReader(Files.readAllBytes(output.resolve("Example.class"))).accept(it, 0) }
        val represented = emitted.methods.filterNot { resolution == Resolution.JAR && it.name == "literal" }
        assertEquals(represented.map { "Example.${it.name}${it.desc}" }.toSet(), methods)
        assertTrue("Example.echo(Llibrary/Outer${'$'}Mode;)Llibrary/Outer${'$'}Mode;" in methods)
        assertTrue("Example.array([Llibrary/Outer${'$'}Mode;)[Llibrary/Outer${'$'}Mode;" in methods)
        assertTrue("Example.<init>(Llibrary/Outer${'$'}Mode;)V" in methods)
    }

    private fun archive(classes: Path): Path {
        val archive = temporary.resolve("dependency.jar")
        JarOutputStream(Files.newOutputStream(archive)).use { jar ->
            Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
                jar.putNextEntry(JarEntry(classes.relativize(file).toString()))
                Files.copy(file, jar)
                jar.closeEntry()
            } }
        }
        return archive
    }

    enum class Resolution { SOURCE, DIRECTORY, JAR }
}
