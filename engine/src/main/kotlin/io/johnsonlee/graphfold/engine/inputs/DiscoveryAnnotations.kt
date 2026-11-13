package io.johnsonlee.graphfold.engine.inputs

/** Compiler bookkeeping annotations do not introduce reflective application discovery. */
fun isDiscoveryAnnotation(descriptor: String): Boolean = descriptor !in COMPILER_ANNOTATIONS

private val COMPILER_ANNOTATIONS = setOf(
    "Lkotlin/Metadata;", "Lorg/jetbrains/annotations/NotNull;", "Lorg/jetbrains/annotations/Nullable;",
    "Lkotlin/jvm/internal/SourceDebugExtension;", "Lkotlin/coroutines/jvm/internal/DebugMetadata;",
    "Lkotlin/jvm/JvmStatic;", "Lkotlin/jvm/JvmField;", "Lkotlin/jvm/JvmName;",
)

const val COMPILER_ORIGIN_MARKER = "Lio/johnsonlee/graphfold/engine/origin/SourceOrigin;"
