package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.bytecode.sootup.resourceReads

import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ResourceRead

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DependencyResourceAnalysisTest {
    private val changedKey = ResourceRead("messages", "changed")
    private val unknown = ResourceRead(null, null)

    @Test fun `library read propagates through a recursive closure without unrelated readers`() {
        val methods = listOf(method("App.run", "Library.first"), method("Library.first", "Library.second"),
            method("Library.second", "Library.first", reads = listOf(changedKey)), method("Unrelated.read", reads = listOf(unknown)))
        val analysis = analyze(methods)
        assertEquals(listOf(changedKey), analysis.reads("App.run()V"))
        assertEquals(listOf(changedKey), analysis.reads("Library.first()V"))
        assertEquals(listOf(unknown), analysis.reads("Unrelated.read()V"))
        assertEquals(listOf(unknown), analysis.reads("Missing.method()V"))
    }

    @Test fun `no surviving call means library resource reads do not affect the application`() {
        val analysis = analyze(listOf(method("App.run"), method("Library.read", reads = listOf(changedKey))))
        assertTrue(analysis.reads("App.run()V").isEmpty())
    }

    @Test fun `missing native and abstract bodies remain unknown`() {
        val methods = listOf(method("App.missing", "Library.absent"), method("App.native", "Library.native"),
            method("App.abstract", "Library.abstract"), method("Library.native", modifiers = setOf(MethodModifier.NATIVE)),
            method("Library.abstract", modifiers = setOf(MethodModifier.ABSTRACT)),
            method("Library.noBody").copy(canonical = "PUBLIC\nNO_BODY"))
        val analysis = analyze(methods)
        listOf("App.missing", "App.native", "App.abstract", "Library.noBody").forEach {
            assertTrue(unknown in analysis.reads("$it()V"), it)
        }
    }

    @Test fun `modeled call still triggers its captured class initializer and superclass initializer`() {
        val methods = listOf(method("App.run", "Library.pure"), method("Library.pure"),
            method("Library.<clinit>", reads = listOf(changedKey)), method("Parent.<clinit>", reads = listOf(ResourceRead("parent", null))))
        val calls = methods.associate { it.identity to emptySet<String>() }
        val initialization = mapOf("App" to emptySet(), "Library" to setOf("Parent"), "Parent" to setOf("java.lang.Object"))
        val analysis = analyze(methods, initialization = initialization, calls = calls)
        assertEquals(setOf(changedKey, ResourceRead("parent", null)), analysis.reads("App.run()V").toSet())
    }

    @Test fun `field reads trigger resource loading initializers without invoking a library method`() {
        val methods = listOf(method("App.run").copy(references = listOf("Library.value:Ljava/lang/String;")),
            method("Library.<clinit>", reads = listOf(changedKey)))
        assertEquals(listOf(changedKey), analyze(methods, fieldOwner = { "Library" }).reads("App.run()V"))
    }

    @Test fun `missing owner and superclass initialization evidence is unknown`() {
        val methods = listOf(method("App.field").copy(references = listOf("Missing.value:I")), method("Child.run"))
        val initialization = mapOf("App" to emptySet(), "Child" to setOf("AbsentParent"))
        val analysis = analyze(methods, initialization = initialization)
        assertEquals(listOf(unknown), analysis.reads("App.field()V"))
        assertEquals(listOf(unknown), analysis.reads("Child.run()V"))
    }

    @Test fun `derived callback origin chain propagates reads while unrelated creations do not`() {
        val methods = listOf(method("App.run", "Library.callback"), method("Library.callback"),
            method("App.callback", reads = listOf(changedKey)), method("Other.callback", reads = listOf(unknown)))
        val direct = site(1, "App.run", "Library.callback")
        val derived = site(2, "App.run", "App.callback", origin = 1)
        val repeated = site(3, "App.run", "App.callback", origin = 2)
        val unrelated = site(4, "App.run", "Other.callback").copy(ordinal = -1)
        val analysis = analyze(methods, listOf(direct, derived, repeated, unrelated))
        assertEquals(listOf(changedKey), analysis.reads("App.run()V"))
    }

    @Test fun `graph candidates never erase the unresolved declared target`() {
        val methods = listOf(method("App.run", "Unknown.call"), method("Known.call"))
        val analysis = analyze(methods, listOf(site(1, "App.run", "Known.call")))
        assertEquals(listOf(unknown), analysis.reads("App.run()V"))
    }

    @Test fun `pure platform initialization does not invent reads but arbitrary receivers stay unknown`() {
        val pure = method("App.pure").copy(calls = setOf("java.lang.String.length()I"),
            references = listOf("java.lang.System.out:Ljava/io/PrintStream;"))
        val custom = method("App.custom").copy(calls = setOf("java.lang.CharSequence.length()I"))
        val methods = listOf(pure, custom)
        val analysis = analyze(methods, calls = mapOf(pure.identity to emptySet(), custom.identity to custom.calls))
        assertTrue(analysis.reads(pure.identity).isEmpty())
        assertEquals(listOf(unknown), analysis.reads(custom.identity))
    }

    @Test fun `modeled dynamic invocation uses operation initializers without pseudoowner`() {
        val method = method("App.run").copy(calls = setOf("soot.dummy.InvokeDynamic.makeConcat()Ljava/lang/String;"))
        val analysis = analyze(listOf(method), calls = mapOf(method.identity to emptySet()),
            initializers = mapOf(method.identity to emptySet()))
        assertTrue(analysis.reads(method.identity).isEmpty())
    }

    @Test fun `inherited API effects attach only to the supplied application owner`() {
        val methods = listOf(method("App.run"), method("Other.run"), method("Library.read", reads = listOf(changedKey)))
        val analysis = analyze(methods, inheritedMethods = mapOf("App" to setOf("Library.read()V")))
        assertEquals(listOf(changedKey), analysis.reads("App.run()V"))
        assertTrue(analysis.reads("Other.run()V").isEmpty())
    }

    @Test fun `incomplete inherited API inventory is an unknown effect only for the affected owner`() {
        val methods = listOf(method("App.run"), method("Other.run"))
        val analysis = analyze(methods, inheritedMethods = mapOf("App" to null, "Other" to emptySet()))
        assertEquals(listOf(unknown), analysis.reads("App.run()V"))
        assertTrue(analysis.reads("Other.run()V").isEmpty())
    }

    @Test fun `a call with no ordinal cannot activate a graph-only callback resource effect`() {
        val methods = listOf(method("App.run", "Library.callback"), method("Library.callback"),
            method("Other.callback", reads = listOf(changedKey)))
        val unknownSite = site(1, "App.run", "Library.callback").copy(ordinal = null)
        val derived = site(2, "App.run", "Other.callback", origin = 1)
        assertTrue(analyze(methods, listOf(unknownSite, derived)).reads("App.run()V").isEmpty())
    }

    @Test fun `entry effect proof requires both inherited methods and field initialization inventory`() {
        val analysis = analyze(listOf(method("App.run")), inheritedMethods = mapOf("App" to emptySet()))
        assertEquals(listOf(unknown), analysis.entryReads("App"))
        assertEquals(listOf(unknown), analysis.entryReads("Missing"))
    }

    @Test fun `a missing call effect capture retains direct dependency reads and an explicit unknown`() {
        val methods = listOf(method("App.run", "Library.read"), method("Library.read", reads = listOf(changedKey)))
        val analysis = analyze(methods, calls = mapOf("Library.read()V" to emptySet()))
        assertEquals(setOf(changedKey, unknown), analysis.reads("App.run()V").toSet())
    }

    private fun method(name: String, vararg calls: String, reads: List<ResourceRead> = emptyList(),
        modifiers: Set<MethodModifier> = emptySet()) =
        MethodSnapshot("$name()V", "body", calls.map { "$it()V" }.toSet(), modifiers, emptySet(), resourceReads = reads)

    private fun site(id: Int, caller: String, callee: String, origin: Int? = null) = CallSiteNode(
        NodeId(id), descriptor(caller), descriptor(callee), null, null, emptyList(), if (origin == null) 0 else -1,
        origin?.let(::NodeId),
    )

    private fun descriptor(identity: String) = MethodDescriptor(TypeDescriptor(identity.substringBeforeLast('.')),
        identity.substringAfterLast('.'), emptyList(), TypeDescriptor("void"))

    private fun analyze(
        methods: List<MethodSnapshot>, sites: List<CallSiteNode> = emptyList(),
        initialization: Map<String, Set<String>> = methods.associate {
            it.identity.substringBefore('(').substringBeforeLast('.') to emptySet()
        },
        calls: Map<String, Set<String>> = methods.associate { it.identity to it.calls },
        initializers: Map<String, Set<String>>? = null,
        fieldOwner: (String) -> String? = { null },
        inheritedMethods: Map<String, Set<String>?> = emptyMap(),
    ): DependencyResourceAnalysis {
        val graph = DefaultGraph.Builder().apply {
            sites.forEach { addMethod(it.caller); addMethod(it.callee); addNode(it) }
        }.build()
        return DependencyResourceAnalysis(graph, methods.associateBy { it.identity }, calls, initialization,
            initializers, fieldOwner, inheritedMethods)
    }
}
