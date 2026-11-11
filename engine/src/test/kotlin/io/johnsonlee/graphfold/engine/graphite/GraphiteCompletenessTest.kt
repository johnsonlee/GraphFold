package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactEvidence
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.input.AnalysisIdentity
import io.johnsonlee.graphite.input.BodyInterceptionEvidence
import io.johnsonlee.graphite.input.JvmMethodIdentity
import io.johnsonlee.graphite.input.MethodFoldObservation
import io.johnsonlee.graphite.sootup.MethodObservationEvidence
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import sootup.core.model.MethodModifier

class GraphiteCompletenessTest {
    private val caller = MethodDescriptor(TypeDescriptor("Application"), "run", emptyList(), TypeDescriptor("void"))
    private val callee = MethodDescriptor(TypeDescriptor("Callback"), "run", emptyList(), TypeDescriptor("void"))
    private val evidence = AnalysisCompleteness(
        AnalysisIdentity(includePackages = listOf("Application"), dependencyReachable = 1),
        mapOf("application.jar" to ArtifactEvidence(ArtifactRole.APPLICATION)),
        mapOf("Application" to ClassEvidence("application.jar", AnalysisStatus.ANALYZED)),
        emptyMap(),
    )

    @Test fun `independent exact dispatch retains the same caller body failure`() {
        val builder = DefaultGraph.Builder()
        builder.addMethod(caller)
        builder.addMethod(callee)
        builder.addNode(CallSiteNode(NodeId(1), caller, callee, null, null, emptyList(), 0))
        builder.setAnalysisCompleteness(evidence.copy(
            methods = mapOf(caller.identity to MethodEvidence(AnalysisStatus.NO_BODY)),
            callSiteGaps = mapOf(1 to CallSiteGap.DISPATCH_UNRESOLVED),
        ))
        val result = GraphiteCompleteness(builder.build(), provenDispatch = { it.id == NodeId(1) })
        val gaps = result.methodDiagnostics.getValue(caller.identity)
        assertEquals(1, gaps.size)
        assertTrue("NO_BODY" in gaps.single().message)
        assertTrue(!gaps.single().dispatchOnly)
    }

    @Test fun `a graph without evidence is unknown rather than empty and complete`() {
        val result = GraphiteCompleteness(DefaultGraph.Builder().build())
        assertEquals(1, result.diagnostics.size)
        assertTrue("did not provide" in result.diagnostics.single())
        assertTrue(result.methodDiagnostics.isEmpty())
    }

