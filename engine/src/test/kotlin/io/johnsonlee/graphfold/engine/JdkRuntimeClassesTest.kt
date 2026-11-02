package io.johnsonlee.graphfold.engine

import java.lang.module.ModuleFinder
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdkRuntimeClassesTest {
    @ParameterizedTest
    @ValueSource(strings = ["java/lang/Object", "java/sql/Connection", "com/sun/source/util/Plugin",
        "com/sun/source/util/TreePathScanner", "com/sun/source/util/TaskListener", "com/sun/tools/javac/api/JavacTool",
        "jdk/javadoc/doclet/Doclet"])
    fun `runtime image exposes classes regardless of bootstrap platform or application module loader`(owner: String) {
        assertTrue(JdkRuntimeClasses.contains(owner))
        val bytes = assertNotNull(JdkRuntimeClasses.bytes(owner))
        assertEquals(owner, ClassReader(bytes).className)
    }

    @Test fun `compiler module bytes match its system module reader`() {
        val reference = ModuleFinder.ofSystem().find("jdk.compiler").orElseThrow()
        val owner = "com/sun/source/util/Plugin"
        val expected = reference.open().use { reader -> reader.open("$owner.class").orElseThrow().use { it.readBytes() } }
        assertTrue(expected.contentEquals(JdkRuntimeClasses.bytes(owner)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["org/objectweb/asm/ClassReader", "io/johnsonlee/graphfold/engine/GraphFoldEngine"])
    fun `classes present on the engine classpath never become JDK declarations`(owner: String) {
        assertNotNull(javaClass.classLoader.getResource("$owner.class"), "Host classpath must contain the negative control")
        assertFalse(JdkRuntimeClasses.contains(owner))
        assertNull(JdkRuntimeClasses.bytes(owner))
    }

    @ParameterizedTest
    @ValueSource(strings = ["java/lang/MissingGraphFoldType", "missing/Unknown", "NoPackage", "java/lang/../Object"])
    fun `missing resources and absent packages do not establish a JDK declaration`(owner: String) {
        assertFalse(JdkRuntimeClasses.contains(owner))
        assertNull(JdkRuntimeClasses.bytes(owner))
    }
}
