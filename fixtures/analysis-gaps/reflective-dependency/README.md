# Reflective dependency boundary

An external Encoder jar reflects private field names and values. Application code only
calls Encoder.encode(this); it never directly references java.lang.reflect or reads its
private field. Renaming amount to offset changes runtime output. The analyzer must
either retain a surviving difference or report an analysis gap. A diagnostic is not
a successful positive in corpus precision/recall. The standalone snapshot test compiles
this local jar, verifies both outputs, then checks that analysis cannot prove equivalence.

The Kotlin counterpart under kotlin/ uses the same Java dependency jar; its private
property is renamed and the library observes the changed backing-field name. Both
language fixtures execute through ReflectiveDependencyIntegrationTest.
