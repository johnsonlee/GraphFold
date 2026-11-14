package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import io.johnsonlee.graphfold.engine.origin.readOriginMetadata
import kotlin.metadata.jvm.KotlinClassMetadata
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.objectweb.asm.tree.AnnotationNode
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

/**
 * PSI supplies source syntax and UTF16 ranges; emitted class files supply actual JVM ownership.
 *
 * Callable declarations resolve against emitted method names and line tables, never guessed descriptors.
 * Properties map to emitted fields. Generated or ambiguous declarations retain CLASS obligations.
 * Inline functions and properties retain a marker for comparing copied code in all reachable callers.
 * Fieldless inline accessors conservatively retain an inline CLASS obligation.
 * Complete named metadata families use their top-level declaration ranges; other owners retain file-wide fallbacks.
 * Uniquely observed object or companion initialization may bind to its emitted static initializer.
 * Exact diff fragments, rather than these enclosing ranges, remain the reported finding locations.
 * Close the index to dispose its headless Kotlin PSI environment.
 *
 * @param classes Directory of application class files emitted for the same revision as the indexed sources.
 * @param sourceOutputs Source-root to original class-output mapping; when supplied, limits owners to their build module.
 */
class KotlinSourceIndex(classes: List<Path>, private val sourceOutputs: Map<Path, List<Path>> = emptyMap()) : AutoCloseable {
    constructor(classes: Path, sourceOutputs: Map<Path, List<Path>> = emptyMap()) : this(listOf(classes), sourceOutputs)

    private val disposable = Disposer.newDisposable("graphfold-kotlin-source")
    private val environment = KotlinCoreEnvironment.createForProduction(
        disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    private val factory = KtPsiFactory(environment.project, false)
    private val inventory: List<Owner> = classes.distinct().flatMap(::readOwners)

    /** Disposes the Kotlin PSI environment; this index must not be used afterward. */
    override fun close() {
        Disposer.dispose(disposable)
    }

    /**
     * Reads a Kotlin source file and binds its PSI declarations to available emitted JVM owners.
     * Ambiguous member matches retain class-level obligations; missing owners and parse errors produce diagnostics.
     *
     * @param root Checkout directory containing the source file.
     * @param relativePath Source path relative to [root].
     * @param revision Revision label attached to every returned source location.
     * @return Original text, resolved symbols or conservative owner obligations, and analysis gaps.
     */
    fun index(root: Path, relativePath: String, revision: Revision): IndexedSource {
        val source = root.resolve(relativePath)
        val text = Files.readString(source)
        val file = factory.createFile(source.fileName.toString(), physicalKotlinPsiText(text))
        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        if (errors.isNotEmpty()) {
            return IndexedSource(relativePath, text, emptyList(), errors.map { it.errorDescription })
        }
        val packageName = file.packageFqName.asString()
        val outputs = sourceOutputs.filterKeys { source.toAbsolutePath().normalize().startsWith(it.toAbsolutePath().normalize()) }
            .values.flatten()
        // SourceFile contains only a basename. Keep module ownership from the build export before
        // matching that name, otherwise another module's same-package file contaminates obligations.
        val scoped = if (sourceOutputs.isEmpty()) inventory else inventory.filter { owner ->
            outputs.any { Files.isRegularFile(it.resolve(owner.name.replace('.', '/') + ".class")) }
        }
        val owners = scoped.filter { it.source == source.fileName.toString() && it.packageName == packageName }
        val diagnostics = mutableListOf<String>().apply {
            if (owners.isEmpty()) add("No emitted JVM owner for Kotlin source $relativePath")
            if (scoped.any { it.kotlin && it.source == null }) {
                add("Kotlin bytecode without SourceFile prevents complete source ownership for $relativePath")
            }
        }
        fun location(element: PsiElement): SourceLocation = location(text, element, relativePath, revision)
        val scopes = declarationScopes(file, owners)
        val symbols = owners.map { SourceSymbol(it.name, it.name, SymbolKind.CLASS, location(scopes[it.name] ?: file)) }.toMutableList()
        symbols += memberSymbols(file, owners, ::location)
        symbols += initializerSymbols(file, owners, ::location)
        return IndexedSource(relativePath, text, symbols, diagnostics)
    }

    /** Keep an entire named top-level family together: companion members can lower into its outer owner. */
    private fun declarationScopes(file: KtFile, owners: List<Owner>): Map<String, KtClassOrObject> {
        val declarations = PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java).filterNot(::isLocal)
            .groupBy { it.fqName?.asString() }
        val emitted = owners.groupBy { it.sourceName }
        val scopes = owners.mapNotNull { owner ->
            val name = owner.sourceName ?: return@mapNotNull null
            val declaration = declarations[name]?.singleOrNull() ?: return@mapNotNull null
            if (emitted.getValue(name).size != 1) return@mapNotNull null
            val topLevel = generateSequence(declaration) { PsiTreeUtil.getParentOfType(it, KtClassOrObject::class.java) }.last()
            owner.name to topLevel
        }.toMap()
        // A narrower known class would otherwise make ProofPlanner discard a broader unknown owner.
        return if (scopes.size == owners.size) scopes else emptyMap()
    }

