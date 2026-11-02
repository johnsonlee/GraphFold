package io.johnsonlee.graphfold.engine

import java.lang.module.ModuleFinder
import java.lang.module.ModuleReference

/**
 * Resolves declarations from the running JDK image, including application-loader modules such as
 * jdk.compiler. System-module readers never search the engine's application or context classpath.
 * Availability establishes only a class declaration, not safe effects or a complete method body.
 */
internal object JdkRuntimeClasses {
    private val packages: Map<String, List<ModuleReference>> by lazy {
        ModuleFinder.ofSystem().findAll().flatMap { module ->
            module.descriptor().packages().map { it to module }
        }.groupBy({ it.first }, { it.second })
    }

    fun bytes(owner: String): ByteArray? = module(owner)?.open()?.use { reader ->
        reader.open("$owner.class").orElse(null)?.use { it.readBytes() }
    }

    fun contains(owner: String): Boolean = module(owner)?.open()?.use { reader ->
        reader.find("$owner.class").isPresent
    } == true

    private fun module(owner: String): ModuleReference? =
        packages[owner.substringBeforeLast('/', "").replace('/', '.')]?.singleOrNull()
}
