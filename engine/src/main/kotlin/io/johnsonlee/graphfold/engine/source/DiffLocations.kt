package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import io.johnsonlee.graphfold.engine.git.ChangedFile

/** Restricts locations to actual changed lines, never to an enclosing method or hunk context. */
internal object DiffLocations {
    fun locations(change: ChangedFile, base: String?, head: String?): List<SourceLocation> = buildList {
        val basePath = change.basePath
        val headPath = change.headPath
        change.edits.forEach { edit ->
            val old = block(base, edit.beginA, edit.lengthA)
            val new = block(head, edit.beginB, edit.lengthB)
            val prefix = old.commonPrefixWith(new).length
            val suffix = old.drop(prefix).commonSuffixWith(new.drop(prefix)).length
            if (hasChangedBlock(old, edit.lengthA, edit.lengthB, prefix + suffix) &&
                basePath != null && base != null) {
                add(location(old, basePath, Revision.BASE, edit.beginA + 1, prefix, old.length - suffix))
            }
            if (hasChangedBlock(new, edit.lengthB, edit.lengthA, prefix + suffix) &&
                headPath != null && head != null) {
                add(location(new, headPath, Revision.HEAD, edit.beginB + 1, prefix, new.length - suffix))
            }
        }
    }

    private fun hasChangedBlock(text: String, lines: Int, otherLines: Int, unchanged: Int): Boolean =
        if (text.isEmpty()) lines > 0 && otherLines == 0 else text.length > unchanged

    private fun block(text: String?, begin: Int, count: Int): String =
        if (count == 0 || text == null) "" else text.split('\n').drop(begin).take(count).joinToString("\n")

    private fun location(text: String, path: String, revision: Revision, firstLine: Int, start: Int, end: Int): SourceLocation {
        fun position(offset: Int): Pair<Int, Int> {
            val before = text.take(offset)
            return firstLine + before.count { it == '\n' } to (offset - before.lastIndexOf('\n'))
        }
        val from = position(start)
        val to = position(end)
        return SourceLocation(revision, path, SourceRange(from.first, to.first), SourceRange(from.second, to.second))
    }
}
