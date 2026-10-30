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
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "output" -> configuration.put(KotlinOriginOptions.OUTPUT, value)
            "runId" -> configuration.put(KotlinOriginOptions.RUN_ID, value)
            else -> error("Unknown GraphFold origin option: ${option.optionName}")
        }
    }
}

private object KotlinOriginOptions {
    val RUN_ID: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("GraphFold analysis run identifier")
    val OUTPUT: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("GraphFold Kotlin provenance output")
}
