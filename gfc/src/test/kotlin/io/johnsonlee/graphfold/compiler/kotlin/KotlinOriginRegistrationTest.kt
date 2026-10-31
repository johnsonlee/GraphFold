package io.johnsonlee.graphfold.compiler.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.KotlinFrontend
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.OutputMessageUtil
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises embedding hosts which register compiler extensions without the command-line option validator. */
@OptIn(ExperimentalCompilerApi::class)
class KotlinOriginRegistrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun `ownership capture does not expose new output messages or alter diagnostics`() {
        val messages = RecordingMessages()
        val configuration = configuration(messages)
        val collector = collector()
        registerOutputCapture(configuration, collector)
        assertTrue(configuration.getBoolean(CommonConfigurationKeys.REPORT_OUTPUT_FILES))
        val wrapper = requireNotNull(configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY))
        val source = Files.writeString(temporary.resolve("Example.kt"), "class Example")
        val location = CompilerMessageLocation.create(source.toString(), 1, 1, "class Example")
        wrapper.report(CompilerMessageSeverity.OUTPUT, outputMessage(source, "sample/Example.class"), null)
        wrapper.report(CompilerMessageSeverity.WARNING, "existing warning", location)
        wrapper.report(CompilerMessageSeverity.ERROR, "existing error", location)
        assertTrue(wrapper.hasErrors())
        assertEquals(listOf(
            Report(CompilerMessageSeverity.WARNING, "existing warning", location),
            Report(CompilerMessageSeverity.ERROR, "existing error", location),
        ), messages.reports)
        collector.finish()
        assertEquals(listOf("sample.Example"), outputOwners())
        wrapper.clear()
        assertFalse(wrapper.hasErrors())
        assertTrue(messages.reports.isEmpty())
    }

    @Test fun `previously requested output reporting remains visible including nonclass artifacts`() {
        val messages = RecordingMessages()
        val configuration = configuration(messages)
        configuration.put(CommonConfigurationKeys.REPORT_OUTPUT_FILES, true)
        val collector = collector()
        registerOutputCapture(configuration, collector)
        val wrapper = requireNotNull(configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY))
        val source = Files.writeString(temporary.resolve("Source with spaces.kt"), "fun value() = 1")
        val classMessage = outputMessage(source, "sample/Facade.class")
        val metadataMessage = outputMessage(source, "META-INF/main.kotlin_module")
        listOf(classMessage, metadataMessage).forEach { wrapper.report(CompilerMessageSeverity.OUTPUT, it, null) }
        collector.finish()
        assertEquals(listOf("sample.Facade"), outputOwners())
        assertEquals(listOf(classMessage, metadataMessage).map { Report(CompilerMessageSeverity.OUTPUT, it, null) }, messages.reports)
    }

    @Test fun `malformed output reports and missing output paths cannot fabricate ownership`() {
        val configuration = configuration()
        val collector = collector()
        registerOutputCapture(configuration, collector)
        val wrapper = requireNotNull(configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY))
        val source = Files.writeString(temporary.resolve("Example.kt"), "class Example")
        listOf("unstructured output", "Output:\nSources:\n$source", outputMessage(source, "META-INF/main.kotlin_module"))
            .forEach { wrapper.report(CompilerMessageSeverity.OUTPUT, it, null) }
        wrapper.report(CompilerMessageSeverity.INFO, "compiler information", null)
        collector.finish()
        assertTrue(outputOwners().isEmpty())
        assertFalse(wrapper.hasErrors())
    }

    @Test fun `host without an output directory cannot claim ownership of emitted paths`() {
        val configuration = CompilerConfiguration()
        val collector = collector()
        registerOutputCapture(configuration, collector)
        val source = Files.writeString(temporary.resolve("Example.kt"), "class Example")
        requireNotNull(configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)).report(
            CompilerMessageSeverity.OUTPUT, outputMessage(source, "sample/Example.class"), null,
        )
        collector.finish()
        assertTrue(outputOwners().isEmpty())
    }

    @Test fun `output outside configured directory fails before recording a fabricated owner`() {
        val configuration = configuration()
        val collector = collector()
        collector.start()
        registerOutputCapture(configuration, collector)
        val source = Files.writeString(temporary.resolve("Example.kt"), "class Example")
        val failure = assertFailsWith<IllegalArgumentException> {
            requireNotNull(configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)).report(
                CompilerMessageSeverity.OUTPUT, outputMessage(source, "../Outside.class"), null,
            )
        }
        assertTrue(failure.message.orEmpty().contains("outside configured output directory"))
        assertTrue(outputOwners().isEmpty())
    }

    @Test fun `inline source identity is accepted without changing origin run identity`() {
        val processor = KotlinOriginCommandLineProcessor()
        val option: AbstractCliOption = processor.pluginOptions.single { it.optionName == "inlineSources" }
        processor.processOption(option, "physical-source-digest", CompilerConfiguration())
        assertFalse(option.required)
    }

    @Test fun `unconfigured embedding host leaves provenance registrar inactive`() {
        val registrar = KotlinOriginRegistrar()
        with(registrar) { CompilerPluginRegistrar.ExtensionStorage().registerExtensions(CompilerConfiguration()) }
        assertTrue(Files.list(temporary).use { it.findAny().isEmpty })
    }

    @Test fun `embedding host providing output without run identity cannot emit provenance`() {
        val configuration = CompilerConfiguration()
        val processor = KotlinOriginCommandLineProcessor()
        val output: AbstractCliOption = processor.pluginOptions.single { it.optionName == "output" }
        processor.processOption(output,
            temporary.resolve("origins").toString(), configuration)
        val registrar = KotlinOriginRegistrar()
        val failure = assertFailsWith<IllegalArgumentException> {
            with(registrar) { CompilerPluginRegistrar.ExtensionStorage().registerExtensions(configuration) }
        }
        assertTrue(requireNotNull(failure.message).contains("runId"))
        assertFalse(Files.exists(temporary.resolve("origins")))
    }

    @Test fun `embedding host cannot silently supply unknown provenance option`() {
        val unknown: AbstractCliOption = CliOption(
            "foreign", "value", "Foreign host option", required = false, allowMultipleOccurrences = false,
        )
        val failure = assertFailsWith<IllegalStateException> {
            KotlinOriginCommandLineProcessor().processOption(unknown, "value", CompilerConfiguration())
        }
        assertTrue(requireNotNull(failure.message).contains("foreign"))
    }

    private fun configuration(messages: MessageCollector? = null) = CompilerConfiguration().apply {
        put(JVMConfigurationKeys.OUTPUT_DIRECTORY, temporary.resolve("classes").toFile())
        if (messages != null) put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messages)
    }

    private fun collector() = KotlinOriginCollector(temporary.resolve("origins"), "2.1.20", KotlinFrontend.K2, "registration-test")

    private fun outputMessage(source: Path, output: String): String = OutputMessageUtil.formatOutputMessage(
        listOf(source.toFile()), temporary.resolve("classes").resolve(output).toFile(),
    )

    private fun outputOwners(): List<String> = Files.list(temporary.resolve("origins")).use { paths ->
        val manifest = paths.filter { it.fileName.toString().endsWith(".json") }.toList().single()
        jacksonObjectMapper().readTree(manifest.toFile())["outputs"].map { it["owner"].asText() }
    }

    private data class Report(val severity: CompilerMessageSeverity, val message: String, val location: CompilerMessageSourceLocation?)

    private class RecordingMessages : MessageCollector {
        val reports = mutableListOf<Report>()
        override fun clear() { reports.clear() }
        override fun hasErrors(): Boolean = reports.any { it.severity.isError }
        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
            reports += Report(severity, message, location)
        }
    }
}