    /** Only uniquely observed static initialization is promoted from the conservative class fallback. */
    private fun initializerSymbols(file: KtFile, owners: List<Owner>, location: (PsiElement) -> SourceLocation): List<SourceSymbol> {
        val initializers = PsiTreeUtil.collectElementsOfType(file, KtAnonymousInitializer::class.java).toList()
        return initializers.filterNot(::isLocal).mapNotNull { initializer ->
            val declaration = PsiTreeUtil.getParentOfType(initializer, KtClassOrObject::class.java) as? KtObjectDeclaration
            if (declaration == null || declaration.isObjectLiteral()) return@mapNotNull null
            val position = location(initializer)
            val range = position.line.start..position.line.end
            val fqName = declaration.fqName ?: return@mapNotNull null
            val sourceName = fqName.asString()
            val allowedNames = mutableSetOf(sourceName)
            if (declaration.isCompanion()) {
                val enclosing = PsiTreeUtil.getParentOfType(declaration, KtClassOrObject::class.java) ?: return@mapNotNull null
                // A non-local declaration with an FQ name has a named containing declaration.
                allowedNames += java.util.Objects.requireNonNull<org.jetbrains.kotlin.name.FqName>(enclosing.fqName).asString()
            }
            val candidates = owners.filter { it.sourceName in allowedNames }
            if (candidates.groupBy { it.sourceName }.values.any { it.size != 1 } ||
                ambiguousInitializer(initializer, file, range, location)) return@mapNotNull null
            val candidate = candidates.flatMap { owner -> owner.methods.filter { method ->
                method.name == "<clinit>" && method.lines.any { it in range }
            }.map { owner to it } }.singleOrNull() ?: return@mapNotNull null
            val (owner, method) = candidate
            SourceSymbol("${owner.name}.${method.name}${method.descriptor}", owner.name, SymbolKind.METHOD, position)
        }
    }

