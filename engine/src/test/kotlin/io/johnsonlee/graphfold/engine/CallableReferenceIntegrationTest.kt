package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.testing.sourceAnalyzer

import io.johnsonlee.graphfold.engine.testing.SourceFixtureCompiler
import io.johnsonlee.graphfold.engine.inputs.archive.toStaticScope
import io.johnsonlee.graphfold.engine.origin.readOrigins
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Generated callable-reference implementations must retain normal graph obligations without anonymous-object pairing. */
class CallableReferenceIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()

    @Test
    fun `catalog accepts reference providers while preserving actual object origins`() {
        val repository = Files.createDirectories(temporary.resolve("repository"))
        val source = repository.resolve("src/main/kotlin/sample/References.kt")
        Files.createDirectories(source.parent)
        Files.copy(Path.of("../gfc/src/test/resources/kotlin/callable-references/References.kt"), source)
        val project = SourceFixtureCompiler(Duration.ofMinutes(2)).compile(repository, temporary.resolve("compiled"))
        val catalog = readOrigins(project.toStaticScope(temporary.resolve("static-input")), repository)
        assertTrue(catalog.diagnostics.isEmpty(), catalog.diagnostics.toString())
        assertEquals(1, catalog.origins.size)
        val origin = catalog.origins.single()
        val text = Files.readString(source)
        assertTrue(text.substring(origin.syntax.range.start, origin.syntax.range.end)
            .startsWith("object : java.util.function.IntSupplier"))
    }

    @ParameterizedTest
    @CsvSource("equivalent_expression,PASS", "changed_target,FAIL", "changed_body,FAIL")
    fun `callable reference changes are checked through the folded graph`(case: String, expected: Verdict) {
        val repository = Files.createDirectories(temporary.resolve("repository"))
        val source = repository.resolve("src/main/kotlin/sample/Example.kt")
        Files.createDirectories(source.parent)
        val sdk = repository.resolve("src/main/java/sample/Ab.java")
        Files.createDirectories(sdk.parent)
        Files.copy(fixtures.resolve("common/Ab.java"), sdk)
        val commits = Git.init().setDirectory(repository.toFile()).call().use { git ->
            listOf("base", "head").map { revision ->
                Files.writeString(source, Files.readString(fixtures.resolve("callable-references/kotlin/$case/$revision/Example.kt")))
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
            assertTrue(result.findings.flatMap { it.locations }.all { it.path == "src/main/kotlin/sample/Example.kt" })
        }
    }
}
