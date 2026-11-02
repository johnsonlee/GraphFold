package io.johnsonlee.graphfold.engine.origin

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtProperty
import io.johnsonlee.graphfold.engine.source.physicalKotlinPsiText
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import java.util.IdentityHashMap

internal fun kotlinOriginSyntax(path: String, text: String): List<OriginSyntax> {
    val disposable = Disposer.newDisposable("graphfold-origin-syntax")
    return try {
        val environment = KotlinCoreEnvironment.createForProduction(
            disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        // Headless PSI rejects CR characters. Preserve every UTF16 offset by replacing each CR
        // with LF (CRLF becomes two line breaks); token contents still come from the physical text.
        val root = KtPsiFactory(environment.project, false).createFile(path.substringAfterLast('/'), physicalKotlinPsiText(text))
        if (PsiTreeUtil.findChildOfType(root, PsiErrorElement::class.java) != null) emptyList() else kotlinOrigins(root, text)
    } finally {
        Disposer.dispose(disposable)
    }
}

private fun kotlinOrigins(root: KtFile, text: String): List<OriginSyntax> {
    val tokens = kotlinTokens(root, text)
    val nodes = PsiTreeUtil.collectElementsOfType(root, KtObjectLiteralExpression::class.java).toList()
    val ranges = IdentityHashMap<KtObjectLiteralExpression, OriginSpan>().apply { nodes.forEach { put(it, kotlinRange(it)) } }
    return nodes.map { node ->
        val range = ranges.getValue(node)
        val ancestors = generateSequence(node.parent) { it.parent }.toList()
        val context = kotlinContext(node, ancestors)
        OriginSyntax(range, originTokenText(tokens, kotlinRange(context), range, ranges.values.toList()),
            originTokenText(tokens, range, null, emptyList()),
            (listOf(root.packageFqName.asString()) + ancestors.asReversed().mapNotNull { kotlinContainer(it, text) }).joinToString("/"),
            ancestors.filterIsInstance<KtObjectLiteralExpression>().firstOrNull()?.let(ranges::getValue),
            setOf(kotlinRange(node.objectDeclaration)), node.objectDeclaration.body?.let(::kotlinRange))
    }
}

private fun kotlinContainer(element: PsiElement, text: String): String? = when (element) {
    is KtObjectDeclaration -> if (element.isObjectLiteral()) null else "type:${element.name ?: "Companion"}"
    is KtClassOrObject -> "type:${element.name}"
    is KtNamedFunction -> "method:${element.receiverTypeReference?.let { kotlinShape(it, text) }.orEmpty()}:${element.name}(" +
        kotlinParameters(element.valueParameters, text) + ")"
    is KtSecondaryConstructor -> "constructor:(" +
        kotlinParameters(element.valueParameters, text) + ")"
    is KtProperty -> if (element.parent is KtClassBody || element.parent is KtFile) "field:${element.name}" else null
    is KtAnonymousInitializer -> "initializer:instance"
    else -> null
}

private fun kotlinRange(element: PsiElement): OriginSpan = element.textRange.let { OriginSpan(it.startOffset, it.endOffset) }

private fun kotlinTokens(element: PsiElement, text: String): List<OriginToken> = when {
    element is PsiComment || element is PsiWhiteSpace -> emptyList()
    element.firstChild == null -> listOf(OriginToken(kotlinRange(element),
        text.substring(element.textRange.startOffset, element.textRange.endOffset)))
    else -> generateSequence(element.firstChild) { it.nextSibling }.flatMap { kotlinTokens(it, text) }.toList()
}

private fun kotlinContext(node: KtObjectLiteralExpression, ancestors: List<PsiElement>): PsiElement {
    val statement = ancestors.firstOrNull { it is KtProperty || it is KtParameter || it.parent is KtBlockExpression }
    val body = ancestors.filterIsInstance<KtNamedFunction>().firstOrNull()?.bodyExpression
        ?.takeIf { it.textRange.contains(node.textRange) }
    return statement ?: body ?: ancestors.filterIsInstance<KtCallExpression>().lastOrNull() ?: node
}

private fun kotlinShape(element: PsiElement, text: String): String = kotlinTokens(element, text).joinToString("\u001f") { it.text }

private fun kotlinParameters(parameters: List<KtParameter>, text: String): String =
    parameters.joinToString(",") { it.typeReference?.let { type -> kotlinShape(type, text) }.orEmpty() }
