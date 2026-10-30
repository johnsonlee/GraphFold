package io.johnsonlee.graphfold.gradle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphFoldIntegrationTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @CsvSource("gated_return,true", "arithmetic,false")
    fun `task verifies real nonempty Java changes with exact diff locations`(name: String, passes: Boolean) {
        val fixture = createFixture(directory, name)
        fixture.repository.resolve("settings.gradle").writeText("rootProject.name = 'consumer'\n")
        fixture.repository.resolve("fold.yml").writeText(fixture.fold.readText())
        fixture.repository.resolve("build.gradle").writeText("plugins { id 'io.johnsonlee.graphfold' }\n")
        val runner = GradleRunner.create().withProjectDir(fixture.repository.toFile()).withPluginClasspath()
            .withArguments(
                "graphFoldCheck", "--stacktrace", "--configuration-cache",
                "-Pgraphfold.base=${fixture.base}", "-Pgraphfold.head=${fixture.head}", "-Pgraphfold.fold=fold.yml",
            )
        val result = if (passes) runner.build() else runner.buildAndFail()
        assertEquals(if (passes) TaskOutcome.SUCCESS else TaskOutcome.FAILED, result.task(":graphFoldCheck")?.outcome)
        val mapper = jacksonObjectMapper()
        val report = mapper.readTree(fixture.repository.resolve("build/reports/graphfold/report.json").toFile())
        assertEquals(if (passes) "PASS" else "FAIL", report["verdict"].asText())
        assertEquals(fixture.base, report["metadata"]["baseSha"].asText())
        assertEquals(fixture.head, report["metadata"]["headSha"].asText())
        if (!passes) {
            val locations = report["findings"][0]["locations"]
            assertEquals(2, locations.size())
            assertEquals(listOf("BASE", "HEAD"), locations.map { it["revision"].asText() })
            locations.forEach { location ->
                assertEquals("src/main/java/sample/Example.java", location["path"].asText())
                assertEquals(3, location["line"]["start"].asInt())
                assertEquals(3, location["line"]["end"].asInt())
                assertEquals(75, location["column"]["start"].asInt())
                assertEquals(76, location["column"]["end"].asInt())
            }
        }
        assertTrue(report["diagnostics"].isEmpty)
        if (passes) {
            val repeated = runner.build()
            assertTrue(repeated.output.contains("Reusing configuration cache"))
            assertEquals(TaskOutcome.SUCCESS, repeated.task(":graphFoldCheck")?.outcome)
        }
    }

    @Test
    fun `published plugin contract executes engine in isolated worker and fails closed`() {
        directory.resolve("settings.gradle").writeText("rootProject.name = 'consumer'\n")
        directory.resolve("build.gradle").writeText("plugins { id 'io.johnsonlee.graphfold' }\n")
        directory.resolve("fold.yml").writeText("folds:\n  - select: 'MATCH (cs:CallSite) RETURN cs'\n    value: false\n")
        val result = GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath()
            .withArguments(
                "graphFoldCheck", "--stacktrace", "--configuration-cache",
                "-Pgraphfold.base=0123456789abcdef", "-Pgraphfold.head=fedcba9876543210", "-Pgraphfold.fold=fold.yml",
            ).buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":graphFoldCheck")?.outcome)
        assertTrue(directory.resolve("build/reports/graphfold/report.json").readText().contains("FAIL"))
        assertTrue(result.output.contains("GraphFold detected uncontrolled or unverifiable changes"))
    }

    @ParameterizedTest
    @CsvSource("graphfold.base", "graphfold.head", "graphfold.fold")
    fun `missing required property reports its command line spelling`(missing: String) {
        directory.resolve("settings.gradle").writeText("rootProject.name = 'consumer'\n")
        directory.resolve("build.gradle").writeText("plugins { id 'io.johnsonlee.graphfold' }\n")
        directory.resolve("fold.yml").writeText("folds: []\n")
        val properties = mapOf("graphfold.base" to "base", "graphfold.head" to "head", "graphfold.fold" to "fold.yml")
        val arguments = listOf("graphFoldCheck") + properties.filterKeys { it != missing }.map { (key, value) ->
            "-P$key=$value"
        }
        val result = GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath()
            .withArguments(arguments).buildAndFail()
        assertTrue(result.output.contains("Missing Gradle property '$missing'"), result.output)
        assertTrue(result.output.contains("-P$missing=<value>"), result.output)
    }

    @Test
    fun `subproject resolves repository and fold against root with properties file`() {
        directory.resolve("settings.gradle").writeText("rootProject.name = 'consumer'\ninclude 'child'\n")
        val child = java.nio.file.Files.createDirectories(directory.resolve("child"))
        directory.resolve("gradle.properties").writeText(
            "graphfold.base=base\ngraphfold.head=head\ngraphfold.fold=rules.yml\n",
        )
        child.resolve("build.gradle").writeText(
            """
            plugins { id 'base'; id 'io.johnsonlee.graphfold' }
            tasks.register('inspectGraphFold') {
                doLast {
                    def analysis = tasks.named('graphFoldCheck').get()
                    assert project.extensions.findByName('graphfold') == null
                    assert analysis.repository.get() == rootDir.absolutePath
                    assert analysis.fold.get().asFile == new File(rootDir, 'rules.yml')
                    assert analysis.base.get() == 'base'
                    assert analysis.head.get() == 'head'
                    assert !tasks.named('check').get().taskDependencies.getDependencies(null).contains(analysis)
                    println 'ROOT_PROPERTIES_VERIFIED'
                }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath()
            .withArguments(":child:inspectGraphFold").build()
        assertTrue(result.output.contains("ROOT_PROPERTIES_VERIFIED"))
    }
}
