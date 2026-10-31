package io.johnsonlee.graphfold.engine.build

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MavenProvenanceTest {
    @TempDir lateinit var temporary: Path

    @Test fun `unchanged records survive while refreshed classes replace their old source and class hashes`() {
        val fixture = Fixture(temporary)
        val retained = fixture.artifact("sample.Retained")
        val changed = fixture.artifact("sample.Changed")
        fixture.emit(records = listOf(retained, changed))
        val first = fixture.publish().single()
        assertEquals(setOf("sample.Retained", "sample.Changed"), first.owners())
        assertEquals(fixture.classHash("sample.Retained"), first.record("sample.Retained").path("classHash").asText())
        val retainedBefore = first.record("sample.Retained")
        val oldChanged = first.record("sample.Changed")

        fixture.provenance.prepare()
        val refreshed = fixture.artifact("sample.Changed", "changed source", "changed bytecode")
        val added = fixture.artifact("sample.Added")
        fixture.emit(records = listOf(refreshed, added))
        val second = fixture.publish().single()
        assertEquals(setOf("sample.Retained", "sample.Changed", "sample.Added"), second.owners())
        assertEquals(retainedBefore, second.record("sample.Retained"))
        assertEquals(refreshed.path("sourceHash"), second.record("sample.Changed").path("sourceHash"))
        assertEquals(fixture.classHash("sample.Changed"), second.record("sample.Changed").path("classHash").asText())
        assertFalse(oldChanged.path("classHash") == second.record("sample.Changed").path("classHash"))
        assertFalse(Files.exists(temporary.resolve("state/snapshot.tmp")))
    }

    @Test fun `removed class records are pruned before their removed sources are validated`() {
        val fixture = Fixture(temporary)
        val removed = fixture.artifact("sample.Removed")
        fixture.emit(records = listOf(removed))
        fixture.publish()
        fixture.provenance.prepare()
        Files.delete(fixture.binary("sample.Removed"))
        Files.delete(Path.of(removed.path("source").asText()))
        assertTrue(fixture.publish().single().owners().isEmpty())
    }

    @Test fun `retained provenance rejects changed or absent sources and changed class bytes without fresh emission`() {
        listOf("source", "missing-source", "class").forEach { mutation ->
            val fixture = Fixture(Files.createDirectory(temporary.resolve(mutation)))
            val original = fixture.artifact("sample.Existing")
            fixture.emit(records = listOf(original))
            fixture.publish()
            val snapshot = Files.readAllBytes(fixture.snapshot)
            fixture.provenance.prepare()
            val source = Path.of(original.path("source").asText())
            when (mutation) {
                "source" -> Files.writeString(source, "edited without compilation")
                "missing-source" -> Files.delete(source)
                else -> Files.writeString(fixture.binary("sample.Existing"), "recompiled without origins")
            }
            val failure = assertFailsWith<BuildFailure> { fixture.publish() }
            assertTrue(if (mutation == "class") "no refreshed provenance" in failure.message.orEmpty()
                else "source provenance" in failure.message.orEmpty(), failure.message)
            assertContentEquals(snapshot, Files.readAllBytes(fixture.snapshot), "Failed publication must preserve the last snapshot")
        }
    }

    @Test fun `fresh source claims are validated even when the binary was emitted in this build`() {
        listOf("bad-hash", "missing").forEach { mutation ->
            val fixture = Fixture(Files.createDirectory(temporary.resolve(mutation)))
            val record = fixture.artifact("sample.New")
            if (mutation == "missing") Files.delete(Path.of(record.path("source").asText()))
            else record.put("sourceHash", "incorrect-hash")
            fixture.emit(records = listOf(record))
            assertTrue(assertFailsWith<BuildFailure> { fixture.publish() }.message.orEmpty().contains("source provenance"))
            assertFalse(Files.exists(fixture.snapshot))
        }
    }

    @Test fun `duplicate freshly emitted owners are rejected within one document or across compiler documents`() {
        listOf(false, true).forEach { separate ->
            val fixture = Fixture(Files.createDirectory(temporary.resolve("duplicate-$separate")))
            val record = fixture.artifact("sample.Duplicate")
            fixture.emit(records = if (separate) listOf(record) else listOf(record, record))
            if (separate) fixture.emit(producer = "kotlin", records = listOf(record))
            assertTrue(assertFailsWith<BuildFailure> { fixture.publish() }.message.orEmpty().contains("Duplicate emitted"))
        }
    }

    @Test fun `schema completeness and run identity are checked for both emitted and retained documents`() {
        listOf("schema", "complete", "runId").forEach { field ->
            listOf(false, true).forEach { retained ->
                val fixture = Fixture(Files.createDirectory(temporary.resolve("$field-$retained")))
                fixture.emit(records = listOf(fixture.artifact("sample.Guarded")))
                val file = if (retained) {
                    fixture.publish()
                    fixture.provenance.prepare()
                    fixture.snapshot
                } else fixture.provenance.pending.resolve("0.json")
                val root = MAPPER.readTree(file.toFile())
                val document = (if (retained) root[0] else root) as ObjectNode
                when (field) {
                    "schema" -> document.put(field, 2)
                    "complete" -> document.put(field, false)
                    else -> document.put(field, "another-run")
                }
                MAPPER.writeValue(file.toFile(), root)
                assertTrue(assertFailsWith<BuildFailure> { fixture.publish() }.message.orEmpty().contains("Incomplete or stale"))
            }
        }
    }

    @Test fun `unresolved origins are retained until fresh resolution and removed when their class disappears`() {
        val fixture = Fixture(temporary)
        val record = fixture.artifact("sample.Unresolved")
        fixture.emit(unresolved = listOf(unresolved("sample.Unresolved")))
        assertEquals("sample.Unresolved", fixture.publish().single().path("unresolved").single().path("owner").asText())
        fixture.provenance.prepare()
        assertEquals(1, fixture.publish().single().path("unresolved").size())

        fixture.emit(records = listOf(record))
        val resolved = fixture.publish().single()
        assertTrue(resolved.path("unresolved").isEmpty)
        assertEquals(setOf("sample.Unresolved"), resolved.owners())
        fixture.provenance.prepare()
        fixture.emit(unresolved = listOf(unresolved("sample.Unresolved")))
        val regressed = fixture.publish().single()
        assertTrue(regressed.owners().isEmpty())
        assertEquals(1, regressed.path("unresolved").size())
        fixture.provenance.prepare()
        Files.delete(fixture.binary("sample.Unresolved"))
        assertTrue(fixture.publish().single().path("unresolved").isEmpty)
    }

    @Test fun `compiler producer groups remain distinct across incremental publication`() {
        val fixture = Fixture(temporary)
        fixture.emit(producer = "javac", records = listOf(fixture.artifact("sample.Java")))
        fixture.emit(producer = "kotlin", records = listOf(fixture.artifact("sample.Kotlin")),
            unresolved = listOf(unresolved("sample.Pending")))
        fixture.artifact("sample.Pending")
        val documents = fixture.publish().associateBy { it.path("producer").path("compiler").asText() }
        assertEquals(setOf("javac", "kotlin"), documents.keys)
        assertEquals(setOf("sample.Java"), documents.getValue("javac").owners())
        assertEquals(setOf("sample.Kotlin"), documents.getValue("kotlin").owners())
        assertTrue(documents.getValue("javac").path("unresolved").isEmpty)
        assertEquals(1, documents.getValue("kotlin").path("unresolved").size())
        fixture.provenance.prepare()
        assertEquals(documents, fixture.publish().associateBy { it.path("producer").path("compiler").asText() })
    }

    @Test fun `prepare removes prior pending files recursively but preserves committed snapshot and unrelated state`() {
        val fixture = Fixture(temporary)
        fixture.emit(records = listOf(fixture.artifact("sample.Kept")))
        fixture.publish()
        val snapshot = Files.readAllBytes(fixture.snapshot)
        val nested = Files.createDirectories(fixture.provenance.pending.resolve("nested"))
        Files.writeString(nested.resolve("partial.tmp"), "aborted compiler output")
        Files.writeString(temporary.resolve("state/unrelated.txt"), "keep")
        fixture.provenance.prepare()
        assertTrue(regularFiles(fixture.provenance.pending).isEmpty())
        assertContentEquals(snapshot, Files.readAllBytes(fixture.snapshot))
        assertEquals("keep", Files.readString(temporary.resolve("state/unrelated.txt")))
        assertEquals(setOf("sample.Kept"), fixture.publish().single().owners())
    }

    @Test fun `empty builds publish no invented producer and ignore nonmanifest pending files`() {
        val fixture = Fixture(temporary)
        Files.writeString(fixture.provenance.pending.resolve("diagnostics.txt"), "compiler progress")
        assertTrue(fixture.publish().isEmpty())
        assertTrue(MAPPER.readTree(fixture.snapshot.toFile()).isEmpty)
        fixture.provenance.prepare()
        assertTrue(fixture.publish().isEmpty())
    }

    private class Fixture(private val root: Path) {
        val provenance = MavenProvenance(root.resolve("state"), "stable-run")
        val snapshot: Path = root.resolve("state/snapshot.json")
        private val classes = Files.createDirectories(root.resolve("classes"))
        private var emitted = 0
        private var publications = 0

        init { provenance.prepare() }

        fun artifact(owner: String, sourceText: String = "source:$owner", binaryText: String = "bytecode:$owner"): ObjectNode {
            val source = root.resolve("sources/${owner.replace('.', '/')}.java")
            Files.createDirectories(source.parent)
            Files.writeString(source, sourceText)
            val binary = binary(owner)
            Files.createDirectories(binary.parent)
            Files.writeString(binary, binaryText)
            return MAPPER.createObjectNode().put("owner", owner).put("source", source.toString()).put("sourceHash", hash(source))
        }

        fun binary(owner: String): Path = classes.resolve(owner.replace('.', '/') + ".class")
        fun classHash(owner: String): String = hash(binary(owner))

        fun emit(producer: String = "javac", records: List<ObjectNode> = emptyList(), unresolved: List<ObjectNode> = emptyList()) {
            val document = MAPPER.createObjectNode().put("schema", 1).put("complete", true).put("runId", "stable-run")
            document.putObject("producer").put("compiler", producer)
            document.putArray("records").addAll(records)
            document.putArray("unresolved").addAll(unresolved)
            MAPPER.writeValue(provenance.pending.resolve("${emitted++}.json").toFile(), document)
        }

        fun publish(): List<JsonNode> {
            val output = root.resolve("output-${publications++}")
            provenance.publish(classes, output)
            return regularFiles(output).sorted().map { MAPPER.readTree(it.toFile()) }
        }
    }

    private fun JsonNode.owners(): Set<String> = path("records").map { it.path("owner").asText() }.toSet()
    private fun JsonNode.record(owner: String): JsonNode = path("records").single { it.path("owner").asText() == owner }
    private fun unresolved(owner: String): ObjectNode = MAPPER.createObjectNode().put("owner", owner).put("reason", "missing origin")

    private companion object {
        val MAPPER = jacksonObjectMapper()
        fun hash(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
    }
}
