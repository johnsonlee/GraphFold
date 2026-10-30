package io.johnsonlee.graphfold.engine.graphite

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Handle
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.TypeAnnotationNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Independent inventory catches frontend omissions and preserves declaration-only changes. */
internal class BytecodeInventory {
    val initialization = ClassInitialization()
    val inheritedEntries = InheritedEntries()
    var overrides: Set<String> = emptySet()
    val methods = linkedSetOf<String>()
    val declarations = linkedMapOf<String, String>()
    val diagnostics = mutableListOf<String>()
    val classes = linkedSetOf<String>()
    val references = linkedSetOf<String>()
    val fields = linkedMapOf<String, FieldSnapshot>()
    val classShapes = linkedMapOf<String, String>()
    val entryClasses = linkedSetOf<String>()
    val externalEntries = linkedSetOf<String>()
    val enclosingClasses = linkedMapOf<String, String>()
    private val fieldReads = linkedSetOf<String>()
    private val propertyTokenDiagnostics = linkedSetOf<String>()
    val ignoredFields: Set<String> get() = fields.values.filter { it.renameSafe && it.identity !in fieldReads }
        .map { it.identity }.toSet()

    fun read(path: Path) {
        if (Files.isDirectory(path)) {
            Files.walk(path).use { paths ->
                paths.filter { Files.isRegularFile(it) }.sorted().forEach(::readFile)
            }
        } else archive(path)
    }

    private fun readFile(file: Path) {
        when {
            file.toString().endsWith(".class") -> add(Files.readAllBytes(file))
            file.toString().endsWith(".jar") -> archive(file)
        }
    }

