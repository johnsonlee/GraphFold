package io.johnsonlee.graphfold.engine.graphite

import org.objectweb.asm.tree.AnnotationNode

internal object DeclarationMetadata {
    fun classShape(value: Any?, owner: String, diagnostics: MutableList<String>): Any? = when {
        value is AnnotationNode && value.desc == "Lkotlin/Metadata;" -> KotlinMetadataShape.normalize(value) ?: run {
            diagnostics.add("Unsupported Kotlin metadata prevents private declaration comparison: $owner")
            value
        }
        value is List<*> -> value.map { classShape(it, owner, diagnostics) }
        else -> value
    }

    fun discoveryAnnotations(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?): Boolean =
        (visible.orEmpty() + invisible.orEmpty()).any { it.desc !in COMPILER_ANNOTATIONS }

    private val COMPILER_ANNOTATIONS = setOf(
        "Lkotlin/Metadata;", "Lorg/jetbrains/annotations/NotNull;", "Lorg/jetbrains/annotations/Nullable;",
        "Lkotlin/jvm/internal/SourceDebugExtension;", "Lkotlin/coroutines/jvm/internal/DebugMetadata;",
        "Lkotlin/jvm/JvmStatic;", "Lkotlin/jvm/JvmField;", "Lkotlin/jvm/JvmName;",
    )
}
