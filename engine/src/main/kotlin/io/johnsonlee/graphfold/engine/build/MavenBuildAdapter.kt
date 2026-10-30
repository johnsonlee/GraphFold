package io.johnsonlee.graphfold.engine.build

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Supports conventional Maven JVM layouts; explicit custom layouts fail instead of being omitted. */
internal class MavenBuildAdapter(private val timeout: Duration) {
    fun compile(checkout: Path, output: Path): CompiledProject {
        val roots = productionRoots(checkout)
        val pomFiles = regularFiles(checkout).filter { it.fileName.toString() == "pom.xml" }
            .filter { path -> checkout.relativize(path).none { it.toString() in setOf("target", "build", ".git") } }
        val modules = pomFiles.map { it.parent }.distinct()
        if (pomFiles.any { Files.readString(it).contains("<sourceDirectory>") }) {
            throw BuildFailure("Custom Maven source directories require an explicit source export adapter")
        }
        val executable = if (Files.exists(checkout.resolve("mvnw"))) listOf("sh", "./mvnw") else listOf("mvn")
        val effective = pomFiles.mapIndexed { index, pom ->
            val model = output.resolve("effective-$index.xml").toAbsolutePath()
            runBuild(checkout, output.resolve("maven-model-$index.log"), executable + listOf(
                "-N", "-f", pom.toAbsolutePath().toString(), "help:effective-pom", "-Doutput=$model",
            ), timeout)
            checkBuild(Files.isRegularFile(model), "Maven did not export its effective compiler configuration")
            pom to model
        }.toMap()
        val runId = CompilerOriginPlugin.runId(output)
        val provider = CompilerOriginPlugin.extract(output)
        MavenOriginOverlay(checkout, output, provider, effective, runId).use { overlay ->
            runBuild(
                checkout, output.resolve("build.log"), executable + listOf(
                    "package", "-DskipTests", "-Dmaven.test.skip=true", "dependency:build-classpath", "-Dmdep.includeScope=compile",
                    "-Dmdep.outputFile=target/graphfold-classpath.txt",
                ), timeout,
            )
            val outputs = modules.map { it.resolve("target/classes") }
            val dependencies = dependencies(modules).filterNot(overlay::isProviderDependency)
            checkBuild(dependencies.all { Files.exists(it) }, "Maven exported missing dependencies")
            return CompiledProject(collectClasses(output, outputs), roots, dependencies, output.resolve("origins"), runId)
        }
    }

    private fun dependencies(modules: List<Path>): List<Path> =
        modules.filter { Files.isDirectory(it.resolve("target/classes")) }.flatMap { module ->
            val manifest = module.resolve("target/graphfold-classpath.txt")
            checkBuild(Files.exists(manifest), "Maven did not export analysis dependencies")
            Files.readString(manifest).trim().split(File.pathSeparator).filter(String::isNotBlank).map(Path::of)
        }.filterNot { dependency -> modules.any { dependency.startsWith(it.resolve("target")) } }.distinct()
}
