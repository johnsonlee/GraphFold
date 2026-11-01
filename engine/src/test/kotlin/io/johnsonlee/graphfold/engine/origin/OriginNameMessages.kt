package io.johnsonlee.graphfold.engine.origin

import java.nio.file.Path
import org.objectweb.asm.tree.ClassNode

/** Raw scanner evidence, before the comparison applies folded reachability and stack scope. */
internal fun originNameMessages(
    nodes: List<ClassNode>, owners: Map<String, String>, dependencies: List<Path> = emptyList(),
): List<String> = originNameObservability(nodes, owners, dependencies).let { observations ->
    observations.diagnostics + observations.methods.values.flatten().map { it.message }
}
