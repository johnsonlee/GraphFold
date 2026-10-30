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

class MavenClassInventoryTest {
    @TempDir lateinit var temporary: Path

    @Test fun `last source deletion prunes both the owned target and exact Kotlin incremental cache entry`() {
        val fixture = Fixture(temporary)
        fixture.seed()
        val cache = fixture.cache()
        Files.createDirectories(cache.parent)
        Files.copy(fixture.binary(), cache)
        val unrelatedCache = fixture.checkout.resolve("target/kotlin-ic/test/classes/sample/Owned.class")
        Files.createDirectories(unrelatedCache.parent)
        Files.copy(fixture.binary(), unrelatedCache)
        Files.delete(fixture.source())
        fixture.inventory().prepare()
        assertFalse(Files.exists(fixture.binary()))
        assertFalse(Files.exists(cache))
        assertTrue(Files.exists(unrelatedCache), "Only the recorded compile cache is eligible for retirement")
        fixture.inventory().reconcile(fixture.pending)
        assertTrue(fixture.records().isEmpty())
    }

    @Test fun `changed source without refreshed compiler ownership preserves the last committed inventory`() {
        val fixture = Fixture(temporary)
        fixture.seed()
        val committed = Files.readAllBytes(fixture.snapshot)
        Files.writeString(fixture.source(), "modified source")
        fixture.inventory().prepare()
        assertFailsWith<BuildFailure> { fixture.inventory().reconcile(fixture.pending) }
        assertContentEquals(committed, Files.readAllBytes(fixture.snapshot))
        assertTrue(Files.exists(fixture.binary()))
    }

    @Test fun `fresh compiler ownership accepts updated source and binary bytes and unchanged records remain reusable`() {
        val fixture = Fixture(temporary)
        fixture.seed()
        val unchanged = fixture.records()
        fixture.inventory().prepare()
        fixture.inventory().reconcile(fixture.pending)
        assertEquals(unchanged, fixture.records())
        Files.writeString(fixture.source(), "updated source")
        Files.writeString(fixture.binary(), "updated bytecode")
        fixture.emit(listOf(fixture.output()))
        fixture.inventory().reconcile(fixture.pending)
        val record = fixture.records().single()
        assertEquals(hash(fixture.binary()), record.path("classHash").asText())
        assertEquals(hash(fixture.source()), record.path("sources").single().path("hash").asText())
        assertFalse(Files.exists(fixture.state.resolve("outputs.tmp")))
    }

    @Test fun `changed retained class bytes cannot reuse stale compiler ownership`() {
        val fixture = Fixture(temporary)
        fixture.seed()
        Files.writeString(fixture.binary(), "unrecorded bytecode")
        assertFailsWith<BuildFailure> { fixture.inventory().reconcile(fixture.pending) }
        assertEquals("unrecorded bytecode", Files.readString(fixture.binary()))
    }

    @Test fun `duplicate fresh owners and duplicate physical output roots are rejected`() {
        val ownerFixture = Fixture(Files.createDirectory(temporary.resolve("owners")))
        ownerFixture.create()
        ownerFixture.emit(listOf(ownerFixture.output(), ownerFixture.output()))
        assertFailsWith<BuildFailure> { ownerFixture.inventory().reconcile(ownerFixture.pending) }

        val binaryFixture = Fixture(Files.createDirectory(temporary.resolve("binaries")))
        binaryFixture.create()
        val secondRoot = Files.createDirectories(binaryFixture.checkout.resolve("second/target/classes"))
        val duplicate = secondRoot.resolve("sample/Owned.class")
        Files.createDirectories(duplicate.parent)
        Files.copy(binaryFixture.binary(), duplicate)
        binaryFixture.emit(listOf(binaryFixture.output()))
        assertFailsWith<BuildFailure> {
            binaryFixture.inventory(listOf(binaryFixture.classes, secondRoot)).reconcile(binaryFixture.pending)
        }
    }

