package io.johnsonlee.graphfold.compiler.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineCopyEvidenceTest {
    @TempDir lateinit var temporary: Path
    private val source = InlineSourceIdentity("/Example.kt", "source-hash", 0, 20, "helper")
    private val target = InlineMethodCapture("sample.Example.helper()I", "helper", source, true, emptyList(), true)
    private val caller = InlineMethodCapture("sample.Example.run()I", "run", null, false, listOf(source), true)

    @Test fun `copy ranges are bound to compiler source targets and exact emitted class bytes`() {
        val document = evidence("valid")
        assertTrue(document["declarations"].single()["complete"].asBoolean(), document.toString())
        val copy = document["copies"].single()
        assertEquals(target.method, copy["target"].asText())
        assertEquals(caller.method, copy["caller"].asText())
        assertEquals(0, copy["startOpcode"].asInt())
        assertEquals(1, copy["endOpcode"].asInt())
        assertEquals(document["classes"].single()["classHash"], copy["classHash"])
        assertEquals(source.source, document["declarations"].single()["source"].asText())
        assertEquals(source.hash, document["declarations"].single()["sourceHash"].asText())
    }

    @Test fun `an uncalled inline declaration has no fabricated copy witness`() {
        val document = evidence("uncalled")
        assertTrue(document["declarations"].single()["complete"].asBoolean(), document.toString())
        assertTrue(document["copies"].isEmpty)
    }

    @Test fun `unrelated abstract method has no copy scope and does not invalidate observed concrete copies`() {
        val document = evidence("abstract-member")
        assertTrue(document["declarations"].single()["complete"].asBoolean(), document.toString())
        assertEquals(caller.method, document["copies"].single()["caller"].asText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent-scope", "extra-scope", "empty-scope", "unknown-scope", "uncaptured-call", "unresolved-target",
        "forged-marker", "conflicting-caller", "ambiguous-name", "invalid-class", "missing-class", "wrong-owner", "unreported-class",
        "duplicate-method", "duplicate-source", "duplicate-scope", "unknown-inline-scope", "missing-caller",
        "invalid-scope-type", "forged-declaration-marker", "conflicting-declaration", "missing-declaration", "noninstruction-scope-start",
        "noninstruction-scope-end", "reversed-scope"])
    fun `incomplete or ambiguous copy evidence cannot authorize removing a declaration`(mode: String) {
        val document = evidence(mode)
        assertFalse(document["declarations"].first()["complete"].asBoolean(), document.toString())
    }

    @Test fun `ordinary code emits no inline evidence claims`() {
        val document = jacksonObjectMapper().readTree(inlineEvidence(listOf(caller), emptySet(), emptyMap(), emptySet()))
        assertTrue(document["declarations"].isEmpty)
        assertTrue(document["copies"].isEmpty)
        assertTrue(document["classes"].isEmpty)
    }

    @Test fun `inline declaration without source identity cannot fabricate a proof claim`() {
        val path = temporary.resolve("unmapped.class")
        val bytes = bytecode("valid")
        Files.write(path, bytes)
        val document = jacksonObjectMapper().readTree(inlineEvidence(
            listOf(target.copy(source = null), caller), emptySet(), mapOf("sample.Example" to path), setOf("sample.Example")))
        assertTrue(document["declarations"].isEmpty)
        assertTrue(document["copies"].isEmpty)
        assertTrue(bytes.contentEquals(Files.readAllBytes(path)), "Missing source evidence must not rewrite emitted bytecode")
    }

    private fun evidence(mode: String): com.fasterxml.jackson.databind.JsonNode {
        val path = temporary.resolve("$mode.class")
        if (mode != "missing-class") Files.write(path, fixtureBytes(mode))
        val actualCaller = caller.copy(
            targets = when (mode) {
                "uncalled", "uncaptured-call" -> emptyList()
                "unresolved-target" -> listOf(null)
                "duplicate-scope" -> listOf(source, source)
                else -> caller.targets
            }, safeMarkers = mode != "forged-marker")
        val declaration = target.copy(safeMarkers = mode != "forged-declaration-marker")
        val methods = listOf(declaration) + if (mode == "missing-caller") emptyList() else listOf(actualCaller)
        val conflicts = when (mode) {
            "conflicting-caller" -> setOf(caller.method)
            "conflicting-declaration" -> setOf(target.method)
            else -> emptySet()
        }
        val owners = setOf("sample.Example") + if (mode == "unreported-class") setOf("sample.HiddenCopy") else emptySet()
        val json = inlineEvidence(methods + additionalDeclarations(mode), conflicts, mapOf("sample.Example" to path), owners)
        return jacksonObjectMapper().readTree(json)
    }

    private fun additionalDeclarations(mode: String): List<InlineMethodCapture> = when (mode) {
        "ambiguous-name" -> listOf(target.copy(method = "sample.Other.helper()I", source = source.copy(source = "/Other.kt")))
        "duplicate-source" -> listOf(target.copy(method = "sample.Example.other()I", name = "other"))
        else -> emptyList()
    }

    private fun ClassWriter.helperBody(value: Int) {
        visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "helper", "()I", null, null).apply {
            visitCode(); visitInsn(value); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
    }

    private fun MethodVisitor.recordScope(mode: String, start: Label, end: Label) {
        if (mode in setOf("uncalled", "absent-scope")) return
        val suffix = when (mode) {
            "unknown-scope" -> "\\1"
            "unknown-inline-scope" -> "\$iv"
            else -> ""
        }
        val name = INLINE_MARKER_PREFIX + "helper" + suffix
        val descriptor = if (mode == "invalid-scope-type") "J" else "I"
        val first = if (mode == "reversed-scope") end else start
        val last = if (mode in setOf("empty-scope", "reversed-scope")) start else end
        visitLocalVariable(name, descriptor, null, first, last, 0)
        if (mode in setOf("extra-scope", "duplicate-scope")) visitLocalVariable(name, "I", null, start, end, 1)
    }

    private fun fixtureBytes(mode: String): ByteArray = when (mode) {
        "invalid-class" -> byteArrayOf(1)
        "noninstruction-scope-start", "noninstruction-scope-end" -> malformedScopeOffset(bytecode(mode), mode)
        else -> bytecode(mode)
    }

    /** Keep executable instructions intact, but place a debug scope endpoint inside BIPUSH's operand. */
    private fun malformedScopeOffset(bytes: ByteArray, mode: String): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.skipNBytes(8)
        val count = input.readUnsignedShort()
        var localVariableTable = -1
        for (index in 1 until count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> if (input.readUTF() == "LocalVariableTable") localVariableTable = index
                7 -> input.readUnsignedShort()
                else -> error("Unexpected constant pool tag in this no-reference fixture: $tag")
            }
        }
        assertTrue(localVariableTable > 0)
        // Exactly one local-variable record: u2 attribute name, u4 length=12, u2 record count=1.
        val header = byteArrayOf((localVariableTable ushr 8).toByte(), localVariableTable.toByte(), 0, 0, 0, 12, 0, 1)
        val matches = (0..bytes.size - header.size).filter { start ->
            header.indices.all { bytes[start + it] == header[it] }
        }
        val offset = matches.single() + header.size
        assertEquals(0, bytes[offset + 1].toInt())
        assertEquals(2, bytes[offset + 3].toInt())
        return bytes.copyOf().also {
            // start=1,length=1 preserves end=2; start=0,length=1 instead makes end=1 invalid.
            if (mode == "noninstruction-scope-start") it[offset + 1] = 1
            it[offset + 3] = 1
        }
    }

    private fun bytecode(mode: String): ByteArray = ClassWriter(0).apply {
        val access = Opcodes.ACC_PUBLIC or if (mode == "abstract-member") Opcodes.ACC_ABSTRACT else 0
        visit(Opcodes.V17, access, if (mode == "wrong-owner") "sample/Other" else "sample/Example",
            null, "java/lang/Object", null)
        if (mode == "abstract-member") {
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "contract", "()I", null, null).visitEnd()
        }
        if (mode != "missing-declaration") helperBody(Opcodes.ICONST_1)
        if (mode == "duplicate-method") helperBody(Opcodes.ICONST_2)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()I", null, null).apply {
            val start = Label()
            val end = Label()
            visitCode(); visitLabel(start)
            if (mode.startsWith("noninstruction-scope-")) visitIntInsn(Opcodes.BIPUSH, 1) else visitInsn(Opcodes.ICONST_1)
            visitLabel(end); visitInsn(Opcodes.IRETURN)
            recordScope(mode, start, end)
            visitMaxs(1, 2); visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
