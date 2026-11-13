package io.johnsonlee.graphfold.engine

import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PublicInputValidationTest {
    @ParameterizedTest
    @ValueSource(strings = ["repository", "base", "head", "fold"])
    fun `repository requests reject absent revision and rule inputs`(field: String) {
        fun value(name: String) = if (name == field) " \t" else "valid"
        val failure = assertFailsWith<IllegalArgumentException> {
            AnalysisRequest(value("repository"), value("base"), value("head"), value("fold"))
        }
        assertEquals("$field must not be blank", failure.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["scenarios", "slots", "negative-timeout", "zero-timeout"])
    fun `repository configuration requires usable resource budgets`(mode: String) {
        val failure = assertFailsWith<IllegalArgumentException> {
            RepositoryConfiguration(Path.of("unused"), maximumScenarios = if (mode == "scenarios") 0 else 1,
                maximumWorkspaceSlots = if (mode == "slots") 0 else 1,
                workspaceLeaseTimeout = when (mode) {
                    "negative-timeout" -> Duration.ofSeconds(-1)
                    "zero-timeout" -> Duration.ZERO
                    else -> Duration.ofSeconds(1)
                })
        }
        assertEquals(when (mode) {
            "scenarios" -> "maximum scenarios must be positive"
            "slots" -> "maximum workspace slots must be positive"
            else -> "lease timeout must be positive"
        }, failure.message)
    }

    @Test fun `source spans reject invalid coordinates and escaping paths but allow multiline columns`() {
        listOf(0 to 1, 1 to 0).forEach { (start, end) ->
            assertFailsWith<IllegalArgumentException> { SourceRange(start, end) }
        }
        listOf(" ", "/absolute.java", "../Outside.java", "nested/../../Outside.java").forEach { path ->
            assertFailsWith<IllegalArgumentException> {
                SourceLocation(Revision.HEAD, path, SourceRange(1, 1), SourceRange(1, 1))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SourceLocation(Revision.BASE, "Example.java", SourceRange(2, 1), SourceRange(1, 2))
        }
        assertFailsWith<IllegalArgumentException> {
            SourceLocation(Revision.BASE, "Example.java", SourceRange(1, 1), SourceRange(2, 1))
        }
        val spanning = SourceLocation(Revision.HEAD, "nested/../Example.java", SourceRange(1, 2), SourceRange(9, 1))
        assertEquals(SourceRange(9, 1), spanning.column)
    }

    @ParameterizedTest
    @ValueSource(strings = ["id", "rule", "message", "locations"])
    fun `findings cannot report an unidentified or unlocated violation`(missing: String) {
        val location = SourceLocation(Revision.HEAD, "Example.java", SourceRange(1, 1), SourceRange(1, 2))
        val failure = assertFailsWith<IllegalArgumentException> {
            Finding(if (missing == "id") " " else "finding", FindingKind.UNVERIFIABLE_CHANGE,
                if (missing == "rule") " " else "rule", if (missing == "message") " " else "message",
                if (missing == "locations") emptyList() else listOf(location))
        }
        assertEquals(if (missing == "locations") "findings require a verified diff location" else
            "finding identity and message required", failure.message)
    }
}
