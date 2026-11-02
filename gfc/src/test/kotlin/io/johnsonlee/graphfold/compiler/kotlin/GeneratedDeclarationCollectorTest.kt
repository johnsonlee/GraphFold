package io.johnsonlee.graphfold.compiler.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.Kind
import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.KotlinFrontend
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Category evidence cannot replace an invalid copied origin or a missing completion callback. */
class GeneratedDeclarationCollectorTest {
    @TempDir lateinit var temporary: Path

    @Test fun `complete marker-free generated implementation is accepted with verified enclosing emission`() {
        val fixture = fixture()
        fixture.collector.completed("sample/Implementation")
        fixture.collector.finish()
        val document = document()
        assertTrue(document["unresolved"].isEmpty, document.toString())
        assertEquals(listOf("sample.Example"), document["records"].map { it["owner"].asText() })
        assertEquals(setOf("sample.Example", "sample.Implementation"), document["outputs"].map { it["owner"].asText() }.toSet())
    }

    @ParameterizedTest
    @ValueSource(strings = ["stale", "hash", "duplicate", "unfinished"])
    fun `valid synthetic category cannot hide corrupt copied markers or unfinished output`(failure: String) {
        val fixture = fixture()
        val origin = fixture.origin
        val marker = mapOf<String, Any?>(
            "schema" to 1, "runId" to "synthetic-test", "originKind" to Kind.DECLARATION.name,
            "source" to origin.source, "sourceHash" to origin.sourceHash, "start" to origin.start, "end" to origin.end,
        )
        when (failure) {
            "stale" -> fixture.collector.annotation("sample/Implementation", marker + ("runId" to "previous-compilation"))
            "hash" -> fixture.collector.annotation("sample/Implementation", marker + ("sourceHash" to "corrupt"))
            "duplicate" -> repeat(2) { fixture.collector.annotation("sample/Implementation", marker) }
        }
        if (failure != "unfinished") fixture.collector.completed("sample/Implementation")
        fixture.collector.finish()
        val document = document()
        assertTrue(document["complete"].asBoolean())
        assertEquals(listOf("sample.Example"), document["records"].map { it["owner"].asText() })
        assertEquals(listOf("sample.Implementation"), document["unresolved"].map { it["owner"].asText() })
    }

    private fun fixture(): Fixture {
        val source = Files.writeString(temporary.resolve("Example.kt"), "class Example { fun run() {} }")
        val collector = KotlinOriginCollector(temporary.resolve("origins"), "2.1.20", KotlinFrontend.K2, "synthetic-test")
        val origin = requireNotNull(collector.capture("sample/Example", source.toString(), 0,
            Files.readString(source).length, Kind.DECLARATION))
        collector.emitted("sample/Example", origin, missingIr = false, anonymous = false)
        collector.completed("sample/Example")
        collector.emitted("sample/Implementation", null, missingIr = true, anonymous = false)
        val owner = classFile("Example") {
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE, "run", "()V", null, null).visitEnd()
        }
        val generated = classFile("Implementation") {
            visitOuterClass("sample/Example", "run", "()V")
            visitAnnotation("Lkotlin/Metadata;", true).apply { visit("k", 3); visitEnd() }
        }
        // Independently establish that the metadata/enclosing-method category gate really accepts this
        // output; failure below must come from the collector's marker/completion checks instead.
        assertEquals(setOf("sample/Implementation"), generatedDeclarationOwners(
            listOf(Files.readAllBytes(owner), Files.readAllBytes(generated)), setOf("sample/Example"),
        ))
        collector.output("sample.Example", listOf(source), owner)
        collector.output("sample.Implementation", listOf(source), generated)
        return Fixture(collector, origin)
    }

    private fun classFile(name: String, populate: ClassWriter.() -> Unit): Path {
        val writer = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/$name", null, "java/lang/Object", null)
            populate()
            visitEnd()
        }
        return Files.write(temporary.resolve("$name.class"), writer.toByteArray())
    }

    private fun document() = Files.list(temporary.resolve("origins")).use { files ->
        jacksonObjectMapper().readTree(files.toList().single().toFile())
    }

    private data class Fixture(val collector: KotlinOriginCollector, val origin: KotlinOriginCollector.Origin)
}
