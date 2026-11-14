package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.testing.DetectionFixtures
import io.johnsonlee.graphfold.engine.testing.DetectionCase
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DetectionCorpusTest {
    @TempDir lateinit var temporary: Path
    private val selected = DetectionFixtures.selectedCases()
    private val fingerprint by lazy { DetectionFixtures.fingerprint() }
    private val measurements = ConcurrentHashMap<String, Measurement>()

    fun cases(): List<DetectionCase> = selected.also { assertTrue(it.isNotEmpty(), "No corpus cases matched") }

    @BeforeAll
    fun `clear previous reports`() {
        selected.map { it.language }.distinct().forEach { language ->
            val directory = Path.of("build/reports/detection/$language")
            Files.deleteIfExists(directory.resolve("results.json"))
            Files.deleteIfExists(directory.resolve("metrics.json"))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `precompiled corpus retains exact static evidence`(entry: DetectionCase) {
        val case = entry.case
        val descriptor = DetectionFixtures.descriptor(entry, fingerprint)
        val result = DetectionFixtures.analyze(descriptor, temporary.resolve(entry.toString()))
        val measurement = Measurement(case.id, case.violation, result.verdict == Verdict.FAIL, result)
        check(measurements.putIfAbsent(entry.toString(), measurement) == null) { "Duplicate case: $entry" }
        assertTrue(result.diagnostics.none { it.blocking }, "Operational failure is not detection evidence: $measurement")
        verifyCaseEvidence(entry.root(DetectionFixtures.fixtures), case, measurement)
        if (entry.language == "java" && case.id.startsWith("stream_")) verifyStreamLocations(measurement)
        assertEquals(measurement.expectedViolation, measurement.predictedViolation, "Every labelled regression must match: $measurement")
    }

    @AfterAll
    fun `write complete shard reports`() {
        val mapper = jacksonObjectMapper()
        val missing = selected.filter { !measurements.containsKey(it.toString()) }
        selected.map { it.language }.distinct().forEach { language ->
            val results = selected.filter { it.language == language }.mapNotNull { measurements[it.toString()] }
            val directory = Files.createDirectories(Path.of("build/reports/detection/$language"))
            fun write(name: String, value: Any) {
                val staging = directory.resolve("$name.tmp")
                mapper.writerWithDefaultPrettyPrinter().writeValue(staging.toFile(), value)
                Files.move(staging, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            write("results.json", results)
            val truePositive = results.count { it.expectedViolation && it.predictedViolation }
            val falsePositive = results.count { !it.expectedViolation && it.predictedViolation }
            val falseNegative = results.count { it.expectedViolation && !it.predictedViolation }
            val shards = System.getProperty("graphfold.testShards", "1").toInt()
            val precision = ratio(truePositive, truePositive + falsePositive)
            val recall = ratio(truePositive, truePositive + falseNegative)
            write("metrics.json", mapOf(
                "filtered" to (System.getenv("GRAPHFOLD_CASE_FILTER") != null), "sharded" to (shards > 1),
                "shard" to System.getProperty("graphfold.testShard", "0").toInt(), "shards" to shards,
                "cases" to results.size, "complete" to missing.isEmpty(),
                "truePositive" to truePositive, "falsePositive" to falsePositive, "falseNegative" to falseNegative,
                "precision" to precision, "recall" to recall,
            ))
            assertTrue((precision == null || precision > MINIMUM_ACCURACY) && (recall == null || recall > MINIMUM_ACCURACY),
                "precision=${precision ?: "undefined"} recall=${recall ?: "undefined"}; " +
                    "failures=${results.filter { it.expectedViolation != it.predictedViolation }}")
        }
        assertTrue(missing.isEmpty(), "Selected corpus cases did not produce results: $missing")
    }

    private fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator == 0) null else numerator.toDouble() / denominator

    private fun verifyCaseEvidence(caseRoot: Path, case: CorpusCase, measurement: Measurement) {
        val inline = case.id.startsWith("kotlin_inline_")
        if (inline && case.violation) verifyInlineLocations(caseRoot, measurement)
        val retainedNegative = case.id.startsWith("negative_local_") || case.id.startsWith("kotlin_negative_")
        val inlineNegative = inline && !case.violation
        if (retainedNegative || inlineNegative) {
            assertTrue(measurement.result.metadata.obligations > 0, "Negative needs actual proof obligations: ${case.id}")
            assertTrue(measurement.result.reasons.any { it.code == ReasonCode.FOLDED_EQUIVALENT },
                "Retained execution must be proved equivalent, not only declared unreachable: $measurement")
        }
    }

    private fun verifyInlineLocations(caseRoot: Path, measurement: Measurement) {
        assertTrue(measurement.result.findings.isNotEmpty(), measurement.toString())
        measurement.result.findings.flatMap { it.locations }.forEach { location ->
            val revision = location.revision.name.lowercase()
            val text = Files.readString(caseRoot.resolve("cases/${measurement.id}/$revision/Example.kt"))
            val otherRevision = if (revision == "base") "head" else "base"
            val other = Files.readString(caseRoot.resolve("cases/${measurement.id}/$otherRevision/Example.kt"))
            val declarationLine = text.lines().zip(other.lines()).indexOfFirst { (before, after) -> before != after } + 1
            assertEquals("src/main/kotlin/sample/Example.kt", location.path)
            assertEquals(SourceRange(declarationLine, declarationLine), location.line,
                "Copied caller differences must remain attributed to the changed inline declaration: $measurement")
        }
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

    private companion object { const val MINIMUM_ACCURACY = 0.90 }

    data class CorpusCase(val id: String, val violation: Boolean)
    data class Measurement(val id: String, val expectedViolation: Boolean, val predictedViolation: Boolean, val result: AnalysisResult)
}
