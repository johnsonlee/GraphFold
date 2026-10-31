package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Follow Maven's effective module declarations, including activated profiles and interpolated module paths. */
internal class MavenReactor(private val checkout: Path) {
    fun models(export: (Path, Int) -> Path): Map<Path, Path> {
        val result = linkedMapOf<Path, Path>()
        val pending = ArrayDeque<Path>()
        pending.add(checkout.resolve("pom.xml"))
        while (pending.isNotEmpty()) {
            val pom = pending.removeFirst().toAbsolutePath().normalize()
            checkBuild(Files.isRegularFile(pom) && pom.toRealPath().startsWith(checkout.toRealPath()),
                "Maven reactor module must have a POM inside the checkout: $pom")
            checkBuild(pom !in result, "Duplicate or cyclic Maven reactor module: $pom")
            checkBuild(!Files.readString(pom).contains("<sourceDirectory>"),
                "Custom Maven source directories require an explicit source export adapter")
            val model = export(pom, result.size)
            result[pom] = model
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            val project = factory.newDocumentBuilder().parse(model.toFile()).documentElement
            val modules = project.children("modules").flatMap { it.children("module") }
            modules.forEach {
                val module = pom.parent.resolve(it.textContent.trim()).normalize()
                pending.add(if (Files.isDirectory(module)) module.resolve("pom.xml") else module)
            }
        }
        return result
    }
}

private fun Element.children(name: String): List<Element> = (0 until childNodes.length).mapNotNull {
    childNodes.item(it) as? Element
}.filter { it.tagName == name }
