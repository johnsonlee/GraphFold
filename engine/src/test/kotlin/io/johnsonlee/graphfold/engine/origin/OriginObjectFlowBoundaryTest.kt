package io.johnsonlee.graphfold.engine.origin

import org.junit.jupiter.api.Test
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rare bytecode shapes and incomplete evidence must never turn an observability guard into a silent pass. */
class OriginObjectFlowBoundaryTest {
    @Test fun `unreachable sinks and invalid operand frames retain a conservative diagnostic`() {
        val end = LabelNode()
        val unreachableSink = render()
        val unreachable = method().apply {
            instructions.add(JumpInsnNode(Opcodes.GOTO, end))
            instructions.add(InsnNode(Opcodes.ACONST_NULL))
            instructions.add(unreachableSink)
            instructions.add(InsnNode(Opcodes.POP))
            instructions.add(end)
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        assertTrue(blocked(unreachable), "An unreachable instruction has no frame proving safe provenance")
        assertFalse(OriginObjectFlow("sample/Caller", unreachable, OriginRenderTypes(emptyList(), emptySet()))
            .nonNullReceiver(unreachableSink, 0))
        val invalidSink = render()
        val invalid = method().apply {
            instructions.add(invalidSink)
            instructions.add(InsnNode(Opcodes.POP))
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        assertTrue(blocked(invalid), "An analyzer error must retain the original guard")
        assertFalse(OriginObjectFlow("sample/Caller", invalid, OriginRenderTypes(emptyList(), emptySet()))
            .nonNullReceiver(invalidSink, 0))
    }

    @Test fun `declared Object remains unknown while final scalar and primitive array parameters are safe`() {
        listOf("Ljava/lang/Object;" to true, "Ljava/lang/String;" to false, "[I" to false,
            "[[D" to false, "[Ljava/lang/Object;" to true).forEach { (parameter, expected) ->
            val method = method("($parameter)V").apply {
                instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                instructions.add(render())
                instructions.add(InsnNode(Opcodes.POP))
                instructions.add(InsnNode(Opcodes.RETURN))
            }
            if (expected) assertTrue(blocked(method), parameter) else assertFalse(blocked(method), parameter)
        }
    }

    @Test fun `wide primitive values count as frame operands rather than JVM slots`() {
        val sink = MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Consumer", "accept", "(IJD)V", false)
        val method = method().apply {
            instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(InsnNode(Opcodes.LCONST_0))
            instructions.add(InsnNode(Opcodes.DCONST_1))
            instructions.add(sink)
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        val flow = OriginObjectFlow("sample/Caller", method, OriginRenderTypes(emptyList(), emptySet()))
        assertTrue(flow.safeOperands(sink, 3))
        assertFalse(flow.safeOperands(sink, 4), "Insufficient operand evidence must not pass")
        assertFalse(flow.nonNullReceiver(sink, 3), "A static operand stack has no implicit receiver")
    }

    @Test fun `unrelated branch local slot reuse cannot contaminate subsequent exact receiver proof`() {
        listOf(Opcodes.ISTORE to Opcodes.ASTORE, Opcodes.ASTORE to Opcodes.ISTORE,
            Opcodes.ISTORE to Opcodes.FSTORE).forEach { (firstStore, secondStore) ->
            val alternate = LabelNode()
            val joined = LabelNode()
            val method = method("(Z)V").apply {
                instructions.add(VarInsnNode(Opcodes.ILOAD, 0))
                instructions.add(JumpInsnNode(Opcodes.IFEQ, alternate))
                instructions.add(localConstant(firstStore))
                instructions.add(VarInsnNode(firstStore, 1))
                instructions.add(JumpInsnNode(Opcodes.GOTO, joined))
                instructions.add(alternate)
                instructions.add(localConstant(secondStore))
                instructions.add(VarInsnNode(secondStore, 1))
                instructions.add(joined)
                allocate(instructions::add)
                instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString",
                    "()Ljava/lang/String;", false))
                instructions.add(InsnNode(Opcodes.POP))
                instructions.add(InsnNode(Opcodes.RETURN))
            }
            assertFalse(blocked(method), "$firstStore / $secondStore")
        }
    }

    @Test fun `numeric constants in frames and concat bootstrap do not become object name sources`() {
        val sink = MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Consumer", "accept", "(D)V", false)
        val numeric = method().apply {
            instructions.add(LdcInsnNode(3.5))
            instructions.add(sink)
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        assertTrue(OriginObjectFlow("sample/Caller", numeric, OriginRenderTypes(emptyList(), emptySet())).safeOperands(sink, 1))
        val concat = InvokeDynamicInsnNode("makeConcatWithConstants", "()Ljava/lang/String;", CONCAT, "\u0002", 42)
        assertFalse(blocked(returned(concat)))
    }

    @Test fun `nonnull bound references still require safe receiver identity and supported target semantics`() {
        val target = Handle(Opcodes.H_INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false)
        val bound = method().apply {
            instructions.add(TypeInsnNode(Opcodes.NEW, "sample/Renamed"))
            instructions.add(InsnNode(Opcodes.DUP))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "sample/Renamed", "<init>", "()V", false))
            instructions.add(InvokeDynamicInsnNode("get", "(Ljava/lang/Object;)Ljava/util/function/Supplier;",
                LAMBDA, Type.getMethodType("()Ljava/lang/Object;"), target, Type.getMethodType("()Ljava/lang/String;")))
            instructions.add(InsnNode(Opcodes.POP))
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        assertTrue(blocked(bound), "A nonnull renamed allocation is still observable")
        val stackReader = Handle(Opcodes.H_INVOKEVIRTUAL, "java/lang/Throwable", "getStackTrace",
            "()[Ljava/lang/StackTraceElement;", false)
        val stack = method().apply {
            instructions.add(InsnNode(Opcodes.ACONST_NULL))
            instructions.add(InvokeDynamicInsnNode("get", "(Ljava/lang/Throwable;)Ljava/util/function/Supplier;",
                LAMBDA, Type.getMethodType("()Ljava/lang/Object;"), stackReader,
                Type.getMethodType("()[Ljava/lang/StackTraceElement;")))
            instructions.add(InsnNode(Opcodes.POP))
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        assertTrue(blocked(stack), "Bound stack accessors are not rendering summaries")
    }

    @Test fun `missing cyclic interface and malformed ancestry cannot prove default rendering`() {
        val cycleA = definition("sample/CycleA", "sample/CycleB")
        val cycleB = definition("sample/CycleB", "sample/CycleA")
        val orphan = definition("sample/Orphan", null)
        val missingParent = definition("sample/MissingParent", "missing/Parent")
        val extension = definition("sample/Extension").apply { interfaces.add("java/lang/CharSequence") }
        val definitions = listOf(cycleA, cycleB, orphan, missingParent, extension)
        val types = OriginRenderTypes(definitions, emptySet())
        (definitions.map { it.name } + "missing/EntireClass").forEach { name ->
            assertFalse(types.safeAllocation(name), name)
        }
        assertTrue(types.safeAllocation("java/lang/Object"))
        assertTrue(types.safeAllocation("java/lang/Integer"))
        assertFalse(OriginRenderTypes(emptyList(), setOf("java/lang/Object")).safeAllocation("java/lang/Object"),
            "Explicitly renamed identities take precedence over builtin rendering summaries")
    }

    @Test fun `static application rendering and overridable output sinks cannot pass without receiver evidence`() {
        val static = method().apply {
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "external/Renderer", "toString",
                "()Ljava/lang/String;", false))
            instructions.add(InsnNode(Opcodes.POP))
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        assertTrue(blocked(static), "Zero operands do not prove an arbitrary static renderer safe")
        val output = method().apply {
            instructions.add(FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"))
            allocate(instructions::add)
            instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false))
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        assertTrue(blocked(output), "PrintStream can override rendering even with a safe argument")
    }

    @Test fun `concat bootstrap class and handle constants remain observable without dynamic reference arguments`() {
        val constants = listOf<Any>(Type.getObjectType("sample/Renamed"),
            Handle(Opcodes.H_INVOKEVIRTUAL, "sample/Renamed", "hashCode", "()I", false),
            ConstantDynamic("origin", "Ljava/lang/Class;", Handle(Opcodes.H_INVOKESTATIC, "custom/Bootstrap", "owner",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;" +
                    "Ljava/lang/Class;)Ljava/lang/Class;", false), Type.getObjectType("sample/Renamed")))
        constants.forEach { constant ->
            val concat = InvokeDynamicInsnNode("makeConcatWithConstants", "()Ljava/lang/String;", CONCAT,
                "\u0002", constant)
            assertTrue(blocked(returned(concat)), constant.toString())
        }
        val primitive = method().apply {
            instructions.add(InsnNode(Opcodes.LCONST_0))
            instructions.add(InvokeDynamicInsnNode("makeConcatWithConstants", "(J)Ljava/lang/String;", CONCAT,
                "\u0001\u0002", Type.getObjectType("sample/Renamed")))
            instructions.add(InsnNode(Opcodes.POP))
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        assertTrue(blocked(primitive), "Primitive dynamic operands do not erase bootstrap references")
    }

    @Test fun `Kotlin console wrappers cannot hide overridable output behavior behind safe arguments`() {
        listOf("print", "println").forEach { name ->
            val method = method().apply {
                allocate(instructions::add)
                instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "kotlin/io/ConsoleKt", name,
                    "(Ljava/lang/Object;)V", false))
                instructions.add(InsnNode(Opcodes.RETURN))
            }
            assertTrue(blocked(method), name)
        }
    }

