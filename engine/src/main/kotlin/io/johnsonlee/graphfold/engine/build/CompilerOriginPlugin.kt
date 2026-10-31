package io.johnsonlee.graphfold.engine.build

import io.johnsonlee.graphfold.compiler.javac.GraphFoldOrigins
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** The isolated compiler loads only the small provider jar, never the engine's analysis dependencies. */
internal object CompilerOriginPlugin {
    fun artifact(): Path = Path.of(GraphFoldOrigins::class.java.protectionDomain.codeSource.location.toURI()).also {
        checkBuild(Files.isRegularFile(it), "Compiler plugin must be loaded from its published JAR: $it")
    }

    fun runId(output: Path): String {
        val file = output.resolve("origin-run-id.txt")
        if (!Files.exists(file)) Files.writeString(file, java.util.UUID.randomUUID().toString())
        return Files.readString(file).trim()
    }

    fun javacArgument(output: Path, root: Path, runId: String): String =
        "-Xplugin:GraphFoldOrigins ${encoded(output)} ${encoded(root)} ${encode(runId)}"

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun encoded(path: Path): String = encode(path.toAbsolutePath().normalize().toString())
}
