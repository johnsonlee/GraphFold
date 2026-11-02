package io.johnsonlee.graphfold.engine.origin

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JdkOriginHierarchyTest {
    @ParameterizedTest
    @ValueSource(strings = ["com/sun/source/util/Plugin", "com/sun/source/util/TreePathScanner", "com/sun/source/util/TaskListener"])
    fun `compiler module ancestry is available without loading JDK classes through the application loader`(owner: String) {
        assertEquals(OriginSubtype.NO, OriginHierarchy(emptyList(), emptyList()).serializable(owner))
    }

    @Test fun `JDK implementation ancestry crosses module boundaries`() {
        assertEquals(OriginSubtype.YES, OriginHierarchy(emptyList(), emptyList())
            .subtype("com/sun/tools/javac/api/JavacTool", "javax/tools/JavaCompiler"))
    }

    @Test fun `host application dependency cannot prove a missing analyzed type nonserializable`() {
        assertNotNull(javaClass.classLoader.getResource("org/objectweb/asm/ClassVisitor.class"))
        assertEquals(OriginSubtype.UNKNOWN, OriginHierarchy(emptyList(), emptyList()).serializable("org/objectweb/asm/ClassVisitor"))
    }
}
