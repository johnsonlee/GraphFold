package io.johnsonlee.graphfold.engine.build

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Supports conventional Maven JVM layouts; explicit custom layouts fail instead of being omitted. */
internal class MavenBuildAdapter(
    private val timeout: Duration,
    private val buildEnvironmentAllowlist: Set<String> = io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT,
) {
    fun compile(checkout: Path, output: Path): CompiledProject {
        val executable = if (Files.exists(checkout.resolve("mvnw"))) listOf("sh", "./mvnw") else listOf("mvn")
        val effective = MavenReactor(checkout).models { pom, index ->
            val model = output.resolve("effective-$index.xml").toAbsolutePath()
            runBuild(checkout, output.resolve("maven-model-$index.log"), executable + listOf(
                "-N", "-f", pom.toAbsolutePath().toString(), "help:effective-pom", "-Doutput=$model",
            ), timeout, buildEnvironmentAllowlist)
            checkBuild(Files.isRegularFile(model), "Maven did not export its effective compiler configuration")
            model
        }
        val modules = effective.keys.map { it.parent }
        val roots = modules.flatMap(::productionRoots)
        val extracted = CompilerOriginPlugin.extract(output)
        val identity = digest(extracted)
        val state = checkout.resolve("target/graphfold/maven")
        val identityFile = state.resolve("identity.txt")
        val configurationIdentity = "maven-v2-$identity" + effective.values.joinToString("") { digest(it) }
        val incomplete = state.resolve("incomplete")
        if (!Files.exists(identityFile) || Files.readString(identityFile) != configurationIdentity || Files.exists(incomplete)) {
            // Reuse is unsafe after build inputs change, but the slot must remain usable on this and later runs.
            modules.map { it.resolve("target") }.forEach { io.johnsonlee.graphfold.engine.git.deleteDirectory(it) }
        }
        Files.createDirectories(state)
        val provider = state.resolve("compiler-origin-plugin.jar")
        if (!Files.exists(provider) || Files.mismatch(extracted, provider) != -1L) {
            Files.copy(extracted, provider, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val runId = "maven-v2-$identity"
        val provenance = MavenProvenance(state, runId)
        val outputs = modules.map { it.resolve("target/classes") }
        val inventory = MavenClassInventory(checkout, state, outputs)
        inventory.prepare()
        provenance.prepare()
        io.johnsonlee.graphfold.engine.git.deleteDirectory(state.resolve("test-origins"))
        Files.writeString(incomplete, configurationIdentity)
        MavenOriginOverlay(checkout, state, provider, effective, runId).use { overlay ->
            runBuild(
                checkout, output.resolve("build.log"), executable + listOf(
                    "package", "-DskipTests", "dependency:build-classpath", "-Dmdep.includeScope=compile",
                    "-Dmdep.outputFile=target/graphfold-classpath.txt",
                ), timeout, buildEnvironmentAllowlist,
            )
            val dependencies = dependencies(modules).filterNot(overlay::isProviderDependency)
            checkBuild(dependencies.all { Files.exists(it) }, "Maven exported missing dependencies")
            inventory.reconcile(provenance.pending)
            val classes = collectClasses(output, outputs)
            val origins = output.resolve("origins")
            provenance.publish(classes, origins)
            Files.writeString(identityFile, configurationIdentity)
            Files.delete(incomplete)
            return CompiledProject(classes, roots, dependencies, origins, runId, modules.flatMap { module ->
                productionRoots(module).map { it to listOf(module.resolve("target/classes")) }
            }.toMap())
        }
    }

    private fun dependencies(modules: List<Path>): List<Path> =
        modules.filter { Files.isDirectory(it.resolve("target/classes")) }.flatMap { module ->
            val manifest = module.resolve("target/graphfold-classpath.txt")
            checkBuild(Files.exists(manifest), "Maven did not export analysis dependencies")
            Files.readString(manifest).trim().split(File.pathSeparator).filter(String::isNotBlank).map(Path::of)
        }.filterNot { dependency -> modules.any { dependency.startsWith(it.resolve("target")) } }.distinct()
}
