package io.johnsonlee.graphfold.engine.inputs

import java.nio.file.Path

/** Direct compiler outputs, explicit source associations and the JDK that produced this input. */
data class SourceInput(
    val classes: Path,
    val sourceRoots: List<Path>,
    val dependencies: List<Path>,
    val javaVersion: Int,
    val sourceOutputs: Map<Path, List<Path>> = emptyMap(),
)
