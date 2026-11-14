package io.johnsonlee.graphfold.engine.origin

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.digest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

class InlineEvidenceReaderTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["valid", "incomplete", "schema", "missing-array", "class-hash", "source-hash", "outside",
        "source-span", "missing-method", "missing-target", "copy-span", "copy-hash", "duplicate-copy", "duplicate-declaration"])
    fun `inline compiler evidence validates exact emitted classes sources and intervals`(mode: String) {
        val fixture = fixture(mode != "incomplete")
        mutate(fixture.document, mode)
        if (mode !in setOf("valid", "incomplete")) {
            assertFailsWith<IllegalArgumentException> { readInlineEvidence(fixture.document, fixture.classes, fixture.root.toRealPath()) }
        } else {
            val evidence = readInlineEvidence(fixture.document, fixture.classes, fixture.root.toRealPath())
            val actual = evidence.declarations.single()
            assertEquals(listOf("Example.bump(I)I", fixture.source.toRealPath(), fixture.sourceHash, 0, 20, mode == "valid"),
                listOf(actual.method, actual.source, actual.sourceHash, actual.start, actual.end, actual.complete))
            val interval = evidence.copies.single()
            assertEquals(listOf("Example.bump(I)I", "Example.run(I)I", 0, 2, fixture.hash),
                listOf(interval.target, interval.caller, interval.startOpcode, interval.endOpcode, interval.classHash))
            assertEquals(mapOf("Example" to fixture.hash), evidence.classes)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ".Example", "Example.", "bad/Owner", "bad\\Owner"])
    fun `inline class ownership cannot escape or reinterpret its archive path`(owner: String) {
        val fixture = fixture()
        entry(fixture.document, "classes").put("owner", owner)
        assertRejected(fixture)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "renamed"])
    fun `inline class identity requires one actual matching class file`(mode: String) {
        val fixture = fixture()
        when (mode) {
            "missing" -> Files.delete(fixture.classes.resolve("Example.class"))
            "duplicate" -> fixture.document.withArray("classes").add(entry(fixture.document, "classes").deepCopy())
            "renamed" -> {
                Files.copy(fixture.classes.resolve("Example.class"), fixture.classes.resolve("Alias.class"))
                entry(fixture.document, "classes").put("owner", "Alias")
            }
        }
        assertRejected(fixture)
    }

    @ParameterizedTest
    @ValueSource(strings = ["start", "end", "complete"])
    fun `declaration coordinates and completeness cannot be defaulted when absent`(field: String) {
        val fixture = fixture()
        entry(fixture.document, "declarations").remove(field)
        assertRejected(fixture)
    }

    @ParameterizedTest
    @CsvSource("-1,20", "0,0", "20,1")
    fun `declaration spans must be nonempty forward physical source intervals`(start: Int, end: Int) {
        val fixture = fixture()
        entry(fixture.document, "declarations").put("start", start).put("end", end)
        assertRejected(fixture)
    }

    @ParameterizedTest
    @ValueSource(strings = ["startOpcode", "endOpcode", "caller"])
    fun `copy evidence requires explicit opcode coordinates and an emitted caller`(field: String) {
        val fixture = fixture()
        entry(fixture.document, "copies").remove(field)
        assertRejected(fixture)
    }

    @ParameterizedTest
    @CsvSource("-1,2", "0,0", "2,1")
    fun `copy intervals cannot be negative empty or reversed`(start: Int, end: Int) {
        val fixture = fixture()
        entry(fixture.document, "copies").put("startOpcode", start).put("endOpcode", end)
        assertRejected(fixture)
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3])
    fun `copy bounds count opcodes rather than debug labels and line records`(end: Int) {
        val fixture = fixture(debug = true)
        entry(fixture.document, "copies").put("endOpcode", end)
        if (end == 2) {
            val evidence = readInlineEvidence(fixture.document, fixture.classes, fixture.root.toRealPath())
            assertEquals(2, evidence.copies.single().endOpcode)
        } else assertRejected(fixture)
    }

    private fun assertRejected(fixture: EvidenceFixture) {
        assertFailsWith<IllegalArgumentException> { readInlineEvidence(fixture.document, fixture.classes, fixture.root.toRealPath()) }
    }

    private fun fixture(complete: Boolean = true, debug: Boolean = false): EvidenceFixture {
        val root = Files.createDirectory(temporary.resolve("checkout"))
        val source = Files.writeString(root.resolve("Example.kt"), "private inline fun bump(x: Int) = x + 1")
        val classes = Files.createDirectory(temporary.resolve("classes"))
        val writer = ClassWriter(0).apply {
            visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "Example", null, "java/lang/Object", null)
            listOf("bump", "run").forEach { identityMethod(it, debug) }
            visitEnd()
        }
        val file = Files.write(classes.resolve("Example.class"), writer.toByteArray())
        val hash = digest(file)
        val sourceHash = digest(source)
        val mapper = jacksonObjectMapper()
        val document = mapper.valueToTree<ObjectNode>(mapOf(
            "schema" to 1,
            "classes" to listOf(mapOf("owner" to "Example", "classHash" to hash)),
            "declarations" to listOf(mapOf("method" to "Example.bump(I)I", "source" to source.toString(),
                "sourceHash" to sourceHash, "start" to 0, "end" to 20, "complete" to complete)),
            "copies" to listOf(mapOf("target" to "Example.bump(I)I", "caller" to "Example.run(I)I",
                "startOpcode" to 0, "endOpcode" to 2, "classHash" to hash))))
        return EvidenceFixture(root, source, classes, hash, sourceHash, document)
    }

    private fun ClassWriter.identityMethod(name: String, debug: Boolean) {
        visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, name, "(I)I", null, null).apply {
            visitCode()
            if (debug) {
                val start = Label()
                visitLabel(start); visitLineNumber(1, start)
            }
            visitVarInsn(Opcodes.ILOAD, 0); visitInsn(Opcodes.IRETURN); visitMaxs(1, 1); visitEnd()
        }
    }

    private data class EvidenceFixture(
        val root: Path, val source: Path, val classes: Path, val hash: String, val sourceHash: String, val document: ObjectNode,
    )

    private fun mutate(document: ObjectNode, mode: String) {
        val declaration = entry(document, "declarations")
        val copy = entry(document, "copies")
        when (mode) {
            "schema" -> document.put("schema", 2)
            "missing-array" -> document.remove("copies")
            "class-hash" -> entry(document, "classes").put("classHash", "stale")
            "source-hash" -> declaration.put("sourceHash", "stale")
            "outside" -> declaration.put("source", Files.writeString(temporary.resolve("outside.kt"), "outside").toString())
            "source-span" -> declaration.put("end", 1000)
            "missing-method" -> declaration.put("method", "Example.missing(I)I")
            "missing-target" -> copy.put("target", "Example.missing(I)I")
            "copy-span" -> copy.put("endOpcode", 1000)
            "copy-hash" -> copy.put("classHash", "stale")
            "duplicate-copy" -> document.withArray("copies").add(copy.deepCopy())
            "duplicate-declaration" -> document.withArray("declarations").add(declaration.deepCopy())
        }
    }

    private fun entry(document: ObjectNode, name: String): ObjectNode =
        requireNotNull(document.path(name)[0] as? ObjectNode)

}
