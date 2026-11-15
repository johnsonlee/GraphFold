package io.johnsonlee.graphfold.engine.inputs.archive

/** Order evidence belongs to static input preparation, independently of application ownership. */
internal enum class StaticClassOrder { UNAMBIGUOUS, EXPLICIT_ORDER }

internal data class ArchiveProjection(val input: ArchiveInput, val classOrder: StaticClassOrder)
