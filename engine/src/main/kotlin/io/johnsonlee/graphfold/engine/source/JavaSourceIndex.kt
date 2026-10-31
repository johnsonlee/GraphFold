package io.johnsonlee.graphfold.engine.source

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import com.github.javaparser.resolution.UnsolvedSymbolException
import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import java.nio.file.Path
import java.nio.file.Files
import java.net.URLClassLoader

/** JVM declaration category used to connect source edits to graph and bytecode evidence. */
enum class SymbolKind {
    /** A class, interface, enum, record, or emitted Kotlin owner. */
    CLASS,
    /** A field declaration or Kotlin property mapped to an emitted field. */
    FIELD,
    /** A method or constructor with a JVM method descriptor. */
    METHOD,
}

/**
 * A source declaration mapped to a JVM owner and, where resolvable, a member.
 *
 * @property identity Dotted class name, owner-qualified field name, or owner-qualified method name and JVM descriptor.
 * @property owner Dotted JVM binary name of the owning class.
 * @property kind Declaration category controlling the subsequent proof obligation.
 * @property location Enclosing declaration range in its revision; findings are narrowed to actual diff fragments later.
 * @property compilerOrigin Whether this symbol's anonymous owner was bound using validated compiler provenance.
 */
data class SourceSymbol(
    val identity: String, val owner: String, val kind: SymbolKind, val location: SourceLocation,
    val compilerOrigin: Boolean = false,
)

/**
 * Source text and resolvable declarations for a single repository-relative path.
 *
 * @property path Path relative to the analyzed checkout.
 * @property text Original source text used for exact diff and location calculations.
 * @property symbols Declarations successfully associated with JVM identities or conservative class-level obligations.
 * @property diagnostics Parse or resolution gaps; an empty symbol list alone does not establish that the source is harmless.
 */
data class IndexedSource(val path: String, val text: String, val symbols: List<SourceSymbol>, val diagnostics: List<String>)

/**
 * Indexes Java declarations with JavaParser's Java 17 grammar and symbol solver.
 * Resolution gaps remain diagnostics rather than guessed JVM names. Close the index to release its dependency loader.
 *
 * @param sourceRoots Production Java source roots used for declaration resolution.
 * @param dependencies Resolved dependency JARs and class directories used for external type resolution.
 */
class JavaSourceIndex(sourceRoots: List<Path>, dependencies: List<Path>) : AutoCloseable {
    private val dependencyLoader = URLClassLoader(
        dependencies.filter(Files::isDirectory).map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader(),
    )
    private val parser: JavaParser

