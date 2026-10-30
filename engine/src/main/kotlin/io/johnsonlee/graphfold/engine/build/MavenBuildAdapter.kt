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
        val extracted = CompilerOriginPlugin.extract(output)
        val identity = digest(extracted)
        val state = Files.createDirectories(checkout.resolve("target/graphfold/maven"))
        val identityFile = state.resolve("identity.txt")
        val configurationIdentity = identity + effective.values.joinToString("") { digest(it) }
        checkBuild(!Files.exists(identityFile) || Files.readString(identityFile) == configurationIdentity,
            "Maven compiler configuration changed; previous incremental provenance cannot establish equivalent compiler inputs")
        val provider = state.resolve("compiler-origin-plugin.jar")
        if (!Files.exists(provider) || Files.mismatch(extracted, provider) != -1L) {
            Files.copy(extracted, provider, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val runId = "maven-v1-$identity"
        val provenance = MavenProvenance(state, runId)
        val outputs = modules.map { it.resolve("target/classes") }
        val inventory = MavenClassInventory(checkout, state, outputs)
        inventory.prepare()
        provenance.prepare()
        MavenOriginOverlay(checkout, state, provider, effective, runId).use { overlay ->
            runBuild(
                checkout, output.resolve("build.log"), executable + listOf(
                    "package", "-DskipTests", "-Dmaven.test.skip=true", "dependency:build-classpath", "-Dmdep.includeScope=compile",
                    "-Dmdep.outputFile=target/graphfold-classpath.txt",
                ), timeout,
            )
            val dependencies = dependencies(modules).filterNot(overlay::isProviderDependency)
            checkBuild(dependencies.all { Files.exists(it) }, "Maven exported missing dependencies")
            inventory.reconcile(provenance.pending)
            val classes = collectClasses(output, outputs)
            val origins = output.resolve("origins")
            provenance.publish(classes, origins)
            Files.writeString(identityFile, configurationIdentity)
            return CompiledProject(classes, roots, dependencies, origins, runId)
        }
    }

    private fun dependencies(modules: List<Path>): List<Path> =
        modules.filter { Files.isDirectory(it.resolve("target/classes")) }.flatMap { module ->
            val manifest = module.resolve("target/graphfold-classpath.txt")
            checkBuild(Files.exists(manifest), "Maven did not export analysis dependencies")
            Files.readString(manifest).trim().split(File.pathSeparator).filter(String::isNotBlank).map(Path::of)
        }.filterNot { dependency -> modules.any { dependency.startsWith(it.resolve("target")) } }.distinct()
}
