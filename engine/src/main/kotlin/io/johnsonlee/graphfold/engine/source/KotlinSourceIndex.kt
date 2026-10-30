package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
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
 * File-wide fallback owners retain constructs such as constructors and compiler-generated declarations.
 * Exact diff fragments, rather than these enclosing ranges, remain the reported finding locations.
 */
class KotlinSourceIndex(classes: Path) : AutoCloseable {
    private val disposable = Disposer.newDisposable("graphfold-kotlin-source")
    private val environment = KotlinCoreEnvironment.createForProduction(
        disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    private val factory = KtPsiFactory(environment.project, false)
    private val inventory: List<Owner> = readOwners(classes)

    override fun close() {
        Disposer.dispose(disposable)
    }

    fun index(root: Path, relativePath: String, revision: Revision): IndexedSource {
        val source = root.resolve(relativePath)
        val text = Files.readString(source)
        val file = factory.createFile(source.fileName.toString(), physicalKotlinPsiText(text))
        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        if (errors.isNotEmpty()) {
            return IndexedSource(relativePath, text, emptyList(), errors.map { it.errorDescription })
        }
        val packageName = file.packageFqName.asString()
        val owners = inventory.filter { it.source == source.fileName.toString() && it.packageName == packageName }
        val diagnostics = mutableListOf<String>().apply {
            if (owners.isEmpty()) add("No emitted JVM owner for Kotlin source $relativePath")
            if (inventory.any { it.kotlin && it.source == null }) {
                add("Kotlin bytecode without SourceFile prevents complete source ownership for $relativePath")
            }
        }
        fun location(element: PsiElement): SourceLocation = location(text, element, relativePath, revision)
        val symbols = owners.map { SourceSymbol(it.name, it.name, SymbolKind.CLASS, location(file)) }.toMutableList()
        symbols += memberSymbols(file, owners, ::location)
        return IndexedSource(relativePath, text, symbols, diagnostics)
    }

    private fun memberSymbols(file: KtFile, owners: List<Owner>, location: (PsiElement) -> SourceLocation): List<SourceSymbol> {
        val result = mutableListOf<SourceSymbol>()
        PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).filterNot(::isLocal).forEach { function ->
            val position = location(function)
            val matches = owners.flatMap { owner ->
                owner.methods.filter { method ->
                    method.name.substringBefore('-') == function.name &&
                        method.lines.any { it in position.line.start..position.line.end }
                }.map { owner to it }
            }
            if (matches.size == 1) {
                val (owner, method) = matches.single()
                result += SourceSymbol("${owner.name}.${method.name}${method.descriptor}", owner.name, SymbolKind.METHOD, position)
            }
        }
        PsiTreeUtil.collectElementsOfType(file, KtProperty::class.java).filterNot(::isLocal).forEach { property ->
            val declaringClass = PsiTreeUtil.getParentOfType(property, KtClassOrObject::class.java)
            val matches = owners.filter { owner ->
                property.name in owner.fields && (declaringClass == null || owner.name.substringAfterLast('$')
                    .substringAfterLast('.') == declaringClass.name)
            }
            if (matches.size == 1) {
                val owner = matches.single().name
                result += SourceSymbol("$owner.${property.name}", owner, SymbolKind.FIELD, location(property))
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
        val name: String, val packageName: String, val source: String?, val kotlin: Boolean,
        val fields: Set<String>, val methods: List<Method>,
    )
}
