package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same packaged input contains a disposable predicate and three independently attributed counterexamples. */
class UnsafeExposureFlagrGoldenTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `packaged exposure retains effects exceptions and reused values after control folding`(boot: Boolean) {
        val project = FlagrBusinessProject(temporary.resolve("repository"), boot)
        writeRevision(project.root, false, boot)
        val base = project.commit()
        writeRevision(project.root, true, boot)
        val head = project.commit()
        val cache = temporary.resolve("analysis")
        val analyzer = PackagedModelAnalyzer(cache)
        listOf(false, true).forEach { selected ->
            val result = analyzer.analyze(AnalysisRequest(project.root.toString(), base, head, """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: sample.FlagrDecisions, callee_name: discountEnabled}}
                    value: $selected
            """.trimIndent()))
            assertEquals(Verdict.FAIL, result.verdict, result.toString())
            assertTrue(result.diagnostics.isEmpty(), "Unsafe exposure must yield source findings, not analysis blockers: $result")
            val expected = names.filter { selected || it != "SafeExposure" }.map(::source).toSet()
            assertEquals(expected, result.findings.flatMap { it.locations }.map { it.path }.toSet(), result.toString())
            assertEquals(listOf("baseline"), result.metadata.scenarios)
            assertEquals(2, result.ruleAudits.size)
            assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == names.size })
        }
        assertEquals(2, packagedFlagrArtifacts(cache, boot).size)
    }

    private fun writeRevision(root: Path, head: Boolean, boot: Boolean) {
        Files.writeString(root.resolve(FlagrBusinessProject.SOURCE), """
            package sample;
            ${if (boot) "@org.springframework.stereotype.Service" else ""}
            public class PricingServiceImpl implements PricingService {
                public int quote(String flag, String platform, String region) throws Exception {
                    int x = platform.length();
                    return ${names.joinToString(" + ") { "$it.quote(flag, platform, region, x)" }};
                }
            }
        """.trimIndent())
        val decision = "FlagrDecisions.discountEnabled(flag, platform, region)"
        val bodies = listOf(
            "return x > 0 && $decision ? 9000 : 10000;",
            "return counted(x) && $decision ? 9000 : 10000;",
            "return 10 / x > 0 && $decision ? 9000 : 10000;",
            "boolean exposed = x > 0; if (exposed && $decision) return 9000; return exposed ? 11000 : 10000;",
        )
        names.zip(bodies).forEach { (name, body) ->
            Files.writeString(root.resolve(source(name)), """
                package sample;
                public final class $name {
                    private static int count;
                    private static boolean counted(int x) { count++; return x > 0; }
                    public static int quote(String flag, String platform, String region, int x) throws Exception {
                        ${if (head) body else "return $decision ? 9000 : 10000;"}
                    }
                }
            """.trimIndent())
        }
    }

    private fun source(name: String): String = "business/src/main/java/sample/$name.java"

    companion object {
        private val names = listOf("SafeExposure", "EffectExposure", "ThrowingExposure", "ReusedExposure")
    }
}
