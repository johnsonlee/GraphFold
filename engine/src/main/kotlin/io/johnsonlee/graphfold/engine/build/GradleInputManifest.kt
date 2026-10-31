package io.johnsonlee.graphfold.engine.build

import java.nio.file.Path
import java.util.Base64

/** Tags in the bundled Gradle init script's compiled-input export protocol. */
internal enum class GradleInputKind(val tag: String, val pathCount: Int, val textCount: Int = 0) {
    CLASSES("classes", 1),
    SOURCE("source", 1),
    DEPENDENCY("dependency", 1),
    SOURCE_OUTPUT("sourceOutput", 2),
    RESOURCE("resource", 1),
    RUNTIME_DEPENDENCY("runtimeDependency", 1),
    ARTIFACT("artifact", 1),
    PRODUCTION_COMPLETE("productionComplete", 0),
    RUNTIME_CLASSPATH("runtimeClasspath", 0, 1);

    companion object {
        fun parse(tag: String): GradleInputKind = entries.firstOrNull { it.tag == tag }
            ?: throw BuildFailure("Unknown Gradle input kind: $tag")
    }
}

internal data class GradleInput(val kind: GradleInputKind, val paths: List<Path>, val text: List<String> = emptyList())

internal fun parseGradleInputs(lines: List<String>): List<GradleInput> = lines.map { line ->
    val parts = line.split('|')
    val kind = GradleInputKind.parse(parts.first())
    checkBuild(parts.size == kind.pathCount + kind.textCount + 1, "Invalid Gradle ${kind.tag} export")
    val payload = parts.drop(1).map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
    GradleInput(kind, payload.take(kind.pathCount).map(Path::of), payload.drop(kind.pathCount))
}
