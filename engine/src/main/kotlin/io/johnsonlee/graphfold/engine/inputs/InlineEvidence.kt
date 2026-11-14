package io.johnsonlee.graphfold.engine.inputs

import java.nio.file.Path

/** A compiler-observed inline declaration and the completeness of its emitted copy inventory. */
class InlineDeclarationEvidence(
    val method: String, val source: Path, val sourceHash: String, val start: Int, val end: Int, val complete: Boolean,
)

/** Exact emitted instruction interval; indices count opcodes, excluding labels, frames and line records. */
class InlineCopyEvidence(
    val target: String, val caller: String, val startOpcode: Int, val endOpcode: Int, val classHash: String,
)

/** One compiler emission's declarations, copies and original class hashes; no runtime execution evidence. */
class InlineEvidence(
    val declarations: List<InlineDeclarationEvidence>, val copies: List<InlineCopyEvidence>, val classes: Map<String, String>,
)
