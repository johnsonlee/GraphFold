package io.johnsonlee.graphfold.engine.graphite

import sootup.core.model.MethodModifier

/**
 * Structural evidence for a folded program; a representation difference alone is not proof of changed behavior.
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
 * @property initializationDependencies Classes and default-method interfaces initialized when each class initializes.
 * @property overrides Method identities implementing or overriding inherited methods.
 * @property enclosingClasses Nested-class owner to enclosing-class owner mapping.
 * @property methodDiagnostics Method-local analysis gaps, checked against the folded change dependency closure.
 * @property resourceDependenciesAnalyzed Whether resource effects include the supplied dependency bodies and initialization
 * metadata without frontend or inventory failures. Unresolved calls still contribute wildcard resource reads.
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
 * @property observedOwners Application classes whose private state may be inspected by a bounded opaque consumer.
 */
data class MethodAnalysisGap(
    val message: String,
    val callee: String? = null,
    val globalObservation: Boolean = false,
    val observedOwners: Set<String> = emptySet(),
)
