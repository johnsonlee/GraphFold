package io.johnsonlee.graphfold.engine.build

import java.io.File
import io.johnsonlee.graphfold.engine.DEFAULT_BUILD_ENVIRONMENT
import java.nio.file.Path
import java.time.Duration

/** A separate compiler JVM gives cancellation and timeout the same guarantees as external builds. */
internal class KotlinBuildAdapter(
    private val timeout: Duration, private val environmentAllowlist: Set<String> = DEFAULT_BUILD_ENVIRONMENT,
) {
    fun compile(checkout: Path, output: Path, classes: Path, sources: List<Path>): List<Path> {
        val dependencies = listOf(classLocation("kotlin.Unit"), classLocation("org.jetbrains.annotations.NotNull"))
        val compilerClasspath = (COMPILER_CLASSES.map(::classLocation) + dependencies).distinct()
        val plugin = CompilerOriginPlugin.extract(output)
        val arguments = listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", compilerClasspath.joinToString(File.pathSeparator), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-java-parameters",
            "-Xplugin=$plugin", "-P", "plugin:io.johnsonlee.graphfold.origin.kotlin:runId=${CompilerOriginPlugin.runId(output)}",
            "-P", "plugin:io.johnsonlee.graphfold.origin.kotlin:output=${output.resolve("origins")}",
            "-classpath", dependencies.joinToString(File.pathSeparator), "-d", classes.toString(),
        ) + sources.map(Path::toString)
        runBuild(checkout, output.resolve("kotlin-build.log"), arguments, timeout, environmentAllowlist)
        return dependencies
    }

    private fun classLocation(name: String): Path =
        Path.of(Class.forName(name).protectionDomain.codeSource.location.toURI())

    private companion object {
        val COMPILER_CLASSES = listOf(
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "kotlin.reflect.jvm.internal.ReflectionFactoryImpl",
            "kotlin.script.templates.standard.ScriptTemplateWithArgs",
            "gnu.trove.THashMap",
            "kotlinx.coroutines.CoroutineScope",
        )
    }
}