    @Test fun `bound references require the known bootstrap and exactly one valid instance rendering target`() {
        val target = Handle(Opcodes.H_INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false)
        val unknown = Handle(Opcodes.H_INVOKESTATIC, "custom/Bootstrap", "link", LAMBDA.desc, false)
        val static = Handle(Opcodes.H_INVOKESTATIC, "custom/Renderer", "toString", "()Ljava/lang/String;", false)
        val variants = listOf(unknown to listOf(target), LAMBDA to listOf(target, target), LAMBDA to listOf(static))
        variants.forEach { (bootstrap, targets) ->
            val method = method().apply {
                allocate(instructions::add)
                instructions.add(InvokeDynamicInsnNode("get", "(Ljava/lang/Object;)Ljava/util/function/Supplier;",
                    bootstrap, *targets.toTypedArray()))
                instructions.add(InsnNode(Opcodes.POP))
                instructions.add(InsnNode(Opcodes.RETURN))
            }
            assertTrue(blocked(method), "$bootstrap / $targets")
        }
    }

    @Test fun `bound stack observation remains blocked even when receiver provenance is null`() {
        val method = method().apply {
            instructions.add(InsnNode(Opcodes.ACONST_NULL))
            instructions.add(InvokeDynamicInsnNode("get", "(Ljava/lang/StackTraceElement;)Ljava/util/function/Supplier;",
                LAMBDA, Type.getMethodType("()Ljava/lang/Object;"),
                Handle(Opcodes.H_INVOKEVIRTUAL, "java/lang/StackTraceElement", "toString", "()Ljava/lang/String;", false),
                Type.getMethodType("()Ljava/lang/String;")))
            instructions.add(InsnNode(Opcodes.POP))
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        assertTrue(blocked(method), "Object-flow evidence must not disable the independent stack guard")
    }

    private fun blocked(method: MethodNode): Boolean {
        val caller = definition("sample/Caller").apply { methods.add(method) }
        return originNameObservability(listOf(caller, definition("sample/Renamed")),
            mapOf("sample.Renamed" to "sample.Stable")).any { "sample/Caller.probe(" in it }
    }

    private fun returned(instruction: AbstractInsnNode): MethodNode = method().apply {
        instructions.add(instruction)
        instructions.add(InsnNode(Opcodes.POP))
        instructions.add(InsnNode(Opcodes.RETURN))
    }

    private fun method(descriptor: String = "()V") = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
        "probe", descriptor, null, null).apply { maxStack = 16; maxLocals = 8 }

    private fun definition(owner: String, parent: String? = "java/lang/Object") = ClassNode().apply {
        name = owner
        superName = parent
    }

    private fun render() = MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
        "(Ljava/lang/Object;)Ljava/lang/String;", false)

    private fun localConstant(store: Int): AbstractInsnNode = InsnNode(when (store) {
        Opcodes.ISTORE -> Opcodes.ICONST_1
        Opcodes.FSTORE -> Opcodes.FCONST_1
        else -> Opcodes.ACONST_NULL
    })

    private fun allocate(append: (AbstractInsnNode) -> Unit) {
        append(TypeInsnNode(Opcodes.NEW, "java/lang/Object"))
        append(InsnNode(Opcodes.DUP))
        append(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
    }

    private companion object {
        val CONCAT = Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false)
        val LAMBDA = Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;", false)
    }
}
