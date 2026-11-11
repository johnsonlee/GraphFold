package io.johnsonlee.graphfold.compiler.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.KotlinFrontend
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.backend.jvm.extensions.ClassGenerator
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OriginClassGeneratorTest {
    @TempDir lateinit var temporary: Path

    @Test fun `unnamed copied marker fields stay untrusted while preserving downstream visitation`() {
        val observed = mutableListOf<Pair<String?, Any?>>()
        var ended = false
        val visitor = object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String?, value: Any?) { observed += name to value }
            override fun visitEnd() { ended = true }
        }
        val downstream = assertIs<ClassGenerator>(Proxy.newProxyInstance(
            ClassGenerator::class.java.classLoader, arrayOf(ClassGenerator::class.java),
        ) {
                _, method, _ ->
            when (method.name) {
                "defineClass", "done" -> null
                "visitAnnotation" -> visitor
                else -> error("Unexpected generator callback: ${method.name}")
            }
        })
        val collector = KotlinOriginCollector(temporary.resolve("origins"), "2.1.20", KotlinFrontend.K2, "null-name")
        val generator = OriginClassGenerator(downstream, null, collector, "null-name")
        generator.defineClass(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Copy", null, "java/lang/Object", emptyArray())
        generator.visitAnnotation(KotlinOriginCollector.MARKER, false).apply {
            visit(null, 1)
            visit("runId", "null-name")
            visitEnd()
        }
        generator.done(false)
        collector.finish()
        assertEquals(listOf<Pair<String?, Any?>>(null to 1, "runId" to "null-name"), observed)
        assertTrue(ended)
        val manifest = Files.list(temporary.resolve("origins")).use { it.toList().single() }
        val document = jacksonObjectMapper().readTree(manifest.toFile())
        assertTrue(document["records"].isEmpty, "An unnamed field must not substitute for the required schema")
        assertEquals(listOf("sample.Copy"), document["unresolved"].map { it["owner"].asText() })
    }
}
