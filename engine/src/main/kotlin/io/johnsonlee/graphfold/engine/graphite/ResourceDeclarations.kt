package io.johnsonlee.graphfold.engine.graphite

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/** Independent declaration inventory for dependency effects; libraries never become application entry points. */
internal class ResourceDeclarations(paths: List<Path>) {
    val classes = linkedSetOf<String>()
    val methods = linkedSetOf<String>()
    val diagnostics = mutableListOf<String>()
    /** Exact engine-runtime byte identity binds the narrow Intrinsics contract to its inspected implementation. */
    var trustedIntrinsics: Boolean = false
        private set
    private val types = linkedMapOf<String, ResourceType>()

    init {
        paths.distinct().forEach(::read)
    }

    fun initialization(): Map<String, Set<String>> = types.mapValues { (_, type) ->
        val result = linkedSetOf<String>()
        if (!type.isInterface) collectClass(type, result, mutableSetOf())
        result
    }

    /** Resolves the actual field declaration; incomplete or ambiguous lookup cannot prove its initialization owner. */
    fun fieldOwner(identity: String): String? {
        val owner = identity.substringBefore(':').substringBeforeLast('.')
        val field = identity.substringBefore(':').substringAfterLast('.') + ":" + identity.substringAfter(':')
        val lookup = lookupField(owner, field, emptySet())
        return if (lookup.complete) lookup.owners.singleOrNull() else null
    }

    /** Callable inherited APIs, scoped to this application type; null means an ancestor could not be inspected. */
    fun inheritedMethods(owner: String): Set<String>? = inheritedMethods(owner, owner, emptySet(), emptySet())
        ?.filterNot { it.substringBefore('(').substringBeforeLast('.') == owner }?.toSet()

    private fun inheritedMethods(
        owner: String, applicationOwner: String, masked: Set<String>, visited: Set<String>,
    ): Set<String>? {
        val type = types[owner]
        return when {
            owner == "java.lang.Object" -> emptySet()
            type == null || owner in visited -> null
            else -> inheritedMethods(type, owner, applicationOwner, masked, visited)
        }
    }

    private fun inheritedMethods(
        type: ResourceType, owner: String, applicationOwner: String, masked: Set<String>, visited: Set<String>,
    ): Set<String>? {
        val visible = type.methods.filter { (signature, access) ->
            inheritedVisibility(signature, access, type.isInterface, owner, applicationOwner)
        }.keys
        val parent = type.parent?.let { inheritedMethods(it, applicationOwner, masked + visible, visited + owner) }
        val classMethods = if (type.isInterface) emptySet() else parent.orEmpty().map(::methodSignature).toSet()
        val interfaces = type.interfaces.map {
            inheritedMethods(it, applicationOwner, masked + visible + classMethods, visited + owner)
        }
        val interfaceMethods = interfaces.filterNotNull().flatten().toSet()
        val ambiguous = interfaceMethods.groupBy(::methodSignature).values.any { it.size > 1 }
        val complete = (type.parent == null || parent != null) && interfaces.none { it == null } && !ambiguous
        return if (!complete) null else {
            visible.filterNot { it in masked }.map { "$owner.$it" }.toSet() + parent.orEmpty() + interfaceMethods
        }
    }

    private fun methodSignature(identity: String): String = identity.substringBefore('(').substringAfterLast('.') +
        "(" + identity.substringAfter('(')

    private fun inheritedVisibility(
        signature: String, access: Int, isInterface: Boolean, owner: String, applicationOwner: String,
    ): Boolean {
        val callable = access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 ||
            owner.substringBeforeLast('.', "") == applicationOwner.substringBeforeLast('.', "")
        val excluded = signature.startsWith('<') || access and Opcodes.ACC_PRIVATE != 0 ||
            isInterface && access and Opcodes.ACC_STATIC != 0
        return callable && !excluded
    }

    /** Declaring owners initialized by externally visible static fields, including fields inherited through interfaces. */
    fun entryInitializers(owner: String): Set<String>? = entryInitializers(owner, owner, emptySet(), emptySet())

