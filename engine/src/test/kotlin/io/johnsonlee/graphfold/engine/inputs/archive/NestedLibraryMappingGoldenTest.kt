package io.johnsonlee.graphfold.engine.inputs.archive

import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import io.johnsonlee.graphfold.engine.Verdict
import io.johnsonlee.graphfold.engine.git.changedFile
import io.johnsonlee.graphfold.engine.PreparedAnalysisRequest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Project-library ownership survives loss of optional compiler associations in either archive layout. */
class NestedLibraryMappingGoldenTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @CsvSource("boot,false,PASS", "boot,true,FAIL", "war,false,PASS", "war,true,FAIL")
    fun `nested business controls fold independently of compiler association`(layout: String, enabled: Boolean, verdict: Verdict) {
        listOf(false, true).forEach { mapped ->
            val root = Files.createDirectories(temporary.resolve("mapping-$mapped"))
            fun revision(name: String, value: Int): io.johnsonlee.graphfold.engine.inputs.PreparedRevision {
                val directory = Files.createDirectories(root.resolve(name))
                val library = projectionArchive(directory.resolve("business.jar"), mapOf("sample/Quote.class" to quote(value)))
                val prefix = if (layout == "boot") "BOOT-INF" else "WEB-INF"
                val archive = projectionArchive(directory.resolve(if (layout == "boot") "app.jar" else "app.war"),
                    mapOf("$prefix/classes/sample/App.class" to emptyClass(), "$prefix/lib/business.jar" to Files.readAllBytes(library)))
                val compiler = Files.createDirectories(directory.resolve("compiler"))
                val input = ArchiveInput("application", listOf(ArchiveEntryInput(archive)),
                    producerArchives = if (mapped) mapOf(library to listOf(compiler)) else emptyMap(),
                    applicationArchives = setOf(library))
                val project = InputProject(compiler, emptyList(), emptyList(), archiveInput = input)
                return prepareRevision(name, directory, project, directory.resolve("prepared"), DependencyInputCache())
            }
            val base = revision("base", 2)
            val head = revision("head", 3)
            listOf(base, head).forEach { revision ->
                assertTrue(revision.scopes.values.all { Files.isRegularFile(it.classes.resolve("sample/Quote.class")) })
            }
            val result = GraphFoldEngine(EngineConfiguration(root.resolve("analysis"))).analyze(
                PreparedAnalysisRequest(base, head, listOf(changedFile("Quote.java", "Quote.java")), """
                    version: 1
                    folds:
                      - match: {CallSite: {callee_class: sample.Quote, callee_name: enabled}}
                        value: $enabled
                """.trimIndent()))
            assertEquals(verdict, result.verdict, result.toString())
            assertEquals(2, result.ruleAudits.size, result.toString())
            assertTrue(result.ruleAudits.all { it.matchedCallCount == 1 }, result.toString())
            assertTrue(result.findings.isEmpty(), "Compiler association alone must not invent source locations")
            if (!enabled) assertTrue(result.diagnostics.isEmpty(), result.toString())
        }
    }

    private fun emptyClass(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/App", null, "java/lang/Object", null)
        visitEnd()
    }.toByteArray()

    private fun quote(value: Int): ByteArray = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Quote", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "enabled", "()Z", null, null).apply {
            visitCode(); visitInsn(Opcodes.ICONST_0); visitInsn(Opcodes.IRETURN); visitMaxs(0, 0); visitEnd()
        }
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            val fallback = Label()
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "sample/Quote", "enabled", "()Z", false)
            visitJumpInsn(Opcodes.IFEQ, fallback)
            visitLdcInsn(value); visitInsn(Opcodes.IRETURN)
            visitLabel(fallback); visitInsn(Opcodes.ICONST_1); visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
