package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnmappedArchiveInputTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["jar", "war", "boot"])
    fun `selected archive retains application bytecode without compiler source attribution`(layout: String) {
        val prefix = when (layout) { "war" -> "WEB-INF/classes/"; "boot" -> "BOOT-INF/classes/"; else -> "" }
        val bytes = bytecode()
        val archive = projectionArchive(temporary.resolve(if (layout == "war") "app.war" else "app.jar"),
            mapOf("${prefix}sample/App.class" to bytes))
        val evidence = InputProject(Files.createDirectory(temporary.resolve("compiler")), emptyList(), emptyList(),
            provenanceIssues = listOf("Compiler source mapping is unavailable"))
        val result = prepareArchiveInput(ArchiveInput("selected", listOf(ArchiveEntryInput(archive))),
            evidence, temporary.resolve("prepared"))
        assertTrue(Files.readAllBytes(result.classes.resolve("sample/App.class")).contentEquals(bytes))
        assertTrue(result.compilerEvidence.isEmpty())
        val normalized = result.preparedScope(temporary)
        assertEquals(evidence.provenanceIssues, normalized.provenanceIssues)
        assertTrue(Files.exists(normalized.classes.resolve("sample/App.class")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "malformed"])
    fun `unreadable selected archive is rejected before preparing a static revision`(mode: String) {
        val archive = temporary.resolve("broken.jar")
        if (mode == "malformed") Files.writeString(archive, "not a zip archive")
        val compiler = InputProject(Files.createDirectory(temporary.resolve("compiler")), emptyList(), emptyList())
        val scope = compiler.copy(archiveInput = ArchiveInput("broken", listOf(ArchiveEntryInput(archive))))
        val failure = assertFailsWith<InputFailure> {
            prepareRevision("sha", temporary, compiler.copy(analysisScopes = mapOf("broken" to scope)),
                temporary.resolve("prepared"), DependencyInputCache())
        }
        assertTrue(failure.message.contains(if (mode == "missing") "Missing packaged archive" else "Unreadable packaged archive"),
            failure.message)
    }

    private fun bytecode(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/App", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode(); visitInsn(Opcodes.ICONST_1); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
