package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.testing.sourceAnalyzer

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OriginFailureContinuationTest {
    @TempDir lateinit var temporary: Path
    private val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()

    @TestFactory
    fun `unrelated unverifiable origins preserve independent folding without clearing the provenance blocker`(): List<DynamicTest> =
        listOf("java", "kotlin").flatMap { language ->
            listOf("uncontrolled", "controlled", "renumbered").map { case ->
                DynamicTest.dynamicTest("$language/$case") { verify(language, case) }
            }
        }

    private fun verify(language: String, case: String) {
        val root = Files.createDirectories(temporary.resolve("$language-$case"))
        val repository = Files.createDirectory(root.resolve("repository"))
        val extension = if (language == "java") "java" else "kt"
        val sourcePath = "src/main/$language/sample/Example.$extension"
        val commits = Git.init().setDirectory(repository.toFile()).call().use { git ->
            listOf("base", "head").map { revision ->
                listOf("Example", "Gap").forEach { name ->
                    val target = repository.resolve("src/main/$language/sample/$name.$extension")
                    Files.createDirectories(target.parent)
                    Files.copy(fixtures.resolve("origin-failure/$language/$case/$revision/$name.$extension"), target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                val sdk = repository.resolve("src/main/java/sample/Ab.java")
                Files.createDirectories(sdk.parent)
                Files.copy(fixtures.resolve("common/Ab.java"), sdk, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                git.add().addFilepattern("src").call()
                git.commit().setMessage(revision).setAuthor("GraphFold Test", "test@example.invalid")
                    .setCommitter("GraphFold Test", "test@example.invalid").call().name
            }
        }
        val result = sourceAnalyzer(RepositoryConfiguration(root.resolve("cache"))).analyze(AnalysisRequest(
            repository.toString(), commits[0], commits[1], Files.readString(fixtures.resolve("fold.yml")),
        ))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.UNVERIFIED_ORIGIN && it.blocking }, result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.ANALYSIS_FAILED }, result.toString())
        assertTrue(result.metadata.obligations > 0, result.toString())
        val expectedProved = if (case == "controlled" || language == "kotlin" && case == "renumbered") 1 else 0
        assertEquals(expectedProved, result.metadata.provedObligations, result.toString())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED }, result.toString())
        assertTrue(result.diagnostics.any { "Ambiguous unchanged anonymous expressions" in it.message }, result.toString())
        assertEquals(expectedProved, result.reasons.count { it.code == ReasonCode.FOLDED_EQUIVALENT }, result.toString())
        if (case == "controlled") {
            assertEquals(result.metadata.obligations, result.metadata.provedObligations, result.toString())
            assertTrue(result.findings.isEmpty(), result.toString())
        } else {
            assertTrue(result.reasons.any { it.code == ReasonCode.SURVIVING_DIFFERENCE }, result.toString())
            assertTrue(result.findings.isNotEmpty(), result.toString())
            assertTrue(result.metadata.provedObligations < result.metadata.obligations, result.toString())
        }
        result.findings.forEach { finding ->
            assertEquals(FindingKind.UNVERIFIABLE_CHANGE, finding.kind)
            assertTrue("original JVM identity" in finding.message, finding.toString())
            assertTrue(finding.locations.isNotEmpty())
            assertTrue(finding.locations.all { it.path == sourcePath && it.line == SourceRange(3, 3) }, finding.toString())
        }
    }
}