    @Test fun `every physical class needs compiler ownership including classes unrelated to fresh records`() {
        val fixture = Fixture(temporary)
        fixture.seed()
        val unowned = fixture.binary("sample.Unowned")
        Files.writeString(unowned, "unowned bytecode")
        assertFailsWith<BuildFailure> { fixture.inventory().reconcile(fixture.pending) }
        assertTrue(Files.exists(unowned))
    }

    @Test fun `partial deletion of a multiple source output never authorizes destructive pruning`() {
        val fixture = Fixture(temporary)
        fixture.create()
        val second = fixture.source("sample.Second")
        Files.writeString(second, "second source")
        fixture.emit(listOf(fixture.output(sources = listOf(fixture.source(), second))))
        fixture.inventory().reconcile(fixture.pending)
        fixture.clearPending()
        Files.delete(fixture.source())
        assertFailsWith<BuildFailure> { fixture.inventory().prepare() }
        assertTrue(Files.exists(fixture.binary()))
        assertTrue(Files.exists(second))
    }

    @Test fun `untrusted stored paths cannot delete external nonoutput or differently owned classes`() {
        listOf("outside", "nonoutput", "wrong-owner").forEach { kind ->
            val fixture = Fixture(Files.createDirectory(temporary.resolve(kind)))
            fixture.seed()
            val unrelated = when (kind) {
                "outside" -> temporary.resolve("outside.class")
                "nonoutput" -> fixture.checkout.resolve("sources/Preserve.class")
                else -> fixture.binary("sample.Other")
            }
            Files.writeString(unrelated, "preserve")
            val snapshot = MAPPER.readTree(fixture.snapshot.toFile())
            val record = snapshot[0] as ObjectNode
            record.put("classFile", fixture.checkout.relativize(unrelated).toString())
            record.put("classHash", hash(unrelated))
            MAPPER.writeValue(fixture.snapshot.toFile(), snapshot)
            Files.delete(fixture.source())
            assertFailsWith<BuildFailure> { fixture.inventory().prepare() }
            assertEquals("preserve", Files.readString(unrelated))
        }
    }

    @Test fun `canonical output ownership does not authorize following a replaced package symlink outside checkout`() {
        val fixture = Fixture(Files.createDirectory(temporary.resolve("checkout")))
        fixture.seed()
        val external = Files.createDirectory(temporary.resolve("external"))
        Files.copy(fixture.binary(), external.resolve("Owned.class"))
        Files.delete(fixture.binary())
        Files.delete(fixture.binary().parent)
        Files.createSymbolicLink(fixture.binary().parent, external)
        Files.delete(fixture.source())
        assertFailsWith<BuildFailure> { fixture.inventory().prepare() }
        assertTrue(Files.exists(external.resolve("Owned.class")))
    }

    @Test fun `mismatched target or cached bytes refuse retirement rather than deleting a newer artifact`() {
        listOf(false, true).forEach { cached ->
            val fixture = Fixture(Files.createDirectory(temporary.resolve("tampered-$cached")))
            fixture.seed()
            val target = if (cached) fixture.cache() else fixture.binary()
            Files.createDirectories(target.parent)
            Files.writeString(target, "newer bytes")
            Files.delete(fixture.source())
            assertFailsWith<BuildFailure> { fixture.inventory().prepare() }
            assertEquals("newer bytes", Files.readString(target))
        }
    }

    @Test fun `fresh outputs retire obsolete owners from the same recompiled source`() {
        val fixture = Fixture(temporary)
        fixture.seed()
        val replacement = fixture.binary("sample.Replacement")
        Files.writeString(replacement, "replacement bytecode")
        fixture.emit(listOf(fixture.output("sample.Replacement")))
        fixture.inventory().reconcile(fixture.pending)
        assertFalse(Files.exists(fixture.binary()))
        assertTrue(Files.exists(replacement))
        assertEquals("sample.Replacement", fixture.records().single().path("owner").asText())
    }