    private fun entryInitializers(
        owner: String, applicationOwner: String, masked: Set<String>, visited: Set<String>,
    ): Set<String>? {
        val type = types[owner]
        return when {
            owner == "java.lang.Object" -> emptySet()
            type == null || owner in visited -> null
            else -> {
                val visible = type.fields.filter { (signature, access) ->
                    access and Opcodes.ACC_STATIC != 0 && inheritedVisibility(signature, access, false, owner, applicationOwner)
                }.keys
                val ancestors = (type.interfaces + listOfNotNull(type.parent)).map {
                    entryInitializers(it, applicationOwner, masked + visible, visited + owner)
                }
                if (ancestors.any { it == null }) null else {
                    val own = if (visible.any { it !in masked }) setOf(owner) else emptySet()
                    own + ancestors.filterNotNull().flatten()
                }
            }
        }
    }

    private fun lookupField(owner: String, field: String, visited: Set<String>): FieldLookup {
        val type = types[owner]
        return when {
            owner in visited -> FieldLookup(emptySet(), false)
            owner == "java.lang.Object" && type == null -> FieldLookup(emptySet(), true)
            type == null -> FieldLookup(emptySet(), false)
            field in type.fields -> FieldLookup(setOf(owner), true)
            else -> inheritedField(type, field, visited + owner)
        }
    }

    private fun inheritedField(type: ResourceType, field: String, visited: Set<String>): FieldLookup {
        val interfaces = type.interfaces.map { lookupField(it, field, visited) }
        val owners = interfaces.flatMap { it.owners }.toSet()
        return when {
            interfaces.any { !it.complete } -> FieldLookup(owners, false)
            owners.isNotEmpty() -> FieldLookup(owners, true)
            type.parent != null -> lookupField(type.parent, field, visited)
            else -> FieldLookup(emptySet(), true)
        }
    }

    private fun collectClass(type: ResourceType, result: MutableSet<String>, visited: MutableSet<String>) {
        type.parent?.let { parent ->
            if (visited.add(parent)) {
                result.add(parent)
                types[parent]?.let { collectClass(it, result, visited) }
            }
        }
        type.interfaces.forEach { collectInterface(it, result, visited) }
    }

    private fun collectInterface(owner: String, result: MutableSet<String>, visited: MutableSet<String>) {
        if (!visited.add(owner)) return
        val type = types[owner]
        if (type == null) {
            result.add(owner)
        } else {
            type.interfaces.forEach { collectInterface(it, result, visited) }
            if (type.hasDefault) result.add(owner)
        }
    }

    private fun read(path: Path) {
        if (Files.isDirectory(path)) {
            Files.walk(path).use { files ->
                files.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    val name = path.relativize(file).joinToString("/")
                    entry(name) { Files.readAllBytes(file) }
                }
            }
        } else {
            ZipFile(path.toFile()).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { item ->
                    entry(item.name) { zip.getInputStream(item).use { it.readBytes() } }
                }
            }
        }
    }

    private fun entry(name: String, bytes: () -> ByteArray) {
        when {
            name == "module-info.class" || name.endsWith("/module-info.class") -> Unit
            name.endsWith(".class") -> {
                val content = bytes()
                val node = ClassNode()
                ClassReader(content).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                if (name != "${node.name}.class") diagnostics.add("Unsupported resource dependency class location: $name")
                val owner = node.name.replace('/', '.')
                val unique = classes.add(owner)
                if (!unique) diagnostics.add("Duplicate resource dependency class: $owner")
                if (owner == "kotlin.jvm.internal.Intrinsics") {
                    trustedIntrinsics = unique && content.contentEquals(INTRINSICS)
                }
                node.methods.forEach { methods.add("$owner.${it.name}${it.desc}") }
                types[owner] = ResourceType(node.superName?.replace('/', '.'), node.interfaces.map { it.replace('/', '.') },
                    node.access and Opcodes.ACC_INTERFACE != 0, node.methods.any {
                        // JVMS 5.5 includes private instance interface methods as initialization triggers.
                        it.name != "<clinit>" && it.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_STATIC) == 0
                    }, node.fields.associate { "${it.name}:${it.desc}" to it.access },
                    node.methods.associate { "${it.name}${it.desc}" to it.access })
            }
            name.endsWith(".jar") -> diagnostics.add("Nested resource dependency archive: $name")
        }
    }

    private companion object {
        val INTRINSICS: ByteArray = EngineRuntimeClasses.requireBytes("kotlin/jvm/internal/Intrinsics")
    }
}

private data class ResourceType(
    val parent: String?, val interfaces: List<String>, val isInterface: Boolean, val hasDefault: Boolean, val fields: Map<String, Int>,
    val methods: Map<String, Int>,
)

private data class FieldLookup(val owners: Set<String>, val complete: Boolean)
