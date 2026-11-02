package io.johnsonlee.graphfold.engine.build

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Inventories conventional Maven JAR outputs; unsupported packaging remains an explicit analysis gap. */
internal class MavenProductionInventory(effectiveModels: Map<Path, Path>) {
    private val root = effectiveModels.keys.first().parent.toRealPath()
    private val modules = effectiveModels.map { (pom, effective) -> module(pom, effective) }
    val supported: Boolean = modules.all { it != null }
    private var resources: MavenResourceInventory? = null

    fun prepare(state: Path) {
        if (supported) {
            val selected = modules.filterNotNull()
            selected.forEach { Files.deleteIfExists(it.manifest) }
            resources = MavenResourceInventory(state, selected.flatMap { it.resources }).also { it.prepare() }
        }
    }

    fun capture(classes: Path, output: Path, isProviderDependency: (Path) -> Boolean): ProductionInventory? {
        if (!supported) return null
        val selected = modules.filterNotNull()
        val classpaths = selected.map { module ->
            checkBuild(Files.isRegularFile(module.manifest), "Maven did not export production runtime dependencies")
            Files.readString(module.manifest).trim().split(File.pathSeparator)
                .filter(String::isNotBlank).map(Path::of).filterNot(isProviderDependency)
        }
        val directory = Files.createDirectories(output.resolve("production-artifacts"))
        val artifacts = selected.mapIndexedNotNull { index, module ->
            module.artifact?.let { artifact ->
                checkBuild(Files.isRegularFile(artifact), "Maven did not produce its declared production artifact: $artifact")
                normalizeArtifact(artifact, directory.resolve("$index.jar"), module)
            }
        }
        val dependencies = classpaths.flatten().mapIndexed { index, dependency ->
            val owner = selected.firstOrNull { dependency.toAbsolutePath().normalize().startsWith(it.output.parent) }
            if (owner == null || !Files.isRegularFile(dependency) || !dependency.toString().endsWith(".jar")) dependency
            else normalizeArtifact(dependency, directory.resolve("dependency-$index.jar"), owner)
        }
        val inventory = captureProductionInventory(classes, selected.map { it.output }, dependencies, artifacts)
        val boundaries = selected.mapIndexed { index, module ->
            "runtime-classpath/$index" to "${module.id}:${classpaths[index].size}"
        }.toMap()
        resources?.publish()
        return inventory.copy(entries = inventory.entries + boundaries, nonClassEntries = inventory.nonClassEntries + boundaries,
            resourceIndependentEntries = inventory.resourceIndependentEntries + boundaries)
    }

    private fun normalizeArtifact(artifact: Path, destination: Path, module: Module): Path {
        // Only replace the POM injected for analysis; retain genuine published metadata from the original POM.
        ZipFile(artifact.toFile()).use { source ->
            ZipOutputStream(Files.newOutputStream(destination)).use { target ->
                source.entries().asSequence().forEach { entry -> copyEntry(source, target, entry, module) }
            }
        }
        return destination
    }

    private fun copyEntry(source: ZipFile, target: ZipOutputStream, entry: ZipEntry, module: Module) {
        target.putNextEntry(ZipEntry(entry.name))
        if (!entry.isDirectory) {
            if (entry.name == module.pomEntry) target.write(module.originalPom)
            else source.getInputStream(entry).use { it.copyTo(target) }
        }
        target.closeEntry()
    }