    private fun archive(path: Path) {
        ZipFile(path.toFile()).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry -> readEntry(zip, entry) }
        }
    }

    private fun readEntry(zip: ZipFile, entry: java.util.zip.ZipEntry) {
        when {
            entry.name.startsWith("META-INF/versions/") ->
                diagnostics.add("Multi-release entries require explicit runtime selection: ${entry.name}")
            entry.name.endsWith(".class") -> zip.getInputStream(entry).use { add(it.readBytes()) }
            entry.name.endsWith(".jar") -> diagnostics.add("Nested archive declaration inventory is unsupported: ${entry.name}")
        }
    }

    private fun add(bytes: ByteArray) {
        val node = ClassNode()
        ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        initialization.add(node)
        val name = node.name.replace('/', '.')
        val enclosing = node.innerClasses.singleOrNull { it.name == node.name }?.outerName ?: node.outerClass
        if (enclosing != null) enclosingClasses[name] = enclosing.replace('/', '.')
        classes.add(name)
        inheritedEntries.add(node)
        if (node.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_PUBLIC && node.outerMethod == null) {
            entryClasses.add(name)
        }
        node.superName?.let(::referenceName)
        node.interfaces.forEach(::referenceName)
        node.fields.forEach { referenceType(Type.getType(it.desc)) }
        val declaration = listOf(
            node.version, node.access, node.signature, node.superName, node.interfaces, node.nestHostClass, node.nestMembers,
            node.outerClass, node.outerMethod, node.outerMethodDesc,
            // javac adds the external Lookup entry for invokedynamic bootstrap signatures.
            // It does not declare a member of this class; surviving call sites remain in the IR.
            node.innerClasses.filterNot {
                it.name == "java/lang/invoke/MethodHandles\$Lookup" && it.outerName == "java/lang/invoke/MethodHandles" &&
                    it.innerName == "Lookup" && it.access == (Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)
            }.map { listOf(it.name, it.outerName, it.innerName, it.access) },
            node.recordComponents?.map { listOf(it.name, it.descriptor, it.signature, it.visibleAnnotations, it.invisibleAnnotations) },
            node.permittedSubclasses, node.visibleAnnotations, node.invisibleAnnotations,
            node.visibleTypeAnnotations, node.invisibleTypeAnnotations,
            node.fields.map { field -> listOf(
                field.name, field.desc, field.access, field.signature, field.value,
                field.visibleAnnotations, field.invisibleAnnotations, field.visibleTypeAnnotations, field.invisibleTypeAnnotations,
            ) },
        )
        classShapes[name] = stable(DeclarationMetadata.classShape(declaration.dropLast(1), name, diagnostics))
        captureFields(node, name)
        if (declarations.put(name, stable(declaration)) != null) diagnostics.add("Duplicate class in input: $name")
        if (!node.attrs.isNullOrEmpty()) diagnostics.add("Unmodeled class attributes: $name")
        captureMethods(node, name)
    }

    private fun captureMethods(node: ClassNode, name: String) {
        val discoveredClass = DeclarationMetadata.discoveryAnnotations(node.visibleAnnotations, node.invisibleAnnotations) ||
            node.fields.any { DeclarationMetadata.discoveryAnnotations(it.visibleAnnotations, it.invisibleAnnotations) }
        for (method in node.methods) {
            val identity = "$name.${method.name}${method.desc}"
            methods.add(identity)
            inspectInstructions(method, identity)
            inspectExternalEntry(method, identity)
            val annotated = DeclarationMetadata.discoveryAnnotations(method.visibleAnnotations, method.invisibleAnnotations)
            val discoverable = method.name == "<init>" || method.access and Opcodes.ACC_PRIVATE == 0
            if (annotated || discoveredClass && discoverable) {
                externalEntries.add(identity)
            }
            if (method.access and Opcodes.ACC_PRIVATE != 0 && annotated) {
                diagnostics.add("Private annotated method may be a framework entry: $identity")
            }
            if (!method.attrs.isNullOrEmpty()) diagnostics.add("Unmodeled method attributes: $identity")
            declarations[identity] = stable(listOf(
                method.access, method.signature, method.exceptions, method.annotationDefault,
                method.visibleAnnotations, method.invisibleAnnotations,
                method.visibleTypeAnnotations, method.invisibleTypeAnnotations,
                method.visibleParameterAnnotations?.toList(), method.invisibleParameterAnnotations?.toList(),
            ))
        }
    }

    private fun captureFields(node: ClassNode, name: String) {
        node.fields.forEach { field ->
            val identity = "$name.${field.name}:${field.desc}"
            val metadata = listOf(field.desc, field.access, field.signature,
                field.visibleAnnotations, field.invisibleAnnotations, field.visibleTypeAnnotations, field.invisibleTypeAnnotations)
            val safe = field.access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_VOLATILE) == Opcodes.ACC_PRIVATE &&
                node.superName == "java/lang/Object" && node.interfaces.isEmpty() &&
                !DeclarationMetadata.discoveryAnnotations(field.visibleAnnotations, field.invisibleAnnotations) &&
                field.visibleTypeAnnotations.isNullOrEmpty() && field.invisibleTypeAnnotations.isNullOrEmpty()
            fields[identity] = FieldSnapshot(identity, stable(metadata), stable(field.value), safe)
        }
    }

    private fun inspectExternalEntry(method: MethodNode, identity: String) {
        if (method.access and Opcodes.ACC_NATIVE != 0) {
            diagnostics.add("Native/JNI method prevents proving private callback reachability: $identity")
        }
        val serializationCallbacks = setOf(
            "readObject(Ljava/io/ObjectInputStream;)V", "writeObject(Ljava/io/ObjectOutputStream;)V",
            "readObjectNoData()V", "readResolve()Ljava/lang/Object;", "writeReplace()Ljava/lang/Object;",
        )
        val privateInstance = method.access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC) == Opcodes.ACC_PRIVATE
        if (privateInstance && method.name + method.desc in serializationCallbacks) {
            diagnostics.add("Private serialization callback requires an external entry model: $identity")
        }
    }

    private fun inspectInstructions(method: MethodNode, identity: String) {
        referenceType(Type.getMethodType(method.desc))
        method.exceptions.forEach(::referenceName)
        method.instructions.forEach { instruction ->
            when (instruction) {
                is MethodInsnNode -> inspectCall(instruction, identity)
                is FieldInsnNode -> {
                    referenceName(instruction.owner)
                    referenceType(Type.getType(instruction.desc))
                    if (instruction.opcode in setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)) {
                        fieldReads.add("${instruction.owner.replace('/', '.')}.${instruction.name}:${instruction.desc}")
                    }
                }
                is TypeInsnNode -> referenceName(instruction.desc)
                is InvokeDynamicInsnNode -> {
                    referenceType(Type.getMethodType(instruction.desc))
                    inspectConstant(instruction.bsm)
                    instruction.bsmArgs.forEach(::inspectConstant)
                }
                is LdcInsnNode -> inspectConstant(instruction.cst)
                is MultiANewArrayInsnNode -> referenceType(Type.getType(instruction.desc))
            }
        }
    }

    private fun inspectConstant(value: Any?) {
        when (value) {
            is Type -> referenceType(value)
            is Handle -> {
                if (value.tag in setOf(Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC)) {
                    fieldReads.add("${value.owner.replace('/', '.')}.${value.name}:${value.desc}")
                }
                referenceName(value.owner)
                referenceType(Type.getType(value.desc))
            }
            is ConstantDynamic -> {
                referenceType(Type.getType(value.descriptor))
                inspectConstant(value.bootstrapMethod)
                repeat(value.bootstrapMethodArgumentCount) { inspectConstant(value.getBootstrapMethodArgument(it)) }
            }
        }
    }

    private fun inspectCall(instruction: MethodInsnNode, identity: String) {
        referenceName(instruction.owner)
        referenceType(Type.getMethodType(instruction.desc))
        if (dynamicAccess(instruction)) {
            val diagnostic = "Unmodeled reflective or dynamic access: $identity -> " +
                "${instruction.owner}.${instruction.name}${instruction.desc} (${instruction.opcode})"
            diagnostics.add(diagnostic)
            if (propertyTokenAdapter(instruction)) propertyTokenDiagnostics.add(diagnostic)
        }
    }

    private fun referenceName(name: String) {
        if (name.startsWith("[")) referenceType(Type.getType(name)) else references.add(name.replace('/', '.'))
    }

    private fun referenceType(type: Type) {
        when (type.sort) {
            Type.OBJECT -> references.add(type.className)
            Type.ARRAY -> referenceType(type.elementType)
            Type.METHOD -> { type.argumentTypes.forEach(::referenceType); referenceType(type.returnType) }
        }
    }

    private fun dynamicAccess(call: MethodInsnNode): Boolean =
        call.owner.startsWith("java/lang/reflect/") || call.owner.startsWith("java/lang/invoke/MethodHandle") ||
            call.owner.startsWith("kotlin/reflect/") || call.owner.startsWith("kotlin/jvm/internal/Reflection") ||
            (call.owner == "java/lang/Class" && call.name != "desiredAssertionStatus") ||
            call.owner.endsWith("ClassLoader")

    /** Only the stdlib-only adapter is an identity operation; kotlin-reflect can replace its factory. */
    private fun propertyTokenAdapter(call: MethodInsnNode): Boolean =
        call.opcode == Opcodes.INVOKESTATIC && !call.itf && call.owner == "kotlin/jvm/internal/Reflection" &&
            call.name == "property1" &&
            call.desc == "(Lkotlin/jvm/internal/PropertyReference1;)Lkotlin/reflect/KProperty1;"

    fun missingReferences(dependencies: List<Path>): Set<String> {
        val available = classes.toMutableSet()
        dependencies.forEach { available.addAll(dependencyClasses(it)) }
        // Keep the call, reference constructor, property name and signature in the IR. This exemption
        // permits constructing delegate metadata only, never invoking KProperty accessors or reflection.
        val factory = "kotlin.jvm.internal.ReflectionFactory"
        val applicationOverrides = classes.intersect(setOf(factory, "kotlin.jvm.internal.Reflection"))
        val standardFactory = factory in available && "kotlin.reflect.jvm.internal.ReflectionFactoryImpl" !in available
        diagnostics.removeAll(propertyTokenDiagnostics)
        diagnostics.addAll(propertyTokenDiagnostics)
        val candidate = propertyTokenDiagnostics.isNotEmpty() && standardFactory && applicationOverrides.isEmpty()
        if (candidate && DelegateMetadataRuntime.trusted(dependencies)) diagnostics.removeAll(propertyTokenDiagnostics)
        return (references - available).filterNot { name ->
            try { Class.forName(name, false, ClassLoader.getPlatformClassLoader()); true }
            catch (_: ClassNotFoundException) { false }
        }.toSet()
    }

    private fun dependencyClasses(path: Path): Set<String> {
        if (!Files.isDirectory(path)) return archiveClasses(path)
        return Files.walk(path).use { files ->
            files.filter { Files.isRegularFile(it) }.toList().flatMap { file ->
                when {
                    file.toString().endsWith(".class") -> listOf(ClassReader(Files.readAllBytes(file)).className.replace('/', '.'))
                    file.toString().endsWith(".jar") -> archiveClasses(file)
                    else -> emptyList()
                }
            }.toSet()
        }
    }

    private fun archiveClasses(path: Path): Set<String> = ZipFile(path.toFile()).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".class") }
            .map { it.name.removeSuffix(".class").replace('/', '.') }.toSet()
    }

    private fun stable(value: Any?): String = when (value) {
        null -> "null"
        is TypeAnnotationNode -> {
            referenceType(Type.getType(value.desc))
            "type-annotation(${value.typeRef},${value.typePath},${stable(value.desc)},${stable(value.values)})"
        }
        is AnnotationNode -> {
            referenceType(Type.getType(value.desc))
            "annotation(${stable(value.desc)},${stable(value.values)})"
        }
        is List<*> -> value.joinToString(",", "[", "]") { stable(it) }
        is Array<*> -> stable(value.toList())
        else -> "${value.javaClass.name}:${value.toString().length}:${value}"
    }

}