    init {
        val solver = CombinedTypeSolver()
        solver.add(ReflectionTypeSolver())
        sourceRoots.forEach { solver.add(JavaParserTypeSolver(it)) }
        dependencies.filterNot(Files::isDirectory).forEach { solver.add(JarTypeSolver(it)) }
        solver.add(ClassLoaderTypeSolver(dependencyLoader))
        parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setSymbolResolver(JavaSymbolSolver(solver)),
        )
    }

    /** Releases the dependency class loader and its open resources. */
    override fun close() {
        dependencyLoader.close()
    }

    /**
     * Reads a Java source file and indexes its resolvable classes, fields, methods, and constructors.
     * Parse errors and unsupported or unresolved declarations are returned as diagnostics.
     *
     * @param root Checkout directory containing the source file.
     * @param relativePath Source path relative to [root].
     * @param revision Revision label attached to every returned source location.
     * @return Original text, resolved symbols, and analysis gaps for the file.
     */
    fun index(root: Path, relativePath: String, revision: Revision): IndexedSource {
        val file = root.resolve(relativePath)
        val text = java.nio.file.Files.readString(file)
        val result = parser.parse(file)
        if (!result.isSuccessful) {
            return IndexedSource(relativePath, text, emptyList(), result.problems.map { it.message })
        }
        val unit = result.result.orElseThrow()
        val symbols = mutableListOf<SourceSymbol>()
        val diagnostics = mutableListOf<String>()
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        unit.findAll(TypeDeclaration::class.java).forEach { declaration ->
            symbols += resolveType(declaration, packageName, relativePath, revision, diagnostics)
        }
        return IndexedSource(relativePath, text, symbols, diagnostics)
    }

    private fun resolveType(
        declaration: TypeDeclaration<*>, packageName: String, path: String,
        revision: Revision, diagnostics: MutableList<String>,
    ): List<SourceSymbol> {
        if (!declaration.isTopLevelType && declaration.parentNode.orElse(null) !is TypeDeclaration<*>) {
            diagnostics += "Local type requires compiler-generated binary identity in $path: ${declaration.nameAsString}"
            return emptyList()
        }
        val owner = binaryName(declaration, packageName)
        val declarationSymbol = SourceSymbol(owner, owner, SymbolKind.CLASS, location(declaration, path, revision))
        return listOf(declarationSymbol) + declaration.members.flatMap { member ->
            resolveMember(member, owner, path, revision, diagnostics)
        }
    }

    private fun resolveMember(
        member: BodyDeclaration<*>, owner: String, path: String, revision: Revision, diagnostics: MutableList<String>,
    ): List<SourceSymbol> = try {
        memberSymbols(member, owner, path, revision)
    } catch (failure: UnsolvedSymbolException) {
        diagnostics += "Cannot resolve declaration in $path: ${failure.message}"
        emptyList()
    } catch (failure: UnsupportedOperationException) {
        diagnostics += "Unsupported declaration in $path: ${failure.message}"
        emptyList()
    }

    private fun memberSymbols(member: BodyDeclaration<*>, owner: String, path: String, revision: Revision): List<SourceSymbol> {
        val position = location(member, path, revision)
        return when (member) {
            is MethodDeclaration -> listOf(SourceSymbol(
                "$owner.${member.nameAsString}${member.resolve().toDescriptor()}", owner, SymbolKind.METHOD, position,
            ))
            is ConstructorDeclaration -> {
                val resolved = member.resolve()
                val descriptor = (0 until resolved.numberOfParams).joinToString("", "(" + constructorPrefix(member), ")V") {
                    resolved.getParam(it).type.erasure().toDescriptor()
                }
                listOf(SourceSymbol("$owner.<init>$descriptor", owner, SymbolKind.METHOD, position))
            }
            is FieldDeclaration -> member.variables.map { variable ->
                SourceSymbol("$owner.${variable.nameAsString}", owner, SymbolKind.FIELD, position)
            }
            else -> emptyList()
        }
    }

    private fun constructorPrefix(constructor: ConstructorDeclaration): String {
        val type = constructor.findAncestor(TypeDeclaration::class.java).orElseThrow()
        if (type is EnumDeclaration) return "Ljava/lang/String;I"
        val enclosing = type.findAncestor(TypeDeclaration::class.java).orElse(null)
        val implicitlyStatic = type is RecordDeclaration ||
            (enclosing is ClassOrInterfaceDeclaration && enclosing.isInterface)
        return if (enclosing != null && !type.isStatic && !implicitlyStatic) {
            val packageName = type.findCompilationUnit().orElseThrow().packageDeclaration.map { it.nameAsString }.orElse("")
            "L" + binaryName(enclosing, packageName).replace('.', '/') + ";"
        } else ""
    }

    private fun binaryName(type: TypeDeclaration<*>, packageName: String): String {
        val parent = type.findAncestor(TypeDeclaration::class.java)
        return if (parent.isPresent) binaryName(parent.get(), packageName) + "$" + type.nameAsString
        else listOf(packageName, type.nameAsString).filter { it.isNotEmpty() }.joinToString(".")
    }

    private fun location(node: Node, path: String, revision: Revision): SourceLocation {
        val range = node.range.orElseThrow()
        return SourceLocation(
            revision, path, SourceRange(range.begin.line, range.end.line),
            SourceRange(range.begin.column, range.end.column + 1),
        )
    }
}
