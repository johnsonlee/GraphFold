# Observation scope

Each Java/Kotlin pair changes only `Example`; the unchanged `Observer` passes an
unknown object graph to the real JDK XMLEncoder and also declares an anonymous
callback. Controlled method edits must pass and surviving edits must produce a
diff-owned finding, despite that unrelated observer and stable anonymous owner.

Private callbacks, unread field initializers and explicit constructor writes are
uncontrolled production changes. Their surviving method or constructor bodies
must produce precise findings without requiring a prediction of the unrelated
observer's runtime behavior. Folding away the observer does not control a change
to the separate, uncalled private method: that case also fails with a surviving
`Example.hidden()` difference.

Private member renames and a folded anonymous-class addition retain blocking
observation gaps because their equivalence proofs normalize member identities or
anonymous nesting metadata. The literal body comparison used by the other cases
does not erase those identities and cannot substitute for these additional proofs.

`ObservationScopeIntegrationTest` executes all sixteen cases through the engine.
Snapshot-level tests separately cover owner-map renumbering, added/removed maps,
whole-program/resource requests and direct caller/dependency gaps.
