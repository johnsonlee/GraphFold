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

class ObservationScopeIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()

    @TestFactory
    fun `opaque observations are scoped without losing private state or normalized identity guards`(): List<DynamicTest> =
        Language.entries.flatMap { language -> Case.entries.map { case ->
            DynamicTest.dynamicTest("${language.directory}/${case.name.lowercase()}") { verify(language, case) }
        } }

    private fun verify(language: Language, case: Case) {
        val root = Files.createDirectories(temporary.resolve("${language.directory}-${case.name}"))
        val repository = Files.createDirectory(root.resolve("repository"))
        val sourcePath = "src/main/${language.directory}/sample/Example.${language.extension}"
        val source = repository.resolve(sourcePath)
        Files.createDirectories(source.parent)
        val input = fixtures.resolve("observation-scope/${language.directory}/${case.name.lowercase()}")
        Files.copy(input.resolve("Observer.${language.extension}"), source.resolveSibling("Observer.${language.extension}"))
        val sdk = repository.resolve("src/main/java/sample/Ab.java")
        Files.createDirectories(sdk.parent)
        Files.copy(fixtures.resolve("common/Ab.java"), sdk)
        val commits = Git.init().setDirectory(repository.toFile()).call().use { git ->
            listOf("base", "head").map { revision ->
                Files.writeString(source, Files.readString(input.resolve("$revision/Example.${language.extension}")))
                git.add().addFilepattern("src").call()
                git.commit().setMessage(revision).setAuthor("GraphFold Test", "test@example.invalid")
                    .setCommitter("GraphFold Test", "test@example.invalid").call().name
            }
        }
        val result = sourceAnalyzer(RepositoryConfiguration(root.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), commits[0], commits[1], Files.readString(fixtures.resolve("fold.yml"))),
        )
        assertEquals(case.verdict, result.verdict, result.toString())
        assertTrue(result.metadata.obligations > 0, result.toString())
        assertEquals(2, result.ruleAudits.size, result.toString())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && (it.matchedCallCount ?: 0) > 0 },
            result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.ANALYSIS_FAILED || it.code == DiagnosticCode.UNVERIFIED_ORIGIN },
            result.toString())
        if (case.gap) {
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.INCOMPLETE_GRAPH && "escape" in it.message },
                result.toString())
            assertTrue(result.reasons.any { it.code == ReasonCode.ANALYSIS_INCOMPLETE }, result.toString())
        } else {
            assertTrue(result.diagnostics.isEmpty(), result.toString())
            if (case.verdict == Verdict.PASS) {
                assertTrue(result.findings.isEmpty(), result.toString())
                assertEquals(result.metadata.obligations, result.metadata.provedObligations)
            } else verifySurvivingDifference(result, requireNotNull(case.survivingMember))
        }
        assertTrue(result.findings.flatMap { it.locations }.all { it.path == sourcePath }, result.toString())
    }

    private fun verifySurvivingDifference(result: AnalysisResult, member: String) {
        assertTrue(result.findings.isNotEmpty(), result.toString())
        assertTrue(result.findings.all { "sample.Example.$member" in it.message }, result.toString())
        assertTrue(result.reasons.any { it.code == ReasonCode.SURVIVING_DIFFERENCE }, result.toString())
        assertEquals(0, result.metadata.provedObligations, result.toString())
        assertEquals(setOf(Revision.BASE, Revision.HEAD), result.findings.flatMap { it.locations }.map { it.revision }.toSet())
    }

    enum class Language(val directory: String, val extension: String) {
        JAVA("java", "java"), KOTLIN("kotlin", "kt"),
    }

    enum class Case(val verdict: Verdict, val gap: Boolean = false, val survivingMember: String? = null) {
        CONTROLLED(Verdict.PASS), UNCONTROLLED(Verdict.FAIL, survivingMember = "run()I"),
        PRIVATE_CALLBACK(Verdict.FAIL, survivingMember = "hidden()I"),
        FIELD_VALUE(Verdict.FAIL, survivingMember = "<init>()V"),
        CONSTRUCTOR_VALUE(Verdict.FAIL, survivingMember = "<init>()V"), PRIVATE_RENAME(Verdict.FAIL, true),
        NESTED_ADDITION(Verdict.FAIL, true), FOLDED_OBSERVER(Verdict.FAIL, survivingMember = "hidden()I"),
    }
}
