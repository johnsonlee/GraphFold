package io.johnsonlee.graphfold.compiler.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jetbrains.kotlin.descriptors.Modality
import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.Kind
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.name.Name
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinInlineCaptureTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `repeated emission accepts identical evidence but rejects conflicting source identity`(conflicting: Boolean) {
        var origin = KotlinOriginCollector.Origin("/Quote.kt", "first-source-hash", 0, 20, Kind.DECLARATION)
        val capture = KotlinInlineCapture { _, _ -> origin }
        val intClass = IrFactoryImpl.buildClass { name = Name.identifier("Int") }
        val intType = IrSimpleTypeImpl(intClass.symbol, false, emptyList(), emptyList())
        val declaration = IrFactoryImpl.buildFun {
            name = Name.identifier("quote")
            isInline = true
            returnType = intType
        }
        val marker = buildVariable(declaration, 0, 20, IrDeclarationOrigin.DEFINED,
            Name.identifier(INLINE_MARKER_PREFIX + "quote"), intType)
        declaration.body = IrFactoryImpl.createBlockBody(0, 20).apply { statements.add(marker) }
        capture.method("sample/Quotes", declaration, "quote", "()I")
        if (conflicting) origin = origin.copy(sourceHash = "different-source-hash")
        capture.method("sample/Quotes", declaration, "quote", "()I")
        val output = Files.write(temporary.resolve("Quotes.class"), quoteClass())
        val document = jacksonObjectMapper().readTree(capture.evidence(mapOf("sample.Quotes" to output), setOf("sample.Quotes")))
        val record = document["declarations"].single()
        assertEquals(!conflicting, record["complete"].asBoolean(), document.toString())
        assertEquals("first-source-hash", record["sourceHash"].asText(), "Conflicting callbacks must not replace the first identity")
        assertTrue(document["copies"].isEmpty, "Repeated callbacks must not fabricate copy witnesses")
    }

    private fun quoteClass(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Quotes", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "quote", "()I", null, null).apply {
            visitCode(); visitInsn(Opcodes.ICONST_1); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()

    @Test fun `repeated bodyless declaration emission cannot invent inline source or copy evidence`() {
        var sourceRequests = 0
        val capture = KotlinInlineCapture { _, _ -> sourceRequests++; null }
        val declaration = IrFactoryImpl.buildFun {
            name = Name.identifier("quote")
            modality = Modality.ABSTRACT
        }
        repeat(2) { capture.method("sample/Quotes", declaration, "quote", "()I") }
        val document = jacksonObjectMapper().readTree(capture.evidence(emptyMap(), setOf("sample.Quotes")))
        assertEquals(0, sourceRequests, "A bodyless ordinary declaration needs no fabricated inline source identity")
        assertTrue(document["declarations"].isEmpty)
        assertTrue(document["copies"].isEmpty)
    }
}
