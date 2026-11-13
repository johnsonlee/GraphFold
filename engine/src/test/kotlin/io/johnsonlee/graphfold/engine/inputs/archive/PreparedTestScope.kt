package io.johnsonlee.graphfold.engine.inputs.archive

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import java.nio.file.Files
import java.nio.file.Path

/** Cross the same normalization boundary as repository analysis before asserting core source evidence. */
internal fun InputProject.preparedScope(temporary: Path): StaticScope =
    toStaticScope(Files.createTempDirectory(temporary, "prepared-inputs-"))
