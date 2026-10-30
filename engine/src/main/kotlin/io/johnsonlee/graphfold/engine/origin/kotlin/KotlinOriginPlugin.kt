package io.johnsonlee.graphfold.engine.origin.kotlin

import java.nio.file.Path
import org.jetbrains.kotlin.backend.jvm.extensions.ClassGenerator
import org.jetbrains.kotlin.backend.jvm.extensions.ClassGeneratorExtension
import org.jetbrains.kotlin.codegen.ClassFileFactory
import org.jetbrains.kotlin.codegen.extensions.ClassFileFactoryFinalizerExtension
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.ir.declarations.IrClass

/** Loaded in the actual compilation JVM; JVM names are observed during emission, never predicted. */
@OptIn(ExperimentalCompilerApi::class)
class KotlinOriginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val output = configuration.get(KotlinOriginOptions.OUTPUT) ?: return
        val runId = requireNotNull(configuration.get(KotlinOriginOptions.RUN_ID)) { "GraphFold origin runId is required" }
        require(KotlinCompilerVersion.VERSION in SUPPORTED_VERSIONS) {
            "Unsupported GraphFold Kotlin provenance compiler: ${KotlinCompilerVersion.VERSION}"
        }
        val collector = KotlinOriginCollector(Path.of(output), KotlinCompilerVersion.VERSION,
            if (configuration.getBoolean(CommonConfigurationKeys.USE_FIR)) "K2" else "K1", runId)
        collector.start()
        registerOutputCapture(configuration, collector)
        ClassGeneratorExtension.registerExtension(object : ClassGeneratorExtension {
            override fun generateClass(generator: ClassGenerator, declaration: IrClass?): ClassGenerator =
                OriginClassGenerator(generator, declaration, collector, runId)
        })
        ClassFileFactoryFinalizerExtension.registerExtension(object : ClassFileFactoryFinalizerExtension {
            override fun finalizeClassFactory(factory: ClassFileFactory) {
                collector.finish()
            }
        })
    }

    private companion object {
        val SUPPORTED_VERSIONS = setOf("1.9.20", "2.0.21", "2.1.20", "2.2.10")
    }
}

@OptIn(ExperimentalCompilerApi::class)
class KotlinOriginCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "io.johnsonlee.graphfold.origin.kotlin"
    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption("output", "directory", "Compilation provenance output directory", required = true, allowMultipleOccurrences = false),
        CliOption("runId", "id", "Shared analysis invocation identifier", required = true, allowMultipleOccurrences = false),
        CliOption("inlineSources", "digest", "Physical inline-source identity for incremental invalidation",
            required = false, allowMultipleOccurrences = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "output" -> configuration.put(KotlinOriginOptions.OUTPUT, value)
            "runId" -> configuration.put(KotlinOriginOptions.RUN_ID, value)
            "inlineSources" -> Unit // A compiler input; changing it refreshes copied physical source markers.
            else -> error("Unknown GraphFold origin option: ${option.optionName}")
        }
    }
}

private object KotlinOriginOptions {
    val RUN_ID: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("GraphFold analysis run identifier")
    val OUTPUT: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("GraphFold Kotlin provenance output")
}

internal fun registerOutputCapture(configuration: CompilerConfiguration, collector: KotlinOriginCollector) {
    val key = org.jetbrains.kotlin.cli.common.CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY
    val delegate = configuration.get(key) ?: org.jetbrains.kotlin.cli.common.messages.MessageCollector.NONE
    val report = configuration.getBoolean(CommonConfigurationKeys.REPORT_OUTPUT_FILES)
    val root = configuration.get(org.jetbrains.kotlin.config.JVMConfigurationKeys.OUTPUT_DIRECTORY)?.toPath()
    configuration.put(CommonConfigurationKeys.REPORT_OUTPUT_FILES, true)
    configuration.put(key, object : org.jetbrains.kotlin.cli.common.messages.MessageCollector by delegate {
        override fun report(severity: org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity, message: String,
            location: org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation?) {
            val output = if (severity == org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.OUTPUT) {
                org.jetbrains.kotlin.cli.common.messages.OutputMessageUtil.parseOutputMessage(message)
            } else null
            val outputFile = output?.outputFile
            if (outputFile != null && root != null && outputFile.name.endsWith(".class")) {
                val directory = root.toAbsolutePath().normalize()
                val path = outputFile.toPath().toAbsolutePath().normalize()
                require(path.startsWith(directory)) { "Compiler output is outside configured output directory: $path" }
                val relative = directory.relativize(path)
                collector.output(relative.toString().removeSuffix(".class").replace(java.io.File.separatorChar, '.'),
                    output.sourceFiles.map { it.toPath() })
            }
            if (report || severity != org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.OUTPUT) {
                delegate.report(severity, message, location)
            }
        }
    })
}
