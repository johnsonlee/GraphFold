package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.ChangeKind
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange

/** A declaration-level obligation retains the exact changed fragments, not the declaration's full span. */
internal data class ProofObligation(
    val identity: String,
    val kind: SymbolKind,
    val owner: String,
    val locations: List<SourceLocation>,
    val changes: Set<ChangeKind> = emptySet(),
    val basePath: String? = null,
    val headPath: String? = null,
)

internal object ProofPlanner {
    fun plan(source: IndexedSource, locations: List<SourceLocation>): List<ProofObligation> {
        val fragments = locations.flatMap { changed ->
            val members = source.symbols.filter { it.kind != SymbolKind.CLASS || it.compilerOrigin }.mapNotNull { symbol ->
                intersection(changed, symbol.location)?.let { symbol to it }
            }
            val completeMember = members.filter { (_, fragment) -> fragment == changed }
            when {
                completeMember.isNotEmpty() -> {
                    val smallest = completeMember.minOf { size(it.first.location) }
                    completeMember.filter { size(it.first.location) == smallest }
                }
                members.isNotEmpty() -> members + if (hasUncoveredToken(source.text, changed, members.map { it.second })) {
                    classFragments(source, changed)
                } else emptyList()
                else -> classFragments(source, changed)
            }
        }
        return fragments.groupBy { it.first.identity }.map { (identity, grouped) ->
            val symbol = grouped.first().first
            ProofObligation(identity, symbol.kind, symbol.owner, grouped.map { it.second }.distinct())
        }
    }

    /** Indentation and blank lines outside member ranges do not change the enclosing declaration. */
    private fun hasUncoveredToken(text: String, changed: SourceLocation, members: List<SourceLocation>): Boolean {
        val covered = members.map { offset(text, it.line.start, it.column.start) until offset(text, it.line.end, it.column.end) }
        val start = offset(text, changed.line.start, changed.column.start)
        val end = offset(text, changed.line.end, changed.column.end)
        return (start until end).any { index -> !text[index].isWhitespace() && covered.none { index in it } }
    }

    private fun offset(text: String, line: Int, column: Int): Int {
        var cursor = 0
        repeat(line - 1) { cursor = text.indexOf('\n', cursor).let { if (it < 0) text.length else it + 1 } }
        return (cursor + column - 1).coerceAtMost(text.length)
    }

    private fun classFragments(source: IndexedSource, changed: SourceLocation): List<Pair<SourceSymbol, SourceLocation>> {
        val classes = source.symbols.filter { it.kind == SymbolKind.CLASS }
        val intersecting = classes.mapNotNull { symbol -> intersection(changed, symbol.location)?.let { symbol to it } }
        // Imports, package declarations and file comments are outside type ranges but can affect every type in the unit.
        return if (intersecting.isEmpty()) classes.map { it to changed }
        else intersecting.filterNot { (symbol, fragment) ->
            // Drop an enclosing type only when a smaller type covers exactly the same changed fragment.
            // Disjoint classes and fragments spanning nested type boundaries each retain their own obligation.
            intersecting.any { (other, otherFragment) ->
                otherFragment == fragment && size(other.location) < size(symbol.location)
            }
        }
    }

    private fun size(location: SourceLocation): Long =
        (location.line.end - location.line.start).toLong() * LINE_WEIGHT + location.column.end - location.column.start

    private fun intersection(first: SourceLocation, second: SourceLocation): SourceLocation? {
        val firstStart = Position(first.line.start, first.column.start)
        val firstEnd = Position(first.line.end, first.column.end)
        val secondStart = Position(second.line.start, second.column.start)
        val secondEnd = Position(second.line.end, second.column.end)
        val start = maxOf(firstStart, secondStart)
        val end = minOf(firstEnd, secondEnd)
        return if (start >= end) null else first.copy(
            line = SourceRange(start.line, end.line), column = SourceRange(start.column, end.column),
        )
    }

    private data class Position(val line: Int, val column: Int) : Comparable<Position> {
        override fun compareTo(other: Position): Int = compareValuesBy(this, other, { it.line }, { it.column })
    }

    private const val LINE_WEIGHT = 1_000_000L
}
