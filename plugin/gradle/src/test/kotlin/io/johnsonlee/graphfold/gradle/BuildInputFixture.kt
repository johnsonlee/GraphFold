package io.johnsonlee.graphfold.gradle

import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.AnalysisResult
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.io.TempDir

open class BuildInputFixture {
    @TempDir
    lateinit var temporary: Path

    protected fun packagedContents(artifactName: String, entryName: String): List<ByteArray> =
        Files.walk(temporary.resolve("cache/export/git/workspaces")).use { paths ->
            paths.filter { it.fileName.toString() == artifactName }.map { artifact ->
                java.util.jar.JarFile(artifact.toFile()).use { jar ->
                    val entry = requireNotNull(jar.getJarEntry(entryName)) { "Missing packaged entry $entryName in $artifact" }
                    jar.getInputStream(entry).use { it.readBytes() }
                }
            }.toList()
        }

    protected fun repository(): Path {
        val root = Files.createDirectory(temporary.resolve("repository"))
        val project = Path.of(System.getProperty("graphfold.test.repository"))
        listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties").forEach { path ->
            val target = root.resolve(path)
            Files.createDirectories(target.parent)
            Files.copy(project.resolve(path), target)
        }
        write(root, ".gitignore", ".gradle/\n.kotlin/\nbuild/\n")
        write(root, "settings.gradle", "rootProject.name='build-input-proof'")
        write(root, "build.gradle", "plugins { id 'application' }; application { mainClass = 'sample.Launcher' }")
        write(root, "src/main/java/sample/Launcher.java",
            "package sample; public class Launcher { public static void main(String[] args) {} }")
        write(root, sourcePath, program("return x;"))
        write(root, "src/main/java/sample/Ab.java",
            "package sample; public class Ab { public static boolean enabled(String key) { return true; } }")
        Git.init().setDirectory(root.toFile()).call().close()
        return root
    }

    protected fun analyze(repository: Path, base: String, head: String): AnalysisResult =
        PackagedModelAnalyzer(temporary.resolve("cache"))
            .analyze(AnalysisRequest(repository.toString(), base, head, """
                version: 1
                folds:
                  - match: {CallSite: {callee_class: sample.Ab, callee_name: enabled}}
                    args: {0: feature}
                    value: false
            """.trimIndent()))

    protected fun commit(root: Path): String = Git.open(root.toFile()).use { git ->
        ensureSnapshotPlugin(git.repository.workTree.toPath())
        git.add().addFilepattern(".").call()
        git.commit().setMessage("fixture").setAuthor("GraphFold", "test@example.invalid")
            .setCommitter("GraphFold", "test@example.invalid").call().name
    }

    protected fun write(root: Path, path: String, content: String) {
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    protected fun program(body: String, treatment: Int = 42): String = """
        package sample;
        public class Example {
          public int run(int x) {
            if (Ab.enabled("feature")) return $treatment;
            $body
          }
        }
    """.trimIndent()

    protected fun generatorScript(): String = """
        def generated = layout.buildDirectory.dir('generated/source')
        tasks.register('generateSource') {
            inputs.file('README.md')
            outputs.dir(generated)
            doLast {
                def source = new File(generated.get().asFile, 'sample/Generated.java')
                source.parentFile.mkdirs()
                source.text = 'package sample; public class Generated { public static int value() { return ' +
                    file('README.md').text + '; } }'
            }
        }
        sourceSets.main.java.srcDir(generated)
        compileJava.dependsOn('generateSource')
    """.trimIndent()

    protected val sourcePath = "src/main/java/sample/Example.java"
}
