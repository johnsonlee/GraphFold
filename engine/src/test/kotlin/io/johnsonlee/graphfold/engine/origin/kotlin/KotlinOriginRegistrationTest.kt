package io.johnsonlee.graphfold.engine.origin.kotlin

import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises embedding hosts which register compiler extensions without the command-line option validator. */
@OptIn(ExperimentalCompilerApi::class)
class KotlinOriginRegistrationTest {
    @TempDir lateinit var temporary: Path

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
}
