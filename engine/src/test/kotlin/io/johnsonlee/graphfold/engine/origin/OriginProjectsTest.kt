package io.johnsonlee.graphfold.engine.origin

import io.johnsonlee.graphfold.engine.build.CompiledProject
import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.git.ChangedFile
import io.johnsonlee.graphfold.engine.git.GitChangeType
import io.johnsonlee.graphite.input.FoldSites
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OriginProjectsTest {
    @TempDir lateinit var temporary: Path

    @Test fun `Kotlin selection graph strips compiler markers and retains original query scope`() {
        val text = """
            package sample
            class Example {
                fun enabled(id: String): Boolean = id.isNotEmpty()
                fun work() {}
                fun first(): Runnable = object : Runnable { override fun run() { if (enabled("checkout")) work() } }
                fun second(): Runnable = object : Runnable { override fun run() { if (enabled("checkout")) work() } }
            }
        """.trimIndent()
        val before = compile("base", "kt", text)
        val after = compile("head", "kt", text)
        assertTrue(nodes(before.project.classes).any(::hasMarker), "Real compiler output must contain provenance markers")
        val projects = prepareOrigins(before.project, after.project, before.root, after.root, emptyList(), temporary.resolve("prepared"))
        assertTrue(projects.diagnostics.isEmpty(), projects.diagnostics.toString())
        val head = assertNotNull(projects.head)
        val originalOwners = nodes(after.project.classes).map { it.name }.toSet()
        val selectionNodes = nodes(head.sourceClasses)
        assertEquals(originalOwners, selectionNodes.map { it.name }.toSet())
        assertTrue(selectionNodes.none(::hasMarker), "Selection must not expose private compiler instrumentation")
        val origins = head.catalog.origins.sortedBy { it.syntax.range.start }
        assertEquals(2, origins.size)
        val selected = origins.first().owner
        val untouched = origins.last().owner
        val rule = FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite) WHERE cs.caller_class = '$selected' AND cs.callee_name = 'enabled' RETURN cs",
            "args" to mapOf(0 to "checkout"), "value" to false,
        ))
        val snapshot = GraphiteAnalyzer().analyzeOriginProject(head, listOf(rule))
        assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
        assertFalse("sample.Example.work()V" in snapshot.methods.getValue(head.owners.getValue(selected) + ".run()V").calls)
        assertTrue("sample.Example.work()V" in snapshot.methods.getValue(head.owners.getValue(untouched) + ".run()V").calls)
        assertEquals(listOf(1), snapshot.matchedRules)
    }

    @Test fun `missing compilation provenance prevents publishing either prepared revision`() {
        val source = compile("source", "java", "class Example { public Object value() { return new Object() {}; } }")
        val projects = prepareOrigins(source.project.copy(originDirectory = null), source.project,
            source.root, source.root, emptyList(), temporary.resolve("prepared"))
        assertNull(projects.base)
        assertNull(projects.head)
        assertTrue(projects.diagnostics.any { it.startsWith("base:") && it.contains("manifest is missing") })
        assertFalse(Files.exists(temporary.resolve("prepared")), "No partly verified artifact may be published")
    }

    @Test fun `ambiguous repeated source allocations block preparation before normalization`() {
        val text = "class Example { void use(Object value) {} public void run() { use(new Object() {}); use(new Object() {}); } }"
        val before = compile("base", "java", text)
        val after = compile("head", "java", text)
        val projects = prepareOrigins(before.project, after.project, before.root, after.root, emptyList(), temporary.resolve("prepared"))
        assertNull(projects.base)
        assertNull(projects.head)
        assertTrue(projects.diagnostics.any { it.contains("Ambiguous") }, projects.diagnostics.toString())
        assertFalse(Files.exists(temporary.resolve("prepared")))
    }

    @Test fun `preparation preserves stable serializable names but reports observable renumbering`() {
        val before = compile("base", "java",
            "class Example { public Object value() { return new java.io.Serializable() {}; } }")
        val stable = prepareOrigins(before.project, before.project, before.root, before.root, emptyList(),
            temporary.resolve("stable"))
        assertTrue(stable.diagnostics.isEmpty(), stable.diagnostics.toString())
        assertTrue(assertNotNull(stable.base).owners.all { it.key == it.value })
        val after = compile("head", "java",
            "class Example { public Object value() { Object extra = new Object() {}; return new java.io.Serializable() {}; } }")
        val renamed = prepareOrigins(before.project, after.project, before.root, after.root, emptyList(),
            temporary.resolve("renamed"))
        assertTrue(renamed.diagnostics.any { it.startsWith("head:") && it.contains("observable through Serializable") },
            renamed.diagnostics.toString())
    }

    @Test fun `file moves preserve source pairs and additions deletions do not become rename hints`() {
        val text = "class Example { public Object value() { return new Object() {}; } }"
        val before = compile("base", "java", text)
        val headRoot = Files.createDirectories(temporary.resolve("head/src/main/java/moved"))
        Files.writeString(headRoot.resolve("Example.java"), text)
        val root = temporary.resolve("head")
        val after = ProjectCompiler(Duration.ofMinutes(2)).compile(root, temporary.resolve("head-output"))
        val changes = listOf(
            ChangedFile("src/main/java/Example.java", "src/main/java/moved/Example.java", emptyList(), emptyList(),
                changeType = GitChangeType.RENAME, similarityScore = 100),
            ChangedFile(null, "README.md", emptyList(), emptyList(), changeType = GitChangeType.ADD),
            ChangedFile("old.md", null, emptyList(), emptyList(), changeType = GitChangeType.DELETE),
            ChangedFile("build.gradle.kts", "build.gradle.kts", emptyList(), emptyList()),
        )
        val projects = prepareOrigins(before.project, after, before.root, root, changes, temporary.resolve("prepared"))
        assertTrue(projects.diagnostics.isEmpty(), projects.diagnostics.toString())
        assertEquals(assertNotNull(projects.base).owners.values.toSet(), assertNotNull(projects.head).owners.values.toSet())
        assertTrue(projects.head.owners.all { it.key == it.value }, "A pure file move must retain emitted identity")
    }

    @Test fun `projects without anonymous allocations reuse marker-free classes for original selection`() {
        val fixture = compile("plain", "java", "class Example { public int value() { return 1; } }")
        val projects = prepareOrigins(fixture.project, fixture.project, fixture.root, fixture.root, emptyList(),
            temporary.resolve("prepared"))
        assertTrue(projects.diagnostics.isEmpty(), projects.diagnostics.toString())
        val head = assertNotNull(projects.head)
        assertTrue(head.owners.isEmpty())
        assertEquals(head.compiled.classes, head.sourceClasses)
        assertTrue(GraphiteAnalyzer().analyzeOriginProject(head, emptyList()).diagnostics.isEmpty())
    }

    private fun compile(name: String, extension: String, text: String): Fixture {
        val root = Files.createDirectories(temporary.resolve(name))
        val language = if (extension == "kt") "kotlin" else "java"
        val sources = Files.createDirectories(root.resolve("src/main/$language"))
        Files.writeString(sources.resolve("Example.$extension"), text)
        return Fixture(root, ProjectCompiler(Duration.ofMinutes(2)).compile(root, temporary.resolve("$name-output")))
    }

    private fun nodes(classes: Path): List<ClassNode> = Files.walk(classes).use { files ->
        files.filter { it.toString().endsWith(".class") }.map { file ->
            ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
        }.toList()
    }

    private fun hasMarker(node: ClassNode): Boolean =
        (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty()).any { it.desc == ORIGIN_MARKER }

    private data class Fixture(val root: Path, val project: CompiledProject)
}
