package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Temporary configuration in the isolated checkout. Original POM bytes are always restored. */
internal class MavenOriginOverlay(
    private val checkout: Path, private val output: Path, plugin: Path,
    effectiveModels: Map<Path, Path>, private val runId: String,
) : AutoCloseable {
    private val originals = effectiveModels.keys.associateWith(Files::readAllBytes)
    private val version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(plugin)))
    private val repository = output.resolve("origin-maven-repository").toAbsolutePath()
    private val pluginJar = plugin.toAbsolutePath()

    init {
        var configured = false
        try {
            publishLocalArtifact(plugin)
            effectiveModels.forEach { (pom, effective) -> configure(pom, read(effective)) }
            configured = true
        } finally {
            if (!configured) close()
        }
    }

    override fun close() {
        originals.forEach { (path, bytes) -> Files.write(path, bytes) }
    }

    fun isProviderDependency(path: Path): Boolean = path.fileName.toString() == "$ARTIFACT-$version.jar"

    private fun publishLocalArtifact(plugin: Path) {
        val directory = Files.createDirectories(repository.resolve("io/johnsonlee/graphfold/$ARTIFACT/$version"))
        val artifact = directory.resolve("$ARTIFACT-$version.jar")
        if (!Files.exists(artifact) || Files.mismatch(plugin, artifact) != -1L) {
            Files.copy(plugin, artifact, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        Files.writeString(directory.resolve("$ARTIFACT-$version.pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>$GROUP</groupId><artifactId>$ARTIFACT</artifactId><version>$version</version></project>
        """.trimIndent())
    }

    private fun configure(pom: Path, effective: Document) {
        val document = read(pom)
        val project = document.documentElement
        val dependency = project.child("dependencies").append("dependency")
        coordinate(dependency)
        dependency.append("scope", "provided")
        dependency.append("optional", "true")
        project.child("repositories").append("repository").apply {
            append("id", "graphfold-origin-$version")
            append("url", repository.toUri().toString())
        }
        val effectivePlugins = effective.documentElement.existing("build")?.existing("plugins")?.children("plugin").orEmpty()
        val compiler = effectivePlugins.firstOrNull { it.value("artifactId") == "maven-compiler-plugin" }
        configureCompiler(project, compiler)
        effectivePlugins.firstOrNull { it.value("artifactId") == "kotlin-maven-plugin" }?.let {
            configureKotlin(project, it)
        }
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.transform(DOMSource(document), StreamResult(pom.toFile()))
    }

    private fun configureCompiler(project: Element, effective: Element?) {
        val plugin = plugin(project, "org.apache.maven.plugins", "maven-compiler-plugin", effective)
        val configurations = configurations(plugin, effective, "testCompile", "default-testCompile")
        configurations.forEach { (target, source, origins) ->
            checkBuild(source?.value("compilerId") in listOf(null, "", "javac"), "Provenance requires the Maven javac backend")
            appendArguments(target, source, "compilerArgs", listOf(
                CompilerOriginPlugin.javacArgument(origins, checkout, runId),
            ))
            // Preserve effective inherited/execution-specific AP paths rather than replacing them with our provider.
            source?.existing("annotationProcessorPaths")?.let { paths ->
                val replacement = target.replaceWithCopy("annotationProcessorPaths", paths)
                replacement.setAttribute("combine.self", "override")
                coordinate(replacement.append("path"))
            }
        }
    }

    private fun configureKotlin(project: Element, effective: Element) {
        val plugin = plugin(project, "org.jetbrains.kotlin", "kotlin-maven-plugin", effective)
        configurations(plugin, effective, "test-compile", "default-test-compile").forEach { (target, source, origins) ->
            appendArguments(target, source, "args", listOf(
                "-Xplugin=$pluginJar", "-P", "plugin:io.johnsonlee.graphfold.origin.kotlin:output=$origins",
                "-P", "plugin:io.johnsonlee.graphfold.origin.kotlin:runId=$runId",
            ))
        }
    }

    private fun configurations(
        plugin: Element, effective: Element?, testGoal: String, defaultTestId: String,
    ): List<CompilerConfiguration> {
        val shared = effective?.existing("configuration")
        val result = mutableListOf(CompilerConfiguration(plugin.child("configuration"), shared, output.resolve("origins")))
        val effectiveExecutions = effective?.existing("executions")?.children("execution").orEmpty()
        val executions = plugin.child("executions")
        val ids = (effectiveExecutions.map { it.value("id") } + defaultTestId).distinct()
        ids.forEach { id ->
            val execution = effectiveExecutions.firstOrNull { it.value("id") == id }
            val goals = execution?.existing("goals")?.children("goal").orEmpty().map { it.textContent.trim() }
            val test = testGoal in goals || id == defaultTestId
            checkBuild(!test || "compile" !in goals, "Mixed production/test compiler executions cannot share provenance: $id")
            val target = executions.children("execution").firstOrNull { it.value("id") == id }
                ?: executions.append("execution").also { it.append("id", id) }
            // Use pre-overlay effective arguments: test goals must never inherit the production destination.
            val source = execution?.existing("configuration") ?: shared
            result += CompilerConfiguration(target.child("configuration"), source, output.resolve(if (test) "test-origins" else "origins"))
        }
        return result
    }

    private data class CompilerConfiguration(val target: Element, val source: Element?, val origins: Path)

    private fun appendArguments(target: Element, source: Element?, name: String, arguments: List<String>) {
        val existing = source?.existing(name)
        val list = if (existing == null) target.child(name) else target.replaceWithCopy(name, existing)
        list.setAttribute("combine.self", "override")
        arguments.forEach { list.append("arg", it) }
    }

    private fun plugin(project: Element, group: String, artifact: String, effective: Element?): Element {
        val plugins = project.child("build").child("plugins")
        return plugins.children("plugin").firstOrNull { it.value("artifactId") == artifact }
            ?: plugins.append("plugin").also {
                it.append("groupId", group)
                it.append("artifactId", artifact)
                effective?.value("version")?.takeIf(String::isNotBlank)?.let { version -> it.append("version", version) }
            }
    }

    private fun coordinate(element: Element) {
        element.append("groupId", GROUP)
        element.append("artifactId", ARTIFACT)
        element.append("version", version)
    }

    private companion object {
        const val GROUP = "io.johnsonlee.graphfold"
        const val ARTIFACT = "compiler-origin-provider"

        fun read(path: Path): Document {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            return factory.newDocumentBuilder().parse(path.toFile())
        }
    }
}

private fun Element.children(name: String): List<Element> = (0 until childNodes.length).mapNotNull {
    childNodes.item(it) as? Element
}.filter { it.tagName == name }
private fun Element.existing(name: String): Element? = children(name).firstOrNull()
private fun Element.value(name: String): String = existing(name)?.textContent.orEmpty()
private fun Element.child(name: String): Element = existing(name) ?: append(name)
private fun Element.append(name: String, text: String? = null): Element = ownerDocument.createElement(name).also {
    if (text != null) it.textContent = text
    appendChild(it)
}
private fun Element.replaceWithCopy(name: String, original: Element): Element {
    children(name).forEach(::removeChild)
    return (ownerDocument.importNode(original, true) as Element).also(::appendChild)
}
