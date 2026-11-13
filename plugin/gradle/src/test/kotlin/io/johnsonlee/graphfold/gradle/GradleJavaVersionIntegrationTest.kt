package io.johnsonlee.graphfold.gradle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.Verdict
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class GradleJavaVersionIntegrationTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(ints = [9, 17, 21])
    fun `JDK 17 build selects only MR 17 despite Java 11 bytecode target`(changedVersion: Int) {
        val repository = Files.createDirectories(temporary.resolve("repository"))
        repository.resolve("settings.gradle").writeText("rootProject.name = 'jdk-selection'\n")
        repository.resolve(".gitignore").writeText("build/\n.gradle/\n")
        repository.resolve("build.gradle").writeText("""
            plugins { id 'java-library'; id 'io.johnsonlee.graphfold' }
            java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
            tasks.withType(JavaCompile).configureEach { options.release = 11 }
            dependencies { implementation files('libs/mr.jar') }
        """.trimIndent())
        val sources = Files.createDirectories(repository.resolve("src/main/java/sample"))
        sources.resolve("Flag.java").writeText("package sample; public class Flag { public static boolean enabled() { return true; } }")
        sources.resolve("Example.java").writeText("""
            package sample;
            public class Example {
                public int value() { return lib.Dependency.value(); }
                public boolean selected() { return Flag.enabled(); }
            }
        """.trimIndent())
        val dependency = Files.createDirectories(repository.resolve("libs")).resolve("mr.jar")
        archive(dependency, null)
        Git.init().setDirectory(repository.toFile()).call().close()
        val base = commit(repository)
        archive(dependency, changedVersion)
        val head = commit(repository)
        val cache = temporary.resolve("cache")
        val result = PackagedModelAnalyzer(cache).analyze(AnalysisRequest(repository.toString(), base, head, """
            folds:
              - select: |
                  MATCH (cs:CallSite {callee_class: "sample.Flag", callee_name: "enabled"}) RETURN cs
                value: false
        """.trimIndent()))
        assertEquals(if (changedVersion == 17) Verdict.FAIL else Verdict.PASS, result.verdict, result.toString())
        assertEquals(listOf("baseline"), result.metadata.scenarios, "A build selects one MR view, never a threshold fan-out")
        if (changedVersion != 17) assertTrue(result.diagnostics.isEmpty(), result.toString())
        val manifests = Files.walk(cache.resolve("snapshots")).use { paths ->
            paths.filter { it.fileName.toString() == "inputs.json" }.toList()
        }
        assertEquals(2, manifests.size)
        manifests.forEach { manifest ->
            val scopes = jacksonObjectMapper().readTree(manifest.toFile())["scopes"]
            assertEquals(1, scopes.size())
            assertEquals(17, scopes.single()["javaVersion"].asInt())
        }
    }

    private fun archive(path: Path, changedVersion: Int?) {
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Multi-Release", "true")
        }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jar ->
            listOf(0, 9, 17, 21).forEach { version ->
                val prefix = if (version == 0) "" else "META-INF/versions/$version/"
                jar.putNextEntry(JarEntry("${prefix}lib/Dependency.class"))
                jar.write(dependencyClass(version + if (version == changedVersion) 1 else 0))
                jar.closeEntry()
            }
        }
    }

    private fun dependencyClass(value: Int): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "lib/Dependency", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, value)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun commit(repository: Path): String = Git.open(repository.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("Build JDK MR selection").setAuthor("GraphFold Test", "test@example.invalid")
            .setCommitter("GraphFold Test", "test@example.invalid").call().name
    }
}
