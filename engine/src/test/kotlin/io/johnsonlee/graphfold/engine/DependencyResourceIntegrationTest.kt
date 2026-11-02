package io.johnsonlee.graphfold.engine

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyResourceIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @ParameterizedTest
    @EnumSource(ResourcePath::class)
    fun `real Kotlin runtime dependencies only block changes reached by surviving readers`(path: ResourcePath) {
        val repository = repository(path)
        write(repository, RESOURCE, "old=Hello")
        val base = commit(repository)
        write(repository, RESOURCE, "old=Hello\nnew=Bonjour")
        val result = analyze(repository, base, commit(repository))
        assertEquals(path.verdict, result.verdict, result.toString())
        assertTrue(result.findings.isEmpty(), "Resource-only changes must not invent source findings: $result")
        assertEquals(RESOURCE, result.reasons.single().headPath)
        if (path.verdict == Verdict.PASS) {
            assertTrue(result.diagnostics.isEmpty(), result.toString())
            assertEquals(ReasonCode.FOLDED_EQUIVALENT, result.reasons.single().code)
            assertEquals(1, result.metadata.provedObligations)
        } else {
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, result.toString())
            assertEquals(ReasonCode.ANALYSIS_INCOMPLETE, result.reasons.single().code)
        }
        if (path == ResourcePath.TEMPLATE) {
            write(repository, RESOURCE, "old=Changed\nnew=Bonjour")
            val liveKey = analyze(repository, base, commit(repository))
            assertEquals(Verdict.FAIL, liveKey.verdict, liveKey.toString())
            assertTrue(liveKey.diagnostics.any { it.code == DiagnosticCode.PRODUCTION_OUTPUT_CHANGED }, liveKey.toString())
        }
    }

    private fun repository(path: ResourcePath): Path {
        val root = Files.createDirectory(temporary.resolve("repository"))
        val project = Path.of(System.getProperty("user.dir")).let { if (Files.exists(it.resolve("gradlew"))) it else it.parent }
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { name ->
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(project.resolve(name), target)
        }
        write(root, ".gitignore", ".gradle/\nbuild/\n")
        write(root, "settings.gradle", "rootProject.name='dependency-resource-proof'")
        write(root, "build.gradle", """
            plugins { id 'org.jetbrains.kotlin.jvm' version '2.1.20' }
            repositories { mavenCentral() }
            dependencies {
                implementation files('libs/readers.jar')
                runtimeOnly files('libs/runtime-helpers.jar')
            }
        """.trimIndent())
        write(root, "src/main/java/sample/Ab.java",
            "package sample; public class Ab { public static boolean enabled(String key) { return true; } }")
        if (path == ResourcePath.ANNOTATED_FIELD) write(root, "src/main/java/sample/Injected.java", """
            package sample;
            public final class Injected {
                @external.Value("${'$'}{new}") private String value = "";
                public String value() { return value; }
            }
        """.trimIndent())
        write(root, "src/main/kotlin/sample/Example.kt", """
            package sample
            class Example {
                fun value(name: ${path.parameterType}): String {
                    val bundle = java.util.ResourceBundle.getBundle("messages")
                    if (Ab.enabled("feature")) return ${path.treatment}
                    return ${path.control}
                }
            }
        """.trimIndent())
        dependency(root)
        Git.init().setDirectory(root.toFile()).call().close()
        return root
    }

    private fun dependency(repository: Path) {
        val source = temporary.resolve("dependency-source")
        val helpers = runtimeDependency(repository, source)
        write(source, "external/Value.java", """
            package external;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
            public @interface Value { String value(); }
        """.trimIndent())
        write(source, "external/Reader.java", """
            package external;
            public final class Reader {
                public interface Callback { String get(); }
                public static String hardcoded() {
                    return java.util.ResourceBundle.getBundle("messages").getString("new");
                }
                public static String dynamic(String name) {
                    return java.util.ResourceBundle.getBundle(name).getString("new");
                }
                public static String callback(Callback callback) { return callback.get(); }
                public static String identity(String value) { return value; }
                public static String runtimeIdentity(String value) { return runtime.Helper.identity(value); }
                public static String runtimeReader() { return runtime.Helper.read(); }
            }
        """.trimIndent())
        write(source, "external/Initializer.java", """
            package external;
            public final class Initializer {
                private static final String VALUE = java.util.ResourceBundle.getBundle("messages").getString("new");
                public static String value() { return VALUE; }
            }
        """.trimIndent())
        val classes = Files.createDirectories(temporary.resolve("dependency-classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-classpath", helpers.toString(), "-d", classes.toString(),
            source.resolve("external/Reader.java").toString(), source.resolve("external/Initializer.java").toString(),
            source.resolve("external/Value.java").toString()))
        archive(classes, repository.resolve("libs/readers.jar"))
        assertTrue(!Files.exists(classes.resolve("runtime/Helper.class")), "The runtime-only helper must not enter the compile dependency")
    }

    private fun runtimeDependency(repository: Path, source: Path): Path {
        write(source, "runtime/Helper.java", """
            package runtime;
            public final class Helper {
                public static String identity(String value) { return value; }
                public static String read() {
                    return java.util.ResourceBundle.getBundle("messages").getString("new");
                }
            }
        """.trimIndent())
        val classes = Files.createDirectories(temporary.resolve("runtime-dependency-classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(),
            source.resolve("runtime/Helper.java").toString()))
        archive(classes, repository.resolve("libs/runtime-helpers.jar"))
        return classes
    }

    private fun archive(classes: Path, jar: Path) {
        Files.createDirectories(jar.parent)
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classes).use { files ->
                files.filter(Files::isRegularFile).sorted().forEach { file ->
                    output.putNextEntry(JarEntry(classes.relativize(file).joinToString("/")))
                    Files.copy(file, output)
                    output.closeEntry()
                }
            }
        }
    }

    private fun analyze(repository: Path, base: String, head: String): AnalysisResult =
        GraphFoldEngine(EngineConfiguration(temporary.resolve("cache"), buildTimeout = Duration.ofMinutes(2)))
            .analyze(AnalysisRequest(repository.toString(), base, head, """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: sample.Ab, callee_name: enabled}}
                    args: {0: feature}
                    value: false
            """.trimIndent()))

    private fun commit(root: Path): String = Git.open(root.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("fixture").setAuthor("GraphFold", "test@example.invalid")
            .setCommitter("GraphFold", "test@example.invalid").call().name
    }

    private fun write(root: Path, path: String, content: String) {
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    enum class ResourcePath(
        val control: String, val verdict: Verdict,
        val treatment: String = "bundle.getString(\"new\")", val parameterType: String = "String",
    ) {
        TEMPLATE("\"\${bundle.getString(\"old\")}!\"", Verdict.PASS),
        UNRELATED("external.Reader.identity(bundle.getString(\"old\"))", Verdict.PASS),
        FOLDED_READER("bundle.getString(\"old\")", Verdict.PASS, "external.Reader.hardcoded()"),
        HARDCODED("external.Reader.hardcoded()", Verdict.FAIL),
        DYNAMIC("external.Reader.dynamic(name)", Verdict.FAIL),
        CALLBACK("external.Reader.callback { java.util.ResourceBundle.getBundle(\"messages\").getString(\"new\") }", Verdict.FAIL),
        INITIALIZER("external.Initializer.value()", Verdict.FAIL),
        RUNTIME_IDENTITY("external.Reader.runtimeIdentity(bundle.getString(\"old\"))", Verdict.PASS),
        RUNTIME_READER("external.Reader.runtimeReader()", Verdict.FAIL),
        UNKNOWN_SEQUENCE("name.trim().toString()", Verdict.FAIL, parameterType = "CharSequence"),
        ANNOTATED_FIELD("Injected().value()", Verdict.FAIL),
    }

    private companion object {
        const val RESOURCE = "src/main/resources/messages.properties"
    }
}
