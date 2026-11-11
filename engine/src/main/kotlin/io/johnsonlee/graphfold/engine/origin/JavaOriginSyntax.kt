package io.johnsonlee.graphfold.engine.origin

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.Statement
import java.util.IdentityHashMap

internal fun javaOriginSyntax(text: String): List<OriginSyntax> {
    val parsed = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)).parse(text)
    // JavaParser only reports a successful compilation-unit parse when its result exists.
    if (!parsed.isSuccessful) return emptyList()
    val root = parsed.result.orElseThrow()
    val offsets = JavaSourceOffsets(text)
    val tokens = root.tokenRange.orElseThrow().filterNot { it.category.isWhitespaceOrComment }.map { token ->
        val range = token.range.orElseThrow()
        OriginToken(OriginSpan(offsets.at(range.begin.line, range.begin.column),
            offsets.at(range.end.line, range.end.column) + 1), token.text)
    }
    val nodes = root.findAll(Node::class.java).filter(::isAnonymousJava)
    val ranges = IdentityHashMap<Node, OriginSpan>().apply { nodes.forEach { put(it, offsets.range(it)) } }
    val packageName = root.packageDeclaration.map { it.nameAsString }.orElse("")
    return nodes.map { node ->
        val range = ranges.getValue(node)
        val ancestors = generateSequence(node.parentNode.orElse(null)) { it.parentNode.orElse(null) }.toList()
        // Every field initializer is owned by a VariableDeclarator before its FieldDeclaration.
        val context = ancestors.firstOrNull {
            it is VariableDeclarator || it is Statement || it is EnumConstantDeclaration
        } ?: node
        OriginSyntax(range, originTokenText(tokens, offsets.range(context), range, ranges.values.toList()),
            originTokenText(tokens, range, null, emptyList()),
            (listOf(packageName) + ancestors.asReversed().mapNotNull(::javaContainer) +
                (if (node is EnumConstantDeclaration) listOf("enum-entry:${node.nameAsString}") else emptyList())).joinToString("/"),
            ancestors.firstOrNull { it in ranges }?.let(ranges::getValue),
            bodyRange = javaBodyRange(node, offsets))
    }
}

private fun javaContainer(node: Node): String? = when (node) {
    is TypeDeclaration<*> -> "type:${node.nameAsString}"
    is MethodDeclaration -> "method:${node.nameAsString}(${node.parameters.joinToString(",") { it.typeAsString }})"
    is ConstructorDeclaration -> "constructor:(${node.parameters.joinToString(",") { it.typeAsString }})"
    is InitializerDeclaration -> if (node.isStatic) "initializer:static" else "initializer:instance"
    is VariableDeclarator -> if (node.parentNode.orElse(null) is FieldDeclaration) "field:${node.nameAsString}" else null
    else -> null
}

private class JavaSourceOffsets(text: String) {
    private val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
    fun at(line: Int, column: Int): Int = starts[line - 1] + column - 1
    fun range(node: Node): OriginSpan = node.range.orElseThrow().let { range ->
        OriginSpan(at(range.begin.line, range.begin.column), at(range.end.line, range.end.column) + 1)
    }
}

private fun isAnonymousJava(node: Node): Boolean = when (node) {
    is ObjectCreationExpr -> node.anonymousClassBody.isPresent
    is EnumConstantDeclaration -> node.classBody.isNotEmpty() || node.tokenRange.orElseThrow().end.text == "}"
    else -> false
}

/** JavaParser exposes anonymous members as a NodeList, so braces come from the verified allocation's tokens. */
private fun javaBodyRange(node: Node, offsets: JavaSourceOffsets): OriginSpan {
    val tokens = node.tokenRange.orElseThrow().filterNot { it.category.isWhitespaceOrComment }.map { token ->
        val range = token.range.orElseThrow()
        OriginToken(OriginSpan(offsets.at(range.begin.line, range.begin.column),
            offsets.at(range.end.line, range.end.column) + 1), token.text)
    }
    return javaAnonymousBodyRange(tokens)
}

/** Validate the body-delimiter evidence before using it to exclude constructor arguments from an origin. */
internal fun javaAnonymousBodyRange(tokens: List<OriginToken>): OriginSpan {
    val closing = tokens.last()
    check(closing.text == "}") { "Parsed anonymous class has no closing body brace" }
    var depth = 0
    val opening = tokens.asReversed().firstOrNull { token ->
        when (token.text) {
            "}" -> depth++
            "{" -> depth--
        }
        depth == 0
    }
    checkNotNull(opening) { "Parsed anonymous class has no matching opening body brace" }
    return OriginSpan(opening.range.start, closing.range.end)
}
