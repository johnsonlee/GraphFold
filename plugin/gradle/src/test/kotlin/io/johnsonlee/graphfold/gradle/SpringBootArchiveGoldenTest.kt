package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpringBootArchiveGoldenTest {
    @TempDir lateinit var temporary: Path

    @Test fun `real Gradle Boot constructor DI and nested library changes retain paired static verdicts`() {
        val repository = project()
        val source = repository.resolve("library/src/main/java/sample/Logic.java")
        org.eclipse.jgit.api.Git.init().setDirectory(repository.toFile()).call().close()
        val base = commit(repository)
        Files.writeString(source, Files.readString(source).replace("? 1", "? 2"))
        val head = commit(repository)
        val engine = PackagedModelAnalyzer(temporary.resolve("cache"))
        for (enabled in listOf(false, true)) {
            val result = engine.analyze(AnalysisRequest(repository.toString(), base, head, fold(enabled)))
            assertEquals(if (enabled) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
            assertTrue(result.diagnostics.isEmpty(), "Supported Boot golden must finish analysis: $result")
            assertEquals(enabled, result.findings.isNotEmpty(), result.toString())
            if (enabled) assertTrue(result.findings.flatMap { it.locations }.all {
                it.path == "library/src/main/java/sample/Logic.java"
            }, "The finding must identify the actual nested-library source: $result")
            val scenarios = listOf("baseline")
            assertEquals(scenarios, result.metadata.scenarios)
            assertEquals(scenarios.associateWith { 2 }, result.ruleAudits.groupingBy { it.scenario }.eachCount())
            assertTrue(result.ruleAudits.all { (it.matchedCallCount ?: 0) > 0 })
        }
        val artifacts = Files.walk(temporary.resolve("cache/export/git/workspaces")).use { paths ->
            paths.filter { it.fileName.toString() == "boot-1.jar" && it.parent.fileName.toString() == "libs" }.toList()
        }
        assertEquals(2, artifacts.size)
        artifacts.forEach(::assertBootArchive)
    }

    @Test fun `real configuration proxy bean changes require static control coverage`() {
        val repository = project()
        val source = repository.resolve("library/src/main/java/sample/Logic.java")
        val original = configurationSource()
        Files.writeString(source, original)
        org.eclipse.jgit.api.Git.init().setDirectory(repository.toFile()).call().close()
        val base = commit(repository)
        val engine = PackagedModelAnalyzer(temporary.resolve("configuration-cache"))
        val mutations = listOf(
            original.replace("new Holder(1)", "new Holder(2)"),
            original.replace("Holder second = holder();", "Holder second = new Holder(1);"),
        )
        mutations.forEachIndexed { index, changed ->
            Files.writeString(source, changed)
            val head = commit(repository)
            for (enabled in listOf(false, true)) {
                val result = engine.analyze(AnalysisRequest(repository.toString(), base, head, fold(enabled)))
                val controlled = index == 1 && enabled
                assertEquals(if (controlled) Verdict.PASS else Verdict.FAIL, result.verdict,
                    "replacement=${index == 1} enabled=$enabled: $result")
                assertTrue(result.diagnostics.isEmpty(), "A proxy-related blocker is not a successful detection: $result")
                assertEquals(!controlled, result.findings.isNotEmpty(), result.toString())
                assertTrue(result.findings.flatMap { it.locations }.all { it.path == "library/src/main/java/sample/Logic.java" })
                assertEquals(result.metadata.scenarios.associateWith { 2 }, result.ruleAudits.groupingBy { it.scenario }.eachCount())
                assertTrue(result.ruleAudits.all { (it.matchedCallCount ?: 0) > 0 })
            }
        }
    }

    private fun fold(enabled: Boolean): String = """
        version: 1
        folds:
          - select: |
              MATCH (cs:CallSite {callee_class: "sample.Logic", callee_name: "enabled"}) RETURN cs
            value: $enabled
    """.trimIndent()

    private fun configurationSource(): String = """
        package sample;
        @org.springframework.context.annotation.Configuration
        public class Logic implements ValueService {
            public static boolean gate;
            public static boolean enabled() { return gate; }
            @org.springframework.context.annotation.Bean
            public Holder holder() { return new Holder(1); }
            public int value() {
                if (enabled()) return 99;
                Holder first = holder();
                Holder second = holder();
                return first == second ? first.value : 100;
            }
            public static class Holder {
                public final int value;
                public Holder(int value) { this.value = value; }
            }
        }
    """.trimIndent()

    private fun project(): Path {
        val root = Files.createDirectory(temporary.resolve("project"))
        Files.writeString(root.resolve(".gitignore"), ".gradle/\nbuild/\n")
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='parent'; include 'library', 'boot'")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'io.johnsonlee.graphfold'; id 'org.springframework.boot' version '3.5.7' apply false }
            allprojects { group='fixture'; version='1'; repositories { mavenCentral() } }
            project(':library') {
                apply plugin: 'java-library'
                dependencies {
                    implementation platform('org.springframework.boot:spring-boot-dependencies:3.5.7')
                    implementation 'org.springframework:spring-context'
                }
            }
            project(':boot') {
                apply plugin: 'java'
                apply plugin: 'org.springframework.boot'
                dependencies {
                    implementation project(':library')
                    implementation platform('org.springframework.boot:spring-boot-dependencies:3.5.7')
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                }
                springBoot { mainClass = 'sample.App' }
            }
        """.trimIndent())
        val library = Files.createDirectories(root.resolve("library"))
        val boot = Files.createDirectories(root.resolve("boot"))
        val sources = Files.createDirectories(boot.resolve("src/main/java/sample"))
        Files.writeString(sources.resolve("App.java"), """
            package sample;
            @org.springframework.boot.autoconfigure.SpringBootApplication
            @org.springframework.web.bind.annotation.RestController
            public class App {
                private final ValueService service;
                public App(ValueService service) { this.service = service; }
                public static void main(String[] args) {
                    Logic.gate = Boolean.parseBoolean(args[0]);
                    org.springframework.boot.SpringApplication.run(App.class, args);
                }
                @org.springframework.web.bind.annotation.GetMapping("/value")
                public int endpoint() { return service.value(); }
            }
        """.trimIndent())
        val librarySources = Files.createDirectories(library.resolve("src/main/java/sample"))
        Files.writeString(librarySources.resolve("ValueService.java"), """
            package sample;
            public interface ValueService { int value(); }
        """.trimIndent())
        Files.writeString(librarySources.resolve("Logic.java"), """
            package sample;
            @org.springframework.stereotype.Service
            public class Logic implements ValueService {
                public static boolean gate;
                public static boolean enabled() { return gate; }
                public int value() { return enabled() ? 1 : 0; }
            }
        """.trimIndent())
        return root
    }

    private fun assertBootArchive(artifact: Path) = JarFile(artifact.toFile()).use { jar ->
        assertEquals("org.springframework.boot.loader.launch.JarLauncher", jar.manifest.mainAttributes.getValue("Main-Class"))
        assertEquals("sample.App", jar.manifest.mainAttributes.getValue("Start-Class"))
        assertTrue(jar.getJarEntry("BOOT-INF/classes/sample/App.class") != null)
        assertTrue(jar.getJarEntry("BOOT-INF/lib/library-1.jar") != null)
        assertTrue(jar.getJarEntry("BOOT-INF/classes/sample/Logic.class") == null)
        assertTrue(jar.entries().asSequence().any { it.name.startsWith("BOOT-INF/lib/spring-context-") })
    }

    private fun commit(repository: Path): String = org.eclipse.jgit.api.Git.open(repository.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("Boot packaged golden").setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }
}
