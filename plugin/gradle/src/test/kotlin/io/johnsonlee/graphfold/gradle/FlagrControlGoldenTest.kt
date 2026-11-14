package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.Verdict
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Packaged business changes are labeled by static control coverage in both fold states. */
class FlagrControlGoldenTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun `Flagr platform and region controls cover packaged business changes`(boot: Boolean, enabledEdit: Boolean) {
        val project = FlagrBusinessProject(temporary.resolve("repository"), boot)
        val base = project.commit()
        project.prices(if (enabledEdit) 8500 else 9000, if (enabledEdit) 10000 else 11000)
        val head = project.commit()
        val cache = temporary.resolve("analysis")
        val engine = PackagedModelAnalyzer(cache)
        val results = listOf(false, true).associateWith { selected ->
            engine.analyze(AnalysisRequest(project.root.toString(), base, head, """
                version: 1
                folds:
                  - select: |
                      MATCH (cs:CallSite {callee_class: "sample.FlagrDecisions", callee_name: "discountEnabled"}) RETURN cs
                    value: $selected
            """.trimIndent()))
        }
        val mutation = if (enabledEdit) "enabled-edit" else "fallback-edit"
        val report = Files.createDirectories(Path.of("build", "reports", "flagr"))
            .resolve("FlagrControlGoldenTest-${if (boot) "boot" else "war"}-$mutation.json")
        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), mapOf(
            "criterion" to "Static coverage of production changes by the selected control",
            "analysis" to results.mapKeys { if (it.key) "on" else "off" },
        ))
        val artifacts = packagedFlagrArtifacts(cache, boot)
        assertEquals(2, artifacts.size)
        results.forEach { (selected, result) ->
            val changed = selected == enabledEdit
            assertEquals(if (changed) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
            assertTrue(result.diagnostics.isEmpty(), "Real Flagr business golden requires complete analysis: $result")
            assertEquals(changed, result.findings.isNotEmpty(), result.toString())
            assertTrue(result.findings.flatMap { it.locations }.all { it.path == FlagrBusinessProject.SOURCE }, result.toString())
            assertEquals(result.metadata.scenarios.associateWith { 2 },
                result.ruleAudits.groupingBy { it.scenario }.eachCount())
            assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
            if (!changed) assertEquals(result.metadata.obligations, result.metadata.provedObligations)
        }
    }

}
