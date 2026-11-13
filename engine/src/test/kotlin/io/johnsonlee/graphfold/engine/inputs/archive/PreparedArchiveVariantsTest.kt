package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import kotlin.test.assertEquals

class PreparedArchiveVariantsTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @CsvSource("jar,8,1", "jar,17,3", "jar,21,4", "war,17,3", "boot,17,3")
    fun `only the build JDK archive view becomes a graph`(layout: String, javaVersion: Int, expected: Int) {
        val project = compiled("selected", layout, javaVersion)
        val revision = prepareRevision("sha", temporary, project, temporary.resolve("input"), DependencyInputCache())
        assertEquals(setOf("source"), revision.scopes.keys)
        val scope = revision.scopes.getValue("source")
        assertEquals(expected, value(scope.classes, scope.dependencies))
    }

    @Test fun `base and head use their own build JDK without multiplying scopes`() {
        val base = compiled("base", "jar", 17)
        val head = compiled("head", "jar", 21)
        val (old, new) = prepareRevisions(CompiledRevisionInput("base", temporary, base),
            CompiledRevisionInput("head", temporary, head), temporary.resolve("inputs"), DependencyInputCache())
        assertEquals(setOf("source"), old.scopes.keys)
        assertEquals(old.scopes.keys, new.scopes.keys)
        assertEquals(3, old.scopes.getValue("source").let { value(it.classes, it.dependencies) })
        assertEquals(4, new.scopes.getValue("source").let { value(it.classes, it.dependencies) })
    }

    @Test fun `different scope build JDKs each produce exactly one graph`() {
        val first = compiled("first", "jar", 17)
        val second = compiled("second", "jar", 21)
        val project = first.copy(analysisScopes = linkedMapOf("first" to first, "second" to second))
        val revision = prepareRevision("sha", temporary, project, temporary.resolve("inputs"), DependencyInputCache())
        assertEquals(setOf("first", "second"), revision.scopes.keys)
        assertEquals(listOf(3, 4), revision.scopes.values.map { value(it.classes, it.dependencies) })
    }

    private fun compiled(side: String, layout: String, javaVersion: Int): InputProject {
        val directory = Files.createDirectory(temporary.resolve(side))
        val library = projectionArchive(directory.resolve("library.jar"), mapOf(
            "sample/Value.class" to bytecode(1), "META-INF/versions/9/sample/Value.class" to bytecode(2),
            "META-INF/versions/17/sample/Value.class" to bytecode(3), "META-INF/versions/21/sample/Value.class" to bytecode(4)),
            mapOf("Multi-Release" to "true"))
        val archive = when (layout) {
            "war" -> projectionArchive(directory.resolve("input.war"), mapOf("WEB-INF/lib/library.jar" to Files.readAllBytes(library)))
            "boot" -> projectionArchive(directory.resolve("input.jar"), mapOf("BOOT-INF/lib/library.jar" to Files.readAllBytes(library)))
            else -> library
        }
        return InputProject(Files.createDirectory(directory.resolve("compiler")), emptyList(), emptyList(),
            archiveInput = ArchiveInput("selected", listOf(ArchiveEntryInput(archive))), javaVersion = javaVersion)
    }

    private fun value(classes: Path, dependencies: List<Path>): Int {
        val owned = classes.resolve("sample/Value.class")
        val bytes = if (Files.exists(owned)) Files.readAllBytes(owned) else dependencies.firstNotNullOf { path ->
            JarFile(path.toFile()).use { jar -> jar.getJarEntry("sample/Value.class")?.let { jar.getInputStream(it).readBytes() } }
        }
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        return requireNotNull(node.methods.single().instructions.toArray().filterIsInstance<LdcInsnNode>().single().cst) as Int
    }

    private fun bytecode(value: Int): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Value", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode(); visitLdcInsn(value); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
