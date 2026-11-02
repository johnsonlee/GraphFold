package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceRange
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaSourceIndexTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `nested types constructors arrays overloads and fields use JVM identities`() {
        val indexed = index("""
            package example;
            public class Example {
                int first, second;
                public Example(String[] names) {}
                public int run(int value) { return value; }
                public String run(String value) { return value; }
                static class Nested { Nested(int n) {} }
                class Inner { Inner(long n) {} }
                enum Choice { A; Choice() {} }
            }
        """.trimIndent())
        assertTrue(indexed.diagnostics.isEmpty(), indexed.diagnostics.toString())
        val identities = indexed.symbols.map { it.identity }.toSet()
        assertTrue("example.Example.<init>([Ljava/lang/String;)V" in identities)
        assertTrue("example.Example.run(I)I" in identities)
        assertTrue("example.Example.run(Ljava/lang/String;)Ljava/lang/String;" in identities)
        assertTrue("example.Example.first" in identities)
        assertTrue("example.Example.second" in identities)
        assertTrue("example.Example${'$'}Nested.<init>(I)V" in identities)
        assertTrue("example.Example${'$'}Inner.<init>(Lexample/Example;J)V" in identities)
        assertTrue("example.Example${'$'}Choice.<init>(Ljava/lang/String;I)V" in identities)
        assertEquals(SourceRange(5, 5), indexed.symbols.single { it.identity == "example.Example.run(I)I" }.location.line)
    }

    @Test
    fun `source columns preserve UTF16 offsets and revision`() {
        val source = "class Example { String emoji = \"😀\"; int number; }"
        val indexed = index(source, Revision.BASE)
        val field = indexed.symbols.single { it.identity == "Example.number" }
        assertEquals(Revision.BASE, field.location.revision)
        assertEquals(SourceRange(source.indexOf("int number") + 1, source.indexOf("; }") + 2), field.location.column)
    }

    @Test
    fun `interface nested classes have no synthetic enclosing instance parameter`() {
        val indexed = index("interface Example { class Nested { Nested() {} } }")
        assertTrue(indexed.diagnostics.isEmpty())
        assertTrue(indexed.symbols.any { it.identity == "Example${'$'}Nested.<init>()V" })
    }

    @Test
    fun `records resolve explicit canonical constructor without enclosing instance`() {
        val indexed = index("class Example { record Entry(int x) { public Entry(int x) { this.x = x; } } }")
        assertTrue(indexed.diagnostics.isEmpty(), indexed.diagnostics.toString())
        assertTrue(indexed.symbols.any { it.identity == "Example${'$'}Entry.<init>(I)V" })
    }

    @Test
    fun `unresolved types malformed source and local binary identities produce diagnostics`() {
        val unresolved = index("class Example { Missing result(Missing value) { return value; } }")
        assertTrue(unresolved.diagnostics.isNotEmpty())
        assertTrue(unresolved.symbols.none { it.kind == SymbolKind.METHOD },
            "An unresolved signature must not invent a JVM method identity: ${unresolved.symbols}")
        val invalid = index("class Example {")
        assertTrue(invalid.diagnostics.isNotEmpty())
        assertTrue(invalid.symbols.isEmpty())
        val local = index("class Example { void run() { class Local {} } }")
        assertTrue(local.diagnostics.any { it.contains("Local type") })
        assertTrue(local.symbols.none { it.identity.contains("Local") })
    }

    @Test
    fun `generic constructor parameters are erased and initializers stay class owned`() {
        val indexed = index("""
            class Example<T extends Number> {
              static { System.out.println("init"); }
              Example(T value) {}
              <R extends CharSequence> R echo(R value) { return value; }
            }
        """.trimIndent())
        assertTrue(indexed.diagnostics.isEmpty(), indexed.diagnostics.toString())
        assertTrue(indexed.symbols.any { it.identity == "Example.<init>(Ljava/lang/Number;)V" })
        assertTrue(indexed.symbols.any { it.identity == "Example.echo(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;" })
    }

    @Test
    fun `resolved dependency JAR and class directory produce identical JVM method identity`() {
        val dependencySource = temporary.resolve("Dependency.java")
        Files.writeString(dependencySource, "package library; public class Dependency {}")
        val classes = Files.createDirectory(temporary.resolve("classes"))
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), dependencySource.toString()))
        val archive = temporary.resolve("dependency.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(archive)).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("library/Dependency.class"))
            Files.copy(classes.resolve("library/Dependency.class"), jar)
            jar.closeEntry()
        }
        Files.writeString(temporary.resolve("Example.java"),
            "class Example { library.Dependency echo(library.Dependency value) { return value; } }")
        listOf(archive, classes).forEach { dependency ->
            JavaSourceIndex(emptyList(), listOf(dependency)).use { index ->
                val result = index.index(temporary, "Example.java", Revision.HEAD)
                assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                assertTrue(result.symbols.any { it.identity == "Example.echo(Llibrary/Dependency;)Llibrary/Dependency;" })
            }
        }
    }

    private fun index(source: String, revision: Revision = Revision.HEAD): IndexedSource {
        Files.writeString(temporary.resolve("Example.java"), source)
        return JavaSourceIndex(listOf(temporary), emptyList()).use { it.index(temporary, "Example.java", revision) }
    }
}