    private fun module(pom: Path, effective: Path): Module? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val project = factory.newDocumentBuilder().parse(effective.toFile()).documentElement
        return project.children("build").singleOrNull()?.let { build -> parseModule(pom, project, build) }
    }

    private fun parseModule(pom: Path, project: Element, build: Element): Module? {
        val moduleRoot = pom.parent.toRealPath()
        val packaging = project.value("packaging").ifEmpty { "jar" }
        val target = moduleRoot.resolve("target")
        val plugins = build.children("plugins").flatMap { it.children("plugin") }
        val supported = listOf(
            packaging in setOf("jar", "pom"),
            matchesPath(build.value("directory"), target),
            matchesPath(build.value("outputDirectory"), target.resolve("classes")),
            plugins.all { it.value("artifactId") in SUPPORTED_PLUGINS },
            supportsResources(plugins.firstOrNull { it.value("artifactId") == "maven-resources-plugin" }),
            supportsJar(plugins.firstOrNull { it.value("artifactId") == "maven-jar-plugin" }),
            validName(build.value("finalName")), project.value("groupId").isNotBlank(), project.value("artifactId").isNotBlank(),
        ).all { it }
        return resourceRoots(build, target.resolve("classes"))?.takeIf { supported }?.let { resources ->
            Module(
                root.relativize(moduleRoot).joinToString("/"), target.resolve("classes"), target.resolve("graphfold-runtime-classpath.txt"),
                if (packaging == "jar") target.resolve("${build.value("finalName")}.jar") else null,
                "META-INF/maven/${project.value("groupId")}/${project.value("artifactId")}/pom.xml", Files.readAllBytes(pom), resources,
            )
        }
    }

    private fun matchesPath(value: String, expected: Path): Boolean =
        value.isNotBlank() && Path.of(value).toFile().canonicalFile == expected.toFile().canonicalFile

    private fun validName(name: String): Boolean =
        name.isNotBlank() && name.none { it in setOf('/', '\\') } && !name.contains("\${")

    private fun supportsJar(plugin: Element?): Boolean {
        val configurations = plugin?.children("configuration").orEmpty() + plugin?.children("executions").orEmpty()
            .flatMap { it.children("execution") }.filter { execution ->
                execution.children("goals").flatMap { it.children("goal") }.any { it.textContent.trim() == "jar" }
            }.flatMap { it.children("configuration") }
        return configurations.none { configuration -> configuration.children().any {
            it.tagName in setOf("classifier", "outputDirectory", "finalName", "classesDirectory")
        } }
    }

    private fun supportsResources(plugin: Element?): Boolean {
        val executions = plugin?.children("executions").orEmpty().flatMap { it.children("execution") }
        val copies = executions.any { execution -> execution.children("goals").flatMap { it.children("goal") }
            .any { it.textContent.trim() == "copy-resources" } }
        val configurations = plugin?.children("configuration").orEmpty() + executions.flatMap { it.children("configuration") }
        return !copies && configurations.none { configuration -> configuration.children().any {
            it.tagName in setOf("outputDirectory", "resources", "fileNameFiltering", "skip")
        } }
    }

    private fun resourceRoots(build: Element, output: Path): List<Pair<Path, Path>>? {
        val resources = build.children("resources").flatMap { it.children("resource") }
        val selectors = resources.any { resource -> resource.children().any { it.tagName in setOf("includes", "excludes", "targetPath") } }
        val directories = resources.map { it.value("directory") }
        val unresolved = directories.any { it.isBlank() || it.contains("\${") || !Path.of(it).isAbsolute }
        return if (selectors || unresolved) null else directories.map { Path.of(it) to output }
    }

    private data class Module(
        val id: String, val output: Path, val manifest: Path, val artifact: Path?, val pomEntry: String, val originalPom: ByteArray,
        val resources: List<Pair<Path, Path>>,
    )

    private companion object {
        val SUPPORTED_PLUGINS = setOf(
            "maven-clean-plugin", "maven-resources-plugin", "maven-compiler-plugin", "maven-surefire-plugin",
            "maven-failsafe-plugin", "maven-jar-plugin", "maven-install-plugin", "maven-deploy-plugin",
            "maven-site-plugin", "maven-dependency-plugin", "maven-source-plugin", "maven-javadoc-plugin",
            "maven-enforcer-plugin", "maven-checkstyle-plugin", "kotlin-maven-plugin",
        )
    }
}

private fun Element.children(name: String? = null): List<Element> = (0 until childNodes.length).mapNotNull {
    childNodes.item(it) as? Element
}.filter { name == null || it.tagName == name }
private fun Element.value(name: String): String = children(name).singleOrNull()?.textContent?.trim().orEmpty()
