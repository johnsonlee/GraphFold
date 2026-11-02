package io.johnsonlee.graphfold.engine.build

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MavenResourceInventoryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `deleting a resource retires only owned output and preserves incremental compiler artifacts`() {
        val source = Files.createDirectories(temporary.resolve("resources"))
        val output = Files.createDirectories(temporary.resolve("classes"))
        val state = temporary.resolve("state")
        Files.writeString(source.resolve("old.properties"), "one")
        Files.writeString(source.resolve("retained.properties"), "two")
        Files.writeString(output.resolve("old.properties"), "filtered one")
        Files.writeString(output.resolve("retained.properties"), "filtered two")
        Files.writeString(output.resolve("App.class"), "compiler output")
        Files.writeString(output.resolve("module.kotlin_module"), "compiler metadata")
        Files.writeString(output.resolve("processor-generated.txt"), "processor output")
        val classTime = Files.getLastModifiedTime(output.resolve("App.class"))
        MavenResourceInventory(state, listOf(source to output)).apply { prepare(); publish() }
        Files.delete(source.resolve("old.properties"))
        MavenResourceInventory(state, listOf(source to output)).apply { prepare(); publish() }
        assertFalse(Files.exists(output.resolve("old.properties")))
        assertEquals("filtered two", Files.readString(output.resolve("retained.properties")))
        assertEquals(classTime, Files.getLastModifiedTime(output.resolve("App.class")))
        assertEquals("compiler metadata", Files.readString(output.resolve("module.kotlin_module")))
        assertEquals("processor output", Files.readString(output.resolve("processor-generated.txt")))
    }

    @Test
    fun `renamed input retires its previous destination and publishes only after successful copy`() {
        val source = Files.createDirectories(temporary.resolve("resources"))
        val output = Files.createDirectories(temporary.resolve("classes"))
        val state = temporary.resolve("state")
        Files.writeString(source.resolve("before.txt"), "content")
        Files.copy(source.resolve("before.txt"), output.resolve("before.txt"))
        MavenResourceInventory(state, listOf(source to output)).apply { prepare(); publish() }
        val previous = Files.readString(state.resolve("production-resources.json"))
        Files.move(source.resolve("before.txt"), source.resolve("after.txt"))
        val next = MavenResourceInventory(state, listOf(source to output))
        next.prepare()
        assertFalse(Files.exists(output.resolve("before.txt")))
        assertFailsWith<BuildFailure> { next.publish() }
        assertEquals(previous, Files.readString(state.resolve("production-resources.json")))
        Files.copy(source.resolve("after.txt"), output.resolve("after.txt"))
        next.publish()
        assertTrue(Files.readString(state.resolve("production-resources.json")).contains("after.txt"))
    }

    @Test
    fun `changed generated output is never deleted using stale resource ownership`() {
        val source = Files.createDirectories(temporary.resolve("resources"))
        val output = Files.createDirectories(temporary.resolve("classes"))
        val state = temporary.resolve("state")
        Files.writeString(source.resolve("value.txt"), "content")
        Files.copy(source.resolve("value.txt"), output.resolve("value.txt"))
        MavenResourceInventory(state, listOf(source to output)).apply { prepare(); publish() }
        Files.delete(source.resolve("value.txt"))
        Files.writeString(output.resolve("value.txt"), "generated replacement")
        assertFailsWith<BuildFailure> { MavenResourceInventory(state, listOf(source to output)).prepare() }
        assertEquals("generated replacement", Files.readString(output.resolve("value.txt")))
        Files.delete(output.resolve("value.txt"))
        MavenResourceInventory(state, listOf(source to output)).apply { prepare(); publish() }
    }

    @Test
    fun `duplicate destinations and escaped ownership fail closed`() {
        val first = Files.createDirectories(temporary.resolve("first"))
        val second = Files.createDirectories(temporary.resolve("second"))
        val output = Files.createDirectories(temporary.resolve("classes"))
        Files.writeString(first.resolve("same.txt"), "first")
        Files.writeString(second.resolve("same.txt"), "second")
        val state = Files.createDirectories(temporary.resolve("state"))
        assertFailsWith<BuildFailure> { MavenResourceInventory(state, listOf(first to output, second to output)).prepare() }
        Files.writeString(state.resolve("production-resources.json"), """
            [{"source":"${first.resolve("same.txt")}","output":"${temporary.resolve("outside.txt")}","hash":"invalid"}]
        """.trimIndent())
        assertFailsWith<BuildFailure> { MavenResourceInventory(state, listOf(first to output)).prepare() }
    }

    @Test
    fun `linked resource directories are owned and retired when the link disappears`() {
        val source = Files.createDirectories(temporary.resolve("resources"))
        val external = Files.createDirectories(temporary.resolve("linked-input"))
        Files.writeString(external.resolve("message.txt"), "linked resource")
        val output = Files.createDirectories(temporary.resolve("classes"))
        Files.createDirectories(output.resolve("linked"))
        Files.copy(external.resolve("message.txt"), output.resolve("linked/message.txt"))
        val link = Files.createSymbolicLink(source.resolve("linked"), external)
        val state = temporary.resolve("state")
        MavenResourceInventory(state, listOf(source to output)).apply { prepare(); publish() }
        Files.delete(link)
        MavenResourceInventory(state, listOf(source to output)).apply { prepare(); publish() }
        assertFalse(Files.exists(output.resolve("linked/message.txt")))
        assertTrue(Files.exists(external.resolve("message.txt")))
    }

    @Test
    fun `absent resource directories do not manufacture missing resource errors`() {
        val output = Files.createDirectories(temporary.resolve("classes"))
        MavenResourceInventory(temporary.resolve("state"), listOf(temporary.resolve("absent") to output)).apply {
            prepare()
            publish()
        }
        assertEquals("[]", Files.readString(temporary.resolve("state/production-resources.json")))
    }
}
