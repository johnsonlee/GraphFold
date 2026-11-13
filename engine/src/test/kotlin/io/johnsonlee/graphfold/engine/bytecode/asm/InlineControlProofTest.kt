package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.inputs.InlineCopyEvidence
import io.johnsonlee.graphfold.engine.inputs.InlineDeclarationEvidence
import io.johnsonlee.graphfold.engine.inputs.InlineEvidence
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.ConstantPattern
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSites
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.VarInsnNode

class InlineControlProofTest {
    @TempDir lateinit var temporary: Path
    private val target = "Example.bump(I)I"
    private val caller = "Example.run(I)I"

    @ParameterizedTest
    @ValueSource(strings = ["gated", "live", "uncalled", "incomplete", "unapplied", "ordinal", "descriptor", "enabled",
        "dead-before", "direct", "handle", "public", "duplicate", "exception", "mixed", "missing-caller",
        "duplicate-catalog", "receipt-count", "receipt-rule", "conflicting-rules"])
    fun `compiler copy intervals require complete same input selected control evidence`(mode: String) {
        val copies = fixture(mode).toMutableList()
        if (mode == "missing-caller") copies += InlineCopyEvidence(target, "Absent.run(I)I", 0, 1, "verified")
        val declaration = InlineDeclarationEvidence(target, temporary.resolve("Example.kt"), "verified", 0, 1, mode != "incomplete")
        val declarations = if (mode == "duplicate") listOf(declaration, declaration) else listOf(declaration)
        val evidence = InlineEvidence(declarations, if (mode == "uncalled") emptyList() else copies, mapOf("Example" to "verified"))
        val rule = FoldSites("selected", setOf(CallSiteKey("Example.run(int)", "(I)I", "Example.enabled()",
            if (mode == "descriptor") "()I" else "()Z", if (mode == "ordinal") 1 else 0)),
            ConstantPattern("BooleanConstant", mapOf("value" to (mode == "enabled"))))
        val receipts = when (mode) {
            "receipt-count" -> mapOf(0 to 2)
            "receipt-rule" -> mapOf(1 to 1)
            "conflicting-rules" -> mapOf(0 to 1, 1 to 1)
            else -> mapOf(0 to 1)
        }
        val applied = if (mode == "unapplied") emptyMap() else mapOf(caller to receipts)
        val catalogs = if (mode == "duplicate-catalog") listOf(evidence, evidence) else listOf(evidence)
        val rules = if (mode == "conflicting-rules") listOf(rule,
            rule.copy(value = ConstantPattern("BooleanConstant", mapOf("value" to true)))) else listOf(rule)
        assertEquals(if (mode == "gated") setOf(target) else emptySet(),
            inlineControlRemovedMethods(temporary, catalogs, rules, applied))
    }

