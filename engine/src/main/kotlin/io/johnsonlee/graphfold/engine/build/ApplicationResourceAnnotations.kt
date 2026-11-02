package io.johnsonlee.graphfold.engine.build

import io.johnsonlee.graphfold.engine.graphite.DeclarationMetadata
import io.johnsonlee.graphfold.engine.origin.ORIGIN_MARKER
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

/** Application discovery metadata may activate framework resource binding without a bytecode loader call. */
internal fun applicationResourceAnnotationsKnown(reader: ClassReader): Boolean {
    val node = ClassNode()
    reader.accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    val annotations = resourceAnnotations(node.visibleAnnotations, node.invisibleAnnotations,
        node.visibleTypeAnnotations, node.invisibleTypeAnnotations) + node.fields.asSequence().flatMap {
        resourceAnnotations(it.visibleAnnotations, it.invisibleAnnotations, it.visibleTypeAnnotations, it.invisibleTypeAnnotations)
    } + node.recordComponents.orEmpty().asSequence().flatMap {
        resourceAnnotations(it.visibleAnnotations, it.invisibleAnnotations, it.visibleTypeAnnotations, it.invisibleTypeAnnotations)
    } + node.methods.asSequence().flatMap {
        resourceAnnotations(it.visibleAnnotations, it.invisibleAnnotations, it.visibleTypeAnnotations, it.invisibleTypeAnnotations) +
            it.visibleParameterAnnotations.orEmpty().asSequence().flatMap { values -> values.orEmpty() } +
            it.invisibleParameterAnnotations.orEmpty().asSequence().flatMap { values -> values.orEmpty() }
    }
    return annotations.none { it.desc != ORIGIN_MARKER && DeclarationMetadata.discoveryAnnotations(listOf(it), null) }
}

private fun resourceAnnotations(vararg annotations: List<AnnotationNode>?): Sequence<AnnotationNode> =
    annotations.asSequence().flatMap { it.orEmpty() }
