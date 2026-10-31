package io.johnsonlee.graphfold.engine.build

import java.nio.file.Path
import java.util.Base64

/** Tags in the bundled Gradle init script's compiled-input export protocol. */
internal enum class GradleInputKind(val tag: String, val pathCount: Int) {
    CLASSES("classes", 1),
    SOURCE("source", 1),
    DEPENDENCY("dependency", 1),
    SOURCE_OUTPUT("sourceOutput", 2);

    companion object {
        fun parse(tag: String): GradleInputKind = entries.firstOrNull { it.tag == tag }
            ?: throw BuildFailure("Unknown Gradle input kind: $tag")
    }
}

internal data class GradleInput(val kind: GradleInputKind, val paths: List<Path>)

internal fun parseGradleInputs(lines: List<String>): List<GradleInput> = lines.map { line ->
    val parts = line.split('|')
    val kind = GradleInputKind.parse(parts.first())
    checkBuild(parts.size == kind.pathCount + 1, "Invalid Gradle ${kind.tag} export")
    GradleInput(kind, parts.drop(1).map { Path.of(String(Base64.getDecoder().decode(it), Charsets.UTF_8)) })
}
