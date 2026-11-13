package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import java.nio.file.Path

/** Source parsing follows the producing compiler's inputs, independently of the selected archive graph inputs. */
internal data class JavaCompilerInputs(val roots: List<Path>, val dependencies: List<Path>)

internal fun javaCompilerInputs(project: StaticScope, source: Path): JavaCompilerInputs {
    if (project.sourceClasspaths.isEmpty()) {
        return JavaCompilerInputs(project.sourceRoots, project.sourceDependencies ?: project.dependencies)
    }
    val absolute = source.toFile().canonicalFile.toPath()
    val selected = project.sourceClasspaths.keys.filter { absolute.startsWith(it.toFile().canonicalFile.toPath()) }
        .maxByOrNull { it.nameCount }
        ?: throw IllegalArgumentException("No producer compiler classpath for source: $source")
    val outputs = project.sourceOutputs[selected].orEmpty().toSet()
    val roots = project.sourceRoots.filter { root -> root == selected ||
        project.sourceOutputs[root].orEmpty().any { it in outputs } }
    return JavaCompilerInputs(roots, project.sourceClasspaths.getValue(selected))
}
