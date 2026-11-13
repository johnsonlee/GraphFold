package io.johnsonlee.graphfold.engine.testing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Discover executable test containers with JUnit itself without executing any tests. */
object TestInventoryMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 2) { "Expected test class directories and output JSON" }
        val roots = arguments[0].split(File.pathSeparator).map(Path::of).filter(Files::isDirectory).toSet()
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClasspathRoots(roots)).build()
        val plan = LauncherFactory.create().discover(request)
        val names = plan.roots.flatMap { plan.getDescendants(it) }.mapNotNull { identifier ->
            (identifier.source.orElse(null) as? ClassSource)?.className?.substringBefore('$')
        }.distinct().sorted()
        check(names.isNotEmpty()) { "JUnit discovered no test classes in $roots" }
        val output = Path.of(arguments[1])
        Files.createDirectories(output.toAbsolutePath().parent)
        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), names)
    }
}
