package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.build.ProductionInventory
import io.johnsonlee.graphfold.engine.git.ChangedFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildInputProofTest {
    @Test
    fun `missing production evidence from either revision blocks instead of proving unchanged outputs`() {
        val inventory = ProductionInventory(emptyMap(), emptyMap())
        listOf(null to null, null to inventory, inventory to null).forEach { (base, head) ->
            val proof = BuildInputProof(listOf(change("README.md", "README.md")), base, head)
            assertFalse(proof.unchanged)
            val gap = assertNotNull(proof.gap())
            assertEquals(AnalysisStage.BUILD, gap.stage)
            assertEquals(DiagnosticCode.PRODUCTION_INVENTORY_UNAVAILABLE, gap.code)
            assertTrue(gap.blocking)
            assertTrue(gap.message.contains("both revisions"))
        }
    }

    @Test
    fun `equal production evidence is order independent and has no analysis gap`() {
        val base = ProductionInventory(mapOf("class/Example.class" to "bytecode", "resource/0/text" to "value"),
            mapOf("resource/0/text" to "value"))
        val head = ProductionInventory(base.entries.entries.reversed().associate { it.toPair() }, base.nonClassEntries.toMap())
        val proof = BuildInputProof(listOf(change("README.md", "README.md")), base, head)
        assertTrue(proof.unchanged)
        assertNull(proof.gap())
        val empty = ProductionInventory(emptyMap(), emptyMap())
        assertTrue(BuildInputProof(emptyList(), empty, empty).unchanged)
    }

    @Test
    fun `class changes require graph proof without introducing a nonclass output gap`() {
        val base = ProductionInventory(mapOf("class/Example.class" to "before"), emptyMap())
        val head = ProductionInventory(mapOf("class/Example.class" to "after", "class/Added.class" to "added"), emptyMap())
        val proof = BuildInputProof(listOf(change("generator.json", "generator.json")), base, head)
        assertFalse(proof.unchanged)
        assertNull(proof.gap())
        assertFalse(BuildInputProof(proof.changes, head, base).unchanged)
        assertNull(BuildInputProof(proof.changes, head, base).gap())
    }

    @Test
    fun `excluding or relocating a still compiled class is a packaging gap`() {
        val artifactPath = "artifact/0/sample/Example.class"
        val base = ProductionInventory(mapOf("class/sample/Example.class" to "class", artifactPath to "class"),
            emptyMap(), mapOf("sample/Example.class" to setOf(artifactPath)))
        val excluded = base.copy(entries = base.entries - artifactPath,
            classPlacements = mapOf("sample/Example.class" to emptySet()))
        val relocatedPath = "artifact/0/BOOT-INF/classes/sample/Example.class"
        val relocated = base.copy(entries = base.entries - artifactPath + (relocatedPath to "class"),
            classPlacements = mapOf("sample/Example.class" to setOf(relocatedPath)))
        listOf(excluded, relocated).forEach { head ->
            val proof = BuildInputProof(listOf(change("build.gradle", "build.gradle")), base, head)
            assertFalse(proof.unchanged)
            val gap = assertNotNull(proof.gap())
            assertEquals(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED, gap.code)
            assertTrue(gap.message.endsWith("classPlacement/sample/Example.class (1 output entries)"))
            assertNotNull(BuildInputProof(proof.changes, head, base).gap())
        }
    }

    @Test
    fun `matched class byte changes and true source class additions or deletions remain graph obligations`() {
        val path = "sample/Example.class"
        val base = ProductionInventory(mapOf("class/$path" to "before", "artifact/0/$path" to "before"),
            emptyMap(), mapOf(path to setOf("artifact/0/$path")))
        val changed = base.copy(entries = base.entries.mapValues { "after" })
        assertFalse(BuildInputProof(emptyList(), base, changed).unchanged)
        assertNull(BuildInputProof(emptyList(), base, changed).gap())
        val addedPath = "sample/Unused.class"
        val added = base.copy(entries = base.entries + mapOf("class/$addedPath" to "unused", "artifact/0/$addedPath" to "unused"),
            classPlacements = base.classPlacements + (addedPath to setOf("artifact/0/$addedPath")))
        assertNull(BuildInputProof(emptyList(), base, added).gap())
        assertNull(BuildInputProof(emptyList(), added, base).gap())
    }

    @Test
    fun `added removed and modified resources are reported once in stable order`() {
        val before = mapOf("resource/removed" to "old", "resource/modified" to "old", "resource/same" to "unchanged")
        val after = mapOf("resource/added" to "new", "resource/modified" to "new", "resource/same" to "unchanged")
        val proof = BuildInputProof(emptyList(), ProductionInventory(before, before), ProductionInventory(after, after))
        assertFalse(proof.unchanged)
        val gap = assertNotNull(proof.gap())
        assertEquals(AnalysisStage.COMPARE, gap.stage)
        assertEquals(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED, gap.code)
        assertTrue(gap.blocking)
        assertTrue(gap.message.endsWith("resource/added, resource/modified, resource/removed (3 output entries)"))
        assertFalse(gap.message.contains("resource/same"))
    }

    @Test
    fun `large output gaps retain total count while bounding sorted detail`() {
        val before = ProductionInventory(emptyMap(), emptyMap())
        val entries = (0 until 25).reversed().associate { "resource/%02d".format(it) to "content" }
        val gap = assertNotNull(BuildInputProof(emptyList(), before, ProductionInventory(entries, entries)).gap())
        assertTrue(gap.message.endsWith("(25 output entries)"))
        assertTrue(gap.message.contains((0 until 20).joinToString { "resource/%02d".format(it) }))
        (20 until 25).forEach { assertFalse(gap.message.contains("resource/%02d".format(it))) }
    }

    @Test
    fun `proof reasons preserve each diff endpoint and scenario without inventing source locations`() {
        val changes = listOf(change(null, "docs/added.md"), change("README.md", null), change("before.yml", "after.yml"))
        val proof = BuildInputProof(changes, null, null)
        assertEquals(changes, proof.changes)
        listOf("control", "treatment").forEach { scenario ->
            val reasons = proof.reasons(ReasonCode.BUILD_OUTPUT_UNCHANGED, scenario, "Observed outputs are equal")
            assertEquals(changes.size, reasons.size)
            changes.zip(reasons).forEach { (change, reason) ->
                assertEquals(change.basePath, reason.basePath)
                assertEquals(change.headPath, reason.headPath)
                assertEquals(scenario, reason.scenario)
                assertEquals(setOf(ChangeKind.BUILD_INPUT_CHANGED), reason.changes)
                assertEquals(ReasonCode.BUILD_OUTPUT_UNCHANGED, reason.code)
                assertEquals("Observed outputs are equal", reason.message)
                assertTrue(reason.locations.isEmpty())
            }
        }
        val failure = proof.reasons(ReasonCode.ANALYSIS_INCOMPLETE, "control", "Unavailable evidence")
        assertTrue(failure.all { it.code == ReasonCode.ANALYSIS_INCOMPLETE && it.message == "Unavailable evidence" })
        assertTrue(BuildInputProof(emptyList(), null, null).reasons(ReasonCode.FOLDED_EQUIVALENT, "control", "Equal").isEmpty())
    }

    @Test
    fun `source classification accounts for both sides of additions deletions and renames`() {
        listOf(change(null, "Added.java"), change(null, "Added.kt"), change("Deleted.java", null),
            change("Deleted.kt", null), change("Before.java", "After.java"), change("Before.kt", "After.kt"),
            change("Before.java", "After.kt"), change("Before.kt", "After.java"),
        ).forEach { assertTrue(it.isJvmSourceChange(), it.toString()) }
        listOf(change(null, "README.md"), change("README.md", null), change("README.md", "README.md"),
            change("Before.java", "After.txt"), change("Before.txt", "After.java"),
            change("Before.kt", "After.txt"), change("Before.txt", "After.kt"),
            change(null, "build.gradle.kts"), change(null, "README.java.md"), change(null, "Upper.JAVA"),
        ).forEach { assertFalse(it.isJvmSourceChange(), it.toString()) }
        // Binary patch handling is a later source-model check, not a filename-based non-JVM exemption.
        assertTrue(change("Example.java", "Example.java").copy(binary = true).isJvmSourceChange())
    }

    private fun change(base: String?, head: String?) = ChangedFile(base, head, emptyList(), emptyList())
}
