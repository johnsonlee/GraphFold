package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Framework package versions do not substitute for static control-coverage evidence. */
class FrameworkBoundaryArchiveTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `selected controls cover business changes independently of framework package versions`(jackson: Boolean) {
        val project = FlagrBusinessProject(temporary.resolve("repository"), jackson)
        if (jackson) {
            Files.writeString(project.root.resolve("build.gradle"), "\n" + """

                allprojects {
                    configurations.configureEach {
                        resolutionStrategy.force 'com.fasterxml.jackson.core:jackson-annotations:2.19.1',
                            'com.fasterxml.jackson.core:jackson-core:2.19.1',
                            'com.fasterxml.jackson.core:jackson-databind:2.19.1'
                    }
                }
            """.trimIndent(), java.nio.file.StandardOpenOption.APPEND)
        } else {
            val servlet = project.root.resolve("src/main/java/sample/QuoteServlet.java")
            Files.writeString(servlet, Files.readString(servlet).replace("jakarta.servlet", "javax.servlet"))
            Files.writeString(project.root.resolve("build.gradle"),
                "\ndependencies { implementation 'javax.servlet:javax.servlet-api:4.0.1' }\n",
                java.nio.file.StandardOpenOption.APPEND)
        }
        val base = project.commit()
        project.prices(8500, 10000)
        val cache = temporary.resolve("analysis")
        val result = PackagedModelAnalyzer(cache).analyze(
            AnalysisRequest(project.root.toString(), base, project.commit(), """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: sample.FlagrDecisions, callee_name: discountEnabled}}
                    value: false
            """.trimIndent()))
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(result.metadata.obligations, result.metadata.provedObligations, result.toString())
        assertTrue(result.metadata.scenarios.isNotEmpty(), result.toString())
        assertEquals(result.metadata.scenarios.associateWith { 2 }, result.ruleAudits.groupingBy { it.scenario }.eachCount())
        assertTrue(result.ruleAudits.all {
            it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1
        }, result.toString())
        result.ruleAudits.groupBy { it.scenario }.values.forEach { audits ->
            assertEquals(setOf(Revision.BASE, Revision.HEAD), audits.map { it.revision }.toSet())
        }
        val expectedEntry = if (jackson) "BOOT-INF/lib/jackson-databind-2.19.1.jar" else "WEB-INF/lib/javax.servlet-api-4.0.1.jar"
        val expectedClass = if (jackson) "com/fasterxml/jackson/databind/ObjectMapper.class" else "javax/servlet/http/HttpServlet.class"
        val packaged = packagedFlagrArtifacts(cache, jackson)
        assertEquals(2, packaged.size)
        packaged.forEach { artifact -> assertPackagedDependency(artifact, expectedEntry, expectedClass) }
    }

    private fun assertPackagedDependency(artifact: Path, entryName: String, className: String) {
        JarFile(artifact.toFile()).use { jar ->
            val entry = requireNotNull(jar.getJarEntry(entryName)) { "Analyzed artifact $artifact lacks $entryName" }
            JarInputStream(jar.getInputStream(entry)).use { dependency ->
                val names = generateSequence { dependency.nextJarEntry }.map { it.name }.toSet()
                assertTrue(className in names, "$entryName in $artifact must contain actual dependency classes")
            }
        }
    }
}
