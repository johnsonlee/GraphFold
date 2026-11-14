package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.git.ChangedFile

/** Source changes can also drive generated classes, resources or placement beyond their own declaration obligations. */
internal fun scopeOutputProof(
    inputs: InputChangeProof, base: StaticScope, head: StaticScope, sourceChanges: Set<ChangedFile>,
): InputChangeProof = if (inputs.changes.isEmpty() && sourceChanges.isNotEmpty() &&
    scopeOutputsChanged(base, head)) {
    InputChangeProof(sourceChanges.toList(), base.production, head.production, sourceChanges)
} else inputs
