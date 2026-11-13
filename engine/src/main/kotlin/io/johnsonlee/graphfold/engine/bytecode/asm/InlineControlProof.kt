package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.inputs.InlineCopyEvidence
import io.johnsonlee.graphfold.engine.inputs.InlineEvidence
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSites
import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode

/** Discharges only private declarations whose every compiler-recorded copy is cut by a selected control. */
internal fun inlineControlRemovedMethods(
    classes: Path, evidence: List<InlineEvidence>, rules: List<FoldRule>, applied: Map<String, Map<Int, Int>>,
): Set<String> {
    if (evidence.isEmpty()) return emptySet()
    val nodes = Files.walk(classes).use { files -> files.filter { it.toString().endsWith(".class") }.map { path ->
        ClassNode().also { ClassReader(Files.readAllBytes(path)).accept(it, ClassReader.SKIP_FRAMES) }
    }.toList() }
    val methods = nodes.flatMap { node -> node.methods.map { "${node.name.replace('/', '.')}.${it.name}${it.desc}" to it } }.toMap()
    val copies = evidence.flatMap { it.copies }.groupBy { it.target }
    return evidence.flatMap { it.declarations }.groupBy { it.method }.filter { (target, records) ->
        val declaration = records.singleOrNull()
        val method = methods[target]
        val present = copies[target].orEmpty()
        declaration?.complete == true && method != null && method.access and Opcodes.ACC_PRIVATE != 0 &&
            present.isNotEmpty() && methods.values.none { referencesInlineTarget(it, target) } && present.all { copy ->
                methods[copy.caller]?.let { inlineCopyRemoved(it, copy, rules, applied[copy.caller].orEmpty()) } == true
            }
    }.keys
}

private fun referencesInlineTarget(method: MethodNode, target: String): Boolean = method.instructions.any { instruction ->
    when (instruction) {
        is MethodInsnNode -> "${instruction.owner.replace('/', '.')}.${instruction.name}${instruction.desc}" == target
        is LdcInsnNode -> inlineHandleTarget(instruction.cst, target)
        is InvokeDynamicInsnNode -> inlineHandleTarget(instruction.bsm, target) ||
            instruction.bsmArgs.any { inlineHandleTarget(it, target) }
        else -> false
    }
}

private fun inlineHandleTarget(value: Any?, target: String): Boolean = when (value) {
    is Handle -> "${value.owner.replace('/', '.')}.${value.name}${value.desc}" == target
    is ConstantDynamic -> inlineHandleTarget(value.bootstrapMethod, target) ||
        (0 until value.bootstrapMethodArgumentCount).any { inlineHandleTarget(value.getBootstrapMethodArgument(it), target) }
    else -> false
}

private fun inlineCopyRemoved(
    method: MethodNode, copy: InlineCopyEvidence, rules: List<FoldRule>, applied: Map<Int, Int>,
): Boolean {
    val instructions = method.instructions.filter { it.opcode >= 0 }
    val subroutine = instructions.any { it.opcode == Opcodes.JSR || it.opcode == Opcodes.RET }
    if (copy.startOpcode !in instructions.indices || copy.endOpcode !in 1..instructions.size || subroutine) return false
    val branches = selectedInlineBranches(instructions, copy.caller, rules, applied)
    return branches != null && copyIntervalRemoved(method, instructions, copy, branches)
}

private fun selectedInlineBranches(
    instructions: List<AbstractInsnNode>, caller: String, rules: List<FoldRule>, applied: Map<Int, Int>,
): Map<Int, Boolean>? {
    val candidates = rules.mapIndexedNotNull { index, rule -> selectedInlineBranch(instructions, caller, rule, applied[index]) }
        .groupBy({ it.first }, { it.second })
    return candidates.takeIf { it.isNotEmpty() && it.values.all { values -> values.distinct().size == 1 } }
        ?.mapValues { it.value.first() }
}

