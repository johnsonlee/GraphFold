package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.inputs.isDiscoveryAnnotation
import org.objectweb.asm.tree.AnnotationNode

internal object DeclarationMetadata {
    fun classShape(
        value: Any?, owner: String, diagnostics: MutableList<String>, excludedNestedClasses: Set<String> = emptySet(),
    ): Any? = when {
        value is AnnotationNode && value.desc == "Lkotlin/Metadata;" -> KotlinMetadataShape.normalize(value, excludedNestedClasses) ?: run {
            diagnostics.add("Unsupported Kotlin metadata prevents private declaration comparison: $owner")
            value
        }
        value is List<*> -> value.map { classShape(it, owner, diagnostics, excludedNestedClasses) }
        else -> value
    }

    fun discoveryAnnotations(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?): Boolean =
        (visible.orEmpty() + invisible.orEmpty()).any { isDiscoveryAnnotation(it.desc) }
}
