package io.johnsonlee.graphfold.engine.origin

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import io.johnsonlee.graphfold.engine.source.IndexedSource
import io.johnsonlee.graphfold.engine.source.SourceSymbol
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.Opcodes

/** Source coordinates remain physical diff coordinates while identities follow typed bytecode normalization. */
internal fun bindOrigins(
    source: IndexedSource, revision: Revision, catalog: OriginCatalog, owners: Map<String, String>,
): IndexedSource {
    val fileOrigins = catalog.origins.filter { it.path == source.path }
    val anonymousOwners = fileOrigins.map { it.owner }.toSet()
    val remapper = object : Remapper(Opcodes.ASM9) {
        override fun map(internalName: String): String = owners[internalName.replace('/', '.')]?.replace('.', '/') ?: internalName
    }
    val symbols = source.symbols.filterNot { it.owner in anonymousOwners }.map { symbol ->
        val owner = owners[symbol.owner] ?: symbol.owner
        val suffix = symbol.identity.removePrefix(symbol.owner)
        val identity = if ('(' in suffix) owner + suffix.substringBefore('(') + remapper.mapMethodDesc("(" + suffix.substringAfter('('))
        else owner + suffix
        symbol.copy(identity = identity, owner = owner)
    }
    val mapped = fileOrigins.mapNotNull { origin ->
        val owner = owners.getValue(origin.owner)
        // Constructor arguments execute in the allocation's caller, outside the generated class body.
        val range = origin.syntax.bodyRange ?: return@mapNotNull null
        fun line(offset: Int) = source.text.take(offset).count { it == '\n' } + 1
        fun column(offset: Int) = offset - source.text.lastIndexOf('\n', offset - 1)
        SourceSymbol(owner, owner, SymbolKind.CLASS, SourceLocation(revision, source.path,
            SourceRange(line(range.start), line(range.end)), SourceRange(column(range.start), column(range.end))), true)
    }
    return source.copy(symbols = symbols + mapped)
}
