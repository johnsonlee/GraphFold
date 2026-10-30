package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import io.johnsonlee.graphfold.engine.git.ChangedFile
import io.johnsonlee.graphfold.engine.git.ChangedLineRange

/** Restricts locations to actual changed lines, never to an enclosing method or hunk context. */
internal object DiffLocations {
    fun locations(change: ChangedFile, base: String?, head: String?): List<SourceLocation> = buildList {
        if (change.hunks.isEmpty()) {
            if (base != null && change.basePath != null) addAll(ranges(base, change.basePath, Revision.BASE, change.baseLines))
            if (head != null && change.headPath != null) addAll(ranges(head, change.headPath, Revision.HEAD, change.headLines))
        } else {
            change.hunks.forEach { hunk ->
                val old = block(base, hunk.baseStart, hunk.baseCount)
                val new = block(head, hunk.headStart, hunk.headCount)
                val prefix = old.commonPrefixWith(new).length
                val suffix = old.drop(prefix).commonSuffixWith(new.drop(prefix)).length
                if (old.length > prefix + suffix && change.basePath != null) {
                    add(location(old, change.basePath, Revision.BASE, hunk.baseStart, prefix, old.length - suffix))
                }
                if (new.length > prefix + suffix && change.headPath != null) {
                    add(location(new, change.headPath, Revision.HEAD, hunk.headStart, prefix, new.length - suffix))
                }
            }
        }
    }

    private fun block(text: String?, start: Int, count: Int): String =
        if (count == 0) "" else requireNotNull(text).split('\n').drop(start - 1).take(count).joinToString("\n")

    private fun location(text: String, path: String, revision: Revision, firstLine: Int, start: Int, end: Int): SourceLocation {
        fun position(offset: Int): Pair<Int, Int> {
            val before = text.take(offset)
            return firstLine + before.count { it == '\n' } to (offset - before.lastIndexOf('\n'))
        }
        val from = position(start)
        val to = position(end)
        return SourceLocation(revision, path, SourceRange(from.first, to.first), SourceRange(from.second, to.second))
    }

    private fun ranges(text: String, path: String, revision: Revision, changed: List<ChangedLineRange>): List<SourceLocation> {
        val lines = text.split('\n')
        return changed.map { range ->
            require(range.end <= lines.size) { "diff exceeds source length: $path" }
            val startText = lines[range.start - 1].removeSuffix("\r")
            val endText = lines[range.end - 1].removeSuffix("\r")
            val first = startText.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: 0
            SourceLocation(revision, path, SourceRange(range.start, range.end), SourceRange(first + 1, endText.length + 1))
        }
    }
}
