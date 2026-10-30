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

enum class SymbolKind { CLASS, FIELD, METHOD }

data class SourceSymbol(
    val identity: String, val owner: String, val kind: SymbolKind, val location: SourceLocation,
    val compilerOrigin: Boolean = false,
)

data class IndexedSource(val path: String, val text: String, val symbols: List<SourceSymbol>, val diagnostics: List<String>)

/** Java-only source adapter. Unsupported languages remain unresolved obligations in the engine. */
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

    override fun close() {
        dependencyLoader.close()
    }

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
