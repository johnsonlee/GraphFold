package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.AnalysisResult
import io.johnsonlee.graphfold.engine.AnalysisStage
import io.johnsonlee.graphfold.engine.ChangeKind
import io.johnsonlee.graphfold.engine.DiagnosticCode
import io.johnsonlee.graphfold.engine.ReasonCode
import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import io.johnsonlee.graphfold.engine.inputs.archive.InputFailure
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchiveClasspathIntegrationTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `independent static scopes with different dependency versions retain exact change verdicts`(live: Boolean) {
        val repository = repository()
        val base = commit(repository)
        write(repository, "one/src/main/java/one/App.java", program("one", if (live) "10" else "11", if (live) "+ 1" else ""))
        write(repository, "two/src/main/java/two/App.java", program("two", "12", ""))
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), """
                version: 1
                folds:
                  - select: |
                      MATCH (cs:CallSite {callee_name: "enabled"})
                      RETURN cs
                    value: false
            """.trimIndent()),
        )
        assertEquals(if (live) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(setOf("baseline [scope :one:jar]", "baseline [scope :two:jar]"), result.metadata.scenarios.toSet())
        assertEquals(4, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
        if (live) {
            val finding = result.findings.single()
            assertTrue(finding.message.contains("one.App.value()I"), result.toString())
            assertEquals(listOf("baseline [scope :one:jar]"), finding.scenarios)
            assertTrue(finding.locations.isNotEmpty())
            assertTrue(finding.locations.all { it.path == "one/src/main/java/one/App.java" })
            assertEquals(4, result.metadata.obligations)
            assertEquals(2, result.metadata.provedObligations)
        } else {
            assertTrue(result.findings.isEmpty())
            assertEquals(result.metadata.obligations, result.metadata.provedObligations, result.toString())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `rules may match unchanged scopes but a rule absent from every scope still blocks`(missing: Boolean) {
        val repository = repository()
        val base = commit(repository)
        write(repository, "two/src/main/java/two/App.java", program("two", "12", ""))
        val owners = listOf("one", "two") + if (missing) listOf("absent") else emptyList()
        val rules = owners.joinToString("\n") { owner ->
            """
              - select: |
                  MATCH (cs:CallSite {callee_class: "$owner.Gate", callee_name: "enabled"})
                  RETURN cs
                value: false
            """.trimIndent().prependIndent("  ")
        }
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), "version: 1\nfolds:\n$rules"),
        )
        assertEquals(if (missing) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
        assertEquals(if (missing) listOf(DiagnosticCode.UNMATCHED_RULE) else emptyList(), result.diagnostics.map { it.code })
        assertTrue(result.findings.isEmpty(), result.toString())
        assertEquals(setOf("baseline [scope :one:jar]", "baseline [scope :two:jar]"), result.metadata.scenarios.toSet())
        assertEquals(listOf(2, 2), (0..1).map { rule ->
            result.ruleAudits.filter { it.ruleIndex == rule }.sumOf { it.matchedCallCount ?: 0 }
        })
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED })
        assertEquals(result.metadata.obligations, result.metadata.provedObligations, result.toString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unowned source input cannot hide a live production change`(deleted: Boolean) {
        val repository = repository()
        if (deleted) write(repository, "outside/Example.java", "class Example { int value() { return 2; } }")
        val base = commit(repository)
        if (deleted) Git.open(repository.toFile()).use { it.rm().addFilepattern("outside/Example.java").call() }
        else write(repository, "outside/Example.java", "class Example { int value() { return 2; } }")
        write(repository, "one/src/main/java/one/App.java", program("one", "10", "+ 1"))
        val result = analyze(repository, base)
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.findings.isNotEmpty(), result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.SOURCE_RESOLUTION_FAILED }, result.toString())
        assertTrue(result.reasons.any { it.code == ReasonCode.BUILD_OUTPUT_UNCHANGED &&
            ChangeKind.BUILD_INPUT_CHANGED in it.changes }, result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.ANALYSIS_FAILED }, result.toString())
        assertTrue(result.metadata.obligations > result.metadata.provedObligations)
        assertTrue(result.ruleAudits.filter { it.scenario.endsWith("[scope :one:jar]") }
            .all { it.selectionStatus == RuleSelectionStatus.SELECTED })
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unowned source additions and deletions are proved by unchanged packaged outputs`(deleted: Boolean) {
        val repository = repository()
        if (deleted) write(repository, "outside/Example.java", "class Example { int value() { return 2; } }")
        val base = commit(repository)
        if (deleted) Git.open(repository.toFile()).use { it.rm().addFilepattern("outside/Example.java").call() }
        else write(repository, "outside/Example.java", "class Example { int value() { return 2; } }")
        val result = analyze(repository, base)
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.reasons.any { it.code == ReasonCode.BUILD_OUTPUT_UNCHANGED &&
            ChangeKind.BUILD_INPUT_CHANGED in it.changes }, result.toString())
        assertEquals(result.metadata.obligations, result.metadata.provedObligations)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.NOT_EXECUTED })
    }

    @Test fun `adding an application archive cannot borrow another archives proof`() {
        val repository = repository()
        val base = commit(repository)
        write(repository, "settings.gradle", "rootProject.name='archive-scopes'; include 'one', 'two', 'three'")
        write(repository, "three/src/main/java/three/App.java", "package three; public class App { " +
            "public static void main(String[] args) { new App().value(); } " +
            "public int value() { return 3; } }")
        write(repository, "one/src/main/java/one/App.java", program("one", "10", "+ 1"))
        val result = analyze(repository, base)
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.findings.isNotEmpty(), result.toString())
        assertTrue(result.findings.flatMap { it.locations }.any {
            it.path == "three/src/main/java/three/App.java" && it.revision == Revision.HEAD
        }, result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.ANALYSIS_FAILED }, result.toString())
        assertTrue(result.metadata.obligations > result.metadata.provedObligations)
        val addedAudits = result.ruleAudits.filter { it.scenario.endsWith("[scope :three:jar]") }
        assertEquals(2, addedAudits.size)
        assertTrue(addedAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED }, result.toString())
    }

    @Test fun `a rule already matched in the changed scope does not inspect unrelated scopes`() {
        val repository = repository()
        val base = commit(repository)
        write(repository, "two/src/main/java/two/App.java", program("two", "12", ""))
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), """
                version: 1
                folds:
                  - select: |
                      MATCH (cs:CallSite {callee_class: "two.Gate", callee_name: "enabled"})
                      RETURN cs
                    value: false
            """.trimIndent()),
        )
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertEquals(result.metadata.obligations, result.metadata.provedObligations, result.toString())
        assertEquals(listOf("baseline [scope :two:jar]"), result.metadata.scenarios)
        assertEquals(2, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
    }

    @Test fun `runtime scenarios retain separate enabled and disabled results`() {
        val repository = repository()
        val base = commit(repository)
        write(repository, "two/src/main/java/two/App.java", program("two", "12", ""))
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), """
                scenarios:
                  - name: disabled
                    folds:
                      - select: |
                          MATCH (cs:CallSite {callee_class: "two.Gate", callee_name: "enabled"})
                          RETURN cs
                        value: false
                  - name: enabled
                    folds:
                      - select: |
                          MATCH (cs:CallSite {callee_class: "two.Gate", callee_name: "enabled"})
                          RETURN cs
                        value: true
            """.trimIndent()),
        )
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(setOf("enabled [scope :two:jar]"),
            result.reasons.filter { it.code == ReasonCode.SURVIVING_DIFFERENCE }.map { it.scenario }.toSet())
        assertTrue(result.reasons.none { it.code == ReasonCode.ANALYSIS_INCOMPLETE }, result.toString())
        assertEquals(0, result.metadata.provedObligations, "A detected output difference is not an equivalence proof")
        assertTrue(result.findings.isNotEmpty(), result.toString())
        assertTrue(result.findings.all { it.scenarios.toSet() == setOf("enabled [scope :two:jar]") }, result.toString())
        assertEquals(setOf("disabled [scope :two:jar]", "enabled [scope :two:jar]"), result.metadata.scenarios.toSet())
        assertEquals(4, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1 })
    }

    @Test fun `moving an unchanged public class between scopes changes packaged visibility`() {
        val repository = repository()
        val source = "package shared; public class Moved { public int value() { return 7; } }"
        val oldPath = "one/src/main/java/shared/Moved.java"
        val newPath = "two/src/main/java/shared/Moved.java"
        write(repository, oldPath, source)
        val base = commit(repository)
        Git.open(repository.toFile()).use { it.rm().addFilepattern(oldPath).call() }
        write(repository, newPath, source)
        val result = analyze(repository, base)
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(setOf(DiagnosticCode.UNLOCATED_MOVE_DIFFERENCE, DiagnosticCode.PRODUCTION_OUTPUT_CHANGED),
            result.diagnostics.map { it.code }.toSet(), result.toString())
        assertTrue(result.diagnostics.all { it.stage == AnalysisStage.COMPARE })
        assertTrue(result.findings.isEmpty(), result.toString())
        assertEquals(setOf("baseline [scope :one:jar]", "baseline [scope :two:jar]"), result.metadata.scenarios.toSet())
        assertEquals(setOf("baseline [scope :one:jar]", "baseline [scope :two:jar]"),
            result.reasons.filter { it.code == ReasonCode.SURVIVING_DIFFERENCE }.map { it.scenario }.toSet())
        assertTrue(result.reasons.all { it.basePath == oldPath && it.headPath == newPath }, result.toString())
        assertEquals(setOf("baseline [scope :one:jar]", "baseline [scope :two:jar]"),
            result.reasons.filter { ChangeKind.FILE_MOVED in it.changes }.map { it.scenario }.toSet(), result.toString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `adding or removing a packaged application does not abort shared application changes`(removed: Boolean) {
        val repository = repository()
        write(repository, "settings.gradle", "rootProject.name='archive-scopes'; include 'one', 'two'" +
            if (removed) ", 'three'" else "")
        val path = "three/src/main/java/three/App.java"
        val source = "package three; public class App { " +
            "public static void main(String[] args) { new App().value(); } " +
            "public int value() { return 3; } }"
        if (removed) write(repository, path, source)
        val base = commit(repository)
        if (removed) Git.open(repository.toFile()).use { it.rm().addFilepattern(path).call() }
        else write(repository, path, source)
        write(repository, "settings.gradle", "rootProject.name='archive-scopes'; include 'one', 'two'" +
            if (removed) "" else ", 'three'")
        write(repository, "one/src/main/java/one/App.java", program("one", "10", "+ 1"))
        val result = analyze(repository, base)
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.findings.flatMap { it.locations }.any {
            it.path == path && it.revision == if (removed) Revision.BASE else Revision.HEAD
        }, result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.ANALYSIS_FAILED }, result.toString())
        assertTrue(result.findings.any { "baseline [scope :one:jar]" in it.scenarios }, result.toString())
        assertTrue(result.metadata.obligations > result.metadata.provedObligations)
    }

    @ParameterizedTest
    @CsvSource("false,false", "true,false", "false,true", "true,true")
    fun `declared APIs resolve while missing types block changed scopes`(changed: Boolean, declaredApi: Boolean) {
        val repository = repository()
        val provided = Files.createDirectory(temporary.resolve("provided"))
        val source = provided.resolve("Missing.java")
        Files.writeString(source, "package provided; public class Missing {}")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", provided.toString(), source.toString()))
        val jar = repository.resolve("libs/provided.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("provided/Missing.class"))
            Files.copy(provided.resolve("provided/Missing.class"), zip)
            zip.closeEntry()
        }
        val compilerInput = if (declaredApi) {
            "dependencies { compileOnly files(rootProject.file('libs/provided.jar')) }"
        } else {
            // A custom compiler-only input is deliberately absent from the exported dependency model.
            "tasks.named('compileJava') { classpath += files(rootProject.file('libs/provided.jar')) }"
        }
        Files.writeString(repository.resolve("build.gradle"),
            "\nproject(':one') { $compilerInput }", java.nio.file.StandardOpenOption.APPEND)
        write(repository, "one/src/main/java/one/App.java", program("one", "10", "")
            .replace("public class App {", "public class App { public provided.Missing unavailable;"))
        val base = commit(repository)
        if (changed) write(repository, "one/src/main/java/one/App.java", program("one", "11", "")
            .replace("public class App {", "public class App { public provided.Missing unavailable;"))
        write(repository, "two/src/main/java/two/App.java", program("two", "12", ""))
        val rules = listOf("one", "two").joinToString("\n") { owner ->
            """
              - select: |
                  MATCH (cs:CallSite {callee_class: "$owner.Gate", callee_name: "enabled"})
                  RETURN cs
                value: false
            """.trimIndent().prependIndent("  ")
        }
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), "version: 1\nfolds:\n$rules"),
        )
        val missingChangedType = changed && !declaredApi
        assertEquals(if (missingChangedType) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
        if (missingChangedType) assertTrue(result.diagnostics.any { it.code == DiagnosticCode.INCOMPLETE_GRAPH &&
            it.message.contains("provided.Missing") }, result.toString())
        else assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(result.metadata.obligations, result.metadata.provedObligations, result.toString())
        assertEquals(setOf("baseline [scope :one:jar]", "baseline [scope :two:jar]"), result.metadata.scenarios.toSet())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED })
        assertEquals(2, result.ruleAudits.filter { it.scenario.endsWith("[scope :one:jar]") && it.ruleIndex == 0 }
            .sumOf { it.matchedCallCount ?: 0 })
    }

    @Test fun `unchanged scope probes cannot invent a match when folding selection fails`() {
        val repository = repository()
        write(repository, "build.gradle", "subprojects { apply plugin: 'application'; application { mainClass = project.name + '.App' } }")
        write(repository, "one/src/main/java/one/Gate.java",
            "package one; public class Gate { public static native boolean enabled(); }")
        write(repository, "one/src/main/java/one/App.java", program("one", "10", "")
            .replace("library.Value.get()", "0"))
        write(repository, "two/src/main/java/two/App.java", program("two", "10", "")
            .replace("library.Value.get()", "0"))
        val base = commit(repository)
        write(repository, "two/src/main/java/two/App.java", program("two", "12", "")
            .replace("library.Value.get()", "0"))
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), """
                version: 1
                folds:
                  - select: |
                      MATCH (cs:CallSite {callee_class: "one.Gate", callee_name: "enabled"})
                      RETURN cs LIMIT 1
                    value: false
                  - select: |
                      MATCH (cs:CallSite {callee_class: "two.Gate", callee_name: "enabled"})
                      RETURN cs
                    value: false
            """.trimIndent()),
        )
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertEquals(result.metadata.obligations, result.metadata.provedObligations, result.toString())
        assertTrue(result.diagnostics.any { it.stage == AnalysisStage.FOLD &&
            it.message.contains("Could not establish rule matches") }, result.toString())
        assertTrue(result.ruleAudits.any { it.scenario.endsWith("[scope :one:jar]") && it.matchedCallCount == null })
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.UNMATCHED_RULE }, result.toString())
    }

    @Test fun `replacing every scope keeps source and build input obligations explicitly unproved`() {
        val repository = repository()
        val base = commit(repository)
        write(repository, "settings.gradle", "rootProject.name='archive-scopes'; include 'three'")
        write(repository, "build.gradle", "subprojects { apply plugin: 'application'; application { mainClass = project.name + '.App' } }")
        write(repository, "three/src/main/java/three/App.java",
            "package three; public class App { " +
            "public static void main(String[] args) { new App().value(); } " +
            "public int value() { return 3; } }")
        val result = analyze(repository, base)
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.findings.flatMap { it.locations }.any {
            it.path == "three/src/main/java/three/App.java" && it.revision == Revision.HEAD
        }, result.toString())
        assertEquals(setOf("baseline [scope :one:jar]", "baseline [scope :two:jar]", "baseline [scope :three:jar]"),
            result.metadata.scenarios.toSet())
        assertTrue(result.ruleAudits.isNotEmpty())
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED }, result.toString())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.ANALYSIS_FAILED }, result.toString())
    }

    @Test fun `conflicting definitions in one selected scope remain an explicit input failure`() {
        val repository = repository()
        val build = repository.resolve("build.gradle")
        Files.writeString(build, Files.readString(build).replace("files(rootProject.file('libs/second.jar'))",
            "files(rootProject.file('libs/second.jar'), rootProject.file('libs/first.jar'))"))
        val base = commit(repository)
        write(repository, "two/src/main/java/two/App.java", program("two", "12", ""))
        val failure = assertFailsWith<InputFailure> { analyze(repository, base) }
        assertTrue(failure.message.contains("library/Value"), failure.message)
    }

    private fun analyze(repository: Path, base: String): AnalysisResult =
        PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), """
                version: 1
                folds:
                  - select: |
                      MATCH (cs:CallSite {callee_name: "enabled"})
                      RETURN cs
                    value: false
            """.trimIndent()),
        )

    private fun repository(): Path {
        val root = Files.createDirectory(temporary.resolve("repository"))
        val project = Path.of(System.getProperty("graphfold.test.repository"))
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { name ->
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(project.resolve(name), target)
        }
        write(root, ".gitignore", ".gradle/\nbuild/\n")
        write(root, "settings.gradle", "rootProject.name='archive-scopes'; include 'one', 'two'")
        write(root, "build.gradle", """
            subprojects { apply plugin: 'application'; application { mainClass = project.name + '.App' } }
            project(':one') { dependencies { implementation files(rootProject.file('libs/first.jar')) } }
            project(':two') {
                dependencies { implementation files(rootProject.file('libs/second.jar')) }
            }
        """.trimIndent())
        dependency(root, "first", "1")
        dependency(root, "second", "2")
        listOf("one", "two").forEach { module ->
            write(root, "$module/src/main/java/$module/Gate.java",
                "package $module; public class Gate { public static boolean enabled() { return true; } }")
            write(root, "$module/src/main/java/$module/App.java", program(module, "10", ""))
        }
        Git.init().setDirectory(root.toFile()).call().close()
        return root
    }

    private fun dependency(root: Path, name: String, value: String) {
        val directory = Files.createDirectory(temporary.resolve(name))
        val source = directory.resolve("Value.java")
        Files.writeString(source, "package library; public class Value { public static int get() { return $value; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()))
        val jar = root.resolve("libs/$name.jar")
        Files.createDirectories(jar.parent)
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("library/Value.class"))
            Files.copy(directory.resolve("library/Value.class"), zip)
            zip.closeEntry()
        }
    }

    private fun program(module: String, controlled: String, live: String): String = """
        package $module;
        public class App {
            public static void main(String[] args) { new App().value(); }
            public int value() { if (Gate.enabled()) return $controlled; return library.Value.get() $live; }
        }
    """.trimIndent()

    private fun write(root: Path, name: String, text: String) {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun commit(root: Path): String = Git.open(root.toFile()).use { git ->
        ensureSnapshotPlugin(git.repository.workTree.toPath())
        git.add().addFilepattern(".").call()
        git.commit().setMessage("scope comparison").setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }
}
