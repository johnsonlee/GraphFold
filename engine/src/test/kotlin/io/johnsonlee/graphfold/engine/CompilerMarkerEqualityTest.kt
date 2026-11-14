package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.git.changedFile
import io.johnsonlee.graphfold.engine.origin.readOrigins
import io.johnsonlee.graphfold.engine.testing.CompilerOriginPlugin
import io.johnsonlee.graphfold.engine.testing.KotlinFixtureCompiler
import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.inputs.archive.normalizeDependencyInputs
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompilerMarkerEqualityTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `verified compiler markers preserve equality without erasing generated method differences`(changed: Boolean) {
        val base = compile("base", 1)
        val head = compile("head", if (changed) 2 else 1)
        assertNotEquals(base.inventory, head.inventory, "Real GFC run and source identities produce distinct marker bytes")
        listOf(base, head).forEach { revision ->
            val origins = readOrigins(revision.scopes.getValue("application"), revision.sourceDirectory)
            assertTrue(origins.diagnostics.isEmpty(), origins.diagnostics.toString())
        }
        val change = changedFile("generator.txt", "generator.txt")
        val fold = """
            version: 1
            folds:
              - match: {CallSite: {callee_class: Example, callee_name: enabled}}
                value: false
        """.trimIndent()
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), fold))
        assertEquals(if (changed) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), "A generator input is not a changed source declaration")
        assertEquals(if (changed) 0 else 1, result.metadata.provedObligations)
        assertEquals(if (changed) listOf(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED) else emptyList(),
            result.diagnostics.map { it.code }, result.toString())
        val selection = if (changed) RuleSelectionStatus.SELECTED else RuleSelectionStatus.NOT_EXECUTED
        assertTrue(result.ruleAudits.all { it.selectionStatus == selection }, result.toString())
    }

    private fun compile(name: String, value: Int): PreparedRevision {
        val root = Files.createDirectories(temporary.resolve(name))
        val source = Files.createDirectory(root.resolve("source"))
        val output = Files.createDirectory(root.resolve("output"))
        val classes = Files.createDirectory(output.resolve("classes"))
        val file = Files.writeString(source.resolve("Example.kt"), """
            class Example {
                fun enabled(): Boolean = true
                fun controlled(): Int = if (enabled()) 1 else 0
                fun generated(): Int = $value
            }
        """.trimIndent())
        val compilerDependencies = KotlinFixtureCompiler(Duration.ofMinutes(2)).compile(source, output, classes, listOf(file))
        val dependencies = normalizeDependencyInputs(compilerDependencies, output.resolve("dependencies"),
            variantThreshold = BUILD_JDK, applicationClasses = classes)
        val entries = Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).toList() }.associate {
            val prefix = if (it.toString().endsWith(".class")) "class" else "resource"
            "$prefix/${classes.relativize(it)}" to digest(it)
        } + dependencies.mapIndexed { index, dependency -> "dependency/$index" to digest(dependency) }
        val inventory = ContentInventory(entries, entries.filterKeys { !it.startsWith("class/") })
        val scope = StaticScope(classes, listOf(source), dependencies, originDirectory = output.resolve("origins"),
            originRunId = CompilerOriginPlugin.runId(output), requireOriginClassHashes = true,
            sourceOutputs = mapOf(source to listOf(classes)), production = inventory)
        return PreparedRevision(name, source, mapOf("application" to scope), inventory)
    }
    private companion object {
        const val BUILD_JDK = 17
    }

    private fun digest(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it) }
}
