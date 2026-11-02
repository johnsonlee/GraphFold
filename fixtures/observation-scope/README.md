# Observation scope

Each Java/Kotlin pair changes only `Example`; the unchanged `Observer` passes an
unknown object graph to the real JDK XMLEncoder and also declares an anonymous
callback. Controlled method edits must pass and surviving edits must produce a
diff-owned finding, despite that unrelated observer and stable anonymous owner.

Private callbacks, unread field values, explicit constructor writes, private
member renames and a folded anonymous-class addition retain blocking observation
gaps. These cover changes hidden by reachability, field normalization, member
alignment and stripped anonymous nesting metadata. Folding away the observer
itself permits the uncalled private-method change.

`ObservationScopeIntegrationTest` executes all sixteen cases through the engine.
Snapshot-level tests separately cover owner-map renumbering, added/removed maps,
whole-program/resource requests and direct caller/dependency gaps.
