package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DetectionCorpusTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `real git Java Cypher and folded IR achieve the detection threshold`() {
        runCorpus("java")
    }

    @Test
    fun `Kotlin equivalent and language specific cases run the full pipeline`() {
        runCorpus("kotlin")
    }

    private fun runCorpus(language: String) {
        val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()
        val mapper = jacksonObjectMapper()
        val cases = mapper.readValue<List<CorpusCase>>(fixtures.resolve("manifest.json").toFile())
        val allCases = if (language == "kotlin") cases +
            mapper.readValue<List<CorpusCase>>(fixtures.resolve("kotlin/features.json").toFile()) else cases
        val filter = System.getenv("GRAPHFOLD_CASE_FILTER")?.toRegex()
        val selected = allCases.filter { filter == null || filter.matches(it.id) }
        assertTrue(selected.isNotEmpty(), "No corpus cases matched")
        val extension = if (language == "java") "java" else "kt"
        val caseRoot = if (language == "java") fixtures else fixtures.resolve("kotlin")
        val report = Path.of("build/reports/detection/$language/results.json")
        Files.createDirectories(report.parent)
        Files.deleteIfExists(report.resolveSibling("metrics.json"))
        val measurements = mutableListOf<Measurement>()
        selected.forEach { case ->
            val measurement = analyzeCase(fixtures, caseRoot, language, extension, case)
            measurements += measurement
            mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), measurements)
            assertTrue(measurement.result.diagnostics.none { it.blocking },
                "Operational failure is not detection evidence: $measurement")
            if (case.id.startsWith("negative_local_") || case.id.startsWith("kotlin_negative_")) {
                assertTrue(measurement.result.metadata.obligations > 0, "Negative needs actual proof obligations: ${case.id}")
                assertTrue(measurement.result.reasons.any { it.code == ReasonCode.FOLDED_EQUIVALENT },
                    "Retained execution must be proved equivalent, not only declared unreachable: $measurement")
            }
        }
        if (language == "java") measurements.filter { it.id.startsWith("stream_") }.forEach(::verifyStreamLocations)
        assertTrue(measurements.all { it.expectedViolation == it.predictedViolation },
            "Every labelled regression must match: ${measurements.filter { it.expectedViolation != it.predictedViolation }}")
        val truePositive = measurements.count { it.expectedViolation && it.predictedViolation }
        val falsePositive = measurements.count { !it.expectedViolation && it.predictedViolation }
        val falseNegative = measurements.count { it.expectedViolation && !it.predictedViolation }
        val precision = truePositive.toDouble() / (truePositive + falsePositive)
        val recall = truePositive.toDouble() / (truePositive + falseNegative)
        mapper.writerWithDefaultPrettyPrinter().writeValue(
            report.resolveSibling("metrics.json").toFile(),
            mapOf("filtered" to (filter != null), "cases" to measurements.size, "truePositive" to truePositive,
                "falsePositive" to falsePositive,
                "falseNegative" to falseNegative, "precision" to precision, "recall" to recall),
        )
        assertTrue(precision > 0.90 && recall > 0.90,
            "precision=$precision recall=$recall; failures=${measurements.filter { it.expectedViolation != it.predictedViolation }}")
    }

    private fun analyzeCase(
        fixtures: Path, caseRoot: Path, language: String, extension: String, case: CorpusCase,
    ): Measurement {
        val repository = Files.createDirectory(temporary.resolve(case.id))
        git(repository, "init", "--quiet")
        git(repository, "config", "user.name", "GraphFold Test")
        git(repository, "config", "user.email", "test@example.invalid")
        val sources = Files.createDirectories(repository.resolve("src/main/java/sample"))
        Files.copy(fixtures.resolve("common/Ab.java"), sources.resolve("Ab.java"))
        val caseSources = Files.createDirectories(repository.resolve("src/main/$language/sample"))
        Files.copy(
            caseRoot.resolve("cases/${case.id}/base/Example.$extension"), caseSources.resolve("Example.$extension"),
        )
        git(repository, "add", ".")
        git(repository, "commit", "--quiet", "-m", "base")
        val base = git(repository, "rev-parse", "HEAD").trim()
        Files.copy(
            caseRoot.resolve("cases/${case.id}/head/Example.$extension"), caseSources.resolve("Example.$extension"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        git(repository, "add", ".")
        git(repository, "commit", "--quiet", "-m", "head")
        val head = git(repository, "rev-parse", "HEAD").trim()
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), base, head, Files.readString(fixtures.resolve("fold.yml"))),
        )
        return Measurement(case.id, case.violation, result.verdict == Verdict.FAIL, result)
    }

    private fun verifyStreamLocations(measurement: Measurement) {
        val ranges = when (measurement.id) {
            "stream_intermediate_call" -> listOf(Triple(Revision.BASE, 32, 38), Triple(Revision.HEAD, 32, 40))
            "stream_add_filter" -> listOf(Triple(Revision.HEAD, 32, 51))
            "stream_remove_filter" -> listOf(Triple(Revision.BASE, 32, 51))
            "stream_lambda_body" -> listOf(Triple(Revision.BASE, 45, 46), Triple(Revision.HEAD, 45, 46))
            "stream_add_predicate" -> listOf(Triple(Revision.HEAD, 49, 59))
            "stream_remove_predicate" -> listOf(Triple(Revision.BASE, 49, 59))
            else -> error("Missing exact-location expectation: ${measurement.id}")
        }
        val expected = ranges.map { (revision, start, end) ->
            SourceLocation(revision, "src/main/java/sample/Example.java", SourceRange(5, 5), SourceRange(start, end))
        }
        assertEquals(expected, measurement.result.findings.single().locations, measurement.id)
    }

    private fun git(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).directory(directory.toFile()).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { text }
        return text
    }

    data class CorpusCase(val id: String, val violation: Boolean)
    data class Measurement(val id: String, val expectedViolation: Boolean, val predictedViolation: Boolean, val result: AnalysisResult)
}