private fun copyIntervalRemoved(
    method: MethodNode, instructions: List<AbstractInsnNode>, copy: InlineCopyEvidence, branches: Map<Int, Boolean>,
): Boolean {
    val (before, after) = listOf(emptyMap(), branches).map { selected ->
        inlineInstructionReachability(method, instructions, selected) ?: return false
    }
    val interval = copy.startOpcode until copy.endOpcode
    return interval.any { it in before } && interval.none { it in after }
}

private fun selectedInlineBranch(
    instructions: List<AbstractInsnNode>, caller: String, rule: FoldRule, applied: Int?,
): Pair<Int, Boolean>? {
    if (applied != 1 || rule !is FoldSites) return null
    val value = rule.value.scalar as? Boolean
    val selected = rule.selected.orEmpty().filter { it.caller.substringBefore('(') + it.callerDescriptor == caller }
        .singleOrNull()?.takeIf { it.ordinal == 0 }
    return if (value != null && selected != null) selectedBooleanBranch(instructions, selected, value) else null
}

private fun selectedBooleanBranch(
    instructions: List<AbstractInsnNode>, selected: CallSiteKey, value: Boolean,
): Pair<Int, Boolean>? {
    // This closed proof supports only the uniquely selected first invocation, with descriptor and ordinal zero.
    // The observed per-method rule receipt proves that this selected invocation was folded, including argument matching.
    val callIndex = instructions.indexOfFirst { it is MethodInsnNode || it is InvokeDynamicInsnNode }
    val call = instructions.getOrNull(callIndex) as? MethodInsnNode
    val branch = instructions.getOrNull(callIndex + 1) as? JumpInsnNode
    if (call == null || branch == null || branch.opcode !in setOf(Opcodes.IFEQ, Opcodes.IFNE)) return null
    val matches = "${call.owner.replace('/', '.')}.${call.name}" == selected.callee.substringBefore('(') &&
        call.desc == selected.calleeDescriptor && call.desc.endsWith(")Z")
    val take = if (branch.opcode == Opcodes.IFNE) value else !value
    return if (matches) callIndex + 1 to take else null
}

/** All unselected branches and all protected-region exception edges remain possible. */
private fun inlineInstructionReachability(
    method: MethodNode, instructions: List<AbstractInsnNode>, branches: Map<Int, Boolean>,
): Set<Int>? {
    val positions = instructions.withIndex().associate { it.value to it.index }
    fun label(label: LabelNode): Int {
        var node: AbstractInsnNode? = label
        while (node != null && node.opcode < 0) node = node.next
        return positions[node] ?: instructions.size
    }
    val handlers = method.tryCatchBlocks.map { Triple(label(it.start), label(it.end), label(it.handler)) }
    val visited = linkedSetOf<Int>()
    val pending = ArrayDeque(listOf(0))
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        // Queue entries start at zero and arise only from nonnegative label positions or index + 1.
        // An absent opcode denotes malformed fall-through or a target at code end, not a removable path.
        if (index >= instructions.size) return null
        if (!visited.add(index)) continue
        val instruction = instructions[index]
        handlers.filter { index in it.first until it.second }.forEach { pending.addLast(it.third) }
        normalInlineSuccessors(instruction, index, branches[index], ::label).forEach(pending::addLast)
    }
    return visited
}

private val terminalInlineOpcodes = setOf(Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN,
    Opcodes.ARETURN, Opcodes.RETURN, Opcodes.ATHROW)

private fun normalInlineSuccessors(
    instruction: AbstractInsnNode, index: Int, selectedBranch: Boolean?, label: (LabelNode) -> Int,
): List<Int> = when (instruction) {
    is JumpInsnNode -> buildList {
        if (selectedBranch != false) add(label(instruction.label))
        if (instruction.opcode != Opcodes.GOTO && selectedBranch != true) add(index + 1)
    }
    is LookupSwitchInsnNode -> (instruction.labels + instruction.dflt).map(label)
    is TableSwitchInsnNode -> (instruction.labels + instruction.dflt).map(label)
    else -> if (instruction.opcode in terminalInlineOpcodes) emptyList() else listOf(index + 1)
}
