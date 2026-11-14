package io.johnsonlee.graphfold.gradle

import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Inspects the exact nested packaged library, not a separate synthetic stand-in for requested Java mechanisms. */
internal object AdvancedBusinessBytecode {
    fun verify(artifact: Path, boot: Boolean) {
        val classes = libraryClasses(artifact, boot)
        val captured = classes.getValue("sample/CapturedQuote")
        val lambda = lambdas(captured).single()
        assertEquals(3, Type.getArgumentTypes(lambda.desc).size)
        assertTrue(captured.methods.any { it.name.startsWith("lambda$") && it.access and Opcodes.ACC_SYNTHETIC != 0 })
        listOf("CheckedQuote", "ReceiverQuote").forEach { name ->
            val contract = classes.getValue("sample/$name")
            assertTrue(contract.access and Opcodes.ACC_INTERFACE != 0)
            assertEquals(1, contract.methods.count { it.access and Opcodes.ACC_ABSTRACT != 0 })
        }
        reference(classes.getValue("sample/BoundReferenceQuote"), 1)
        reference(classes.getValue("sample/UnboundReferenceQuote"), 0)
        verifyHandles(classes)
        verifyStream(classes.getValue("sample/StreamQuote"))
    }

    fun verifyLazy(artifact: Path, boot: Boolean) {
        val owner = libraryClasses(artifact, boot).getValue("sample/LazyQuote")
        assertEquals(1, lambdas(owner).size)
        assertTrue(owner.methods.any { it.name.startsWith("lambda$") && it.access and Opcodes.ACC_SYNTHETIC != 0 })
        val operations = calls(owner).filter { it.owner == "java/util/stream/Stream" }.map { it.name }.toSet()
        assertEquals(setOf("of", "map"), operations, "The actual packaged pipeline must have no terminal operation")
    }

    private fun verifyHandles(classes: Map<String, ClassNode>) {
        val exact = calls(classes.getValue("sample/StaticHandleQuote"))
        assertTrue(exact.any { it.owner == "java/lang/invoke/MethodHandles" && it.name == "lookup" })
        assertTrue(exact.any { it.owner == "java/lang/invoke/MethodHandles\$Lookup" && it.name == "findStatic" })
        assertTrue(exact.any { it.owner == "java/lang/invoke/MethodHandle" && it.name == "invokeExact" &&
            it.desc == "(Lsample/StaticHandleQuote\$Traffic;)I" })
        val bound = calls(classes.getValue("sample/BoundHandleQuote"))
        assertTrue(bound.any { it.owner == "java/lang/invoke/MethodHandles\$Lookup" && it.name == "findVirtual" })
        assertTrue(bound.any { it.owner == "java/lang/invoke/MethodHandle" && it.name == "bindTo" })
        assertTrue(bound.any { it.owner == "java/lang/invoke/MethodHandle" && it.name == "invoke" &&
            it.desc == "()Ljava/lang/Object;" })
    }

    private fun verifyStream(stream: ClassNode) {
        val operations = calls(stream).filter { it.owner == "java/util/stream/Stream" }.map { it.name }.toSet()
        assertTrue(operations.containsAll(setOf("filter", "map", "collect", "reduce")), operations.toString())
        assertTrue(lambdas(stream).any { Type.getArgumentTypes(it.desc).size == 3 })
    }

    private fun reference(owner: ClassNode, captures: Int) {
        val reference = lambdas(owner).single()
        assertEquals(captures, Type.getArgumentTypes(reference.desc).size)
        val target = reference.bsmArgs.filterIsInstance<Handle>().single()
        assertEquals(Opcodes.H_INVOKEVIRTUAL, target.tag)
        assertEquals("value", target.name)
    }

    private fun calls(owner: ClassNode): List<MethodInsnNode> = owner.methods.flatMap {
        it.instructions.filterIsInstance<MethodInsnNode>()
    }

    private fun lambdas(owner: ClassNode): List<InvokeDynamicInsnNode> = owner.methods.flatMap {
        it.instructions.filterIsInstance<InvokeDynamicInsnNode>()
    }.filter { it.bsm.owner == "java/lang/invoke/LambdaMetafactory" }

    private fun libraryClasses(artifact: Path, boot: Boolean): Map<String, ClassNode> = JarFile(artifact.toFile()).use { jar ->
        val prefix = if (boot) "BOOT-INF/lib/" else "WEB-INF/lib/"
        val library = jar.entries().asSequence().single { it.name.startsWith(prefix) && it.name.endsWith("/business.jar") }
        jar.getInputStream(library).use { input -> readClasses(JarInputStream(input)) }
    }

    private fun readClasses(jar: JarInputStream): Map<String, ClassNode> {
        val classes = linkedMapOf<String, ClassNode>()
        jar.use {
            var entry = jar.nextJarEntry
            while (entry != null) {
                readClassEntry(jar, entry)?.let { node -> classes[node.name] = node }
                entry = jar.nextJarEntry
            }
        }
        return classes
    }

    private fun readClassEntry(jar: JarInputStream, entry: java.util.jar.JarEntry): ClassNode? =
        if (!entry.isDirectory && entry.name.endsWith(".class")) {
            ClassNode().also { ClassReader(jar.readBytes()).accept(it, 0) }
        } else null
}
