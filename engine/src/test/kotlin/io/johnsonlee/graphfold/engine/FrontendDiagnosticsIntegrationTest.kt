package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.testing.sourceAnalyzer

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrontendDiagnosticsIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()

    @ParameterizedTest
    @CsvSource("java,java,controlled,PASS", "java,java,uncontrolled,FAIL",
        "kotlin,kt,controlled,PASS", "kotlin,kt,uncontrolled,FAIL")
    fun `enum diagnostic strings neither block controlled changes nor hide violations`(
        language: String, extension: String, case: String, expected: Verdict,
    ) {
        val repository = Files.createDirectories(temporary.resolve("repository"))
        val source = repository.resolve("src/main/$language/sample/Example.$extension")
        Files.createDirectories(source.parent)
        val sdk = repository.resolve("src/main/java/sample/Ab.java")
        Files.createDirectories(sdk.parent)
        Files.copy(fixtures.resolve("common/Ab.java"), sdk)
        val commits = Git.init().setDirectory(repository.toFile()).call().use { git ->
            listOf("base", "head").map { revision ->
                Files.writeString(source, Files.readString(
                    fixtures.resolve("frontend-diagnostics/$language/$case/$revision/Example.$extension")))
                git.add().addFilepattern("src").call()
                git.commit().setMessage(revision).setAuthor("GraphFold Test", "test@example.invalid")
                    .setCommitter("GraphFold Test", "test@example.invalid").call().name
            }
        }
        val result = sourceAnalyzer(RepositoryConfiguration(temporary.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), commits[0], commits[1], Files.readString(fixtures.resolve("fold.yml"))),
        )
        assertTrue(result.diagnostics.none { it.blocking }, result.toString())
        assertEquals(expected, result.verdict, result.toString())
        assertTrue(result.metadata.obligations > 0, result.toString())
        if (expected == Verdict.PASS) {
            assertTrue(result.findings.isEmpty(), result.toString())
            assertEquals(result.metadata.obligations, result.metadata.provedObligations)
        } else {
            assertTrue(result.findings.isNotEmpty(), result.toString())
            assertTrue(result.findings.flatMap { it.locations }.all {
                it.path == "src/main/$language/sample/Example.$extension"
            }, result.toString())
        }
    }
}