    private fun ambiguousInitializer(
        initializer: KtAnonymousInitializer, file: KtFile, range: IntRange, location: (PsiElement) -> SourceLocation,
    ): Boolean {
        val initializers = PsiTreeUtil.collectElementsOfType(file, KtAnonymousInitializer::class.java)
        val properties = PsiTreeUtil.collectElementsOfType(file, KtProperty::class.java).filter {
            it.initializer != null || it.delegateExpression != null || it.accessors.any { accessor -> accessor.bodyExpression != null }
        }
        val functions = PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java)
        return (initializers + properties + functions).any { other ->
            other !== initializer && !PsiTreeUtil.isAncestor(initializer, other, true) &&
                location(other).line.let { it.start <= range.last && it.end >= range.first }
        }
    }

    private fun memberSymbols(file: KtFile, owners: List<Owner>, location: (PsiElement) -> SourceLocation): List<SourceSymbol> {
        val result = mutableListOf<SourceSymbol>()
        PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).filterNot(::isLocal).forEach { function ->
            val position = location(function)
            val inlined = function.hasModifier(KtTokens.INLINE_KEYWORD)
            val matches = owners.flatMap { owner ->
                owner.methods.filter { method ->
                    method.name.substringBefore('-') == function.name &&
                        method.lines.any { it in position.line.start..position.line.end }
                }.map { owner to it }
            }
            if (matches.size == 1) {
                val (owner, method) = matches.single()
                result += SourceSymbol("${owner.name}.${method.name}${method.descriptor}", owner.name, SymbolKind.METHOD, position,
                    inlined = inlined)
            } else if (inlined) {
                // Renamed or ambiguous inline declarations still require checking copies in every reachable owner.
                result += owners.map { SourceSymbol(it.name, it.name, SymbolKind.CLASS, position, inlined = true) }
            }
        }
        PsiTreeUtil.collectElementsOfType(file, KtProperty::class.java).filterNot(::isLocal).forEach { property ->
            val inlined = property.hasModifier(KtTokens.INLINE_KEYWORD) ||
                property.accessors.any { it.hasModifier(KtTokens.INLINE_KEYWORD) }
            if (inlined) {
                // Inline accessors have no backing field; avoid inventing getter/setter JVM names.
                result += owners.map { SourceSymbol(it.name, it.name, SymbolKind.CLASS, location(property), inlined = true) }
                return@forEach
            }
            val declaringClass = PsiTreeUtil.getParentOfType(property, KtClassOrObject::class.java)
            val matches = owners.filter { owner ->
                property.name in owner.fields && (declaringClass == null || owner.name.substringAfterLast('$')
                    .substringAfterLast('.') == declaringClass.name)
            }
            if (matches.size == 1) {
                val owner = matches.single().name
                result += SourceSymbol("$owner.${property.nameAsSafeName.asString()}", owner, SymbolKind.FIELD, location(property))
            }
        }
        return result
    }

    private fun readOwners(classes: Path): List<Owner> = Files.walk(classes).use { files ->
        files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.map { path ->
            val node = ClassNode()
            ClassReader(Files.readAllBytes(path)).accept(node, ClassReader.SKIP_FRAMES)
            val annotations = node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty()
            val name = node.name.replace('/', '.')
            Owner(name, name.substringBeforeLast('.', ""), node.sourceFile, annotations.any { it.desc == "Lkotlin/Metadata;" },
                sourceClassName(annotations),
                node.fields.map { it.name }.toSet(), node.methods.map { method ->
                    Method(method.name, method.desc, method.instructions.filterIsInstance<LineNumberNode>().map { it.line }.toSet())
                })
        }.toList()
    }

    private fun isLocal(element: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java) != null

    private fun location(text: String, element: PsiElement, path: String, revision: Revision): SourceLocation {
        val start = element.textRange.startOffset
        val end = element.textRange.endOffset
        return SourceLocation(revision, path,
            SourceRange(text.take(start).count { it == '\n' } + 1, text.take(end).count { it == '\n' } + 1),
            SourceRange(start - text.lastIndexOf('\n', start - 1), end - text.lastIndexOf('\n', end - 1)))
    }

    private data class Method(val name: String, val descriptor: String, val lines: Set<Int>)
    private data class Owner(
        val name: String, val packageName: String, val source: String?, val kotlin: Boolean, val sourceName: String?,
        val fields: Set<String>, val methods: List<Method>,
    )
}

/** Metadata supplies source nesting independently of JVM binary-name spelling. Unknown schemas keep file-wide scope. */
private fun sourceClassName(annotations: List<AnnotationNode>): String? {
    val annotation = annotations.singleOrNull { it.desc == "Lkotlin/Metadata;" } ?: return null
    return try {
        val metadata = KotlinClassMetadata.readStrict(readOriginMetadata(annotation))
        if (metadata is KotlinClassMetadata.Class) metadata.kmClass.name.replace('/', '.') else null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}
