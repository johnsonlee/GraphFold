package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.JdkRuntimeClasses
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * A bounded bytecode proof that an exact dependency call cannot discover or invoke hidden application members.
 * It does not prove purity or resource independence. Missing/ambiguous declarations, unknown dispatch, native
 * methods, reflection, method handles and dynamic bootstrap code remain unknown, even for zero-argument calls.
 */
internal class DependencyObservationProof(
    private val dependencies: List<Path>, private val applicationOwners: Set<String>,
) : AutoCloseable {
    private val classes = mutableMapOf<String, ClassNode?>()
    private class Proofs {
        val methods = mutableMapOf<String, Boolean>()
        val initializers = mutableMapOf<String, Boolean>()
    }
    private val observationProofs = Proofs()
    private val retentionProofs = Proofs()
    private var committed = observationProofs
    private var provisional = Proofs()
    private val activeMethods = hashSetOf<String>()
    private val activeInitializers = hashSetOf<String>()
    private var rejectRetention = false
    private val archives = mutableMapOf<Path, ZipFile>()

    override fun close() { archives.values.forEach(ZipFile::close) }

    fun proves(call: MethodInsnNode): Boolean = evaluate(call, false)

    /** A separate proof: no reference can be stored or returned for a later observer. */
    fun provesNoRetention(call: MethodInsnNode): Boolean = evaluate(call, true)

    private fun evaluate(call: MethodInsnNode, retention: Boolean): Boolean {
        rejectRetention = retention
        committed = if (retention) retentionProofs else observationProofs
        provisional = Proofs()
        return try {
            attempt(call).also { succeeded ->
                // A cycle-dependent result is valid only once its entire root traversal succeeds.
                if (succeeded) {
                    committed.methods.putAll(provisional.methods)
                    committed.initializers.putAll(provisional.initializers)
                }
            }
        } finally {
            // Failed and exceptional roots discard only their own provisional work.
            provisional = Proofs()
        }
    }

    private fun attempt(call: MethodInsnNode): Boolean = try {
        invocation(call, 0)
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IndexOutOfBoundsException) {
        false
    }

    private fun invocation(call: MethodInsnNode, depth: Int): Boolean =
        depth < MAX_DEPTH && !reflective(call.owner) && target(call)?.let { (owner, method) ->
            val identity = "${owner.name}.${method.name}${method.desc}"
            committed.methods[identity] ?: provisional.methods.getOrPut(identity) {
                if (!activeMethods.add(identity)) false else try {
                    val initialization = call.opcode == Opcodes.INVOKESTATIC || call.name == "<init>"
                    (!initialization || initialized(owner.name, depth + 1)) &&
                        method.instructions.all { instruction(it, depth + 1) }
                } finally {
                    activeMethods -= identity
                }
            }
        } == true

    private fun target(call: MethodInsnNode): Pair<ClassNode, MethodNode>? = type(call.owner)?.let { owner ->
        owner.methods.singleOrNull { it.name == call.name && it.desc == call.desc }?.takeIf { method ->
            val exact = call.opcode == Opcodes.INVOKESTATIC || call.opcode == Opcodes.INVOKESPECIAL ||
                method.access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL) != 0 || owner.access and Opcodes.ACC_FINAL != 0
            exact && method.access and (Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT) == 0 && method.instructions.size() > 0
        }?.let { owner to it }
    }

    private fun instruction(instruction: AbstractInsnNode, depth: Int): Boolean =
        (!rejectRetention || !retainsReference(instruction)) && when (instruction) {
            is MethodInsnNode -> invocation(instruction, depth)
            is FieldInsnNode -> instruction.owner !in applicationOwners &&
                (instruction.opcode != Opcodes.GETSTATIC && instruction.opcode != Opcodes.PUTSTATIC ||
                    initialized(instruction.owner, depth))
            is TypeInsnNode -> instruction.opcode != Opcodes.NEW || initialized(instruction.desc, depth)
            is InvokeDynamicInsnNode -> false
            is LdcInsnNode -> instruction.cst !is Handle && instruction.cst !is ConstantDynamic
            else -> true
        }

    private fun retainsReference(instruction: AbstractInsnNode): Boolean = when (instruction.opcode) {
        Opcodes.AASTORE, Opcodes.ARETURN, Opcodes.ATHROW -> true
        Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> (instruction as FieldInsnNode).desc.first() in "L["
        else -> false
    }

    private fun initialized(name: String, depth: Int): Boolean = depth < MAX_DEPTH &&
        if (name in activeInitializers) true else committed.initializers[name] ?: provisional.initializers.getOrPut(name) {
            type(name)?.let { owner ->
                activeInitializers.add(name)
                try {
                    val parents = owner.interfaces + listOfNotNull(owner.superName)
                    parents.all { initialized(it, depth + 1) } && owner.methods.filter { it.name == "<clinit>" }.all { method ->
                        method.access and (Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT) == 0 && method.instructions.size() > 0 &&
                            method.instructions.all { instruction(it, depth + 1) }
                    }
                } finally {
                    activeInitializers -= name
                }
            } == true
        }

    private fun type(name: String): ClassNode? {
        if (name !in classes) classes[name] = if (name in applicationOwners) null else read(name)
        return classes[name]
    }

    private fun read(name: String): ClassNode? {
        val platform = JdkRuntimeClasses.bytes(name)
        val bytes = platform ?: dependencies.mapNotNull { dependency ->
            val resource = "$name.class"
            if (Files.isDirectory(dependency)) {
                dependency.resolve(resource).takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
            } else archives.getOrPut(dependency) { ZipFile(dependency.toFile()) }.let { archive ->
                archive.getEntry(resource)?.let { entry -> archive.getInputStream(entry).use { it.readBytes() } }
            }
        }.singleOrNull()
        return bytes?.let { ClassNode().also { node ->
            ClassReader(it).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }.takeIf { node -> node.name == name } }
    }

    private fun reflective(owner: String): Boolean = owner == "java/lang/Class" || owner.endsWith("ClassLoader") ||
        owner.startsWith("java/lang/reflect/") || owner.startsWith("java/lang/invoke/") ||
        owner.startsWith("kotlin/reflect/") || owner.startsWith("kotlin/jvm/internal/Reflection")

    private companion object { const val MAX_DEPTH = 32 }
}
