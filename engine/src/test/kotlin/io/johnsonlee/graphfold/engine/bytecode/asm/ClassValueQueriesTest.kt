package io.johnsonlee.graphfold.engine.bytecode.asm

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassValueQueriesTest {
    @TempDir lateinit var directory: Path

    @Test fun `compiled type queries do not produce reflective observation gaps`() {
        compile()
        val inventory = BytecodeInventory().also { it.read(directory) }
        val gaps = inventory.methodDiagnostics
        assertEquals(REFLECTIVE_METHODS, gaps.keys.map { it.substringAfter("Queries.").substringBefore('(') }.toSet())
        assertTrue(gaps.values.flatten().all { it.globalObservation })
        assertTrue(gaps.values.all { methodGaps -> methodGaps.any { it.callee?.startsWith("java.lang.Class.") == true } })
    }

    @Test fun `normalized types retain runtime name and failed cast observation boundaries`() {
        compile()
        val inventory = BytecodeInventory(normalizedRuntimeNames = true).also { it.read(directory) }
        val names = inventory.methodDiagnostics.keys.map { it.substringAfter("Queries.").substringBefore('(') }.toSet()
        assertEquals(REFLECTIVE_METHODS + setOf("name", "typeName", "simpleName", "packageName", "cast"), names)
        assertTrue(inventory.methodDiagnostics.values.flatten().all { it.globalObservation })
    }

    private fun compile() {
        val source = directory.resolve("Queries.java")
        Files.writeString(source, """
            public class Queries {
                public String name(Class<?> type) { return type.getName(); }
                public String typeName(Class<?> type) { return type.getTypeName(); }
                public String simpleName(Class<?> type) { return type.getSimpleName(); }
                public String packageName(Class<?> type) { return type.getPackageName(); }
                public boolean assertion(Class<?> type) { return type.desiredAssertionStatus(); }
                public boolean instance(Class<?> type, Object value) { return type.isInstance(value); }
                public Object cast(Class<?> type, Object value) { return type.cast(value); }
                public boolean assignable(Class<?> type, Class<?> from) { return type.isAssignableFrom(from); }
                public boolean array(Class<?> type) { return type.isArray(); }
                public boolean inter(Class<?> type) { return type.isInterface(); }
                public Object members(Class<?> type) { return type.getDeclaredMethods(); }
                public Object fields(Class<?> type) { return type.getDeclaredFields(); }
                public Object construct(Class<?> type) throws Exception { return type.getDeclaredConstructor().newInstance(); }
                public Object instantiate(Class<?> type) throws Exception { return type.newInstance(); }
                public Object named(String name) throws Exception { return Class.forName(name); }
                public Object resource(Class<?> type) { return type.getResource("messages.properties"); }
                public Object stream(Class<?> type) { return type.getResourceAsStream("messages.properties"); }
                public Object loader(Class<?> type) { return type.getClassLoader(); }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", directory.toString(), source.toString()))
    }

    private companion object {
        val REFLECTIVE_METHODS = setOf("members", "fields", "construct", "instantiate", "named", "resource", "stream", "loader")
    }
}
