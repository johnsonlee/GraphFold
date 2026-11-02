# Default-superinterface initialization

An existing production Example implements Marker. Marker declares a real JVM default
method and a static field initialized by initialize(). Initializing Example triggers
Marker initialization even though application code never invokes Marker.value or
reads Marker.TOKEN. Changing initializer output must fail.

The Kotlin case deliberately uses the same Java interface to ensure real JVM default
method semantics independently of Kotlin compiler DefaultImpls/jvm-default modes.
InitializationIntegrationTest loads checked-in base/head trees, creates real commits,
and asserts detection without analysis diagnostics and exact changed-file provenance.

The added-default cases keep Marker.initialize unchanged. Initially Marker declares
no default method, so initializing Example leaves Marker dormant. Adding an otherwise
uncalled default method changes JVM initialization prerequisites and causes Marker
to print. Tests assert the newly added declaration at head line 5 and independently
instantiate Example in isolated class loaders: base emits nothing; head emits marker.