    @Test fun `an analyzed class and method require no exceptional status record`() {
        val result = inspect(evidence)
        assertTrue(result.diagnostics.isEmpty())
        assertTrue(result.incompleteMethods.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = [
        "ANALYZED_WITHOUT_CONTROL_FLOW", "NATIVE", "NO_BODY", "INCOMPLETE", "RESOLUTION_FAILED",
    ])
    fun `unproved method state is attributable without poisoning unrelated methods`(status: AnalysisStatus) {
        val result = inspect(evidence.copy(methods = mapOf(caller.identity to MethodEvidence(status))))
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(setOf(caller.identity), result.incompleteMethods)
        val gap = result.methodDiagnostics.getValue(caller.identity).single()
        assertTrue(status.name in gap.message)
        assertEquals(null, gap.callee, "The evidence already describes a surviving folded method")
    }

    @Test fun `frontend failure detail is retained with its method identity`() {
        val result = inspect(evidence.copy(methods = mapOf(
            caller.identity to MethodEvidence(AnalysisStatus.INCOMPLETE, "body processing stopped"),
        )))
        assertTrue("body processing stopped" in result.methodDiagnostics.getValue(caller.identity).single().message)
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = ["ANALYZED", "ABSTRACT"])
    fun `an analyzed body or abstract declaration alone is not a gap`(status: AnalysisStatus) {
        val result = inspect(evidence.copy(methods = mapOf(caller.identity to MethodEvidence(status))))
        assertTrue(result.methodDiagnostics.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = ["EXCLUDED_BY_SCOPE", "NOT_EXPANDED"])
    fun `unreached or budget limited dependency is not a repository wide failure`(status: AnalysisStatus) {
        val result = inspect(evidence.copy(classes = evidence.classes +
            ("UnusedDependency" to ClassEvidence("dependency.jar", status))))
        assertTrue(result.diagnostics.isEmpty())
        assertTrue(result.incompleteMethods.isEmpty())
    }

    @Test fun `failed class declaration blocks when method ownership cannot be established`() {
        val result = inspect(evidence.copy(classes = mapOf(
            "Broken" to ClassEvidence("broken.jar", AnalysisStatus.RESOLUTION_FAILED),
        )))
        assertTrue("Broken" in result.diagnostics.single())
        assertTrue("broken.jar" in result.diagnostics.single())
    }

    @Test fun `runtime variant and duplicate resolution remain explicit failures`() {
        val result = inspect(evidence.copy(
            artifacts = evidence.artifacts + ("versions.jar" to ArtifactEvidence(ArtifactRole.LIBRARY, multiRelease = true)),
            duplicateClasses = mapOf("Dependency" to listOf("first.jar", "second.jar")),
        ))
        assertEquals(2, result.diagnostics.size)
        assertTrue(result.diagnostics.any { "Dependency" in it && "first.jar, second.jar" in it })
        assertTrue(result.diagnostics.any { "multi-release" in it && "versions.jar" in it })
    }

    @Test fun `independently verified descriptor metadata exempts only its exact artifact origin`() {
        val checked = mutableListOf<String>()
        val result = inspect(evidence.copy(artifacts = evidence.artifacts + mapOf(
            "metadata.jar" to ArtifactEvidence(ArtifactRole.LIBRARY, multiRelease = true),
            "variants.jar" to ArtifactEvidence(ArtifactRole.LIBRARY, multiRelease = true),
        ))) { origin ->
            checked += origin
            origin == "metadata.jar"
        }
        assertEquals(listOf("metadata.jar", "variants.jar"), checked)
        assertEquals(1, result.diagnostics.size)
        assertTrue("variants.jar" in result.diagnostics.single())
    }

    @Test fun `descriptor exemption does not hide an incomplete method body`() {
        val result = inspect(evidence.copy(
            artifacts = mapOf("application.jar" to ArtifactEvidence(ArtifactRole.APPLICATION, multiRelease = true)),
            methods = mapOf(caller.identity to MethodEvidence(AnalysisStatus.INCOMPLETE)),
        )) { true }
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(setOf(caller.identity), result.incompleteMethods)
    }

    @ParameterizedTest
    @EnumSource(CallSiteGap::class)
    fun `incomplete function value dispatch belongs to its caller`(reason: CallSiteGap) {
        val result = inspect(evidence.copy(callSiteGaps = mapOf(1 to reason)))
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(setOf(caller.identity), result.incompleteMethods)
        assertTrue(reason.name in result.methodDiagnostics.getValue(caller.identity).single().message)
    }

    @Test fun `corrupt call site evidence cannot become a missing harmless call`() {
        val result = inspect(evidence.copy(callSiteGaps = mapOf(
            2 to CallSiteGap.DISPATCH_UNRESOLVED,
            99 to CallSiteGap.DISPATCH_PARTIAL,
        )))
        assertEquals(2, result.diagnostics.size)
        assertTrue(result.diagnostics.all { "unavailable call site" in it })
    }

    @Test fun `body failure and unresolved dispatch both survive for the same caller`() {
        val result = inspect(evidence.copy(
            methods = mapOf(caller.identity to MethodEvidence(AnalysisStatus.NO_BODY)),
            callSiteGaps = mapOf(1 to CallSiteGap.DISPATCH_UNRESOLVED),
        ))
        assertEquals(2, result.methodDiagnostics.getValue(caller.identity).size)
        assertEquals(setOf(caller.identity), result.incompleteMethods)
    }

    private fun inspect(
        completeness: AnalysisCompleteness, metadataOnlyMultiRelease: (String) -> Boolean = { false },
    ): GraphiteCompleteness {
        val builder = DefaultGraph.Builder()
        builder.addMethod(caller)
        builder.addMethod(callee)
        builder.addNode(CallSiteNode(NodeId(1), caller, callee, null, null, emptyList(), 0, null))
        builder.addNode(ReturnNode(NodeId(2), caller))
        builder.setAnalysisCompleteness(completeness)
        return GraphiteCompleteness(builder.build(), metadataOnlyMultiRelease = metadataOnlyMultiRelease)
    }
}

class GraphiteObservationCompletenessTest {
    private val caller = MethodDescriptor(TypeDescriptor("Application"), "run", emptyList(), TypeDescriptor("void"))
    private val callee = MethodDescriptor(TypeDescriptor("Dependency"), "run", emptyList(), TypeDescriptor("void"))
    private val applicationOwners = setOf("Application")
    private val evidence = AnalysisCompleteness(
        AnalysisIdentity(includePackages = listOf("Application"), dependencyReachable = 1),
        mapOf("application.jar" to ArtifactEvidence(ArtifactRole.APPLICATION),
            "dependency.jar" to ArtifactEvidence(ArtifactRole.LIBRARY)),
        mapOf("Application" to ClassEvidence("application.jar", AnalysisStatus.ANALYZED),
            "Dependency" to ClassEvidence("dependency.jar", AnalysisStatus.ANALYZED)),
        emptyMap(),
    )
    private val application = TestReceipt(JvmMethodIdentity.of(caller))
    private val dependency = TestReceipt(JvmMethodIdentity.of(callee), origin = "dependency.jar",
        role = ArtifactRole.LIBRARY, application = false)
    private val receipts = mapOf(caller.identity to application, callee.identity to dependency)

    @Test fun `same load receipts reconcile without promoting a dependency to an application`() {
        val result = inspect()
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertTrue(result.incompleteMethods.isEmpty())
    }

    @Test fun `missing graph method receipt is blocking`() {
        val result = inspect(observed = receipts - callee.identity)
        assertEquals(listOf("Graphite has no observation receipt for declared method: ${callee.identity}"), result.diagnostics)
    }

    @Test fun `receipt identity cannot be substituted for another declaration`() {
        val result = inspect(observed = receipts + (caller.identity to dependency))
        assertTrue(result.diagnostics.any { "identity mismatch" in it && caller.identity in it })
    }

    @Test fun `unrecognized receipt cannot claim an analyzed body`() {
        val identity = JvmMethodIdentity("Dependency", "invented", "()V")
        val result = inspect(observed = receipts + (identity.identity to dependency.copy(method = identity)))
        assertTrue(result.diagnostics.any { "no matching declaration" in it && identity.identity in it })
    }

    @Test fun `local and final extraction status must agree`() {
        val result = inspect(observed = receipts + (caller.identity to application.copy(status = AnalysisStatus.INCOMPLETE)))
        assertTrue(result.diagnostics.any { "status mismatch" in it && "INCOMPLETE" in it && "ANALYZED" in it })
        assertEquals(setOf(caller.identity), result.incompleteMethods)
    }

    @Test fun `selected artifact origin and role must match final provenance`() {
        val wrongOrigin = inspect(observed = receipts + (caller.identity to application.copy(origin = "dependency.jar")))
        assertTrue(wrongOrigin.diagnostics.any { "origin mismatch" in it })
        val wrongRole = inspect(observed = receipts + (caller.identity to application.copy(role = ArtifactRole.LIBRARY)))
        assertTrue(wrongRole.diagnostics.any { "artifact role mismatch" in it })
    }

    @Test fun `successful observation needs declaration modifiers ownership and class evidence`() {
        val noModifiers = inspect(observed = receipts + (caller.identity to application.copy(modifiers = null)))
        assertTrue(noModifiers.diagnostics.any { "incomplete declaration metadata" in it })
        val noOwnership = inspect(observed = receipts + (caller.identity to application.copy(application = null)))
        assertTrue(noOwnership.diagnostics.any { "incomplete declaration metadata" in it })
        val noClass = inspect(completeness = evidence.copy(classes = evidence.classes - "Application"))
        assertTrue(noClass.diagnostics.any { "incomplete declaration metadata" in it })
    }

    @Test fun `application classification is checked against the independent inventory`() {
        val result = inspect(observed = mapOf(
            caller.identity to application.copy(application = false),
            callee.identity to dependency.copy(application = true),
        ))
        assertEquals(2, result.diagnostics.count { "application ownership mismatch" in it })
    }

    @Test fun `an absent independent inventory does not fabricate ownership certainty`() {
        val result = inspect(owners = null)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test fun `unknown origin is retained when the final class has the same unknown origin`() {
        val result = inspect(
            completeness = evidence.copy(classes = evidence.classes +
                ("Dependency" to ClassEvidence(null, AnalysisStatus.ANALYZED))),
            observed = receipts + (callee.identity to dependency.copy(origin = null, role = null)),
        )
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
    }

    @Test fun `failed declaration receipt can lack modifiers and application classification`() {
        val identity = JvmMethodIdentity("Dependency", "broken", "()V")
        val failed = dependency.copy(method = identity, status = AnalysisStatus.RESOLUTION_FAILED,
            modifiers = null, application = null, message = "declaration could not be read")
        val result = inspect(
            completeness = evidence.copy(methods = mapOf(identity.identity to
                MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, failed.message))),
            observed = receipts + (identity.identity to failed),
        )
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(setOf(identity.identity), result.incompleteMethods)
    }

    @Test fun `failed declaration must not disappear from the observation receipts`() {
        val identity = "Dependency.broken()V"
        val result = inspect(completeness = evidence.copy(methods = mapOf(identity to
            MethodEvidence(AnalysisStatus.RESOLUTION_FAILED))))
        assertTrue(result.diagnostics.any { "no observation receipt" in it && identity in it })
    }

    @Test fun `recovered interception remains a method gap despite complete local graph extraction`() {
        val result = inspect(observed = receipts + (callee.identity to dependency.copy(
            recovery = BodyInterceptionEvidence("fallback", "requested fold pipeline failed"),
        )))
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(setOf(callee.identity), result.incompleteMethods)
        val gap = result.methodDiagnostics.getValue(callee.identity).single()
        assertTrue("fallback" in gap.message && "requested fold pipeline failed" in gap.message)
    }

    private fun inspect(
        completeness: AnalysisCompleteness = evidence,
        observed: Map<String, MethodObservationEvidence> = receipts,
        owners: Set<String>? = applicationOwners,
    ): GraphiteCompleteness {
        val builder = DefaultGraph.Builder()
        builder.addMethod(caller)
        builder.addMethod(callee)
        builder.addNode(ReturnNode(NodeId(1), caller))
        builder.addNode(ReturnNode(NodeId(2), callee))
        builder.setAnalysisCompleteness(completeness)
        return GraphiteCompleteness(builder.build(), observed, owners)
    }
}

class GraphiteResourceCompletenessTest {
    private val caller = MethodDescriptor(TypeDescriptor("Application"), "run", emptyList(), TypeDescriptor("void"))
    private val modeled = MethodDescriptor(TypeDescriptor("Modeled"), "read", emptyList(), TypeDescriptor("void"))
    private val unmodeled = MethodDescriptor(TypeDescriptor("Unknown"), "read", emptyList(), TypeDescriptor("void"))
    private val evidence = AnalysisCompleteness(
        AnalysisIdentity(includePackages = listOf("Application")),
        mapOf("application.jar" to ArtifactEvidence(ArtifactRole.APPLICATION)),
        mapOf("Application" to ClassEvidence("application.jar", AnalysisStatus.ANALYZED)),
        emptyMap(),
    )

    @ParameterizedTest
    @EnumSource(CallSiteGap::class)
    fun `a modeled direct dispatch is discharged only for resource analysis`(reason: CallSiteGap) {
        val result = inspect(evidence.copy(callSiteGaps = mapOf(1 to reason)), modeledCall = { it.callee == modeled })
        val checked = mutableListOf<CallSiteNode>()
        assertTrue(result.incompleteResourceMethods { site ->
            checked += site
            site.callee == modeled && site.ordinal == 0
        }.isEmpty())
        assertEquals(listOf(NodeId(1)), checked.map { it.id })
        assertEquals(setOf(caller.identity), result.incompleteMethods)
        assertTrue(reason.name in result.methodDiagnostics.getValue(caller.identity).single().message)
        assertTrue(result.methodDiagnostics.getValue(caller.identity).single().resourceEffectModeled)
    }

    @Test fun `an unmodeled dispatch remains unknown`() {
        val result = inspect(evidence.copy(callSiteGaps = mapOf(2 to CallSiteGap.DISPATCH_UNRESOLVED)))
        assertEquals(setOf(caller.identity), result.incompleteResourceMethods { it.callee == modeled })
    }

    @Test fun `one modeled call cannot erase another dispatch gap in the same caller`() {
        val result = inspect(evidence.copy(callSiteGaps = mapOf(
            1 to CallSiteGap.DISPATCH_UNRESOLVED,
            2 to CallSiteGap.DISPATCH_PARTIAL,
        )), modeledCall = { it.callee == modeled })
        assertEquals(setOf(caller.identity), result.incompleteResourceMethods { it.callee == modeled })
        assertEquals(listOf(false, true), result.methodDiagnostics.getValue(caller.identity).map { it.resourceEffectModeled }.sorted())
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = [
        "ANALYZED_WITHOUT_CONTROL_FLOW", "NATIVE", "NO_BODY", "INCOMPLETE", "RESOLUTION_FAILED",
    ])
    fun `resource dispatch contracts cannot discharge any unproved body status`(status: AnalysisStatus) {
        val result = inspect(evidence.copy(
            methods = mapOf(caller.identity to MethodEvidence(status)),
            callSiteGaps = mapOf(1 to CallSiteGap.DISPATCH_UNRESOLVED),
        ), modeledCall = { true })
        assertEquals(setOf(caller.identity), result.incompleteResourceMethods { true })
        assertEquals(listOf(false, true), result.methodDiagnostics.getValue(caller.identity).map { it.resourceEffectModeled }.sorted())
    }

    @Test fun `resource dispatch contracts cannot discharge recovered interception`() {
        val receipt = TestReceipt(JvmMethodIdentity.of(caller),
            recovery = BodyInterceptionEvidence("fallback", "original interception failed"))
        val result = inspect(evidence.copy(callSiteGaps = mapOf(1 to CallSiteGap.DISPATCH_UNRESOLVED)), receipt) { true }
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        assertEquals(setOf(caller.identity), result.incompleteResourceMethods { true })
        assertEquals(listOf(false, true), result.methodDiagnostics.getValue(caller.identity).map { it.resourceEffectModeled }.sorted())
    }

    @Test fun `malformed call site evidence remains globally blocking despite an accepting model`() {
        val result = inspect(evidence.copy(callSiteGaps = mapOf(99 to CallSiteGap.DISPATCH_UNRESOLVED)))
        var consulted = false
        assertTrue(result.incompleteResourceMethods { consulted = true; true }.isEmpty())
        assertEquals(false, consulted)
        assertTrue("unavailable call site" in result.diagnostics.single())
    }

    private fun inspect(
        completeness: AnalysisCompleteness, receipt: TestReceipt? = null, modeledCall: (CallSiteNode) -> Boolean = { false },
    ): GraphiteCompleteness {
        val builder = DefaultGraph.Builder()
        builder.addMethod(caller)
        builder.addNode(CallSiteNode(NodeId(1), caller, modeled, null, null, emptyList(), 0, null))
        builder.addNode(CallSiteNode(NodeId(2), caller, unmodeled, null, null, emptyList(), 1, null))
        builder.setAnalysisCompleteness(completeness)
        return GraphiteCompleteness(builder.build(), receipt?.let { mapOf(caller.identity to it) }, modeledResourceCall = modeledCall)
    }
}

private data class TestReceipt(
    override val method: JvmMethodIdentity,
    override val modifiers: Set<MethodModifier>? = setOf(MethodModifier.PUBLIC),
    override val origin: String? = "application.jar",
    override val role: ArtifactRole? = ArtifactRole.APPLICATION,
    override val application: Boolean? = true,
    override val status: AnalysisStatus = AnalysisStatus.ANALYZED,
    override val message: String? = null,
    override val recovery: BodyInterceptionEvidence? = null,
    override val folding: MethodFoldObservation? = null,
) : MethodObservationEvidence
