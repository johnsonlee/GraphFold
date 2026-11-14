package io.johnsonlee.graphfold.engine.inputs.archive

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptionalProvenanceArchiveTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["valid", "invalid-json", "invalid-path", "input-failure"])
    fun `compiler inventory failures preserve independently packaged application bytes`(mode: String) {
        val classes = Files.createDirectory(temporary.resolve("compiler"))
        val issues = mutableListOf<String>()
        val inventory = optionalProvenance(issues) {
            when (mode) {
                "invalid-json" -> jacksonObjectMapper().readTree("{unfinished")
                "invalid-path" -> Path.of("\u0000")
                "input-failure" -> throw InputFailure("Compiler inventory is incomplete")
                else -> classes
            }
        }
        assertEquals(mode == "valid", inventory != null)
        val expected = when (mode) {
            "invalid-json" -> "Cannot read compiler provenance"
            "invalid-path" -> "Invalid compiler provenance"
            "input-failure" -> "Compiler inventory is incomplete"
            else -> null
        }
        if (expected == null) assertTrue(issues.isEmpty()) else assertTrue(issues.single().contains(expected), issues.toString())
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/App", null, "java/lang/Object", null)
            visitEnd()
        }.toByteArray()
        val archive = projectionArchive(temporary.resolve("app.jar"), mapOf("sample/App.class" to bytes))
        val prepared = prepareArchiveInput(ArchiveInput("app", listOf(ArchiveEntryInput(archive))),
            InputProject(classes, emptyList(), emptyList(), provenanceIssues = issues), temporary.resolve("prepared"))
        val scope = prepared.preparedScope(temporary)
        assertContentEquals(bytes, Files.readAllBytes(scope.classes.resolve("sample/App.class")))
        assertEquals(issues, scope.provenanceIssues)
        assertTrue(scope.sourceRoots.isEmpty())
    }
}
