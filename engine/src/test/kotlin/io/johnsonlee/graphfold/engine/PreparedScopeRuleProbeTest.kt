package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.git.changedFile
import io.johnsonlee.graphfold.engine.inputs.CompilerEvidence
import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.eclipse.jgit.diff.Edit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unchanged scopes contribute real rule-selection evidence, never coverage for another scope's changed code. */
class PreparedScopeRuleProbeTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["matched", "native-control", "absent", "unknown"])
    fun `unchanged scope rule probe cannot excuse uncontrolled changes or invent global nonmatches`(mode: String) {
        val base = revision("base", 1, mode)
        val head = revision("head", 2, mode)
        val change = changedFile("a/Changed.java", "a/Changed.java", edits = listOf(Edit(1, 2, 1, 2)))
        // A has no matching rows. B exposes the scalar projection error before any typed selection is published.
        val projection = if (mode == "unknown") "cs.callee_name" else "cs"
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), """
                version: 1
                folds:
                  - select: |
                      MATCH (cs:CallSite {callee_class: "Control", callee_name: "enabled"}) RETURN $projection
                    value: false
            """.trimIndent()))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        // Scope A contributes its changed source declaration and its changed-output inventory proof.
        // Scope B contributes selection audits only, with no additional obligations or verdict reasons.
        assertEquals(2, result.metadata.obligations, result.toString())
        assertEquals(2, result.reasons.size, result.toString())
        assertTrue(result.reasons.all { it.scenario == "baseline [scope a]" &&
            it.code == ReasonCode.SURVIVING_DIFFERENCE }, result.toString())
        assertTrue(result.reasons.any { "covered by verified source findings" in it.message }, result.toString())
        assertEquals(0, result.metadata.provedObligations)
        assertTrue(result.findings.isNotEmpty(), result.toString())
        assertEquals(setOf("a/Changed.java"), result.findings.flatMap { it.locations }.map { it.path }.toSet())
        assertTrue(result.reasons.any { it.code == ReasonCode.SURVIVING_DIFFERENCE }, result.toString())
        val own = result.ruleAudits.filter { it.scenario == "baseline [scope a]" }
        val probe = result.ruleAudits.filter { it.scenario == "baseline [scope b]" }
        assertEquals(2, own.size, result.toString())
        assertTrue(own.all { it.matchedCallCount == 0 }, result.toString())
        assertEquals(2, probe.size, result.toString())
        verifyProbe(mode, probe, result)
    }

    @Test fun `verified source-only edits do not invent unmatched controls when selection was unnecessary`() {
        fun verified(name: String): PreparedRevision {
            val revision = revision(name, 1, "unchanged-source")
            val a = revision.scopes.getValue("a")
            val evidence = CompilerEvidence(a.classes, requireNotNull(a.originDirectory).resolve("javac.json"))
            return revision.copy(scopes = revision.scopes + ("a" to a.copy(compilerEvidence = listOf(evidence))))
        }
        val base = verified("base")
        val head = verified("head")
        assertEquals(base.scopes.getValue("a").production, head.scopes.getValue("a").production)
        val change = changedFile("a/Changed.java", "a/Changed.java", edits = listOf(Edit(1, 2, 1, 2)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: Control, callee_name: enabled}}
                    value: false
            """.trimIndent()))
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(1, result.metadata.obligations)
        assertEquals(1, result.metadata.provedObligations)
        assertEquals(setOf(ReasonCode.BUILD_OUTPUT_UNCHANGED), result.reasons.map { it.code }.toSet())
        assertEquals(2, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.scenario == "baseline [scope a]" && it.matchedCallCount == null &&
            "graph selection was not executed" in it.message.orEmpty() }, result.toString())
    }

    private fun verifyProbe(mode: String, probe: List<RuleAudit>, result: AnalysisResult) {
        val unmatched = result.diagnostics.filter { it.code == DiagnosticCode.UNMATCHED_RULE }
        when (mode) {
            "matched", "native-control" -> {
                assertTrue(probe.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
                assertTrue(result.diagnostics.isEmpty(), result.toString())
            }
            "absent" -> {
                assertTrue(probe.all { it.matchedCallCount == 0 }, result.toString())
                assertEquals(1, unmatched.size, result.toString())
                assertTrue("matched no input scope" in unmatched.single().message)
            }
            "unknown" -> {
                assertTrue(probe.any { it.selectionStatus == RuleSelectionStatus.FAILED && it.matchedCallCount == null &&
                    "CallSite" in it.message.orEmpty() }, result.toString())
                assertTrue(unmatched.isEmpty(), "Unknown selection cannot establish a global nonmatch: $result")
                assertTrue(result.diagnostics.any { it.code == DiagnosticCode.INCOMPLETE_GRAPH &&
                    "unchanged input scope" in it.message }, result.toString())
            }
        }
    }

    private fun revision(name: String, value: Int, mode: String): PreparedRevision {
        val root = Files.createDirectories(temporary.resolve(name))
        val source = Files.createDirectories(root.resolve("source"))
        val comment = if (mode == "unchanged-source") " // $name" else ""
        val a = scope(root, source, "a", "Changed", "public class Changed {\n public static int value() { return $value; }$comment\n}")
        val declaration = if (mode == "native-control") "public static native boolean enabled();"
            else "public static boolean enabled() { return true; }"
        val body = if (mode == "absent") "return 0;" else "return enabled() ? 1 : 0;"
        val b = scope(root, source, "b", "Control", "public class Control { $declaration public static int value() { $body } }")
        val entries = a.production!!.entries.mapKeys { "a/${it.key}" } + b.production!!.entries.mapKeys { "b/${it.key}" }
        return PreparedRevision(name, source, linkedMapOf("a" to a, "b" to b), ContentInventory(entries, emptyMap()))
    }

    private fun scope(root: Path, checkout: Path, name: String, owner: String, text: String): StaticScope {
        val source = Files.createDirectories(checkout.resolve(name))
        val classes = Files.createDirectories(root.resolve("classes-$name"))
        val file = Files.writeString(source.resolve("$owner.java"), text)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), file.toString()))
        val origins = Files.createDirectories(root.resolve("origins-$name"))
        Files.writeString(origins.resolve("javac.json"), """
            {"schema":1,"complete":true,"runId":"$name","producer":{"compiler":"javac","backend":"JVM","version":"17"},
             "records":[],"unresolved":[]}
        """.trimIndent())
        val entries = Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).toList() }
            .associate { "class/${classes.relativize(it)}" to digest(it) }
        return StaticScope(classes, listOf(source), emptyList(), originDirectory = origins, originRunId = name,
            sourceOutputs = mapOf(source to listOf(classes)), production = ContentInventory(entries, emptyMap()))
    }
}
