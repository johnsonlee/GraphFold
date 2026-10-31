package io.johnsonlee.graphfold.engine.source

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.SwitchExpr
import com.github.javaparser.ast.stmt.SwitchStmt
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.WhileStmt
import com.github.javaparser.ast.stmt.DoStmt
import com.github.javaparser.ast.stmt.ForStmt
import io.johnsonlee.graphfold.engine.ChangeKind
import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtWhenCondition
import org.jetbrains.kotlin.psi.KtWhileExpressionBase
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.lexer.KtTokens

/** Descriptive AST change labels only: these never establish behavioral equivalence or a verdict. */
internal object ChangeClassifier {
    fun classify(before: IndexedSource?, after: IndexedSource?, locations: List<SourceLocation>): Set<ChangeKind> {
        val old = before?.let { AstClassifier().facts(it) }.orEmpty()
        val new = after?.let { AstClassifier().facts(it) }.orEmpty()
        val oldChanged = changedFacts(old, before, Revision.BASE, locations)
        val newChanged = changedFacts(new, after, Revision.HEAD, locations)
        if ((oldChanged + newChanged).isEmpty()) {
            return fallback(before, after, locations)
        }
        val result = linkedSetOf<ChangeKind>()
        val affected = (oldChanged + newChanged).map { it.key }.toSet()
        val removed = old.filter { entry -> new.none { it.key == entry.key } }.toMutableList()
        val added = new.filter { entry -> old.none { it.key == entry.key } }.toMutableList()
        renames(removed, added, affected, result)
        removed.filter { it.key in affected }.forEach { result += removal(it.kind) }
        added.filter { it.key in affected }.forEach { result += addition(it.kind) }
        affected.forEach { key ->
            val previous = old.singleOrNull { it.key == key }
            val current = new.singleOrNull { it.key == key }
            if (previous != null && current != null && previous.content != current.content) {
                result += modification(previous, current)
            }
        }
        return result.ifEmpty { fallback(before, after, locations) }
    }

    private fun changedFacts(
        facts: List<Fact>, source: IndexedSource?, revision: Revision, locations: List<SourceLocation>,
    ): List<Fact> = source?.let { indexed ->
        facts.filter { fact ->
            locations.any { it.revision == revision && it.path == indexed.path && intersects(indexed.text, fact, it) }
        }
    }.orEmpty()

    private fun renames(
        removed: MutableList<Fact>, added: MutableList<Fact>, affected: Set<String>, result: MutableSet<ChangeKind>,
    ) {
        removed.toList().filter { it.kind == Kind.FIELD || it.kind == Kind.METHOD }.forEach { previous ->
            val candidates = added.filter { it.kind == previous.kind && it.shape == previous.shape &&
                it.key.substringBeforeLast(':') == previous.key.substringBeforeLast(':') }
            if (candidates.size != 1 || removed.count { it.kind == previous.kind && it.shape == previous.shape } != 1) return@forEach
            removed.remove(previous)
            added.remove(candidates.single())
            if (previous.key in affected || candidates.single().key in affected) {
                result += if (previous.kind == Kind.FIELD) ChangeKind.FIELD_RENAMED else ChangeKind.METHOD_RENAMED
            }
        }
    }

    private fun modification(old: Fact, new: Fact): ChangeKind = when (old.kind) {
        Kind.FIELD -> if (old.initializer != new.initializer) ChangeKind.FIELD_INITIALIZER_CHANGED else ChangeKind.DECLARATION_CHANGED
        Kind.CONDITION -> when {
            new.terms > old.terms -> ChangeKind.CONDITION_ADDED
            new.terms < old.terms -> ChangeKind.CONDITION_REMOVED
            else -> ChangeKind.CONDITION_CHANGED
        }
        Kind.CALL -> ChangeKind.CALL_CHANGED
        Kind.METHOD, Kind.TYPE -> ChangeKind.DECLARATION_CHANGED
        Kind.INITIALIZER -> ChangeKind.CLASS_INITIALIZATION_CHANGED
    }

    private fun addition(kind: Kind): ChangeKind = when (kind) {
        Kind.FIELD -> ChangeKind.FIELD_ADDED
        Kind.METHOD -> ChangeKind.METHOD_ADDED
        Kind.TYPE -> ChangeKind.TYPE_ADDED
        Kind.CONDITION -> ChangeKind.CONDITION_ADDED
        Kind.CALL -> ChangeKind.CALL_CHANGED
        Kind.INITIALIZER -> ChangeKind.CLASS_INITIALIZATION_CHANGED
    }

    private fun removal(kind: Kind): ChangeKind = when (kind) {
        Kind.FIELD -> ChangeKind.FIELD_REMOVED
        Kind.METHOD -> ChangeKind.METHOD_REMOVED
        Kind.TYPE -> ChangeKind.TYPE_REMOVED
        Kind.CONDITION -> ChangeKind.CONDITION_REMOVED
        Kind.CALL -> ChangeKind.CALL_CHANGED
        Kind.INITIALIZER -> ChangeKind.CLASS_INITIALIZATION_CHANGED
    }

