package io.johnsonlee.graphfold.engine.origin

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import io.johnsonlee.graphfold.engine.source.JavaSourceIndex
import io.johnsonlee.graphfold.engine.source.KotlinSourceIndex
import io.johnsonlee.graphfold.engine.source.ProofPlanner
import io.johnsonlee.graphfold.engine.source.SymbolKind
import io.johnsonlee.graphfold.engine.source.IndexedSource
import io.johnsonlee.graphfold.engine.source.SourceSymbol
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OriginSourceIndexTest {
    @TempDir lateinit var temporary: Path

    @Test fun `constructor arguments belong to caller while anonymous members belong to compiler origin`() {
        val examples = mapOf(
            "java" to ("class Base { Base(int x) {} } class Example { public Base create() { " +
                "return new Base(1) { int added() { return 3; } }; } }"),
            "kt" to "open class Base(x: Int); class Example { fun create(): Base = object : Base(1) { fun added(): Int = 3 } }",
        )
        examples.forEach { (extension, text) ->
            val root = Files.createDirectories(temporary.resolve(extension))
            val language = if (extension == "kt") "kotlin" else "java"
            val path = "src/main/$language/Example.$extension"
            Files.createDirectories(root.resolve(path).parent)
            Files.writeString(root.resolve(path), text)
            val compiled = ProjectCompiler(Duration.ofMinutes(2)).compile(root, temporary.resolve("$extension-output"))
            val catalog = readOrigins(compiled, root)
            assertTrue(catalog.diagnostics.isEmpty(), catalog.diagnostics.toString())
            val origin = catalog.origins.single()
            val indexed = if (extension == "java") {
                JavaSourceIndex(compiled.sourceRoots, listOf(compiled.classes)).use { it.index(root, path, Revision.HEAD) }
            } else KotlinSourceIndex(compiled.classes).use { it.index(root, path, Revision.HEAD) }
            assertTrue(indexed.diagnostics.isEmpty(), indexed.diagnostics.toString())
            val bound = bindOrigins(indexed, Revision.HEAD, catalog, mapOf(origin.owner to origin.owner))
            val header = location(path, text.indexOf("(1)") + 1)
            val body = location(path, text.indexOf("3"))
            val headerProof = ProofPlanner.plan(bound, listOf(header)).single()
            assertEquals(SymbolKind.METHOD, headerProof.kind)
            assertEquals("Example.create()LBase;", headerProof.identity)
            assertEquals(listOf(header), headerProof.locations)
            val bodyProof = ProofPlanner.plan(bound, listOf(body)).single()
            assertEquals(SymbolKind.CLASS, bodyProof.kind)
            assertEquals(origin.owner, bodyProof.identity)
            assertEquals(listOf(body), bodyProof.locations)
        }
    }

    @Test fun `binding remaps typed descriptors and nested owners without rewriting source text`() {
        val path = "Example.kt"
        val text = "class Example"
        val location = location(path, 0)
        val original = "sample.Example\$create\$1"
        val canonical = "sample.Example\$stable"
        val child = "$original\$Child"
        val source = IndexedSource(path, text, listOf(
            SourceSymbol("$child.copy(L${child.replace('.', '/')};Ljava/lang/String;)L${original.replace('.', '/')};",
                child, SymbolKind.METHOD, location),
            SourceSymbol(child, child, SymbolKind.CLASS, location),
            SourceSymbol("sample.Example", "sample.Example", SymbolKind.CLASS, location),
        ), emptyList())
        val bound = bindOrigins(source, Revision.HEAD, OriginCatalog(emptyList(), emptyList()),
            mapOf(original to canonical, child to "$canonical\$Child"))
        assertEquals(listOf("$canonical\$Child.copy(L${canonical.replace('.', '/')}\$Child;Ljava/lang/String;)" +
            "L${canonical.replace('.', '/')};", "$canonical\$Child", "sample.Example"), bound.symbols.map { it.identity })
        assertEquals(text, bound.text)
        assertTrue(bound.symbols.all { it.location == location })
    }

    @Test fun `missing anonymous body range retains enclosing method proof obligation`() {
        val text = "fun create() = object {}"
        val path = "Example.kt"
        val enclosing = SourceLocation(Revision.HEAD, path, SourceRange(1, 1), SourceRange(1, text.length + 1))
        val method = SourceSymbol("ExampleKt.create()Ljava/lang/Object;", "ExampleKt", SymbolKind.METHOD, enclosing)
        val syntax = OriginSyntax(OriginSpan(15, text.length), "anchor", "body", "create", null)
        val origin = SourceOrigin("ExampleKt\$create\$1", path, syntax)
        val bound = bindOrigins(IndexedSource(path, text, listOf(method), emptyList()), Revision.HEAD,
            OriginCatalog(listOf(origin), emptyList()), mapOf(origin.owner to origin.owner))
        assertEquals(listOf(method), bound.symbols)
        assertEquals(method.identity, ProofPlanner.plan(bound, listOf(location(path, 22))).single().identity)
    }

    private fun location(path: String, offset: Int) = SourceLocation(
        Revision.HEAD, path, SourceRange(1, 1), SourceRange(offset + 1, offset + 2),
    )
}
