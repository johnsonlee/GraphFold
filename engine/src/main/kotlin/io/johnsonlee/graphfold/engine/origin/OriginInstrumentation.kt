package io.johnsonlee.graphfold.engine.origin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/** Remove only the reserved compiler marker; unverified identities, metadata and instructions remain untouched. */
internal fun stripOriginInstrumentation(classes: Path, destination: Path): Path {
    Files.createDirectories(destination)
    Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
        val target = destination.resolve(classes.relativize(file))
        Files.createDirectories(target.parent)
        if (file.toString().endsWith(".class")) {
            val reader = ClassReader(Files.readAllBytes(file))
            val writer = ClassWriter(reader, 0)
            reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                    if (descriptor == ORIGIN_MARKER) null else super.visitAnnotation(descriptor, visible)
            }, 0)
            Files.write(target, writer.toByteArray())
        } else {
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } }
    return destination
}
