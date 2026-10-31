package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectCompilerTest {
    @TempDir
    lateinit var temporary: Path
    private val compiler = ProjectCompiler(Duration.ofSeconds(60))

    private val mavenEffectiveModel = """
        for argument in "${'$'}@"; do
          case "${'$'}argument" in -Doutput=*) cp pom.xml "${'$'}{argument#-Doutput=}"; exit 0;; esac
        done

    """.trimIndent() + "\n"

    @Test
    fun `plain Java compile produces classes with source information`() {
        val source = project()
        val output = Files.createDirectory(temporary.resolve("output"))
        val result = compiler.compile(source, output)
        assertTrue(Files.exists(result.classes.resolve("example/App.class")))
        assertEquals(listOf(source.resolve("src/main/java")), result.sourceRoots)
        assertTrue(result.dependencies.isEmpty())
    }

    @Test
    fun `reusing compiler output cannot mix old classes or provenance with a new compilation`() {
        val source = project()
        val output = temporary.resolve("output")
        val result = compiler.compile(source, output)
        val classFile = result.classes.resolve("example/App.class")
        val originalClass = Files.readAllBytes(classFile)
        val runId = Files.readString(output.resolve("origin-run-id.txt"))
        Files.writeString(source.resolve("src/main/java/example/App.java"), "not valid Java")
        val failure = assertFailsWith<BuildFailure> { compiler.compile(source, output) }
        assertTrue("must be empty" in requireNotNull(failure.message))
        assertContentEquals(originalClass, Files.readAllBytes(classFile))
        assertEquals(runId, Files.readString(output.resolve("origin-run-id.txt")))
    }

    @Test
    fun `nonempty user output is rejected without deleting unrelated files`() {
        val source = project()
        val output = Files.createDirectory(temporary.resolve("output"))
        val existing = output.resolve("keep.txt")
        Files.writeString(existing, "user content")
        assertFailsWith<BuildFailure> { compiler.compile(source, output) }
        assertEquals("user content", Files.readString(existing))
        assertFalse(Files.exists(output.resolve("origin-run-id.txt")))
    }

    @Test
    fun `compile errors are typed failures and not successful empty builds`() {
        val source = project("not valid Java")
        assertFailsWith<BuildFailure> { compiler.compile(source, temporary.resolve("out")) }
    }

    @Test
    fun `missing sources and module declarations are explicit failures`() {
        val empty = Files.createDirectory(temporary.resolve("empty"))
        assertFailsWith<BuildFailure> { compiler.compile(empty, temporary.resolve("out1")) }
        val source = project()
        Files.writeString(source.resolve("src/main/java/module-info.java"), "module example {}")
        assertFailsWith<BuildFailure> { compiler.compile(source, temporary.resolve("out3")) }
        assertFailsWith<IllegalArgumentException> { ProjectCompiler(Duration.ZERO) }
    }

    @Test
    fun `Gradle project without wrapper is rejected`() {
        val source = project()
        Files.writeString(source.resolve("build.gradle.kts"), "plugins { java }")
        assertFailsWith<BuildFailure> { compiler.compile(source, temporary.resolve("out")) }
    }

    @Test
    fun `duplicate output classes cannot be silently overwritten`() {
        val a = Files.createDirectory(temporary.resolve("a"))
        val b = Files.createDirectory(temporary.resolve("b"))
        Files.write(a.resolve("A.class"), byteArrayOf(1))
        Files.write(b.resolve("A.class"), byteArrayOf(2))
        assertFailsWith<BuildFailure> { collectClasses(temporary.resolve("out"), listOf(a, b)) }
        assertFailsWith<BuildFailure> { collectClasses(temporary.resolve("empty"), emptyList()) }
    }

    @Test
    fun `build timeout and interruption stop execution`() {
        assertFailsWith<BuildFailure> {
            runBuild(temporary, temporary.resolve("timeout.log"), listOf("sh", "-c", "sleep 30"), Duration.ofMillis(10))
        }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> {
                runBuild(temporary, temporary.resolve("interrupt.log"), listOf("sh", "-c", "sleep 30"), Duration.ofSeconds(60))
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertFailsWith<BuildFailure> {
            runBuild(temporary, temporary.resolve("failure.log"), listOf("sh", "-c", "exit 9"), Duration.ofSeconds(10))
        }
    }

    @Test
    fun `Gradle export compiles mixed language modules and captures external classpath`() {
        val source = project()
        val repo = Path.of(System.getProperty("user.dir")).let { if (Files.exists(it.resolve("gradlew"))) it else it.parent }
        Files.copy(repo.resolve("gradlew"), source.resolve("gradlew"))
        Files.createDirectories(source.resolve("gradle/wrapper"))
        listOf("gradle-wrapper.jar", "gradle-wrapper.properties").forEach {
            Files.copy(repo.resolve("gradle/wrapper/$it"), source.resolve("gradle/wrapper/$it"))
        }
        Files.writeString(source.resolve("settings.gradle"), "rootProject.name='fixture'\ninclude 'library', 'deep', 'unrelated'\n")
        Files.writeString(source.resolve("build.gradle"), """
            plugins { id 'org.jetbrains.kotlin.jvm' version '2.1.20' }
            allprojects { apply plugin: 'java'; repositories { mavenCentral() } }
            dependencies { implementation project(':library'); implementation files('external') }
            tasks.register('graphFoldCheck') { doLast { throw new GradleException('must not invoke lint') } }
            tasks.named('compileKotlin') {
                doFirst {
                    def arguments = compilerOptions.freeCompilerArgs.get()
                    assert arguments.any { it.startsWith('-Xplugin=') }
                    ['output=', 'runId=', 'inlineSources='].each { option ->
                        assert arguments.any { it.startsWith('plugin:io.johnsonlee.graphfold.origin.kotlin:' + option) }
                    }
                    def deepBuild = project(':deep').layout.buildDirectory.get().asFile.toPath()
                    assert !sourceSets.main.compileClasspath.files.any { it.toPath().startsWith(deepBuild) }
                }
            }
        """.trimIndent())
        val library = Files.createDirectories(source.resolve("library/src/main/java/example"))
        Files.writeString(library.resolve("Library.java"),
            "package example; public class Library { public Object callback() { return new Object() {}; } }")
        Files.writeString(source.resolve("library/build.gradle"),
            "plugins { id 'org.jetbrains.kotlin.jvm'; id 'java-library' }; dependencies { implementation project(':deep') }")
        val deep = Files.createDirectories(source.resolve("deep/src/main/kotlin/example"))
        Files.writeString(source.resolve("deep/build.gradle"), "plugins { id 'org.jetbrains.kotlin.jvm'; id 'java-library' }")
        Files.writeString(deep.resolve("InlineDeep.kt"), "package example; inline fun deepCallback(): Any = object {}")
        val libraryKotlin = Files.createDirectories(source.resolve("library/src/main/kotlin/example"))
        Files.writeString(libraryKotlin.resolve("InlineLibrary.kt"),
            "package example; inline fun libraryCallback(): Any = deepCallback()")
        val unrelated = Files.createDirectories(source.resolve("unrelated/src/main/kotlin/isolated"))
        Files.writeString(source.resolve("unrelated/build.gradle"), "plugins { id 'org.jetbrains.kotlin.jvm' }")
        Files.writeString(unrelated.resolve("Independent.kt"), "package isolated; inline fun independent(): Any = object {}")
        val kotlin = Files.createDirectories(source.resolve("src/main/kotlin/example"))
        Files.writeString(kotlin.resolve("KotlinApp.kt"),
            "package example; class KotlinApp : Library() { fun callbackKt(): Any = object {} }")
        Files.writeString(kotlin.resolve("Untouched.kt"), "package example; class Untouched { fun callback(): Any = object {} }")
        val inlineSource = kotlin.resolve("InlineFactory.kt")
        Files.writeString(inlineSource, "package example; inline fun inlineCallback(): Any = object {}")
        Files.writeString(kotlin.resolve("InlineCaller.kt"),
            "package example; fun inlineCaller(): Any = inlineCallback(); fun libraryCaller(): Any = libraryCallback()")
        val external = Files.createDirectories(source.resolve("external"))
        Files.writeString(source.resolve("src/main/java/example/App.java"), "package example; public class App extends Library {}")
        Files.writeString(source.resolve("gradle.properties"), "org.gradle.caching=true\norg.gradle.logging.level=info\n")
        val result = compiler.compile(source, temporary.resolve("out"))
        assertTrue(Files.exists(result.classes.resolve("example/App.class")))
        assertTrue(Files.exists(result.classes.resolve("example/Library.class")))
        assertTrue(external.toRealPath() in result.dependencies.map { it.toRealPath() })
        assertTrue(Files.exists(result.classes.resolve("example/KotlinApp.class")))
        assertTrue(result.dependencies.any { it.fileName.toString().startsWith("kotlin-stdlib-") })
        assertEquals(6, result.sourceRoots.size)
        val origins = Files.list(requireNotNull(result.originDirectory)).use { files ->
            files.filter { it.toString().endsWith(".json") }.map {
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(it.toFile())
            }.toList()
        }
        assertTrue(origins.all { it["runId"].asText() == result.originRunId })
        val owners = origins.flatMap { it["records"].map { record -> record["owner"].asText() } }.toSet()
        assertTrue("example.Library${'$'}1" in owners, owners.toString())
        assertTrue(owners.any { it.startsWith("example.KotlinApp${'$'}") }, owners.toString())
        Files.list(temporary.resolve("out")).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().endsWith(".gradle") })
        }
        verifyIncrementalBuilds(source, kotlin, inlineSource, result)
    }

    private fun verifyIncrementalBuilds(source: Path, kotlin: Path, inlineSource: Path, result: CompiledProject) {
        val plugin = CompilerOriginPlugin.artifact()
        val trustedPlugin = Files.readAllBytes(plugin)
        val unchanged = compiler.compile(source, temporary.resolve("unchanged"))
        assertContentEquals(trustedPlugin, Files.readAllBytes(plugin))
        assertFalse(Files.exists(source.resolve(".gradle/graphfold")))
        assertTrue(Files.readString(temporary.resolve("unchanged/build.log")).contains(":compileKotlin UP-TO-DATE"))
        assertTrue(Files.readString(temporary.resolve("unchanged/build.log")).contains(":library:compileJava UP-TO-DATE"))
        assertEquals(result.originRunId, unchanged.originRunId)
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(unchanged, source).diagnostics.isEmpty())
        Files.writeString(source.resolve("src/main/java/example/App.java"),
            "package example; public class App extends Library { public int value() { return 2; } }")
        val changed = compiler.compile(source, temporary.resolve("changed"))
        assertTrue(Files.readString(temporary.resolve("changed/build.log")).contains(":library:compileJava UP-TO-DATE"))
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(changed, source).diagnostics.isEmpty())
        val retainedClass = source.resolve("build/classes/kotlin/main/example/Untouched${'$'}callback${'$'}1.class")
        val retainedTimestamp = Files.getLastModifiedTime(retainedClass)
        Files.writeString(kotlin.resolve("KotlinApp.kt"),
            "package example; class KotlinApp : Library() { fun callbackKt(): Any = object { fun value() = 2 } }")
        val kotlinChanged = compiler.compile(source, temporary.resolve("kotlinChanged"))
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(kotlinChanged, source).diagnostics.isEmpty())
        val kotlinLog = Files.readString(temporary.resolve("kotlinChanged/build.log"))
        assertTrue(kotlinLog.contains("Using Kotlin/JVM incremental compilation"), kotlinLog)
        assertEquals(retainedTimestamp, Files.getLastModifiedTime(retainedClass), "Unchanged class must not be re-emitted")
        assertContentEquals(Files.readAllBytes(changed.classes.resolve("example/Untouched${'$'}callback${'$'}1.class")),
            Files.readAllBytes(kotlinChanged.classes.resolve("example/Untouched${'$'}callback${'$'}1.class")))
        Files.delete(kotlin.resolve("Untouched.kt"))
        val deleted = compiler.compile(source, temporary.resolve("deleted"))
        val deletedOrigins = io.johnsonlee.graphfold.engine.origin.readOrigins(deleted, source)
        assertTrue(deletedOrigins.diagnostics.isEmpty(), deletedOrigins.diagnostics.toString())
        assertTrue(deletedOrigins.origins.none { it.owner.startsWith("example.Untouched") })
        val inlineDefinitions = listOf(inlineSource, source.resolve("library/src/main/kotlin/example/InlineLibrary.kt"),
            source.resolve("deep/src/main/kotlin/example/InlineDeep.kt"))
        inlineDefinitions.forEachIndexed { index, file ->
            Files.writeString(file, "\n" + Files.readString(file))
            val build = temporary.resolve("inlineChanged$index")
            val inlineChanged = runCatching { compiler.compile(source, build) }.getOrElse {
                throw AssertionError(Files.readString(build.resolve("build.log")), it)
            }
            assertTrue(Files.readString(build.resolve("build.log")).contains(":unrelated:compileKotlin UP-TO-DATE"))
            val inlineOrigins = io.johnsonlee.graphfold.engine.origin.readOrigins(inlineChanged, source)
            assertTrue(inlineOrigins.diagnostics.isEmpty(), inlineOrigins.diagnostics.toString())
            assertTrue(inlineOrigins.origins.any {
                it.owner.startsWith("example.InlineCallerKt${'$'}") && it.path == "deep/src/main/kotlin/example/InlineDeep.kt"
            }, "App inline copy must retain the hidden implementation dependency's physical source")
        }
        source.resolve("build").toFile().deleteRecursively()
        source.resolve("library/build").toFile().deleteRecursively()
        val cached = compiler.compile(source, temporary.resolve("cached"))
        val cachedLog = Files.readString(temporary.resolve("cached/build.log"))
        assertTrue(cachedLog.contains(":compileKotlin FROM-CACHE"), cachedLog)
        assertTrue(cachedLog.contains(":library:compileJava FROM-CACHE"), cachedLog)
        assertTrue(io.johnsonlee.graphfold.engine.origin.readOrigins(cached, source).diagnostics.isEmpty())
    }

    @Test
    fun `Maven custom layout is rejected before executing build`() {
        val source = project()
        Files.writeString(source.resolve("pom.xml"), "<project><build><sourceDirectory>custom</sourceDirectory></build></project>")
        assertFailsWith<BuildFailure> { compiler.compile(source, temporary.resolve("out")) }
    }

    @Test
    fun `Gradle missing malformed and incomplete exports fail closed`() {
        val source = project()
        val wrapper = source.resolve("gradlew")
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n")
        assertFailsWith<BuildFailure> { compiler.compile(source, temporary.resolve("out1")) }
        val selectManifest = "for arg in \"${'$'}@\"; do case \"${'$'}arg\" in " +
            "-Dgraphfold.export.path=*) manifest=\"${'$'}{arg#*=}\";; esac; done\n"
        Files.writeString(wrapper, selectManifest + "echo malformed > \"${'$'}manifest\"\n")
        assertFailsWith<BuildFailure> { compiler.compile(source, temporary.resolve("out2")) }
        val absent = java.util.Base64.getEncoder().encodeToString(temporary.resolve("absent.jar").toString().toByteArray())
        Files.writeString(wrapper, selectManifest + "echo 'dependency|$absent' > \"${'$'}manifest\"\n")
        assertFailsWith<BuildFailure> { compiler.compile(source, temporary.resolve("out3")) }
    }

    @Test
    fun `Maven wrapper export captures classes and dependency paths`() {
        val source = project()
        Files.writeString(source.resolve("pom.xml"), "<project/>")
        Files.writeString(source.resolve("mvnw"), mavenEffectiveModel + """
            mkdir -p target/classes
            plugin_arg=${'$'}(sed -n 's/.*<arg>\(-Xplugin:GraphFoldOrigins.*\)<\/arg>.*/\1/p' pom.xml | head -1)
            provider=${'$'}(find target/graphfold/maven/origin-maven-repository -name '*.jar' -print -quit)
            javac -g -processorpath "${'$'}provider" "${'$'}plugin_arg" \
              -d target/classes src/main/java/example/App.java
            mkdir -p external
            printf '%s' "${'$'}PWD/external" > target/graphfold-classpath.txt
        """.trimIndent())
        val result = compiler.compile(source, temporary.resolve("out"))
        assertTrue(Files.exists(result.classes.resolve("example/App.class")))
        assertEquals(listOf(source.resolve("external").toRealPath()), result.dependencies.map { it.toRealPath() })
        // A failed build may leave unowned outputs; recovery must rebuild instead of poisoning the slot.
        Files.write(source.resolve("target/classes/Unowned.class"), byteArrayOf(1))
        Files.writeString(source.resolve("target/graphfold/maven/incomplete"), "interrupted")
        val recovered = compiler.compile(source, temporary.resolve("recovered"))
        assertFalse(Files.exists(recovered.classes.resolve("Unowned.class")))
        assertFalse(Files.exists(source.resolve("target/graphfold/maven/incomplete")))
    }

    @Test
    fun `Maven absent and missing dependency exports fail closed`() {
        val source = project()
        Files.writeString(source.resolve("pom.xml"), "<project/>")
        Files.writeString(source.resolve("mvnw"), mavenEffectiveModel + "mkdir -p target/classes\nexit 0")
        assertFailsWith<BuildFailure> { compiler.compile(source, temporary.resolve("out1")) }
        Files.writeString(source.resolve("mvnw"),
            mavenEffectiveModel + "mkdir -p target\necho /nonexistent/graphfold-dependency.jar > target/graphfold-classpath.txt")
        assertFailsWith<BuildFailure> { compiler.compile(source, temporary.resolve("out2")) }
    }

    @Test
    fun `test Kotlin and generated build directories do not hide production Java`() {
        assertFailsWith<IllegalArgumentException> { ProjectCompiler(Duration.ofSeconds(-1)) }
        val source = project()
        val tests = Files.createDirectories(source.resolve("src/test/kotlin"))
        Files.writeString(tests.resolve("Test.kt"), "class Test")
        val generated = Files.createDirectories(source.resolve("build/src/main/java"))
        Files.writeString(generated.resolve("Ignored.kt"), "class Ignored")
        listOf("src/it/fixture", "src/test/resources/fixture", "examples/unlisted").forEach { path ->
            val fixture = Files.createDirectories(source.resolve("$path/src/main/java"))
            Files.writeString(fixture.resolve("Broken.java"), "deliberately invalid Java")
        }
        assertTrue(Files.exists(compiler.compile(source, temporary.resolve("out")).classes.resolve("example/App.class")))
        Files.writeString(source.resolve("build.gradle"), "apply plugin: 'java'")
        assertFailsWith<BuildFailure> { compiler.compile(source, temporary.resolve("out2")) }
    }

    @Test
    fun `plain Kotlin compiles with standard library and source debug information`() {
        val root = Files.createDirectories(temporary.resolve("kotlin/src/main/kotlin/example"))
        Files.writeString(root.resolve("Example.kt"), """
            package example
            class Example { fun value(values: List<Int>): Int = values.filter { it > 0 }.sum() }
        """.trimIndent())
        val result = compiler.compile(temporary.resolve("kotlin"), temporary.resolve("out"))
        assertTrue(Files.exists(result.classes.resolve("example/Example.class")))
        assertEquals(listOf(root.parent), result.sourceRoots)
        assertTrue(result.dependencies.any { it.fileName.toString().startsWith("kotlin-stdlib-") })
        val bytes = Files.readAllBytes(result.classes.resolve("example/Example.class"))
        val node = org.objectweb.asm.tree.ClassNode()
        org.objectweb.asm.ClassReader(bytes).accept(node, 0)
        assertEquals("Example.kt", node.sourceFile)
        assertTrue(node.methods.flatMap { it.instructions.toList() }.any { it is org.objectweb.asm.tree.LineNumberNode })
    }

    @Test
    fun `mixed Java and Kotlin resolve references in both directions`() {
        val source = project("package example; public class App { public int value() { return new Other().value(); } }")
        val root = Files.createDirectories(source.resolve("src/main/kotlin/example"))
        Files.writeString(root.resolve("Other.kt"), """
            package example
            class Other { fun value(): Int = 1; fun app(): App = App() }
        """.trimIndent())
        val result = compiler.compile(source, temporary.resolve("out"))
        assertTrue(Files.exists(result.classes.resolve("example/App.class")))
        assertTrue(Files.exists(result.classes.resolve("example/Other.class")))
        assertEquals(2, result.sourceRoots.size)
    }

    @Test
    fun `Kotlin compiler errors fail closed`() {
        val root = Files.createDirectories(temporary.resolve("kotlin/src/main/kotlin"))
        Files.writeString(root.resolve("Example.kt"), "class Example { invalid syntax }")
        assertFailsWith<BuildFailure> { compiler.compile(temporary.resolve("kotlin"), temporary.resolve("out")) }
        assertTrue(Files.readString(temporary.resolve("out/kotlin-build.log")).contains("error:"))
    }

    private fun project(code: String = "package example; public class App { public int value() { return 1; } }"): Path {
        val source = Files.createDirectories(temporary.resolve("project/src/main/java/example"))
        Files.writeString(source.resolve("App.java"), code)
        return temporary.resolve("project")
    }
}
