package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.git.ChangedFile
import io.johnsonlee.graphfold.engine.git.changedFile
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionSourceChangesTest {
    @TempDir lateinit var temporary: Path
    private val baseRoot = Path.of("/checkout/base")
    private val headRoot = Path.of("/checkout/head")
    private val paths = listOf("app/src/main/java", "library/customKotlin")
    private val base = project(baseRoot)
    private val head = project(headRoot)

    @Test fun `every endpoint must belong to actual packaged producers independent of project or source set names`() {
        assertTrue(classify("app/src/main/java/App.java", "library/customKotlin/Other.kt"))
        assertTrue(classify(null, "app/src/main/java/Added.java"))
        assertTrue(classify("library/customKotlin/Removed.kt", null))
        for (path in listOf("app/src/test/java/Test.java", "buildSrc/src/main/java/Plugin.java", "tools/Generator.kt",
            "unlaunched/src/main/java/App.java", "app/src/main/resources/Literal.java", "app/src/main/javaOther/Wrong.java")) {
            assertFalse(classify(path, path), path)
            assertFalse(classify("app/src/main/java/App.java", path), "Production to nonproduction: $path")
            assertFalse(classify(path, "app/src/main/java/App.java"), "Nonproduction to production: $path")
        }
        assertFalse(classify("app/src/main/java/data.txt", "app/src/main/java/data.txt"))
        assertFalse(classify(null, null))
    }

    @Test fun `source only compilation uses its declared roots and revision ownership never crosses checkouts`() {
        val old = StaticScope(baseRoot.resolve("classes"), listOf(baseRoot.resolve("source")), emptyList())
        val new = StaticScope(headRoot.resolve("classes"), listOf(headRoot.resolve("renamed")), emptyList())
        assertTrue(isProductionSourceChange(change("source/App.java", "renamed/App.java"), listOf(old), listOf(new), baseRoot, headRoot))
        assertFalse(isProductionSourceChange(change("renamed/App.java", "source/App.java"), listOf(old), listOf(new), baseRoot, headRoot))
    }

    @Test fun `actual output changes activate an archive scope but absent inventories cannot invent a changed artifact`() {
        val old = base.copy(production = io.johnsonlee.graphfold.engine.inputs.ContentInventory(mapOf("entry" to "old"), emptyMap()))
        val same = head.copy(production = old.production)
        val changed = head.copy(production = requireNotNull(old.production).copy(entries = mapOf("entry" to "new")))
        assertTrue(scopeOutputsChanged(old, changed))
        assertFalse(scopeOutputsChanged(old, same))
        assertFalse(scopeOutputsChanged(base, changed))
        assertFalse(scopeOutputsChanged(old, head))
    }

    @Test fun `changed artifacts require every change while unchanged artifacts select inputs and owned sources`() {
        val input = change("build.gradle", "build.gradle")
        val own = change("app/App.java", "app/App.java")
        val foreign = change("other/App.java", "other/App.java")
        val changes = listOf(input, own, foreign)
        assertEquals(changes, changesRequiringScopeProof(changes, listOf(input), true) { false })
        assertEquals(listOf(input, own), changesRequiringScopeProof(changes, listOf(input), false) { it == own })
    }

    @Test fun `checkout aliases preserve production ownership for present and absent source endpoints`() {
        val physical = Files.createDirectory(temporary.resolve("physical"))
        val sourceRoot = Files.createDirectories(physical.resolve("src/main/java"))
        Files.writeString(sourceRoot.resolve("Present.java"), "class Present {}")
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), physical)
        val project = StaticScope(physical.resolve("classes"), listOf(sourceRoot.toRealPath()), emptyList())
        for (name in listOf("Present.java", "Absent.java")) {
            val path = "src/main/java/$name"
            assertTrue(scopeContainsSource(project, alias, path))
            assertTrue(isProductionSourceChange(change(path, path), listOf(project), listOf(project), alias, alias))
            assertTrue(scopeContainsSource(project.copy(sourceRoots = listOf(alias.resolve("src/main/java"))), physical, path))
        }
        assertFalse(scopeContainsSource(project, alias, "src/test/java/Present.java"))
    }

    private fun classify(before: String?, after: String?): Boolean =
        isProductionSourceChange(change(before, after), listOf(base), listOf(head), baseRoot, headRoot)

    private fun change(before: String?, after: String?): ChangedFile = changedFile(before, after)

    private fun project(root: Path): StaticScope =
        StaticScope(root.resolve("normalized-classes"), paths.map(root::resolve), emptyList())
}
