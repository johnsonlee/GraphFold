package io.johnsonlee.graphfold.engine.bytecode.asm

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue

/**
 * Specializes scalar-payload return-value proofs for exact application helper targets and actual argument values.
 * This does not summarize effects or waive diagnostics inside the helper. Unknown object fields, general arrays, opaque
 * returns and unresolved dispatch remain unknown; recursion and the depth budget cannot manufacture a proof.
 */
internal class ApplicationReturnValues(
    private val classes: Map<String, ClassNode>,
    private val interpreter: (Map<Int, BasicValue>) -> BasicInterpreter,
    private val safe: (BasicValue) -> Boolean,
) {
    private data class Context(val owner: String, val method: MethodNode, val arguments: List<BasicValue>)
    private val summaries = mutableMapOf<Context, BasicValue?>()
    private val active = mutableSetOf<MethodNode>()

    fun resolve(call: MethodInsnNode, arguments: List<BasicValue>): BasicValue? = target(call)?.let { (owner, method) ->
        val context = Context(owner.name, method, arguments.toList())
        when {
            summaries.containsKey(context) -> summaries[context]
            active.size >= MAX_HELPER_DEPTH || !active.add(method) -> null
            else -> try {
                analyze(context).also { summaries[context] = it }
            } catch (_: AnalyzerException) {
                null.also { summaries[context] = it }
            } finally {
                active.remove(method)
            }
        }
    }

    private fun target(call: MethodInsnNode): Pair<ClassNode, MethodNode>? =
        if (Type.getReturnType(call.desc).sort !in setOf(Type.OBJECT, Type.ARRAY)) null else classes[call.owner]?.let { owner ->
            owner.methods.singleOrNull { it.name == call.name && it.desc == call.desc }?.takeIf { method ->
                val exact = call.opcode == Opcodes.INVOKESTATIC || call.opcode == Opcodes.INVOKESPECIAL ||
                    method.access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL) != 0 || owner.access and Opcodes.ACC_FINAL != 0
                exact && method.access and (Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT) == 0
            }?.let { owner to it }
        }

    private fun analyze(context: Context): BasicValue? {
        var slot = 0
        val bindings = context.arguments.associateBy { value -> slot.also { slot += value.size } }
        val flow = interpreter(bindings)
        val frames = Analyzer(flow).analyze(context.owner, context.method)
        val returns = context.method.instructions.toArray().mapIndexedNotNull { index, instruction ->
            frames[index]?.takeIf { instruction.opcode == Opcodes.ARETURN }?.let { it.getStack(it.stackSize - 1) }
        }
        return returns.takeIf { it.isNotEmpty() && it.all(safe) }?.reduce(flow::merge)
    }

    private companion object {
        const val MAX_HELPER_DEPTH = 32
    }
}
