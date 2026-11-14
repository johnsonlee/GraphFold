package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.Verdict
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Both static control values cover all/single/multiple platform × region predicates in real archives. */
class ExposureFlagrControlGoldenTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `safe new and existing exposure conditions remain controlled by Flagr`(boot: Boolean) {
        val project = ExposureFlagrBusinessProject(temporary.resolve("repository"), boot)
        val base = project.commit()
        project.revision(true)
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
        val report = Files.createDirectories(Path.of("build", "reports", "flagr"))
            .resolve("ExposureFlagrControlGoldenTest-${if (boot) "boot" else "war"}.json")
        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), mapOf(
            "criterion" to "Static coverage of production changes by the selected control",
            "exposurePolicies" to ExposureFlagrBusinessProject.policyNames,
            "analysis" to results.mapKeys { if (it.key) "on" else "off" },
        ))
        val artifacts = packagedFlagrArtifacts(cache, boot)
        assertEquals(2, artifacts.size)
        results.forEach { (selected, result) ->
            val changed = selected
            assertEquals(if (changed) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
            assertTrue(result.diagnostics.isEmpty(), "Real Flagr business golden requires complete analysis: $result")
            assertEquals(changed, result.findings.isNotEmpty(), result.toString())
            if (changed) assertEquals(ExposureFlagrBusinessProject.sources,
                result.findings.flatMap { it.locations }.map { it.path }.toSet(), result.toString())
            assertEquals(result.metadata.scenarios.associateWith { 2 },
                result.ruleAudits.groupingBy { it.scenario }.eachCount())
            assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED &&
                it.matchedCallCount == ExposureFlagrBusinessProject.controlCalls })
            if (!changed) assertEquals(result.metadata.obligations, result.metadata.provedObligations)
        }
    }

}
