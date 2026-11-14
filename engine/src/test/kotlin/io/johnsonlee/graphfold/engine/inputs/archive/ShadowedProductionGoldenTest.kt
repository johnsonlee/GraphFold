package io.johnsonlee.graphfold.engine.inputs.archive

import io.johnsonlee.graphfold.engine.DiagnosticCode
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import io.johnsonlee.graphfold.engine.PreparedAnalysisRequest
import io.johnsonlee.graphfold.engine.Verdict
import io.johnsonlee.graphfold.engine.git.changedFile
import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Effective graph selection cannot discharge changes to another packaged production definition. */
class ShadowedProductionGoldenTest {
    @TempDir lateinit var root: Path

    @ParameterizedTest
    @ValueSource(strings = ["unchanged", "body", "annotation", "resource"])
    fun `only unchanged shadowed production permits the selected control proof`(change: String) {
        val external = projectionArchive(root.resolve("external.jar"), mapOf("sample/Value.class" to definition(1, false)))
        fun revision(name: String, changed: Boolean): PreparedRevision {
            val app = application(external, name, if (changed) 2 else 1)
            val value = if (changed && change == "body") 3 else 2
            val annotated = changed && change == "annotation"
            val resource = if (changed && change == "resource") "changed" else "original"
            val owned = projectionArchive(root.resolve("$name-owned.jar"),
                mapOf("sample/Value.class" to definition(value, annotated), "data.txt" to resource.toByteArray()))
            val input = prepareArchiveInput(ArchiveInput("application", listOf(ArchiveEntryInput(app),
                ArchiveEntryInput(external), ArchiveEntryInput(owned, application = true))),
                InputProject(root.resolve("unused"), emptyList(), emptyList()), root.resolve("$name-input"), 17)
            val scope = input.toStaticScope(root.resolve("$name-scope"))
            return PreparedRevision(name, root, mapOf("application" to scope), input.production)
        }
        val base = revision("base", false)
        val head = revision("head", true)
        fun selected(revision: PreparedRevision): ByteArray = revision.scopes.getValue("application").dependencies
            .firstNotNullOf { readWarArchive(it)["sample/Value.class"] }
        assertContentEquals(definition(1, false), selected(base))
        assertContentEquals(selected(base), selected(head))
        val result = GraphFoldEngine(EngineConfiguration(root.resolve("analysis"))).analyze(PreparedAnalysisRequest(
            base, head, listOf(changedFile("Value.java", "Value.java")), """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: sample.App, callee_name: enabled}}
                    value: false
            """.trimIndent()))
        assertEquals(if (change == "unchanged") Verdict.PASS else Verdict.FAIL, result.verdict, result.toString())
        assertEquals(change != "unchanged", result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED },
            result.toString())
        if (change == "unchanged") assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.UNMATCHED_RULE }, result.toString())
        assertEquals(2, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.matchedCallCount == 1 }, result.toString())
    }

    private fun application(dependency: Path, side: String, treatment: Int): Path {
        val source = Files.createDirectories(root.resolve("$side-source")).resolve("App.java")
        Files.writeString(source, """
            package sample;
            public class App {
                static boolean enabled() { return false; }
                public static int run() { return enabled() ? $treatment : Value.value(); }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("$side-compiled"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "17", "-g:none", "-cp", dependency.toString(), "-d", classes.toString(), source.toString()))
        return projectionArchive(root.resolve("$side-application.jar"),
            mapOf("sample/App.class" to Files.readAllBytes(classes.resolve("sample/App.class"))))
    }

    private fun definition(value: Int, annotated: Boolean): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "sample/Value", null, "java/lang/Object", null)
        if (annotated) visitAnnotation("Ljava/lang/Deprecated;", true).visitEnd()
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode(); visitLdcInsn(value); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
