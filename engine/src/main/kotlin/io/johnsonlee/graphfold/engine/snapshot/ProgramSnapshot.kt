package io.johnsonlee.graphfold.engine.snapshot


import sootup.core.model.MethodModifier

/**
 * Static evidence for comparing production declarations after folding explicitly selected controls.
 * Class names use dotted JVM binary names; method identities append a method name and JVM descriptor.
 *
 * @property methods Application methods captured after folding, keyed by JVM method identity.
 * @property declarations Canonical class and method metadata keyed by class or method identity, before folding.
 * @property diagnostics Analysis gaps that callers must account for before declaring changes controlled.
 * @property matchedRules Number of fold matches reported for each applied rule, in rule order.
 * @property fields Field metadata keyed by dotted owner, field name, and colon-prefixed JVM descriptor.
 * @property classShapes Canonical class metadata excluding fields, for independent member comparison.
 * @property ignoredFields Unread fields eligible for private-field normalization under the inventory's restrictions.
 * @property entryClasses Candidate public application entry classes; null means no explicit entry-class inventory.
 * @property externalEntries Method identities that annotation-based discovery may invoke externally.
 * @property initializationDependencies Superclasses and superinterfaces declaring concrete instance methods initialized with each class.
 * @property initializationTriggerMethods Exact non-abstract, non-static interface method identities requiring
 * interface initialization when an implementing class initializes, including private methods.
 * @property overrides Method identities implementing or overriding inherited methods.
 * @property enclosingClasses Nested-class owner to enclosing-class owner mapping.
 * @property methodDiagnostics Method-local analysis gaps, checked against the folded change dependency closure.
 * @property resourceDependenciesAnalyzed Whether resource effects include the supplied dependency bodies and initialization
 * metadata without frontend or inventory failures. Unresolved calls still contribute wildcard resource reads.
 * @property originalOwners Normalized owner to original JVM owner, preserving rename and nested-type inventory differences.
 * @property resourceEntryReads Resource effects of inherited APIs and exposed fields, keyed by application owner.
 * Consumers activate these only for existing public entry classes or owners reached by the folded program; dependency
 * classes and newly added unused application types do not become entry points merely by appearing in this map.
 */
data class ProgramSnapshot(
    val methods: Map<String, MethodSnapshot>,
    val declarations: Map<String, String>,
    val diagnostics: List<String>,
    val matchedRules: List<Int>,
    val fields: Map<String, FieldSnapshot> = emptyMap(),
    val classShapes: Map<String, String> = emptyMap(),
    val ignoredFields: Set<String> = emptySet(),
    val entryClasses: Set<String>? = null,
    val externalEntries: Set<String> = emptySet(),
    val initializationDependencies: Map<String, Set<String>> = emptyMap(),
    val overrides: Set<String> = emptySet(),
    val enclosingClasses: Map<String, String> = emptyMap(),
    val methodDiagnostics: Map<String, List<MethodAnalysisGap>> = emptyMap(),
    val resourceDependenciesAnalyzed: Boolean = false,
    val resourceEntryReads: Map<String, List<ResourceRead>> = emptyMap(),
    val originalOwners: Map<String, String> = emptyMap(),
    val initializationTriggerMethods: Set<String> = emptySet(),
    /** Same-revision pre-fold minus post-fold reachability, with identical roots, positive controls and complete evidence. */
    val controlRemovedMethods: Set<String> = emptySet(),
    /** Class metadata split only at explicit nested-type ownership records, for certified control removal. */
    val structuralClassShapes: Map<String, StructuralClassShape> = emptyMap(),
)

/**
 * Folded method body and call evidence, retaining separate references for safe member-rename alignment.
 *
 * @property identity Dotted JVM binary owner and method name followed by the JVM method descriptor.
 * @property canonical Canonical modifiers and folded IR, with stable local and statement numbering.
 * @property calls Possible callee method identities, including dispatch targets supplied by the graph.
 * @property modifiers JVM method modifiers captured by the frontend.
 * @property lines Positive source line numbers attached to the surviving method statements.
 * @property resourceReads Resource accesses surviving folding; unknown paths or keys conservatively denote any matching resource.
 * @property referenceCanonical Canonical IR with member references replaced by indexed placeholders.
 * @property references Member identities in placeholder order in [referenceCanonical].
 * @property dynamicCalls Surviving invokedynamic names followed by JVM descriptors, before callback dispatch expansion.
 */
data class MethodSnapshot(
    val identity: String,
    val canonical: String,
    val calls: Set<String>,
    val modifiers: Set<MethodModifier>,
    val lines: Set<Int>,
    val referenceCanonical: String = canonical,
    val references: List<String> = emptyList(),
    val resourceReads: List<ResourceRead> = emptyList(),
    val dynamicCalls: Set<String> = emptySet(),
    /** Implementation handles of surviving, validated standard lambda factories; not invocation edges. */
    val lambdaTargets: Set<String> = emptySet(),
)

/**
 * A field's identity is separate from metadata, so rename alignment cannot rewrite constants or annotations.
 *
 * @property identity Dotted JVM binary owner and field name followed by a colon and JVM field descriptor.
 * @property declaration Canonical type, modifiers, signature, and annotation metadata, excluding name and constant value.
 * @property constant Canonical class-file constant value, including the representation of an absent value.
 * @property renameSafe Whether inventory restrictions permit considering this field for private rename alignment.
 */
data class FieldSnapshot(
    val identity: String,
    val declaration: String,
    val constant: String,
    val renameSafe: Boolean,
)

/**
 * A surviving resource observation in a folded method.
 *
 * @property bundle Slash-separated ResourceBundle base name, or null when the loader/path is unknown.
 * @property key Constant properties key, or null when the complete resource can be observed.
 */
data class ResourceRead(val bundle: String?, val key: String?)

/**
 * A gap in one method, retained separately from artifact-wide inventory failures.
 *
 * @property message Human-readable evidence explaining the missing model.
 * @property callee Dotted JVM callee identity; a gap is inactive when folding removes all calls to it.
 * @property globalObservation Whether an opaque access can discover arbitrary application declarations without call edges.
 * @property observedOwners Application classes whose state or runtime names may be observed by this operation.
 * @property requiresObservedFrame Whether the operation must execute below an observed owner on the current call stack.
 * @property dynamicCall Invokedynamic name and JVM descriptor; inactive when folding removes every matching factory call.
 */
data class MethodAnalysisGap(
    val message: String,
    val callee: String? = null,
    val globalObservation: Boolean = false,
    val observedOwners: Set<String> = emptySet(),
    val requiresObservedFrame: Boolean = false,
    val dynamicCall: String? = null,
    /** The existing resource-effect contract covers this dispatch gap, without proving structural call targets. */
    val resourceEffectModeled: Boolean = false,
    /** Missing dispatch targets only; body extraction, recovery and observation gaps must not set this flag. */
    val dispatchOnly: Boolean = false,
    /** Unknown external observation semantics, not missing application bytecode or folded method observations. */
    val externalObservationOnly: Boolean = false,
)

/** All non-nesting metadata remains literal; each nesting record retains its referenced JVM owner. */
data class StructuralClassShape(val core: String, val nestedTypes: List<Pair<String, String>>)
