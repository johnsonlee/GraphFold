package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.ChangeKind
import io.johnsonlee.graphfold.engine.DiagnosticCode
import io.johnsonlee.graphfold.engine.FindingKind
import io.johnsonlee.graphfold.engine.ReasonCode
import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.IntInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildInputIntegrationTest : BuildInputFixture() {
    @Test
    fun `README CI and unpackaged translations pass from actual unchanged production outputs`() {
        val repository = repository()
        val paths = listOf("README.md", ".github/workflows/ci.yml", "translations/messages.properties")
        paths.forEach { write(repository, it, "before") }
        val base = commit(repository)
        paths.forEach { write(repository, it, "after") }
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertEquals(paths.toSet(), result.reasons.mapNotNull { it.headPath }.toSet())
        assertTrue(result.reasons.all { it.code == ReasonCode.BUILD_OUTPUT_UNCHANGED }, result.toString())
        assertEquals(paths.size, result.metadata.provedObligations)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.NOT_EXECUTED })
    }

    @Test
    fun `test only Java changes pass from actual packaged output equality without source obligations`() {
        val repository = repository()
        val test = "src/test/java/sample/ExampleTest.java"
        write(repository, test, "package sample; public class ExampleTest { public int expected() { return 1; } }")
        val base = commit(repository)
        write(repository, test, "package sample; public class ExampleTest { public int expected() { return 2; } }")
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertEquals(ReasonCode.BUILD_OUTPUT_UNCHANGED, result.reasons.single().code)
        assertEquals(setOf(ChangeKind.BUILD_INPUT_CHANGED), result.reasons.single().changes)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.NOT_EXECUTED })
    }

    @Test
    fun `mixed test and production changes use whole program evidence and findings remain on production source`() {
        val repository = repository()
        val test = "src/test/java/sample/ExampleTest.java"
        write(repository, test, "package sample; public class ExampleTest { public int expected() { return 1; } }")
        val base = commit(repository)
        write(repository, test, "package sample; public class ExampleTest { public int expected() { return 2; } }")
        write(repository, sourcePath, program("return x;", treatment = 43))
        val controlled = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, controlled.verdict, controlled.toString())
        assertEquals(ReasonCode.FOLDED_EQUIVALENT, controlled.reasons.single { it.headPath == test }.code)
        write(repository, sourcePath, program("return x + 1;", treatment = 43))
        val uncontrolled = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, uncontrolled.verdict, uncontrolled.toString())
        assertTrue(uncontrolled.findings.isNotEmpty(), uncontrolled.toString())
        assertTrue(uncontrolled.findings.flatMap { it.locations }.all { it.path == sourcePath }, uncontrolled.toString())
        assertTrue(uncontrolled.diagnostics.none { it.code == DiagnosticCode.SOURCE_RESOLUTION_FAILED }, uncontrolled.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["tools/BuildValue.java", "src/test/java/sample/BuildValue.java"])
    fun `Java file used as a custom build input cannot borrow declaration proof or a test path exemption`(generator: String) {
        val repository = repository()
        write(repository, sourcePath, program("return Generated.value();"))
        write(repository, generator, "class BuildValue { static final int VALUE = 1; }")
        val script = generatorScript().replace("README.md", generator)
            .replace("file('$generator').text", "file('$generator').text.replaceAll('[^0-9]', '')")
        write(repository, "build.gradle", "plugins { id 'application' }; application { mainClass = 'sample.Launcher' }\n" + script)
        val base = commit(repository)
        write(repository, generator, "class BuildValue { static final int VALUE = 2; }")
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED && "Generated" in it.message })
        assertTrue(result.findings.isEmpty(), "The changed generator input is not a packaged source declaration")
        assertEquals(generator, result.reasons.single().headPath)
        assertEquals(setOf(ChangeKind.BUILD_INPUT_CHANGED), result.reasons.single().changes)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `source findings cannot conceal other live generated class changes in the same application`(live: Boolean) {
        val repository = repository()
        val generatedScript = generatorScript().replace("README.md", sourcePath)
            .replace("file('$sourcePath').text", "(file('$sourcePath').text =~ /return ([0-9]+);/)[0][1]")
        write(repository, "build.gradle", "plugins { id 'application' }; application { mainClass = 'sample.Launcher' }\n" +
            generatedScript)
        write(repository, "src/main/java/sample/GeneratedReader.java", """
            package sample;
            public class GeneratedReader {
                public int read() { return Generated.value(); }
            }
        """.trimIndent())
        val base = commit(repository)
        write(repository, sourcePath, program(if (live) "return x + 1;" else "return x;", treatment = 43))
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        val values = packagedContents("build-input-proof.jar", "sample/Generated.class").map { bytes ->
            val generated = ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
            val method = generated.methods.single { it.name == "value" && it.desc == "()I" }
            assertTrue(method.instructions.any { it.opcode == Opcodes.IRETURN })
            method.instructions.filterIsInstance<IntInsnNode>().single { it.opcode == Opcodes.BIPUSH }.operand
        }
        assertEquals(setOf(42, 43), values.toSet())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED && "Generated.value" in it.message },
            result.toString())
        val sourceReason = if (live) ReasonCode.SURVIVING_DIFFERENCE else ReasonCode.FOLDED_EQUIVALENT
        assertTrue(result.reasons.any { it.code == sourceReason && it.headPath == sourcePath })
        assertTrue(result.reasons.any { it.code == ReasonCode.SURVIVING_DIFFERENCE && it.headPath == sourcePath &&
            it.changes.isEmpty() }, result.toString())
        assertEquals(live, result.findings.isNotEmpty(), result.toString())
        assertTrue(result.findings.flatMap { it.locations }.all { it.path == sourcePath }, result.toString())
        assertTrue(result.findings.flatMap { it.evidence }.none { "Generated.value" in it.description }, result.toString())
    }

    @Test
    fun `a folded production source change still requires proof for its live packaged source resource`() {
        val repository = repository()
        write(repository, "build.gradle", """
            plugins { id 'application' }
            application { mainClass = 'sample.Launcher' }
            jar { from('src/main/java/sample/Example.java') { rename { 'messages.properties' } } }
        """.trimIndent())
        write(repository, "src/main/java/sample/SourceReader.java", """
            package sample;
            public class SourceReader {
                public String read() { return java.util.ResourceBundle.getBundle("messages").getString("if"); }
            }
        """.trimIndent())
        val base = commit(repository)
        write(repository, sourcePath, program("return x;", treatment = 43))
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        val contents = packagedContents("build-input-proof.jar", "messages.properties").map { it.decodeToString() }
        assertTrue(contents.any { "return 42" in it } && contents.any { "return 43" in it }, contents.toString())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED && "messages.properties" in it.message })
        assertTrue(result.reasons.any { it.code == ReasonCode.FOLDED_EQUIVALENT && it.headPath == sourcePath },
            "The declaration proof remains separate from the failed packaged-resource proof")
        assertTrue(result.reasons.any { it.code == ReasonCode.ANALYSIS_INCOMPLETE && it.headPath == sourcePath })
    }

    @Test
    fun `a changed application archive must prove resource effects driven by another applications production source`() {
        val repository = repository()
        listOf(sourcePath, "src/main/java/sample/Ab.java", "src/main/java/sample/Launcher.java").forEach { path ->
            val moved = repository.resolve("producer/$path")
            Files.createDirectories(moved.parent)
            Files.move(repository.resolve(path), moved)
        }
        write(repository, "settings.gradle", "rootProject.name='cross-archive'; include 'producer', 'reader'")
        write(repository, "build.gradle", """
            subprojects { apply plugin: 'application'; application { mainClass = 'sample.Launcher' } }
            project(':reader') {
                jar { from(rootProject.file('producer/src/main/java/sample/Example.java')) { rename { 'messages.properties' } } }
            }
        """.trimIndent())
        write(repository, "reader/src/main/java/sample/Launcher.java",
            "package sample; public class Launcher { public static void main(String[] args) {} }")
        write(repository, "reader/src/main/java/sample/SourceReader.java", """
            package sample;
            public class SourceReader {
                public String read() { return java.util.ResourceBundle.getBundle("messages").getString("if"); }
            }
        """.trimIndent())
        val base = commit(repository)
        write(repository, "producer/$sourcePath", program("return x;", treatment = 43))
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        val contents = packagedContents("reader.jar", "messages.properties").map { it.decodeToString() }
        assertTrue(contents.any { "return 42" in it } && contents.any { "return 43" in it }, contents.toString())
        assertTrue(result.reasons.any { it.scenario.endsWith("[scope :reader:jar]") && it.code == ReasonCode.ANALYSIS_INCOMPLETE })
        assertTrue(result.reasons.any { it.scenario.endsWith("[scope :producer:jar]") && it.code == ReasonCode.FOLDED_EQUIVALENT })
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED })
    }

    @Test
    fun `Kotlin README change passes from identical compiler outputs without matching a fold`() {
        val repository = repository()
        Files.delete(repository.resolve(sourcePath))
        Files.delete(repository.resolve("src/main/java/sample/Ab.java"))
        write(repository, "build.gradle", """
            plugins { id 'org.jetbrains.kotlin.jvm' version '2.1.20'; id 'application' }; application { mainClass = 'sample.Launcher' }
            repositories { mavenCentral() }
        """.trimIndent())
        write(repository, "src/main/kotlin/sample/Example.kt", """
            package sample
            class Example {
                fun callback(): Runnable = object : Runnable {
                    override fun run() {}
                }
                fun callbackName(): String = callback().javaClass.name
            }
        """.trimIndent())
        write(repository, "README.md", "before")
        val base = commit(repository)
        write(repository, "README.md", "after")
        // The configured sample.Ab.enabled selector cannot match this pure Kotlin program.
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.diagnostics.isEmpty(), result.toString())
        assertTrue(result.findings.isEmpty(), result.toString())
        assertEquals(ReasonCode.BUILD_OUTPUT_UNCHANGED, result.reasons.single().code)
        assertEquals("README.md", result.reasons.single().headPath)
        assertEquals(1, result.metadata.provedObligations)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.NOT_EXECUTED }, result.toString())

    }

    @Test
    fun `packaged translation changes block with production output evidence`() {
        val repository = repository()
        val path = "src/main/resources/messages.properties"
        write(repository, path, "greeting=Hello")
        write(repository, sourcePath,
            program("return java.util.ResourceBundle.getBundle(\"messages\").getString(\"greeting\").length();"))
        val base = commit(repository)
        write(repository, path, "greeting=Bonjour")
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(listOf(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED), result.diagnostics.map { it.code })
        assertTrue(result.findings.isEmpty(), "A resource gap must not invent a source-code violation")
        assertEquals(path, result.reasons.single().headPath)
        assertEquals(ReasonCode.ANALYSIS_INCOMPLETE, result.reasons.single().code)
    }

    @Test
    fun `mixed README and source changes require folded whole program proof`() {
        val repository = repository()
        write(repository, "README.md", "before")
        val base = commit(repository)
        write(repository, "README.md", "after")
        write(repository, sourcePath, program("return x;", treatment = 43))
        val controlled = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, controlled.verdict, controlled.toString())
        assertTrue(controlled.diagnostics.isEmpty(), controlled.toString())
        assertTrue(controlled.findings.isEmpty(), controlled.toString())
        assertEquals(2, controlled.metadata.obligations)
        assertEquals(2, controlled.metadata.provedObligations)
        assertEquals(ReasonCode.FOLDED_EQUIVALENT, controlled.reasons.single { it.headPath == "README.md" }.code)
        write(repository, sourcePath, program("return x + 1;", treatment = 43))
        val uncontrolled = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, uncontrolled.verdict, uncontrolled.toString())
        assertTrue(uncontrolled.diagnostics.isEmpty(), uncontrolled.toString())
        assertEquals(2, uncontrolled.metadata.obligations)
        assertEquals(0, uncontrolled.metadata.provedObligations)
        val finding = uncontrolled.findings.single()
        assertEquals(FindingKind.UNVERIFIABLE_CHANGE, finding.kind)
        // The diff includes both treatment 42 -> 43 and the surviving fallback edit in one hunk.
        assertEquals(listOf(
            SourceLocation(Revision.BASE, sourcePath, SourceRange(4, 5), SourceRange(40, 13)),
            SourceLocation(Revision.HEAD, sourcePath, SourceRange(4, 5), SourceRange(40, 17)),
        ), finding.locations, uncontrolled.toString())
        assertTrue(finding.evidence.all { "sample.Example.run(I)I" in it.description }, uncontrolled.toString())
        assertTrue(uncontrolled.ruleAudits.all {
            it.selectionStatus == RuleSelectionStatus.SELECTED && it.matchedCallCount == 1
        }, uncontrolled.toString())
        assertEquals(ReasonCode.SURVIVING_DIFFERENCE, uncontrolled.reasons.single { it.headPath == "README.md" }.code)
    }

    @Test
    fun `README used by a source generator cannot pass as an unrelated document`() {
        val repository = repository()
        write(repository, sourcePath, program("return Generated.value();"))
        write(repository, "README.md", "1")
        write(repository, "build.gradle",
            "plugins { id 'application' }; application { mainClass = 'sample.Launcher' }\n" + generatorScript())
        val base = commit(repository)
        write(repository, "README.md", "2")
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        assertTrue(result.diagnostics.any { "Generated" in it.message }, result.toString())
        assertTrue(result.findings.isEmpty(), "Generated source outside the Git diff cannot become a source-code finding")
        assertEquals("README.md", result.reasons.single().headPath)
        assertEquals(ReasonCode.SURVIVING_DIFFERENCE, result.reasons.single().code)
        assertEquals(0, result.metadata.provedObligations)
    }

    @Test
    fun `excluding a compiled application class from the production jar cannot pass through graph equivalence`() {
        val repository = repository()
        val base = commit(repository)
        write(repository, "build.gradle",
            "plugins { id 'application' }; application { mainClass = 'sample.Launcher' }\n" +
                "jar { exclude('sample/Example.class') }\n")
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertEquals(listOf(DiagnosticCode.PRODUCTION_OUTPUT_CHANGED), result.diagnostics.map { it.code })
        assertTrue(result.diagnostics.single().message.contains("sample.Example"), result.toString())
        assertTrue(result.findings.isEmpty(), "Packaging differences must not invent a Java diff location")
        assertEquals("build.gradle", result.reasons.single().headPath)
        assertEquals(ReasonCode.SURVIVING_DIFFERENCE, result.reasons.single().code)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "return bundle.getString(\"old\");",
        "System.out.println(\"hello\"); return bundle.getString(\"old\");",
        "return bundle.getString(\"old\") + \"!\";",
    ])
    fun `new properties key read only in folded branch passes while live key edit fails`(output: String) {
        val repository = repository()
        val path = "src/main/resources/messages.properties"
        write(repository, path, "old=Hello")
        write(repository, sourcePath, """
            package sample;
            public class Example {
                public String value() {
                    java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("messages");
                    if (Ab.enabled("feature")) return bundle.getString("new");
                    $output
                }
            }
        """.trimIndent())
        val base = commit(repository)
        write(repository, path, "old=Hello\nnew=Bonjour")
        val controlled = analyze(repository, base, commit(repository))
        assertEquals(Verdict.PASS, controlled.verdict, controlled.toString())
        assertTrue(controlled.diagnostics.isEmpty(), controlled.toString())
        assertEquals(1, controlled.metadata.provedObligations)
        write(repository, path, "old=Changed\nnew=Bonjour")
        val uncontrolled = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, uncontrolled.verdict, uncontrolled.toString())
        assertTrue(uncontrolled.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, uncontrolled.toString())
    }

    @Test
    fun `mixed opaque resource and source changes retain source findings despite resource gap`() {
        val repository = repository()
        val path = "src/main/resources/data.txt"
        write(repository, path, "before")
        val base = commit(repository)
        write(repository, path, "after")
        write(repository, sourcePath, program("return x + 1;"))
        val result = analyze(repository, base, commit(repository))
        assertEquals(Verdict.FAIL, result.verdict, result.toString())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        assertTrue(result.findings.isNotEmpty(), result.toString())
        assertTrue(result.findings.flatMap { it.locations }.all { it.path == sourcePath }, result.toString())
        assertTrue(result.reasons.any { it.headPath == path && it.code == ReasonCode.ANALYSIS_INCOMPLETE }, result.toString())
        assertTrue(result.reasons.any { it.headPath == sourcePath && it.code == ReasonCode.SURVIVING_DIFFERENCE }, result.toString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `resource analysis includes exposed static fields without external dependencies or callable constructors`(exposed: Boolean) {
        val repository = repository()
        val path = "src/main/resources/messages.properties"
        val visibility = if (exposed) "public" else "private"
        write(repository, "src/main/java/sample/Extra.java", """
            package sample;
            public final class Extra {
                $visibility static final String VALUE = java.util.ResourceBundle.getBundle("messages").getString("greeting");
                private Extra() {}
            }
        """.trimIndent())
        write(repository, path, "greeting=Hello")
        val base = commit(repository)
        write(repository, path, "greeting=Bonjour")
        val result = analyze(repository, base, commit(repository))
        assertEquals(if (exposed) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), "Resource-only changes must not invent source findings")
        if (exposed) {
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
        } else {
            assertTrue(result.diagnostics.isEmpty(), result.toString())
            assertEquals(ReasonCode.FOLDED_EQUIVALENT, result.reasons.single().code)
        }
    }

    @Test
    fun `a failed resource scenario does not prevent later scenarios from being checked`() {
        val repository = repository()
        val path = "src/main/resources/messages.properties"
        write(repository, path, "greeting=Hello")
        write(repository, sourcePath,
            program("return java.util.ResourceBundle.getBundle(\"messages\").getString(\"greeting\").length();"))
        val base = commit(repository)
        write(repository, path, "greeting=Bonjour")
        val scenarios = listOf("first", "second").joinToString("\n") { name ->
            """
              - name: $name
                folds:
                  - match: {CallSite: {callee_class: sample.Ab, callee_name: enabled}}
                    args: {0: feature}
                    value: false
            """.trimIndent().prependIndent("  ")
        }
        val result = PackagedModelAnalyzer(temporary.resolve("cache")).analyze(
            AnalysisRequest(repository.toString(), base, commit(repository), "scenarios:\n$scenarios"),
        )
        assertEquals(Verdict.FAIL, result.verdict)
        assertEquals(listOf("first", "second"), result.reasons.map { it.scenario })
        assertTrue(result.reasons.all { it.code == ReasonCode.ANALYSIS_INCOMPLETE }, result.toString())
        assertEquals(4, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all { it.selectionStatus == RuleSelectionStatus.SELECTED }, result.toString())
    }

}