    private fun fallback(before: IndexedSource?, after: IndexedSource?, locations: List<SourceLocation>): Set<ChangeKind> {
        if (locations.none { it.path == before?.path || it.path == after?.path }) return emptySet()
        val old = before?.let { AstClassifier().canonical(it) }
        val new = after?.let { AstClassifier().canonical(it) }
        return setOf(if (old != null && old == new) ChangeKind.SOURCE_ONLY else ChangeKind.BODY_CHANGED)
    }

    private fun intersects(text: String, fact: Fact, location: SourceLocation): Boolean =
        fact.start < offset(text, location.line.end, location.column.end) &&
            fact.end > offset(text, location.line.start, location.column.start)

}

private class AstClassifier {
    private val javaParser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))

    fun facts(source: IndexedSource): List<Fact> = if (source.path.endsWith(".kt")) kotlinFacts(source.text)
        else javaFacts(source.text)

    private fun javaFacts(text: String): List<Fact> {
        val root = javaParser.parse(text).takeIf { it.isSuccessful }?.result?.orElse(null) ?: return emptyList()
        val result = mutableListOf<Fact>()
        root.walk { node ->
            fun add(kind: Kind, name: String, content: String, shape: String = content, initializer: String? = null, terms: Int = 1) {
                val range = node.range.orElseThrow()
                result += Fact(kind, "$kind:${javaScope(node)}:$name", content, shape, initializer, terms,
                    offset(text, range.begin.line, range.begin.column), offset(text, range.end.line, range.end.column) + 1)
            }
            when (node) {
                is FieldDeclaration -> node.variables.forEach { variable ->
                    val shape = javaFieldShape(node)
                    add(Kind.FIELD, variable.nameAsString, javaText(node), javaText(shape),
                        variable.initializer.map(::javaText).orElse(null))
                    if (hasStaticInitializer(node, variable)) {
                        add(Kind.INITIALIZER, variable.nameAsString, javaText(variable.initializer.orElseThrow()))
                    }
                }
                is InitializerDeclaration -> if (node.isStatic) {
                    add(Kind.INITIALIZER, result.count { it.kind == Kind.INITIALIZER }.toString(), javaText(node))
                }
                is MethodDeclaration -> {
                    val shape = javaText(node.clone().setName("__name"))
                    add(Kind.METHOD, node.signature.asString(), javaText(node.clone().removeBody()), shape)
                }
                is TypeDeclaration<*> -> add(Kind.TYPE, node.nameAsString, javaText(node.clone().also { it.members.clear() }))
                is MethodCallExpr -> add(Kind.CALL, result.count { it.kind == Kind.CALL }.toString(), javaText(node))
                else -> javaCondition(node)?.let { condition ->
                    val terms = javaTerms(condition)
                    add(Kind.CONDITION, result.count { it.kind == Kind.CONDITION }.toString(), javaText(condition), terms = terms)
                }
            }
        }
        return result
    }

    private fun javaFieldShape(node: FieldDeclaration): FieldDeclaration =
        node.clone().also { copy -> copy.variables.forEach { it.setName("__name") } }

    private fun hasStaticInitializer(field: FieldDeclaration, variable: com.github.javaparser.ast.body.VariableDeclarator): Boolean =
        field.isStatic && variable.initializer.isPresent

    private fun javaTerms(condition: Node): Int = 1 + condition.findAll(BinaryExpr::class.java).count {
        it.operator == BinaryExpr.Operator.AND || it.operator == BinaryExpr.Operator.OR
    }

    private fun javaCondition(node: Node): Node? = when (node) {
        is IfStmt -> node.condition
        is WhileStmt -> node.condition
        is DoStmt -> node.condition
        is ForStmt -> node.compare.orElse(null)
        is ConditionalExpr -> node.condition
        is SwitchStmt -> node.selector
        is SwitchExpr -> node.selector
        else -> null
    }

    private fun javaScope(node: Node): String = generateSequence(node.parentNode.orElse(null)) { it.parentNode.orElse(null) }
        .mapNotNull {
            when (it) {
                is TypeDeclaration<*> -> it.nameAsString
                is MethodDeclaration -> it.signature.asString()
                else -> null
            }
        }.toList().reversed().joinToString(".")

    private fun javaText(node: Node): String = node.clone().also { copy ->
        copy.allContainedComments.forEach { it.remove() }
        copy.removeComment()
    }.toString()

    private fun kotlinFacts(text: String): List<Fact> = withKotlin(text) { root ->
        val result = mutableListOf<Fact>()
        PsiTreeUtil.collectElements(root, ::interestingKotlinNode).forEach { node ->
            fun add(kind: Kind, name: String, content: String, shape: String = content, initializer: String? = null, terms: Int = 1) {
                val scope = kotlinScope(node)
                result += Fact(kind, "$kind:$scope:$name", content, shape, initializer, terms,
                    node.textRange.startOffset, node.textRange.endOffset)
            }
            when (node) {
                is KtProperty -> if (!node.isLocal) {
                    add(Kind.FIELD, node.name.orEmpty(), kotlinText(node), kotlinShape(node), node.initializer?.let(::kotlinText))
                    node.initializer?.takeIf { isKotlinStatic(node) }?.let { initializer ->
                        add(Kind.INITIALIZER, node.name.orEmpty(), kotlinText(initializer))
                    }
                }
                is KtAnonymousInitializer -> if (isKotlinStatic(node)) {
                    add(Kind.INITIALIZER, result.count { it.kind == Kind.INITIALIZER }.toString(), kotlinText(node))
                }
                is KtNamedFunction -> {
                    val signature = node.name + node.valueParameters.joinToString { it.typeReference?.text.orEmpty() }
                    val header = node.children.filter { it != node.bodyExpression }.map(::kotlinText)
                        .filter { it.isNotEmpty() }.joinToString(" ")
                    add(Kind.METHOD, signature, header, kotlinShape(node))
                }
                is KtClassOrObject -> add(Kind.TYPE, node.name.orEmpty(),
                    node.children.filter { it != node.body }.map(::kotlinText).filter { it.isNotEmpty() }.joinToString(" "))
                is KtCallExpression -> add(Kind.CALL, result.count { it.kind == Kind.CALL }.toString(), kotlinText(node))
                else -> {
                    val condition = kotlinCondition(node)
                    if (condition != null) {
                        val terms = kotlinTerms(condition)
                        add(Kind.CONDITION, result.count { it.kind == Kind.CONDITION }.toString(), kotlinText(condition), terms = terms)
                    }
                }
            }
        }
        result
    }

    private fun kotlinScope(node: PsiElement): String = generateSequence(node.parent) { it.parent }.mapNotNull {
        when (it) {
            is KtClassOrObject -> it.name
            is KtNamedFunction -> it.name + it.valueParameters.joinToString { parameter -> parameter.typeReference?.text.orEmpty() }
            else -> null
        }
    }.toList().reversed().joinToString(".")

    private fun kotlinCondition(node: PsiElement): PsiElement? = when (node) {
        is KtIfExpression -> node.condition
        is KtWhileExpressionBase -> node.condition
        is KtWhenCondition -> node
        else -> null
    }

    private fun interestingKotlinNode(node: PsiElement): Boolean = node is KtNamedDeclaration || node is KtCallExpression ||
        node is KtIfExpression || node is KtWhileExpressionBase || node is KtAnonymousInitializer || node is KtWhenCondition

    private fun kotlinTerms(condition: PsiElement): Int =
        1 + PsiTreeUtil.collectElementsOfType(condition, KtBinaryExpression::class.java).count {
            it.operationToken == KtTokens.ANDAND || it.operationToken == KtTokens.OROR
        }

    private fun isKotlinStatic(node: PsiElement): Boolean = node.parent is KtFile ||
        PsiTreeUtil.getParentOfType(node, KtClassOrObject::class.java) is KtObjectDeclaration

    private fun kotlinShape(node: KtNamedDeclaration): String = kotlinTokenText(node, node.nameIdentifier)

    private fun kotlinText(element: PsiElement): String = kotlinTokenText(element, null)

    fun canonical(source: IndexedSource): String? = if (source.path.endsWith(".kt")) withKotlin(source.text) { root ->
        if (PsiTreeUtil.findChildOfType(root, PsiErrorElement::class.java) == null) kotlinText(root) else null
    } else javaParser.parse(source.text).takeIf { it.isSuccessful }?.result?.orElse(null)?.let(::javaText)

    private fun <T> withKotlin(text: String, block: (PsiElement) -> T): T {
        val disposable = Disposer.newDisposable("graphfold-change-classifier")
        return try {
            val environment = KotlinCoreEnvironment.createForProduction(
                disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
            block(KtPsiFactory(environment.project, false).createFile(physicalKotlinPsiText(text)))
        } finally {
            Disposer.dispose(disposable)
        }
    }

}

/** Read PSI tokens without copy/replace operations that require unavailable IDE extension points. */
private fun kotlinTokenText(element: PsiElement, renamed: PsiElement?): String = when {
    element is PsiComment || element is PsiWhiteSpace -> ""
    element === renamed -> "__name"
    element.firstChild == null -> element.text
    else -> generateSequence(element.firstChild) { it.nextSibling }.map { kotlinTokenText(it, renamed) }
        .filter { it.isNotEmpty() }.joinToString(" ")
}

private fun offset(text: String, line: Int, column: Int): Int {
    var cursor = 0
    repeat(line - 1) { cursor = text.indexOf('\n', cursor).let { next -> if (next < 0) text.length else next + 1 } }
    return (cursor + column - 1).coerceAtMost(text.length)
}

private enum class Kind { FIELD, METHOD, TYPE, CONDITION, CALL, INITIALIZER }
private data class Fact(
    val kind: Kind, val key: String, val content: String, val shape: String, val initializer: String?,
    val terms: Int, val start: Int, val end: Int,
)
