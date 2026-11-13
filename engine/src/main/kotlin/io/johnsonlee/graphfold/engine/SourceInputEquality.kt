package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.git.ChangedFile
import io.johnsonlee.graphfold.engine.origin.readOrigins
import java.nio.file.Path

/** Source changes absent from complete normalized inputs require no fictitious graph declaration. */
internal fun unchangedSourceInputs(
    base: StaticScope, head: StaticScope, baseRoot: Path, headRoot: Path, changes: List<ChangedFile>,
    sourceChanges: Set<ChangedFile>,
): InputChangeProof? {
    val proof = InputChangeProof(changes, base.production, head.production, sourceChanges)
    val verifiedOutputs = base.compilerEvidence.isNotEmpty() && head.compilerEvidence.isNotEmpty()
    return proof.takeIf { verifiedOutputs && sourceChanges.isNotEmpty() && proof.unchanged &&
        readOrigins(base, baseRoot).diagnostics.isEmpty() && readOrigins(head, headRoot).diagnostics.isEmpty() }
}