    @Test fun `unsupported or incomplete compiler documents cannot authorize retirement of previous owners`() {
        listOf("schema", "complete").forEach { field ->
            val fixture = Fixture(Files.createDirectory(temporary.resolve(field)))
            fixture.seed()
            val committed = Files.readAllBytes(fixture.snapshot)
            Files.writeString(fixture.binary("sample.Replacement"), "replacement bytecode")
            fixture.emit(listOf(fixture.output("sample.Replacement")))
            val pending = fixture.pending.resolve("compiler.json")
            val document = MAPPER.readTree(pending.toFile()) as ObjectNode
            if (field == "schema") document.put(field, 2) else document.put(field, false)
            MAPPER.writeValue(pending.toFile(), document)
            assertFailsWith<BuildFailure> { fixture.inventory().reconcile(fixture.pending) }
            assertTrue(Files.exists(fixture.binary()), "Untrusted emission must not retire previous output")
            assertContentEquals(committed, Files.readAllBytes(fixture.snapshot))
        }
    }

    @Test fun `fresh records require nonempty existing in-checkout source ownership with matching hashes`() {
        listOf("empty", "missing", "outside", "hash").forEach { kind ->
            val fixture = Fixture(Files.createDirectory(temporary.resolve(kind)))
            fixture.create()
            val record = fixture.output()
            when (kind) {
                "empty" -> record.putArray("sources")
                "missing" -> Files.delete(fixture.source())
                "outside" -> {
                    val external = temporary.resolve("external.java")
                    Files.writeString(external, "external source")
                    (record.path("sources")[0] as ObjectNode).put("path", external.toString()).put("hash", hash(external))
                }
                else -> (record.path("sources")[0] as ObjectNode).put("hash", "wrong")
            }
            fixture.emit(listOf(record))
            assertFailsWith<BuildFailure> { fixture.inventory().reconcile(fixture.pending) }
        }
    }

    private class Fixture(val checkout: Path) {
        val state: Path = checkout.resolve(".graphfold/inventory")
        val pending: Path = Files.createDirectories(checkout.resolve(".graphfold/pending"))
        val classes: Path = Files.createDirectories(checkout.resolve("target/classes"))
        val snapshot: Path = state.resolve("outputs.json")

        fun inventory(directories: List<Path> = listOf(classes)) = MavenClassInventory(checkout, state, directories)
        fun source(owner: String = "sample.Owned"): Path = checkout.resolve("sources/${owner.replace('.', '/')}.java")
        fun binary(owner: String = "sample.Owned"): Path = classes.resolve(owner.replace('.', '/') + ".class")
        fun cache(): Path = checkout.resolve("target/kotlin-ic/compile/classes/sample/Owned.class")

        fun create() {
            Files.createDirectories(source().parent)
            Files.createDirectories(binary().parent)
            Files.writeString(source(), "source")
            Files.writeString(binary(), "bytecode")
        }

        fun seed() {
            create()
            emit(listOf(output()))
            inventory().reconcile(pending)
            clearPending()
        }

        fun output(owner: String = "sample.Owned", sources: List<Path> = listOf(source())): ObjectNode =
            MAPPER.createObjectNode().put("owner", owner).apply {
                val values = putArray("sources")
                sources.forEach { path -> values.addObject().put("path", path.toString()).put("hash", hash(path)) }
            }

        fun emit(outputs: List<ObjectNode>) {
            val document = MAPPER.createObjectNode().put("schema", 1).put("complete", true).put("runId", "test")
            document.putObject("producer").put("compiler", "fixture")
            document.putArray("outputs").addAll(outputs)
            MAPPER.writeValue(pending.resolve("compiler.json").toFile(), document)
        }

        fun clearPending() { regularFiles(pending).forEach(Files::delete) }
        fun records(): List<JsonNode> = MAPPER.readTree(snapshot.toFile()).toList()
    }

    private companion object {
        val MAPPER = jacksonObjectMapper()
        fun hash(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
    }
}
