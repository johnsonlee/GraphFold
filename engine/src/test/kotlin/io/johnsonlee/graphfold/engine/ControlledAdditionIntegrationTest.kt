package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.testing.git
import io.johnsonlee.graphfold.engine.testing.sourceAnalyzer
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Added declarations need static control coverage, including their initialization. */
class ControlledAdditionIntegrationTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource(
        "java, gated_new_class, TYPE_ADDED", "kotlin, gated_new_class, TYPE_ADDED",
        "java, gated_new_field, FIELD_ADDED", "kotlin, gated_new_field, FIELD_ADDED",
    )
    fun `selected control removes every static path to added declaration`(language: String, id: String, change: ChangeKind) {
        verify(language, id, change, controlled = true)
    }

    @ParameterizedTest(name = "{0}/{1} with surviving construction")
    @CsvSource(
        "java, gated_new_class, TYPE_ADDED", "kotlin, gated_new_class, TYPE_ADDED",
        "java, gated_new_field, FIELD_ADDED", "kotlin, gated_new_field, FIELD_ADDED",
    )
    fun `construction outside selected control retains added declaration`(language: String, id: String, change: ChangeKind) {
        verify(language, id, change, controlled = false)
    }

    private fun verify(language: String, id: String, change: ChangeKind, controlled: Boolean) {
        val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()
        val cases = if (language == "java") fixtures else fixtures.resolve("kotlin")
        val extension = if (language == "java") "java" else "kt"
        val repository = Files.createDirectories(temporary.resolve("repository"))
        git(repository, "init", "--quiet")
        git(repository, "config", "user.name", "GraphFold Test")
        git(repository, "config", "user.email", "test@example.invalid")
        val javaSources = Files.createDirectories(repository.resolve("src/main/java/sample"))
        Files.copy(fixtures.resolve("common/Ab.java"), javaSources.resolve("Ab.java"))
        val source = Files.createDirectories(repository.resolve("src/main/$language/sample")).resolve("Example.$extension")
        fun revision(name: String): String {
            Files.copy(cases.resolve("cases/$id/$name/Example.$extension"), source, StandardCopyOption.REPLACE_EXISTING)
            if (name == "head" && !controlled) {
                val construction = if (language == "java") "new Treatment(); " else "Treatment(); "
                val text = Files.readString(source)
                Files.writeString(source, text.replace("if (Ab.enabled", "${construction}if (Ab.enabled"))
            }
            git(repository, "add", ".")
            git(repository, "commit", "--quiet", "-m", name)
            return git(repository, "rev-parse", "HEAD").trim()
        }
        val base = revision("base")
        val head = revision("head")
        val result = sourceAnalyzer(RepositoryConfiguration(temporary.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), base, head, Files.readString(fixtures.resolve("fold.yml"))),
        )
        assertEquals(if (controlled) Verdict.PASS else Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(controlled, result.findings.isEmpty(), result.toString())
        val reason = if (controlled) ReasonCode.UNREACHABLE else ReasonCode.SURVIVING_DIFFERENCE
        assertTrue(result.reasons.any { it.code == reason && change in it.changes }, result.toString())
    }
}
