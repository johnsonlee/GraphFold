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

/** Equal runtime output does not excuse a production callback change with no flag control. */
class LazyFlagrProductionChangeGoldenTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `unused uncontrolled production callback changes fail in actual packaged applications`(boot: Boolean) {
        val project = AdvancedFlagrBusinessProject(temporary.resolve("repository"), boot)
        project.lazyPrice(9000)
        val base = project.commit()
        project.lazyPrice(8500)
        val cache = temporary.resolve("analysis")
        val result = PackagedModelAnalyzer(cache)
            .analyze(AnalysisRequest(project.root.toString(), base, project.commit(), """
                version: 1
                folds:
                  - select: |
                      MATCH (cs:CallSite {callee_class: "sample.FlagrDecisions", callee_name: "discountEnabled"}) RETURN cs
                    value: false
            """.trimIndent()))
        packagedFlagrArtifacts(cache, boot).forEach { AdvancedBusinessBytecode.verifyLazy(it, boot) }
        val report = Files.createDirectories(Path.of("build", "reports", "flagr"))
            .resolve("lazy-uncontrolled-${if (boot) "boot" else "tomcat"}.json")
        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), mapOf(
            "criterion" to "Uncontrolled production changes fail even when a lazy callback is never invoked",
            "expectedVerdict" to "FAIL", "analysis" to result,
        ))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), "Uncontrolled change must be a precise finding, not an analysis blocker: $result")
        assertEquals(setOf("business/src/main/java/sample/LazyQuote.java"),
            result.findings.flatMap { it.locations }.map { it.path }.toSet(), result.toString())
        assertEquals(result.metadata.scenarios.associateWith { 2 },
            result.ruleAudits.groupingBy { it.scenario }.eachCount())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED &&
            it.matchedCallCount == AdvancedFlagrBusinessProject.styles.size })
    }

}
