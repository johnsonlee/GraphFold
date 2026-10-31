package io.johnsonlee.graphfold.engine.origin

/** UTF16, zero-based, end-exclusive physical source range. */
internal data class OriginSpan(val start: Int, val end: Int)

/** Syntax evidence for pairing compiler identities; equal anchors alone do not prove uniqueness. */
internal data class OriginSyntax(
    val range: OriginSpan,
    val anchor: String,
    val body: String,
    val container: String,
    val parentRange: OriginSpan?,
    val aliases: Set<OriginSpan> = emptySet(),
    val bodyRange: OriginSpan? = null,
)

/** Parse once per source file. Duplicate entries are retained so callers can reject ambiguous matches. */
internal fun originSyntax(path: String, text: String): List<OriginSyntax> = when {
    path.endsWith(".java") -> javaOriginSyntax(text)
    path.endsWith(".kt") -> kotlinOriginSyntax(path, text)
    else -> emptyList()
}

/** Only exact compiler/AST ranges match. Never substitute a nearby or merely enclosing expression. */
internal fun originSyntax(path: String, text: String, start: Int, end: Int): OriginSyntax? {
    val requested = OriginSpan(start, end)
    return originSyntax(path, text).singleOrNull { it.range == requested || requested in it.aliases }
}

internal data class OriginToken(val range: OriginSpan, val text: String)

internal fun originTokenText(tokens: List<OriginToken>, scope: OriginSpan, target: OriginSpan?, origins: List<OriginSpan>): String {
    val contained = origins.filter { it.start >= scope.start && it.end <= scope.end }
    val masks = contained.filter { candidate -> contained.none { other -> other != candidate &&
        other.start <= candidate.start && other.end >= candidate.end
    } }
    val emitted = mutableSetOf<OriginSpan>()
    return tokens.filter { it.range.start >= scope.start && it.range.end <= scope.end }.mapNotNull { token ->
        val mask = if (target == null) null else masks.firstOrNull {
            token.range.start >= it.start && token.range.end <= it.end
        }
        when {
            mask == null -> token.text
            !emitted.add(mask) -> null
            mask == target -> "<graphfold-origin>"
            else -> "<graphfold-anonymous>"
        }
    }.joinToString("\u001f")
}