    @ParameterizedTest
    @ValueSource(strings = ["indy-bootstrap", "indy-argument", "indy-unrelated", "condy-bootstrap", "condy-argument",
        "condy-nested", "condy-unrelated", "scalar", "unrelated-handle"])
    fun `surviving dynamic and constant handles retain their inline declaration`(mode: String) {
        val copies = fixture("gated")
        edit { node ->
            node.version = Opcodes.V11
            val reference = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "reference", "()V", null, null)
            reference.instructions.add(referenceInstruction(mode))
            reference.instructions.add(InsnNode(Opcodes.POP)); reference.instructions.add(InsnNode(Opcodes.RETURN))
            node.methods.add(reference)
        }
        val unrelated = mode in setOf("indy-unrelated", "condy-unrelated", "scalar", "unrelated-handle")
        assertEquals(if (unrelated) setOf(target) else emptySet(), prove(copies))
    }

    private fun referenceInstruction(mode: String): AbstractInsnNode {
        val targetHandle = Handle(Opcodes.H_INVOKESTATIC, "Example", "bump", "(I)I", false)
        val other = Handle(Opcodes.H_INVOKESTATIC, "Example", "bootstrap", "(I)I", false)
        val nested = ConstantDynamic("nested", "I", other, targetHandle)
        return when (mode) {
            "indy-bootstrap" -> InvokeDynamicInsnNode("invoke", "()I", targetHandle)
            "indy-argument" -> InvokeDynamicInsnNode("invoke", "()I", other, 1, targetHandle)
            "indy-unrelated" -> InvokeDynamicInsnNode("invoke", "()I", other, 1, other)
            "condy-bootstrap" -> LdcInsnNode(ConstantDynamic("value", "I", targetHandle))
            "condy-argument" -> LdcInsnNode(ConstantDynamic("value", "I", other, 1, targetHandle))
            "condy-nested" -> LdcInsnNode(ConstantDynamic("value", "I", other, nested))
            "condy-unrelated" -> LdcInsnNode(ConstantDynamic("value", "I", other, 1, other))
            "scalar" -> LdcInsnNode("ordinary constant")
            else -> LdcInsnNode(other)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["non-boolean", "unresolved", "empty", "caller", "ambiguous", "callee", "constant-rule",
        "no-invoke", "dynamic-first", "no-branch", "other-branch", "non-boolean-callee"])
    fun `fold receipts cannot substitute for exact supported call and branch binding`(mode: String) {
        fixture("gated")
        val copies = edit { node -> alterBinding(node, mode) }
        assertEquals(emptySet(), prove(copies, listOf(bindingRule(mode))))
    }

    private fun bindingRule(mode: String): FoldRule {
        val key = CallSiteKey("Example.run(int)", "(I)I", "Example.enabled()", "()Z", 0)
        val rule = FoldSites("selected", setOf(key), ConstantPattern("BooleanConstant", mapOf("value" to false)))
        return when (mode) {
            "non-boolean" -> rule.copy(value = ConstantPattern("IntConstant", mapOf("value" to 0)))
            "unresolved" -> rule.copy(selected = null)
            "empty" -> rule.copy(selected = emptySet())
            "caller" -> rule.copy(selected = setOf(key.copy(caller = "Other.run(int)")))
            "ambiguous" -> rule.copy(selected = setOf(key, key.copy(callee = "Example.other()")))
            "callee" -> rule.copy(selected = setOf(key.copy(callee = "Example.other()")))
            "non-boolean-callee" -> rule.copy(selected = setOf(key.copy(calleeDescriptor = "()I")))
            "constant-rule" -> ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf("callee_name" to "enabled")),
                "value" to false))
            else -> rule
        }
    }

    private fun alterBinding(node: ClassNode, mode: String) {
        val method = node.methods.single { it.name == "run" }
        val invocation = method.instructions.filterIsInstance<MethodInsnNode>().single()
        val branch = method.instructions.filterIsInstance<JumpInsnNode>().single()
        when (mode) {
            "no-invoke" -> method.instructions.set(invocation, InsnNode(Opcodes.ICONST_0))
            "dynamic-first" -> method.instructions.set(invocation, InvokeDynamicInsnNode("enabled", "()Z",
                Handle(Opcodes.H_INVOKESTATIC, "Example", "bootstrap", "()Z", false)))
            "no-branch" -> method.instructions.set(branch, InsnNode(Opcodes.POP))
            "other-branch" -> method.instructions.set(branch, JumpInsnNode(Opcodes.IFLT, branch.label))
            "non-boolean-callee" -> {
                invocation.desc = "()I"
                node.methods.single { it.name == "enabled" }.desc = "()I"
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ifne", "lookup-gated", "table-gated", "lookup-bypass", "table-bypass", "cycle", "jsr", "ret"])
    fun `copy removal retains every unselected CFG edge and rejects unsupported subroutines`(mode: String) {
        fixture("gated")
        val copies = edit { node -> alterControlFlow(node.methods.single { it.name == "run" }, mode) }
        val rule = bindingRule("default") as FoldSites
        val selected = if (mode == "ifne") rule.copy(value = ConstantPattern("BooleanConstant", mapOf("value" to true))) else rule
        val controlled = mode in setOf("ifne", "lookup-gated", "table-gated", "cycle")
        assertEquals(if (controlled) setOf(target) else emptySet(), prove(copies, listOf(selected)))
    }

    private fun alterControlFlow(method: MethodNode, mode: String) {
        val branch = method.instructions.filterIsInstance<JumpInsnNode>().single()
        when {
            mode == "ifne" -> method.instructions.set(branch, JumpInsnNode(Opcodes.IFNE, branch.label))
            mode.startsWith("lookup") || mode.startsWith("table") -> insertSwitch(method, branch, mode)
            mode == "cycle" -> method.instructions.insert(branch.label, InsnList().apply {
                add(VarInsnNode(Opcodes.ILOAD, 0)); add(JumpInsnNode(Opcodes.IFNE, branch.label))
            })
            else -> insertSubroutine(method, branch.label, mode == "ret")
        }
    }

    private fun insertSwitch(method: MethodNode, branch: JumpInsnNode, mode: String) {
        val copyStart = LabelNode()
        method.instructions.insert(branch, copyStart)
        val selector = InsnList().apply { add(VarInsnNode(Opcodes.ILOAD, 0)) }
        val bypass = mode.endsWith("bypass")
        val default = if (bypass) LabelNode() else copyStart
        selector.add(if (mode.startsWith("lookup")) LookupSwitchInsnNode(default, intArrayOf(0), arrayOf(copyStart))
            else TableSwitchInsnNode(0, 0, default, copyStart))
        if (bypass) {
            selector.add(default)
            method.instructions.insert(selector)
        } else method.instructions.insertBefore(copyStart, selector)
    }

    private fun insertSubroutine(method: MethodNode, fallback: LabelNode, earlierReturn: Boolean) {
        val subroutine = LabelNode()
        method.instructions.insert(fallback, JumpInsnNode(Opcodes.JSR, subroutine))
        val body = InsnList().apply { add(subroutine); add(VarInsnNode(Opcodes.ASTORE, 1)); add(VarInsnNode(Opcodes.RET, 1)) }
        if (earlierReturn) {
            val entry = LabelNode()
            val prefix = InsnList().apply { add(JumpInsnNode(Opcodes.GOTO, entry)); add(body); add(entry) }
            method.instructions.insert(prefix)
        } else method.instructions.add(body)
    }

    @ParameterizedTest
    @CsvSource("-1,6", "100,101", "2,0", "2,100")
    fun `copy coordinates outside the actual emitted caller cannot prove control`(start: Int, end: Int) {
        fixture("gated")
        val copy = InlineCopyEvidence(target, caller, start, end, "verified")
        assertEquals(emptySet(), prove(listOf(copy)))
    }

    @Test fun `evidence for a declaration absent from current class bytes cannot prove removal`() {
        val copies = fixture("gated")
        edit { node -> node.methods.removeIf { it.name == "bump" } }
        assertEquals(emptySet(), prove(copies))
    }

    @Test fun `a caller without instructions cannot substantiate a compiler copy interval`() {
        fixture("gated")
        edit { node -> node.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE,
            "nativeCaller", "(I)I", null, null)) }
        val copy = InlineCopyEvidence(target, "Example.nativeCaller(I)I", 0, 1, "verified")
        assertEquals(emptySet(), prove(listOf(copy)))
    }

    @Test fun `exception scopes ending at the final label preserve selected control dominance`() {
        fixture("gated")
        val copies = edit { node ->
            val method = node.methods.single { it.name == "run" }
            val branch = method.instructions.filterIsInstance<JumpInsnNode>().single()
            val start = LabelNode()
            method.instructions.insert(branch, start)
            val handler = LabelNode()
            method.instructions.add(handler)
            method.instructions.add(InsnNode(Opcodes.POP))
            method.instructions.add(InsnNode(Opcodes.ICONST_0))
            method.instructions.add(InsnNode(Opcodes.IRETURN))
            val end = LabelNode()
            method.instructions.add(end)
            method.tryCatchBlocks.add(TryCatchBlockNode(start, end, handler, "java/lang/Throwable"))
        }
        assertEquals(setOf(target), prove(copies))
    }

    @ParameterizedTest
    @ValueSource(strings = ["fall-through", "selected-path-end-target"])
    fun `malformed emitted control flow cannot authorize copy removal`(mode: String) {
        fixture("gated")
        val copies = edit { node ->
            val method = node.methods.single { it.name == "run" }
            if (mode == "fall-through") {
                method.instructions.remove(method.instructions.last)
            } else {
                val branch = method.instructions.filterIsInstance<JumpInsnNode>().single()
                val codeEnd = LabelNode()
                method.instructions.insert(branch, InsnList().apply {
                    add(VarInsnNode(Opcodes.ILOAD, 0)); add(JumpInsnNode(Opcodes.IFNE, codeEnd))
                })
                method.instructions.add(codeEnd)
            }
        }
        assertEquals(emptySet(), prove(copies))
    }

    private fun prove(copies: List<InlineCopyEvidence>, rules: List<FoldRule> = listOf(bindingRule("default"))): Set<String> {
        val declaration = InlineDeclarationEvidence(target, temporary.resolve("Example.kt"), "verified", 0, 1, true)
        val evidence = InlineEvidence(listOf(declaration), copies, mapOf("Example" to "verified"))
        return inlineControlRemovedMethods(temporary, listOf(evidence), rules, mapOf(caller to mapOf(0 to 1)))
    }

    private fun edit(change: (ClassNode) -> Unit): List<InlineCopyEvidence> {
        val path = temporary.resolve("Example.class")
        val node = ClassNode().also { ClassReader(Files.readAllBytes(path)).accept(it, 0) }
        change(node)
        val opcodes = node.methods.single { it.name == "run" }.instructions.filter { it.opcode >= 0 }
        val body = listOf(Opcodes.ILOAD, Opcodes.ICONST_1, Opcodes.IADD, Opcodes.IRETURN)
        val start = opcodes.map { it.opcode }.windowed(body.size).withIndex().single { it.value == body }.index
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        Files.write(path, writer.toByteArray())
        return listOf(InlineCopyEvidence(target, caller, start, start + body.size, "verified"))
    }

    private fun fixture(mode: String): List<InlineCopyEvidence> {
        val node = ClassNode().apply {
            version = Opcodes.V1_6; access = Opcodes.ACC_PUBLIC; name = "Example"; superName = "java/lang/Object"
        }
        node.methods.add(MethodNode(Opcodes.ACC_STATIC or if (mode == "public") Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE,
            "bump", "(I)I", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ILOAD, 0)); instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(InsnNode(Opcodes.IADD)); instructions.add(InsnNode(Opcodes.IRETURN))
        })
        node.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "enabled", "()Z", null, null).apply {
            instructions.add(InsnNode(Opcodes.ICONST_0)); instructions.add(InsnNode(Opcodes.IRETURN))
        })
        val copies = mutableListOf<InlineCopyEvidence>()
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "(I)I", null, null)
        fun copy() {
            val start = method.instructions.count { it.opcode >= 0 }
            method.instructions.add(VarInsnNode(Opcodes.ILOAD, 0)); method.instructions.add(InsnNode(Opcodes.ICONST_1))
            method.instructions.add(InsnNode(Opcodes.IADD)); method.instructions.add(InsnNode(Opcodes.IRETURN))
            copies += InlineCopyEvidence(target, caller, start, start + 4, "verified")
        }
        val fallback = LabelNode()
        if (mode == "dead-before") method.instructions.add(JumpInsnNode(Opcodes.GOTO, fallback))
        val protectedStart = LabelNode().also(method.instructions::add)
        method.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "Example", "enabled", "()Z", false))
        method.instructions.add(JumpInsnNode(Opcodes.IFEQ, fallback))
        if (mode != "live") copy() else {
            method.instructions.add(VarInsnNode(Opcodes.ILOAD, 0)); method.instructions.add(InsnNode(Opcodes.IRETURN))
        }
        val protectedEnd = LabelNode().also(method.instructions::add)
        method.instructions.add(fallback)
        if (mode in setOf("live", "mixed")) copy() else {
            method.instructions.add(VarInsnNode(Opcodes.ILOAD, 0)); method.instructions.add(InsnNode(Opcodes.IRETURN))
        }
        if (mode == "exception") {
            val handler = LabelNode().also(method.instructions::add)
            method.instructions.add(InsnNode(Opcodes.POP))
            copy()
            method.tryCatchBlocks.add(TryCatchBlockNode(protectedStart, protectedEnd, handler, "java/lang/Throwable"))
        }
        node.methods.add(method)
        if (mode in setOf("direct", "handle")) node.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "reference", "()V", null, null).apply {
            if (mode == "direct") {
                instructions.add(InsnNode(Opcodes.ICONST_0))
                instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "Example", "bump", "(I)I", false))
            } else instructions.add(LdcInsnNode(Handle(Opcodes.H_INVOKESTATIC, "Example", "bump", "(I)I", false)))
            instructions.add(InsnNode(Opcodes.POP)); instructions.add(InsnNode(Opcodes.RETURN))
        })
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        Files.write(temporary.resolve("Example.class"), writer.toByteArray())
        return copies
    }

}
