package io.johnsonlee.graphfold.engine.origin.kotlin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.origin.protocol.OriginProtocol.Kind
import io.johnsonlee.graphfold.engine.origin.protocol.OriginProtocol.KotlinFrontend
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinOriginCollectorTest {
    @TempDir lateinit var temporary: Path

    @Test fun `output inventory includes named anonymous and synthetic classes independently of origin records`() {
        val source = Files.writeString(temporary.resolve("Example.kt"), "class Example { fun callback() = object {} }")
        val owners = listOf("sample.Example", "sample.Example\$callback\$1", "sample.Example\$DefaultImpls")
        val collector = collector()
        collector.start()
        assertTrue(document()["outputs"].isEmpty)
        owners.forEach { collector.output(it, listOf(source)) }
        collector.finish()

        val result = document()
        assertTrue(result["complete"].asBoolean())
        assertTrue(result["records"].isEmpty)
        assertEquals(owners.toSet(), result["outputs"].map { it["owner"].asText() }.toSet())
        result["outputs"].forEach { output ->
            val input = output["sources"].single()
            assertEquals(source.toRealPath().toString(), input["path"].asText())
            assertEquals(sha256(Files.readAllBytes(source)), input["hash"].asText())
        }
    }

    @Test fun `multifile output retains each physical source hash once including escaped filenames`() {
        val first = Files.writeString(temporary.resolve("First\"Part.kt"), "// 😀\r\nfun first() = 1\r\n")
        val second = Files.writeString(temporary.resolve("SecondPart.kt"), "\uFEFFfun second() = 2\n")
        val collector = collector()
        collector.output("sample.CombinedFacade", listOf(first, second, first, second))
        collector.finish()

        val output = document()["outputs"].single()
        assertEquals("sample.CombinedFacade", output["owner"].asText())
        assertEquals(2, output["sources"].size())
        val actual = output["sources"].associate { it["path"].asText() to it["hash"].asText() }
        val expected = listOf(first, second).associate { it.toRealPath().toString() to sha256(Files.readAllBytes(it)) }
        assertEquals(expected, actual)
    }

    @Test fun `output without compiler source associations remains explicit rather than disappearing`() {
        val collector = collector()
        collector.output("sample.Unverifiable", emptyList())
        collector.finish()

        val output = document()["outputs"].single()
        assertEquals("sample.Unverifiable", output["owner"].asText())
        assertTrue(output["sources"].isArray)
        assertTrue(output["sources"].isEmpty)
    }

    @Test fun `missing output source prevents a completed ownership manifest`() {
        val collector = collector()
        collector.start()
        assertFailsWith<java.io.IOException> {
            collector.output("sample.Missing", listOf(temporary.resolve("absent.kt")))
        }
        assertFalse(document()["complete"].asBoolean())
        assertTrue(document()["outputs"].isEmpty)
    }

    @ParameterizedTest
    @ValueSource(strings = ["directory", "invalidUtf8"])
    fun `unreadable or malformed source cannot become verified output ownership`(input: String) {
        val source = if (input == "directory") Files.createDirectory(temporary.resolve("Source.kt")) else {
            Files.write(temporary.resolve("Source.kt"), byteArrayOf(INVALID_UTF8_LEAD, '('.code.toByte()))
        }
        val collector = collector()
        collector.start()
        val failure = assertFailsWith<IllegalArgumentException> { collector.output("sample.Invalid", listOf(source)) }
        assertTrue(failure.message.orEmpty().contains("Compiler output source is unreadable"))
        assertFalse(document()["complete"].asBoolean())
        assertTrue(document()["outputs"].isEmpty)
    }

    @Test fun `direct emission records exact UTF16 source and completes only after finalization`() {
        val text = "// 😀\nobject : Runnable { override fun run() {} }"
        val source = Files.writeString(temporary.resolve("Example.kt"), text)
        val collector = collector()
        collector.start()
        assertFalse(document()["complete"].asBoolean())
        val origin = requireNotNull(collector.capture("sample/Owner", source.toString(), text.indexOf("object"), text.length, ANONYMOUS))
        collector.emitted("sample/Owner", origin, false, true)
        collector.completed("sample/Owner")
        collector.finish()
        val result = document()
        assertTrue(result["complete"].asBoolean())
        assertEquals("run-one", result["runId"].asText())
        assertTrue(result["unresolved"].isEmpty)
        val record = result["records"].single()
        assertEquals("sample.Owner", record["owner"].asText())
        assertEquals(text.indexOf("object"), record["allocation"]["start"].asInt())
        assertEquals(text.length, record["allocation"]["end"].asInt())
        assertEquals(record["allocation"], record["declaration"])
        assertEquals(sha256(Files.readAllBytes(source)), record["sourceHash"].asText())
    }

    @Test fun `inline copies preserve shared definition provenance and emitted enclosing context`() {
        val source = Files.writeString(temporary.resolve("Example.kt"), "object : Runnable { override fun run() {} }")
        val collector = collector()
        val origin = requireNotNull(collector.capture("Original", source.toString(), 0, Files.readString(source).length, ANONYMOUS))
        for (owner in listOf("sample/FirstCopy", "sample/SecondCopy")) {
            collector.emitted(owner, null, true, false)
            collector.annotation(owner, marker(origin))
            collector.enclosing(owner, "sample/Caller", "invoke", "()V")
            collector.completed(owner)
        }
        collector.finish()
        val records = document()["records"].toList()
        assertEquals(2, records.size)
        assertEquals(records.first()["declaration"], records.last()["declaration"])
        assertTrue(records.all { it["provenance"].asText() == "INLINE_COPY" })
        assertTrue(records.all { it["originKind"].asText() == ANONYMOUS.name })
        assertTrue(records.all { it["enclosingOwner"].asText() == "sample.Caller" })
        assertTrue(records.all { it["enclosingMethod"].asText() == "invoke" && it["enclosingDescriptor"].asText() == "()V" })
    }

    @Test fun `stale corrupt absent duplicate and unfinished copy provenance remain explicit gaps`() {
        val source = Files.writeString(temporary.resolve("Example.kt"), "object {}")
        val collector = collector()
        val origin = requireNotNull(collector.capture("Original", source.toString(), 0, 9, ANONYMOUS))
        val changes = mapOf(
            "Stale" to (marker(origin) + ("runId" to "other-run")),
            "WrongHash" to (marker(origin) + ("sourceHash" to "invalid")),
            "Malformed" to (marker(origin) + ("start" to "not an offset")),
            "WrongSchema" to (marker(origin) + ("schema" to 2)),
        )
        changes.forEach { (owner, attributes) ->
            collector.emitted(owner, null, true, false)
            collector.annotation(owner, attributes)
            collector.completed(owner)
        }
        collector.emitted("Missing", null, true, false)
        collector.completed("Missing")
        collector.emitted("Duplicate", origin, false, true)
        collector.annotation("Duplicate", marker(origin))
        collector.completed("Duplicate")
        collector.emitted("Unfinished", origin, false, true)
        collector.finish()
        assertTrue(document()["records"].isEmpty)
        assertEquals(changes.keys + setOf("Missing", "Duplicate", "Unfinished"),
            document()["unresolved"].map { it["owner"].asText() }.toSet())
    }

    @Test fun `missing physical ranges block anonymous objects but do not require generated named classes`() {
        val source = Files.writeString(temporary.resolve("Example.kt"), "object {}")
        val collector = collector()
        val invalid = collector.capture("Anonymous", source.toString(), -1, 9, ANONYMOUS)
        collector.emitted("Anonymous", invalid, false, true)
        collector.completed("Anonymous")
        val inverted = collector.capture("Inverted", source.toString(), 5, 1, ANONYMOUS)
        collector.emitted("Inverted", inverted, false, true)
        collector.completed("Inverted")
        val absent = collector.capture("MissingFile", temporary.resolve("absent.kt").toString(), 0, 1, ANONYMOUS)
        collector.emitted("MissingFile", absent, false, true)
        collector.completed("MissingFile")
        collector.emitted("GeneratedNamedClass", null, false, false)
        collector.completed("GeneratedNamedClass")
        collector.finish()
        assertEquals(setOf("Anonymous", "Inverted", "MissingFile"), document()["unresolved"].map { it["owner"].asText() }.toSet())
        assertTrue(document()["records"].isEmpty)
    }

    @Test fun `one compilation cannot assign two emissions to the same JVM owner`() {
        val collector = collector()
        collector.emitted("Duplicate", null, true, false)
        assertFailsWith<IllegalStateException> { collector.emitted("Duplicate", null, true, false) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["originKind", "source", "sourceHash", "start", "end"])
    fun `copied marker missing any required field remains unresolved`(field: String) {
        val source = Files.writeString(temporary.resolve("Example.kt"), "object {}")
        val collector = collector()
        val origin = requireNotNull(collector.capture("Original", source.toString(), 0, 9, ANONYMOUS))
        collector.emitted("Copy", null, true, false)
        collector.annotation("Copy", marker(origin) - field)
        collector.completed("Copy")
        collector.finish()
        assertTrue(document()["records"].isEmpty)
        assertEquals("Copy", document()["unresolved"].single()["owner"].asText())
    }

    @Test fun `copied markers reject foreign kinds blank fields and invalid physical ranges`() {
        val source = Files.writeString(temporary.resolve("Example.kt"), "object {}")
        val collector = collector()
        val origin = requireNotNull(collector.capture("Original", source.toString(), 0, 9, ANONYMOUS))
        val invalid = mapOf(
            "ForeignKind" to ("originKind" to "LAMBDA"),
            "BlankKind" to ("originKind" to ""),
            "BlankSource" to ("source" to ""),
            "MissingFile" to ("source" to temporary.resolve("absent.kt").toString()),
            "BlankHash" to ("sourceHash" to ""),
            "NegativeStart" to ("start" to -1),
            "NegativeEnd" to ("end" to -1),
            "EndOutsideFile" to ("end" to 10),
            "StartAfterEnd" to ("start" to 10),
        )
        invalid.forEach { (owner, replacement) ->
            collector.emitted(owner, null, true, false)
            collector.annotation(owner, marker(origin) + replacement)
            collector.completed(owner)
        }
        collector.finish()
        assertTrue(document()["records"].isEmpty)
        assertEquals(invalid.keys, document()["unresolved"].map { it["owner"].asText() }.toSet())
    }

    @Test fun `valid named declaration marker supports copied backend classes and nullable enclosing method`() {
        val source = Files.writeString(temporary.resolve("Example.kt"), "class Example")
        val collector = collector()
        val origin = requireNotNull(collector.capture("Original", source.toString(), 0, 13, Kind.DECLARATION))
        collector.emitted("Copy", null, true, false)
        collector.annotation("Copy", marker(origin))
        collector.enclosing("Copy", "sample/Outer", null, null)
        collector.completed("Copy")
        collector.finish()
        assertTrue(document()["unresolved"].isEmpty)
        val record = document()["records"].single()
        assertEquals("DECLARATION", record["originKind"].asText())
        assertEquals("sample.Outer", record["enclosingOwner"].asText())
        assertTrue(record["enclosingMethod"].isNull && record["enclosingDescriptor"].isNull)
    }

    @Test fun `compiler metadata JSON round trips physical filenames and run IDs containing escape characters`() {
        val characters = "quote\" slash\\ newline\n return\r tab\t control\u0001"
        val source = Files.writeString(temporary.resolve("$characters.kt"), "object {}")
        val collector = KotlinOriginCollector(temporary.resolve("origins"), "2.1.20", KotlinFrontend.K2, characters)
        val origin = requireNotNull(collector.capture("Owner", source.toString(), 0, 9, ANONYMOUS))
        collector.emitted("Owner", origin, false, true)
        collector.completed("Owner")
        collector.finish()
        assertEquals(characters, document()["runId"].asText())
        assertEquals(source.toRealPath().toString(), document()["records"].single()["source"].asText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r", "\r\n\n\r"])
    fun `normalized compiler boundaries become physical UTF16 boundaries exactly once`(lineEnding: String) {
        val text = "// 😀\t" + lineEnding + "object {}" + lineEnding
        val normalized = text.replace("\r\n", "\n")
        val source = Files.writeString(temporary.resolve("Example.kt"), text)
        val collector = collector()
        val origin = requireNotNull(collector.capture("Original", source.toString(),
            normalized.indexOf("object"), normalized.indexOf("object") + 9, ANONYMOUS))
        assertEquals(text.indexOf("object"), origin.start)
        assertEquals(text.indexOf("object") + 9, origin.end)
        collector.emitted("Copy", null, true, false)
        collector.annotation("Copy", marker(origin))
        collector.completed("Copy")
        collector.finish()
        assertTrue(document()["unresolved"].isEmpty)
        val record = document()["records"].single()
        assertEquals(origin.start, record["declaration"]["start"].asInt())
        assertEquals(origin.end, record["declaration"]["end"].asInt())
    }

    @Test fun `IR range beyond normalized length cannot borrow extra CRLF bytes`() {
        val text = "\r\nobject {}\r\n"
        val source = Files.writeString(temporary.resolve("Example.kt"), text)
        val collector = collector()
        val invalid = collector.capture("Invalid", source.toString(), 1, text.length, ANONYMOUS)
        collector.emitted("Invalid", invalid, false, true)
        collector.completed("Invalid")
        collector.finish()
        assertTrue(document()["records"].isEmpty)
        assertEquals("Invalid", document()["unresolved"].single()["owner"].asText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "\uFEFF"])
    fun `only an initial BOM is omitted from compiler coordinates while embedded BOM stays physical`(prefix: String) {
        val body = "object { val text = \"\uFEFF\" }"
        val text = prefix + body + "\r\n"
        val source = Files.writeString(temporary.resolve("Example.kt"), text)
        val collector = collector()
        val origin = requireNotNull(collector.capture("Original", source.toString(), 0, body.length, ANONYMOUS))
        assertEquals(prefix.length, origin.start)
        assertEquals(prefix.length + body.length, origin.end)
        assertEquals(body, text.substring(origin.start, origin.end))
        collector.emitted("Copy", null, true, false)
        collector.annotation("Copy", marker(origin))
        collector.completed("Copy")
        collector.finish()
        assertTrue(document()["unresolved"].isEmpty)
        assertEquals(origin.start, document()["records"].single()["declaration"]["start"].asInt())
        assertEquals(origin.end, document()["records"].single()["declaration"]["end"].asInt())
    }

    @Test fun `compiler range cannot count the initial BOM as an extra source unit`() {
        val text = "\uFEFFobject {}"
        val source = Files.writeString(temporary.resolve("Example.kt"), text)
        val collector = collector()
        val invalid = collector.capture("Invalid", source.toString(), 0, text.length, ANONYMOUS)
        collector.emitted("Invalid", invalid, false, true)
        collector.completed("Invalid")
        collector.finish()
        assertTrue(document()["records"].isEmpty)
        assertEquals("Invalid", document()["unresolved"].single()["owner"].asText())
    }

    private fun collector() = KotlinOriginCollector(temporary.resolve("origins"), "2.1.20", KotlinFrontend.K2, "run-one")
    private fun document(): JsonNode = Files.list(temporary.resolve("origins")).use { files ->
        jacksonObjectMapper().readTree(files.filter { it.toString().endsWith(".json") }.toList().single().toFile())
    }
    private fun marker(origin: KotlinOriginCollector.Origin): Map<String, Any> = mapOf(
        "schema" to 1, "runId" to "run-one", "originKind" to origin.kind.name,
        "source" to origin.source, "sourceHash" to origin.sourceHash, "start" to origin.start, "end" to origin.end,
    )
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val ANONYMOUS = Kind.ANONYMOUS_OBJECT
        const val INVALID_UTF8_LEAD: Byte = -61
    }
}
