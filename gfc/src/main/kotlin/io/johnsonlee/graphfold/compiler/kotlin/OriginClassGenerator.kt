package io.johnsonlee.graphfold.compiler.kotlin

import io.johnsonlee.graphfold.compiler.protocol.OriginProtocol.Kind
import org.jetbrains.kotlin.backend.jvm.extensions.ClassGenerator
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.isAnonymousObject
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

internal class OriginClassGenerator(
    private val generator: ClassGenerator,
    private val declaration: IrClass?,
    private val collector: KotlinOriginCollector,
    private val runId: String,
) : ClassGenerator by generator {
    private lateinit var owner: String
    override fun defineClass(
        version: Int, access: Int, name: String, signature: String?, superName: String, interfaces: Array<out String>,
    ) {
        owner = name
        generator.defineClass(version, access, name, signature, superName, interfaces)
        val sourceAnonymous = declaration?.let {
            it.isAnonymousObject && GeneratedClassOrigin.values().none { origin -> origin.name == it.origin.name }
        } == true
        val origin = declaration?.let {
            collector.capture(name, it.file.fileEntry.name, it.startOffset, it.endOffset,
                if (sourceAnonymous) Kind.ANONYMOUS_OBJECT else Kind.DECLARATION)
        }
        collector.emitted(name, origin, declaration == null, sourceAnonymous)
        if (origin != null) {
            generator.visitAnnotation(KotlinOriginCollector.MARKER, false).apply {
                visit("schema", 1)
                visit("runId", runId)
                visit("originKind", origin.kind.name)
                visit("source", origin.source)
                visit("sourceHash", origin.sourceHash)
                visit("start", origin.start)
                visit("end", origin.end)
                visitEnd()
            }
        }
    }

    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor {
        val downstream = generator.visitAnnotation(desc, visible)
        return if (desc != KotlinOriginCollector.MARKER) downstream else
            object : AnnotationVisitor(Opcodes.ASM9, downstream) {
                private val values = linkedMapOf<String, Any?>()
                override fun visit(name: String?, value: Any?) {
                    values[name.orEmpty()] = value
                    super.visit(name, value)
                }
                override fun visitEnd() {
                    collector.annotation(owner, values)
                    super.visitEnd()
                }
            }
    }

    override fun visitEnclosingMethod(owner: String, name: String?, desc: String?) {
        collector.enclosing(this.owner, owner, name, desc)
        generator.visitEnclosingMethod(owner, name, desc)
    }

    override fun done(generateSmapCopyToAnnotation: Boolean) {
        generator.done(generateSmapCopyToAnnotation)
        collector.completed(owner)
    }
}

// Backend-generated lambda/continuation classes also satisfy IrClass.isAnonymousObject.
// Only these explicit compiler origins are exempt; an unknown source anonymous object remains required.
// IrDeclarationOrigin.name is ABI-stable across K1/K2; JvmLoweredDeclarationOrigin singleton layout is not.
private enum class GeneratedClassOrigin { LAMBDA_IMPL, SUSPEND_LAMBDA, CONTINUATION_CLASS }
