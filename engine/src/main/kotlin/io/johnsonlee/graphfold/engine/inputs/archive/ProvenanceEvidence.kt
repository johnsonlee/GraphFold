package io.johnsonlee.graphfold.engine.inputs.archive

import java.io.IOException

/** Source attribution can fail while independently packaged bytecode remains valid graph input. */
internal fun <T> optionalProvenance(issues: MutableList<String>, read: () -> T): T? = try {
    read()
} catch (failure: InputFailure) {
    issues += failure.message
    null
} catch (failure: IOException) {
    issues += "Cannot read compiler provenance: $failure"
    null
} catch (failure: IllegalArgumentException) {
    issues += "Invalid compiler provenance: $failure"
    null
}
