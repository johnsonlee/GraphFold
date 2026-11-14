package io.johnsonlee.graphfold.compiler.kotlin

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/** Observes compiler-bound symbols before bytecode inlining; never resolves a target from its spelling alone. */
internal class KotlinInlineCapture(private val origin: (String, IrFunction) -> KotlinOriginCollector.Origin?) {
    private val methods = linkedMapOf<String, InlineMethodCapture>()
    private val conflicting = mutableSetOf<String>()

    fun method(owner: String, declaration: IrFunction?, name: String, descriptor: String) {
        if (declaration == null) return
        val identity = owner.replace('/', '.') + "." + name + descriptor
        val targets = mutableListOf<InlineSourceIdentity?>()
        val markers = mutableListOf<String>()
        declaration.body?.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) { element.acceptChildrenVoid(this) }
            override fun visitFunction(declaration: IrFunction) = Unit // A nested function has its own emitted method.
            override fun visitVariable(declaration: IrVariable) {
                if (declaration.name.asString().startsWith(INLINE_MARKER_PREFIX)) markers += declaration.name.asString()
                declaration.acceptChildrenVoid(this)
            }
            // Class emission runs after symbol binding; this opt-in is compile-time only across supported host compilers.
            @OptIn(UnsafeDuringIrConstructionAPI::class)
            override fun visitCall(expression: IrCall) {
                val target = expression.symbol.owner
                if (target.isInline) targets += source(identity, target)
                expression.acceptChildrenVoid(this)
            }
        })
        val ownMarker = INLINE_MARKER_PREFIX + name
        val safeMarkers = if (declaration.isInline) markers == listOf(ownMarker) else markers.isEmpty()
        val capture = InlineMethodCapture(identity, name, if (declaration.isInline) source(identity, declaration) else null,
            declaration.isInline, targets, safeMarkers)
        val previous = methods.putIfAbsent(identity, capture)
        if (previous != null && previous != capture) conflicting += identity
    }

    fun evidence(outputs: Map<String, java.nio.file.Path>, emittedOwners: Set<String>): String =
        inlineEvidence(methods.values.toList(), conflicting, outputs, emittedOwners)

    private fun source(identity: String, declaration: IrFunction): InlineSourceIdentity? =
        runCatching { origin(identity, declaration) }.getOrNull()?.let {
            InlineSourceIdentity(it.source, it.sourceHash, it.start, it.end, declaration.name.asString())
        }
}

internal const val INLINE_MARKER_PREFIX = "\$i\$f\$"

internal data class InlineSourceIdentity(val source: String, val hash: String, val start: Int, val end: Int, val name: String)
internal data class InlineMethodCapture(
    val method: String, val name: String, val source: InlineSourceIdentity?, val inline: Boolean,
    val targets: List<InlineSourceIdentity?>, val safeMarkers: Boolean,
)
