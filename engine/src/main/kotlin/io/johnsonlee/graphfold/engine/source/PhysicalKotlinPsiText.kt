package io.johnsonlee.graphfold.engine.source

/** Adapt physical source for headless PSI while retaining every UTF-16 offset used by source locations. */
internal fun physicalKotlinPsiText(text: String): String {
    val lineBreaks = text.replace('\r', '\n')
    return if (lineBreaks.startsWith('\uFEFF')) " " + lineBreaks.substring(1) else lineBreaks
}
