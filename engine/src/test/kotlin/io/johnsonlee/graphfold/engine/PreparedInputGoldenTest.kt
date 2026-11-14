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
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Core proof consumes normalized files directly: no repository, build adapter, archive or installed runtime. */
class PreparedInputGoldenTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @CsvSource("false,false,PASS", "false,true,FAIL", "true,false,FAIL")
    fun `explicit control coverage determines verdict independently of runtime inactivity`(
        uncontrolled: Boolean, enabled: Boolean, verdict: Verdict,
    ) {
        val base = revision("base", 1, 1)
        val head = revision("head", if (uncontrolled) 1 else 2, if (uncontrolled) 2 else 1)
        val line = if (uncontrolled) 5 else 4
        val change = changedFile("Example.java", "Example.java", edits = listOf(Edit(line - 1, line, line - 1, line)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: Example, callee_name: enabled}}
                    value: $enabled
            """.trimIndent()))
        assertEquals(verdict, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(verdict == Verdict.FAIL, result.findings.isNotEmpty(), result.toString())
        assertEquals(2, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.matchedCallCount == 1 }, result.toString())
    }

    @ParameterizedTest
    @CsvSource("unused,FAIL", "controlled,PASS", "removed-caller,FAIL", "lambda,FAIL", "stream,FAIL",
        "field,FAIL", "field-instance,FAIL", "field-static,FAIL", "nested,FAIL", "nested-controlled,PASS",
        "added-unused,FAIL", "added-controlled,PASS", "added-class,FAIL", "added-public,FAIL")
    fun `private changes require same input selected control evidence rather than absence of calls`(
        mode: String, expected: Verdict,
    ) {
        val base = revision("base", 1, 1, mode, false)
        val head = revision("head", 1, 2, mode, true)
        val lines = when (mode) {
            "lambda", "stream" -> 4..4
            "removed-caller", "added-controlled" -> 4..5
            else -> 5..5
        }
        val change = changedFile("Example.java", "Example.java",
            edits = listOf(Edit(lines.first - 1, lines.last, lines.first - 1, lines.last)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: Example, callee_name: enabled}}
                    value: false
            """.trimIndent()))
        assertEquals(expected, result.verdict, result.toString())
        if (mode == "added-class") {
            // BASE contains only indentation here: native edits must not fabricate enclosing-type attribution.
            // The added class has exact HEAD findings; Example's changed NestMembers still needs output proof.
            val diagnostic = result.diagnostics.single()
            assertEquals(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED, diagnostic.code)
            assertTrue(diagnostic.message.contains(", Example,"), diagnostic.message)
            val locations = result.findings.flatMap { it.locations }
            assertEquals(setOf(Revision.HEAD), locations.map { it.revision }.toSet())
            assertEquals(setOf("Example.java"), locations.map { it.path }.toSet())
            assertEquals(setOf(SourceRange(5, 5)), locations.map { it.line }.toSet())
            assertEquals(setOf(SourceRange(39, 79), SourceRange(5, 81)), locations.map { it.column }.toSet())
        } else {
            assertTrue(result.diagnostics.isEmpty(), result.toString())
        }
        assertEquals(expected == Verdict.FAIL, result.findings.isNotEmpty(), result.toString())
        assertEquals(2, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.matchedCallCount == 1 }, result.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["unused", "added-class"])
    fun `non source changes cannot hide generated private bodies or unused new classes`(mode: String) {
        val base = revision("base", 1, 1, mode, false)
        val head = revision("head", 1, 2, mode, true)
        val change = changedFile("generator.txt", "generator.txt")
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: Example, callee_name: enabled}}
                    value: false
            """.trimIndent()))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        assertTrue(result.findings.isEmpty(), "Generated output changes without changed source must not fabricate source locations")
        assertEquals(2, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.matchedCallCount == 1 }, result.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["controlled", "enabled", "unused", "missing-inventory"])
    fun `optional source provenance permits complete raw input control proofs without source findings`(mode: String) {
        fun raw(revision: PreparedRevision): PreparedRevision {
            val scope = revision.scopes.getValue("application").copy(originDirectory = null, originRunId = null,
                sourceRoots = emptyList(), sourceOutputs = emptyMap(),
                production = if (mode == "missing-inventory") null else revision.inventory)
            return revision.copy(sourceDirectory = temporary.resolve("unavailable-source-${revision.sha}"),
                scopes = mapOf("application" to scope), inventory = if (mode == "missing-inventory") null else revision.inventory)
        }
        val base = raw(revision("base", 1, 1))
        val head = raw(revision("head", if (mode == "unused") 1 else 2, if (mode == "unused") 2 else 1))
        val change = changedFile("Example.java", "Example.java", edits = listOf(Edit(3, 5, 3, 5)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), fold(mode == "enabled")))
        assertEquals(if (mode == "controlled") Verdict.PASS else Verdict.FAIL, result.verdict, result.toString())
        assertEquals(2, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.matchedCallCount == 1 }, result.toString())
        assertTrue(result.findings.isEmpty(), "Raw evidence must not invent source locations")
        assertTrue(result.diagnostics.none { it.stage == AnalysisStage.SOURCE }, result.toString())
        if (mode == "controlled") assertTrue(result.diagnostics.isEmpty(), result.toString())
        if (mode == "enabled" || mode == "unused") {
            assertEquals(listOf(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED), result.diagnostics.map { it.code }, result.toString())
            assertTrue(result.reasons.all { it.code == ReasonCode.SURVIVING_DIFFERENCE }, result.toString())
            assertEquals(0, result.metadata.provedObligations)
        }
        if (mode == "missing-inventory") {
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_INVENTORY_UNAVAILABLE }, result.toString())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-manifest", "missing-sources", "missing-compiler-output", "invalid-compiler-output",
        "missing-inventory", "identical-bytes", "provenance-issues", "required-class-hashes", "compiler-evidence-without-run"])
    fun `mapping omissions retain actual graph folding and explicit precision diagnostics`(mode: String) {
        fun withoutMapping(revision: PreparedRevision): PreparedRevision {
            val original = revision.scopes.getValue("application")
            val missing = temporary.resolve("absent-${revision.sha}")
            val scope = original.copy(originDirectory = null,
                originRunId = if (mode in setOf("provenance-issues", "required-class-hashes", "compiler-evidence-without-run")) {
                    null
                } else original.originRunId,
                provenanceIssues = if (mode == "provenance-issues") listOf("Adapter could not verify source ownership") else emptyList(),
                requireOriginClassHashes = mode == "required-class-hashes",
                sourceRoots = if (mode == "missing-sources") listOf(missing) else original.sourceRoots,
                compilerEvidence = optionalCompilerEvidence(mode, missing, original),
                production = if (mode == "missing-inventory") null else original.production)
            return revision.copy(sourceDirectory = if (mode == "missing-sources") missing else revision.sourceDirectory,
                scopes = mapOf("application" to scope), inventory = if (mode == "missing-inventory") null else revision.inventory)
        }
        val base = withoutMapping(revision("base", 1, 1))
        val head = withoutMapping(revision("head", if (mode == "identical-bytes") 1 else 2, 1))
        val path = if (mode == "missing-inventory") "inputs.txt" else "Example.java"
        val change = changedFile(path, path, edits = listOf(Edit(3, 4, 3, 4)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), fold(false)))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(2, result.ruleAudits.size, result.toString())
        assertTrue(result.ruleAudits.all { it.matchedCallCount == 1 }, result.toString())
        assertTrue(result.diagnostics.any { it.stage == AnalysisStage.SOURCE }, result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.ANALYSIS_FAILED || it.stage == AnalysisStage.FOLD },
            result.toString())
        assertTrue(result.findings.isEmpty(), "Missing mapping must not fabricate an uncontrolled source location: $result")
        assertMappingDiagnostic(mode, result)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `added and removed static scopes compare their actual contents against empty inputs`(removed: Boolean) {
        val present = revision("present", 1, 1, "unused")
        val absent = PreparedRevision("absent", Files.createDirectories(temporary.resolve("absent-source")), emptyMap(),
            ContentInventory(emptyMap(), emptyMap()))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(if (removed) present else absent, if (removed) absent else present,
                listOf(changedFile("inputs.txt", "inputs.txt")), fold(false)))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(2, result.ruleAudits.size, result.toString())
        assertEquals(listOf(0, 1), result.ruleAudits.mapNotNull { it.matchedCallCount }.sorted(), result.toString())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.ANALYSIS_FAILED || it.code == DiagnosticCode.UNVERIFIED_ORIGIN },
            result.toString())
    }

    private fun fold(enabled: Boolean): String = """
        version: 1
        folds:
          - match: {CallSite: {callee_class: Example, callee_name: enabled}}
            value: $enabled
    """.trimIndent()

    private fun assertMappingDiagnostic(mode: String, result: AnalysisResult) {
        val message = when (mode) {
            "invalid-compiler-output" -> "Unsupported class file major version"
            "provenance-issues" -> "Adapter could not verify source ownership"
            "required-class-hashes" -> "Compiler origin manifest is missing"
            else -> null
        }
        if (message != null) assertTrue(result.diagnostics.any {
            it.code == DiagnosticCode.UNVERIFIED_ORIGIN && message in it.message
        }, result.toString())
    }

    @Test fun `a later controlled scenario cannot erase an earlier raw input proof failure`() {
        fun raw(revision: PreparedRevision): PreparedRevision = revision.copy(scopes = revision.scopes.mapValues { (_, scope) ->
            scope.copy(originDirectory = null, originRunId = null, sourceRoots = emptyList(), sourceOutputs = emptyMap())
        })
        val base = raw(revision("base", 1, 1))
        val head = raw(revision("head", 2, 1))
        val change = changedFile("Example.java", "Example.java", edits = listOf(Edit(3, 4, 3, 4)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), """
                scenarios:
                  - name: enabled
                    folds:
                      - match: {CallSite: {callee_class: Example, callee_name: enabled}}
                        value: true
                  - name: disabled
                    folds:
                      - match: {CallSite: {callee_class: Example, callee_name: enabled}}
                        value: false
            """.trimIndent()))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(0, result.metadata.provedObligations)
        assertEquals(4, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.matchedCallCount == 1 }, result.toString())
        assertTrue(result.findings.isEmpty(), "Raw bytecode proof cannot invent source attribution")
        assertTrue(result.reasons.any { it.scenario == "enabled" && it.code == ReasonCode.SURVIVING_DIFFERENCE }, result.toString())
        assertTrue(result.reasons.any { it.scenario == "disabled" && it.code == ReasonCode.FOLDED_EQUIVALENT }, result.toString())
    }

    @Test fun `mapped source and README obligations retain failure across a later passing scenario`() {
        val base = revision("base", 1, 1)
        val head = revision("head", 2, 1)
        val changes = listOf(
            changedFile("Example.java", "Example.java", edits = listOf(Edit(3, 4, 3, 4))),
            changedFile("README.md", "README.md"),
        )
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, changes, """
                scenarios:
                  - name: enabled
                    folds:
                      - match: {CallSite: {callee_class: Example, callee_name: enabled}}
                        value: true
                  - name: disabled
                    folds:
                      - match: {CallSite: {callee_class: Example, callee_name: enabled}}
                        value: false
            """.trimIndent()))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(2, result.metadata.obligations)
        assertEquals(0, result.metadata.provedObligations)
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(listOf("enabled"), result.findings.single().scenarios)
        assertTrue(result.findings.single().locations.all { it.path == "Example.java" }, result.toString())
        listOf("Example.java", "README.md").forEach { path ->
            assertEquals(ReasonCode.SURVIVING_DIFFERENCE,
                result.reasons.single { it.scenario == "enabled" && it.headPath == path }.code)
            assertEquals(ReasonCode.FOLDED_EQUIVALENT,
                result.reasons.single { it.scenario == "disabled" && it.headPath == path }.code)
        }
        assertEquals(4, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
    }

    @Test fun `raw equality cannot accept contradictory caller supplied class placement evidence`() {
        fun placed(input: PreparedRevision, archive: String): PreparedRevision {
            val inventory = requireNotNull(input.inventory).copy(classPlacements = mapOf("Example.class" to setOf(archive)))
            return input.copy(inventory = inventory, scopes = input.scopes.mapValues { (_, scope) ->
                scope.copy(originDirectory = null, originRunId = null, production = inventory)
            })
        }
        // Deliberately inconsistent caller evidence: entries stay equal while the claimed placement changes.
        val base = placed(revision("base", 1, 1), "artifact/0/Example.class")
        val head = placed(revision("head", 1, 1), "artifact/1/Example.class")
        val source = head.sourceDirectory.resolve("Example.java")
        Files.writeString(source, Files.readString(source).replace("public class Example {", "public class Example { // relocated"))
        val change = changedFile("Example.java", "Example.java", edits = listOf(Edit(0, 1, 0, 1)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), fold(false)))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(0, result.metadata.provedObligations)
        assertTrue(result.diagnostics.any {
            it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED && "classPlacement/Example.class" in it.message
        }, result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
    }

    @Test fun `raw callback gaps remain when added declarations prevent literal inventory proof`() {
        fun raw(input: PreparedRevision): PreparedRevision = input.copy(scopes = input.scopes.mapValues { (_, scope) ->
            scope.copy(originDirectory = null, originRunId = null)
        })
        val base = raw(revision("base", 1, 1, "external-callback", false))
        val head = raw(revision("head", 2, 1, "external-callback", true))
        val change = changedFile("Example.java", "Example.java", edits = listOf(Edit(3, 5, 3, 5)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), fold(false)))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(0, result.metadata.provedObligations)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.INCOMPLETE_GRAPH }, result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.ANALYSIS_FAILED }, result.toString())
        assertTrue(result.findings.isNotEmpty(), result.toString())
        assertTrue(result.findings.flatMap { it.locations }.all { it.path == "Example.java" }, result.toString())
        assertTrue(result.findings.flatMap { it.evidence }.any { "Example.value()I" in it.description }, result.toString())
        assertTrue(result.findings.flatMap { it.evidence }.any { "Example.additional()I" in it.description }, result.toString())
        assertTrue(result.reasons.any { it.code == ReasonCode.ANALYSIS_INCOMPLETE }, result.toString())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
    }

    private fun optionalCompilerEvidence(mode: String, missing: Path, original: StaticScope): List<CompilerEvidence> {
        if (mode == "invalid-compiler-output") {
            Files.createDirectories(missing)
            val bytes = Files.readAllBytes(original.classes.resolve("Example.class"))
            // An invalid version in optional compiler evidence must never replace valid application bytecode.
            bytes[6] = 0x7f; bytes[7] = 0xff.toByte()
            Files.write(missing.resolve("Example.class"), bytes)
        }
        return if (mode in setOf("missing-compiler-output", "invalid-compiler-output", "compiler-evidence-without-run"))
            listOf(CompilerEvidence(missing, missing.resolve("origin.json"))) else emptyList()
    }

    @Test fun `a request with changed inputs but no prepared static scopes cannot silently pass`() {
        val base = revision("base", 1, 1).copy(scopes = emptyMap())
        val head = revision("head", 2, 1).copy(scopes = emptyMap())
        val change = changedFile("Example.java", "Example.java", edits = listOf(Edit(3, 4, 3, 4)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), fold(false)))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(1, result.metadata.obligations)
        assertEquals(0, result.metadata.provedObligations)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.UNVERIFIED_BUILD_INPUT && it.blocking }, result.toString())
        assertTrue(result.findings.isEmpty(), "Absent scopes cannot invent source attribution")
        assertTrue(result.ruleAudits.isEmpty(), "Absent scopes cannot claim graph or fold execution")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `raw equality checks actual bytes even when claimed inventories match`(changed: Boolean) {
        val original = revision("base", 1, 1)
        fun raw(revision: PreparedRevision): PreparedRevision = revision.copy(scopes = revision.scopes.mapValues { (_, scope) ->
            scope.copy(originDirectory = null, originRunId = null, production = original.inventory)
        }, inventory = original.inventory)
        val base = raw(original)
        val head = raw(revision("head", 1, if (changed) 2 else 1))
        val change = changedFile("Example.java", "Example.java", edits = listOf(Edit(4, 5, 4, 5)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), fold(false)))
        assertEquals(if (changed) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
        assertEquals(if (changed) 0 else 1, result.metadata.provedObligations)
        val reason = if (changed) ReasonCode.SURVIVING_DIFFERENCE else ReasonCode.BUILD_OUTPUT_UNCHANGED
        assertTrue(result.reasons.all { it.code == reason }, result.toString())
        val audit = if (changed) RuleSelectionStatus.SELECTED else RuleSelectionStatus.NOT_EXECUTED
        assertTrue(result.ruleAudits.all { it.selectionStatus == audit }, result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.UNMATCHED_RULE }, result.toString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `raw source evidence remains separate from a failed resource obligation`(controlled: Boolean) {
        fun raw(revision: PreparedRevision, resource: String): PreparedRevision {
            val originalInventory = requireNotNull(revision.inventory)
            val inventory = originalInventory.copy(
                entries = originalInventory.entries + ("resource/data.txt" to resource),
                nonClassEntries = mapOf("resource/data.txt" to resource))
            return revision.copy(scopes = revision.scopes.mapValues { (_, scope) ->
                scope.copy(originDirectory = null, originRunId = null, production = inventory)
            }, inventory = inventory)
        }
        val base = raw(revision("base", 1, 1), "before")
        val head = raw(revision("head", if (controlled) 2 else 1, if (controlled) 1 else 2), "after")
        val line = if (controlled) 4 else 5
        val changes = listOf(changedFile("Example.java", "Example.java",
            edits = listOf(Edit(line - 1, line, line - 1, line))), changedFile("data.txt", "data.txt"))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, changes, fold(false)))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(2, result.metadata.obligations)
        assertEquals(0, result.metadata.provedObligations)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        val sourceReason = if (controlled) ReasonCode.FOLDED_EQUIVALENT else ReasonCode.SURVIVING_DIFFERENCE
        assertEquals(1, result.reasons.count { it.code == sourceReason && it.headPath == "Example.java" }, result.toString())
        assertEquals(1, result.reasons.count { it.code == ReasonCode.ANALYSIS_INCOMPLETE && it.headPath == "data.txt" })
        assertEquals(if (controlled) 0 else 1, result.findings.size, result.toString())
        assertTrue(result.findings.flatMap { it.locations }.all { it.path == "Example.java" }, result.toString())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mixed input failure requires a generic diagnostic only for unlocated generated differences`(generated: Boolean) {
        fun raw(revision: PreparedRevision): PreparedRevision = revision.copy(scopes = revision.scopes.mapValues { (_, scope) ->
            scope.copy(originDirectory = null, originRunId = null)
        })
        val base = raw(revision("base", 1, 1))
        var head = raw(revision("head", 1, 2))
        if (generated) {
            val classes = head.scopes.getValue("application").classes
            val source = Files.writeString(temporary.resolve("Generated.java"),
                "public class Generated { public static int value() { return 3; } }")
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString()))
            val original = requireNotNull(head.inventory)
            val generatedDigest = digest(classes.resolve("Generated.class"))
            val inventory = original.copy(entries = original.entries + ("class/Generated.class" to generatedDigest))
            head = head.copy(inventory = inventory, scopes = head.scopes.mapValues { (_, scope) -> scope.copy(production = inventory) })
        }
        val changes = listOf(changedFile("Example.java", "Example.java", edits = listOf(Edit(4, 5, 4, 5))),
            changedFile("generator.txt", "generator.txt"))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, changes, fold(false)))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(2, result.metadata.obligations)
        assertEquals(0, result.metadata.provedObligations)
        assertEquals(1, result.findings.size, result.toString())
        assertTrue(result.findings.flatMap { it.locations }.all { it.path == "Example.java" }, result.toString())
        assertEquals(generated, result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        assertTrue(result.diagnostics.all { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `missing optional source files cannot prevent raw folding or fabricate source findings`(controlled: Boolean) {
        fun withoutSource(input: PreparedRevision): PreparedRevision {
            Files.delete(input.sourceDirectory.resolve("Example.java"))
            return input.copy(scopes = input.scopes.mapValues { (_, scope) ->
                scope.copy(originDirectory = null, originRunId = null)
            })
        }
        val base = withoutSource(revision("base", 1, 1))
        val head = withoutSource(revision("head", if (controlled) 2 else 1, if (controlled) 1 else 2))
        val line = if (controlled) 4 else 5
        val change = changedFile("Example.java", "Example.java", edits = listOf(Edit(line - 1, line, line - 1, line)))
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("analysis"))).analyze(
            PreparedAnalysisRequest(base, head, listOf(change), fold(false)))
        assertEquals(if (controlled) Verdict.PASS else Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), "Absent source bytes must not become invented source locations")
        assertEquals(if (controlled) 1 else 0, result.metadata.provedObligations)
        assertEquals(if (controlled) emptyList() else listOf(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED),
            result.diagnostics.map { it.code }, result.toString())
        assertEquals(2, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
    }

    private fun revision(
        name: String, controlled: Int, unused: Int, mode: String = "public", changed: Boolean = false,
    ): PreparedRevision {
        val source = Files.createDirectories(temporary.resolve(name).resolve("source"))
        val classes = Files.createDirectories(temporary.resolve(name).resolve("classes"))
        val expression = when (mode) {
            "controlled" -> "enabled() ? unused() : 0"
            "nested-controlled" -> "enabled() ? Hidden.price() : 0"
            "added-controlled" -> if (changed) "enabled() ? unused() : 0" else "enabled() ? 1 : 0"
            "removed-caller" -> if (changed) "enabled() ? $controlled : 0" else "unused() + (enabled() ? $controlled : 0)"
            else -> "enabled() ? $controlled : 0"
        }
        val body = when (mode) {
            "external-callback" -> "return external.getAsInt() + $controlled + (enabled() ? 1 : 0);"
            "lambda" -> "java.util.function.IntSupplier ignored = () -> $unused; return enabled() ? 1 : 0;"
            "stream" -> "java.util.stream.Stream.of(1).map(value -> value + $unused); return enabled() ? 1 : 0;"
            else -> "return $expression;"
        }
        val declaration = declaration(mode, unused, changed)
        val file = Files.writeString(source.resolve("Example.java"), """
            public class Example {
                public static boolean flag;
                public static boolean enabled() { return flag; }
                public static int value() { $body }
                $declaration
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), file.toString()))
        // This named-only Java fixture emits no anonymous classes requiring origin records.
        val origins = Files.createDirectories(temporary.resolve(name).resolve("origins"))
        Files.writeString(origins.resolve("javac.json"), """
            {"schema":1,"complete":true,"runId":"$name","producer":{"compiler":"javac","backend":"JVM","version":"17"},
             "records":[],"unresolved":[]}
        """.trimIndent())
        val entries = Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).toList() }
            .associate { "class/${classes.relativize(it)}" to digest(it) }
        val inventory = ContentInventory(entries, emptyMap())
        val scope = StaticScope(classes, listOf(source), emptyList(), originDirectory = origins, originRunId = name,
            sourceOutputs = mapOf(source to listOf(classes)), production = inventory)
        return PreparedRevision(name, source, mapOf("application" to scope), inventory)
    }
    private fun declaration(mode: String, unused: Int, changed: Boolean): String = when {
        mode == "external-callback" -> "public static java.util.function.IntSupplier external; " +
            if (changed) "public static int additional() { return 3; }" else ""
        mode.startsWith("added-") && !changed -> ""
        mode == "added-class" -> "private static class Additional { private static int value() { return 9; } }"
        mode == "field" -> "private static final int unused = $unused;"
        mode == "field-instance" -> "private int unused = $unused;"
        mode == "field-static" -> "private static int unused = $unused;"
        mode in setOf("nested", "nested-controlled") ->
            "private static class Hidden { private static int price() { return $unused; } }"
        else -> {
            val value = if (mode in setOf("lambda", "stream")) 1 else unused
            val access = if (mode in setOf("public", "added-public")) "public" else "private"
            "$access static int unused() { return $value; }"
        }
    }

}
