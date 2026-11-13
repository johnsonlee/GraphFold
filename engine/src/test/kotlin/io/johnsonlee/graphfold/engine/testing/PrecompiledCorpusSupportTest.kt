package io.johnsonlee.graphfold.engine.testing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrecompiledCorpusSupportTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `every corpus case belongs to exactly one shard with stable names`() {
        val cases = DetectionFixtures.cases()
        val fixtures = DetectionFixtures.fixtures
        val mapper = jacksonObjectMapper()
        val common = mapper.readTree(fixtures.resolve("manifest.json").toFile()).size()
        val kotlinOnly = mapper.readTree(fixtures.resolve("kotlin/features.json").toFile()).size()
        assertEquals(common + common + kotlinOnly, cases.size)
        assertEquals(cases.size, cases.map { it.toString() }.distinct().size)
        listOf(1, 4, 8, 16).forEach { count ->
            val partitions = (0 until count).map { DetectionFixtures.select(cases, count, it) }
            assertEquals(cases.toSet(), partitions.flatten().toSet())
            assertEquals(cases.size, partitions.sumOf { it.size })
            cases.forEachIndexed { index, case -> assertTrue(case in partitions[index % count]) }
        }
        assertFailsWith<IllegalArgumentException> { DetectionFixtures.select(cases, 0, 0) }
        assertFailsWith<IllegalArgumentException> { DetectionFixtures.select(cases, 4, 4) }
    }

    @Test
    fun `descriptor requires existing fresh compilation without fallback`() {
        val descriptor = temporary.resolve("descriptor.json")
        assertFailsWith<IllegalStateException> { DetectionFixtures.readDescriptor(descriptor, "fresh") }
        assertEquals(emptyList(), Files.list(temporary).use { it.toList() })
        val revision = revision()
        jacksonObjectMapper().writeValue(descriptor.toFile(), DetectionDescriptor("old", "repository", revision, revision))
        assertFailsWith<IllegalStateException> { DetectionFixtures.readDescriptor(descriptor, "fresh") }
        assertEquals("old", DetectionFixtures.readDescriptor(descriptor, "old").fingerprint)
    }

    @Test
    fun `stored input preserves original bytes and source-only scope and rejects relocation or corruption`() {
        val revision = revision()
        val classes = temporary.resolve(revision.classes)
        val before = Files.readAllBytes(classes.resolve("Example.class"))
        val project = DetectionFixtures.project(revision, temporary)
        assertNull(project.production)
        assertEquals(classes, project.classes)
        assertEquals(revision.runId, project.originRunId)
        assertEquals(listOf(Path.of(revision.checkout).resolve("src/main/java")), project.sourceRoots)
        assertEquals(project.sourceRoots.associateWith { listOf(classes) }, project.sourceOutputs)
        assertTrue(before.contentEquals(Files.readAllBytes(classes.resolve("Example.class"))))
        assertFailsWith<IllegalStateException> { DetectionFixtures.project(revision, temporary.resolve("elsewhere")) }
        Files.writeString(classes.resolve("Example.class"), "corrupted")
        assertFailsWith<IllegalStateException> { DetectionFixtures.project(revision, temporary) }
    }

    private fun revision(): DetectionRevision {
        val checkout = Files.createDirectories(temporary.resolve("base"))
        Files.createDirectories(checkout.resolve("src/main/java"))
        val classes = Files.createDirectories(temporary.resolve("classes"))
        val emitted = classes.resolve("Example.class")
        Files.writeString(emitted, "unchanged compiler bytes")
        Files.createDirectories(temporary.resolve("origins"))
        return DetectionRevision("sha", checkout.toString(), "classes", "origins", "original-run", listOf("src/main/java"),
            emptyMap(), mapOf("classes/Example.class" to fileDigest(emitted)))
    }
}
