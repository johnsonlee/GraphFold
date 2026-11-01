package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradleProductionInventoryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `Gradle inventories processed resources runtime dependencies packaged inputs and generated classes`() {
        val source = project()
        val readme = source.resolve("README.md")
        val resource = source.resolve("src/main/resources/messages.properties")
        Files.createDirectories(resource.parent)
        Files.writeString(resource, "greeting=Hello\n")
        Files.writeString(readme, "initial")
        val runtimeInput = source.resolve("runtime-input.jar")
        val runtime = source.resolve("build/runtime/runtime.jar")
        writeRuntime(runtimeInput, "runtime=true")
        val build = source.resolve("build.gradle")
        val script = """
            plugins { id 'java' }
            tasks.register('generateRuntime', Copy) {
                from('runtime-input.jar')
                into(layout.buildDirectory.dir('runtime'))
                rename { 'runtime.jar' }
            }
            dependencies {
                runtimeOnly files(layout.buildDirectory.file('runtime/runtime.jar')).builtBy('generateRuntime')
            }
        """.trimIndent()
        Files.writeString(build, script)
        val baseline = compile(source, "baseline")
        val manifest = parseGradleInputs(Files.readAllLines(temporary.resolve("baseline/gradle-inputs.txt")))
        val runtimePaths = manifest.filter { it.kind == GradleInputKind.RUNTIME_DEPENDENCY }.map { it.paths.single() }
        val expectedRuntime = listOf(source.resolve("build/classes/java/main"), source.resolve("build/resources/main"), runtime)
        assertEquals(expectedRuntime.map { it.toRealPath() }, runtimePaths.map { it.toRealPath() })
        assertEquals(listOf(":"), manifest.single { it.kind == GradleInputKind.RUNTIME_CLASSPATH }.text)
        assertFalse(runtime.toRealPath() in baseline.dependencies.map { it.toRealPath() })
        assertTrue(manifest.any { it.kind == GradleInputKind.RESOURCE })
        assertTrue(manifest.any { it.kind == GradleInputKind.ARTIFACT && Files.isRegularFile(it.paths.single()) })
        Files.writeString(readme, "documentation")
        assertEquals(baseline.production, compile(source, "readme-only").production)
        Files.writeString(resource, "greeting=Bonjour\n")
        val translated = compile(source, "translation")
        assertNotEquals(baseline.production, translated.production)
        Files.delete(resource)
        val removed = compile(source, "resource-removed")
        assertNotEquals(translated.production, removed.production)
        assertFalse(Files.exists(source.resolve("build/resources/main/messages.properties")))
        assertTrue(requireNotNull(removed.production).entries.keys.none { it.endsWith("/messages.properties") })
        Files.writeString(build, script + "\njar { from('README.md') }\n")
        val packaged = compile(source, "packaged")
        Files.writeString(readme, "packaged-change")
        assertNotEquals(packaged.production, compile(source, "packaged-readme").production)
        Files.writeString(build, script + "\n" + generatorScript())
        val generated = compile(source, "generated")
        assertTrue(Files.isRegularFile(generated.classes.resolve("example/Generated.class")))
        Files.writeString(readme, "generated-change")
        val changed = compile(source, "generated-readme")
        assertNotEquals(Files.readAllBytes(generated.classes.resolve("example/Generated.class")).toList(),
            Files.readAllBytes(changed.classes.resolve("example/Generated.class")).toList())
        assertNotEquals(generated.production, changed.production)
        writeRuntime(runtimeInput, "runtime=false")
        assertNotEquals(changed.production, compile(source, "runtime-generated-change").production)
    }

    @Test
    fun `incomplete production exports cannot claim a complete inventory`() {
        val source = Files.createDirectory(temporary.resolve("protocol"))
        val classes = Files.createDirectory(source.resolve("classes"))
        Files.write(classes.resolve("Example.class"), byteArrayOf(1))
        val encoded = Base64.getEncoder().encodeToString(classes.toString().toByteArray())
        val manifest = source.resolve("export.txt")
        Files.writeString(source.resolve("gradlew"), """
            for arg in "${'$'}@"; do case "${'$'}arg" in
              -Dgraphfold.export.path=*) cp export.txt "${'$'}{arg#*=}";;
            esac; done
        """.trimIndent())
        val adapter = GradleBuildAdapter(Duration.ofSeconds(30))
        Files.writeString(manifest, "classes|$encoded")
        assertNull(adapter.compile(source, Files.createDirectory(temporary.resolve("legacy"))).production)
        listOf("productionComplete", "productionComplete\nproductionComplete").forEachIndexed { index, completion ->
            Files.writeString(manifest, "classes|$encoded\n$completion")
            assertFailsWith<BuildFailure> {
                adapter.compile(source, Files.createDirectory(temporary.resolve("incomplete-$index")))
            }
        }
    }

    @Test
    fun `published documentation and Java test fixtures preserve complete production inventory`() {
        val source = project()
        Files.writeString(source.resolve("build.gradle"), """
            plugins {
                id 'java-library'
                id 'java-test-fixtures'
            }
            java {
                withSourcesJar()
                withJavadocJar()
            }
        """.trimIndent())
        val fixture = source.resolve("src/testFixtures/java/example/AppFixture.java")
        Files.createDirectories(fixture.parent)
        Files.writeString(fixture, "package example; public class AppFixture { public int value() { return 1; } }")
        val readme = source.resolve("README.md")
        Files.writeString(readme, "initial documentation")
        val baseline = compile(source, "published-baseline")
        val manifest = parseGradleInputs(Files.readAllLines(temporary.resolve("published-baseline/gradle-inputs.txt")))
        assertTrue(manifest.any { it.kind == GradleInputKind.PRODUCTION_COMPLETE })
        val artifacts = manifest.filter { it.kind == GradleInputKind.ARTIFACT }.flatMap { it.paths }
        assertEquals(listOf("production-inventory.jar"), artifacts.map { it.fileName.toString() })
        assertFalse(Files.exists(baseline.classes.resolve("example/AppFixture.class")))
        Files.writeString(readme, "updated documentation")
        Files.writeString(fixture, "package example; public class AppFixture { public int value() { return 2; } }")
        assertEquals(baseline.production, compile(source, "published-nonproduction-change").production)
    }

    @ParameterizedTest
    @ValueSource(strings = ["feature", "testFixtures"])
    fun `additional consumable runtime feature variants leave production inventory unavailable`(featureName: String) {
        val source = project()
        Files.writeString(source.resolve("build.gradle"), """
            plugins { id 'java-library' }
            sourceSets { $featureName }
            java { registerFeature('$featureName') { usingSourceSet(sourceSets.$featureName) } }
        """.trimIndent())
        val feature = source.resolve("src/$featureName/java/example/Feature.java")
        Files.createDirectories(feature.parent)
        Files.writeString(feature, "package example; public class Feature {}")
        val result = ProjectCompiler(Duration.ofMinutes(2)).compile(source, temporary.resolve("feature-output"))
        assertTrue(Files.isRegularFile(result.classes.resolve("example/App.class")))
        assertNull(result.production)
        val manifest = parseGradleInputs(Files.readAllLines(temporary.resolve("feature-output/gradle-inputs.txt")))
        assertTrue(manifest.none { it.kind == GradleInputKind.PRODUCTION_COMPLETE })
    }

    private fun writeRuntime(path: Path, content: String) {
        ZipOutputStream(Files.newOutputStream(path)).use { archive ->
            archive.putNextEntry(ZipEntry("runtime.properties"))
            archive.write(content.toByteArray())
            archive.closeEntry()
        }
    }

    private fun compile(source: Path, name: String): CompiledProject =
        ProjectCompiler(Duration.ofMinutes(2)).compile(source, temporary.resolve(name)).also { assertNotNull(it.production) }

    private fun project(): Path {
        val source = Files.createDirectory(temporary.resolve("project"))
        val repo = Path.of(System.getProperty("user.dir")).let { if (Files.exists(it.resolve("gradlew"))) it else it.parent }
        Files.copy(repo.resolve("gradlew"), source.resolve("gradlew"))
        Files.createDirectories(source.resolve("gradle/wrapper"))
        listOf("gradle-wrapper.jar", "gradle-wrapper.properties").forEach {
            Files.copy(repo.resolve("gradle/wrapper/$it"), source.resolve("gradle/wrapper/$it"))
        }
        Files.writeString(source.resolve("settings.gradle"), "rootProject.name='production-inventory'")
        val java = source.resolve("src/main/java/example/App.java")
        Files.createDirectories(java.parent)
        Files.writeString(java, "package example; public class App { public int value() { return 1; } }")
        return source
    }

    private fun generatorScript(): String = """
        def generated = layout.buildDirectory.dir('generated/source')
        tasks.register('generateSource') {
            inputs.file('README.md')
            outputs.dir(generated)
            doLast {
                def source = new File(generated.get().asFile, 'example/Generated.java')
                source.parentFile.mkdirs()
                source.text = 'package example; public class Generated { public String value() { return "' +
                    file('README.md').text + '"; } }'
            }
        }
        sourceSets.main.java.srcDir(generated)
        compileJava.dependsOn('generateSource')
    """.trimIndent()
}
