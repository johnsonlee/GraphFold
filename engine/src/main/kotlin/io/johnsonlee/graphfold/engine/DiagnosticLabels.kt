package io.johnsonlee.graphfold.engine

/** Make missing diagnostic context explicit without inventing a source path or artifact identity. */
internal fun diagnosticLabel(value: Any?): String = if (value == null) "<unknown>" else value.toString()
