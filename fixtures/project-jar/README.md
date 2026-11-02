# Project JAR dependencies

The app explicitly selects its sibling library's JAR variant. Both modules also
export application class directories. `ProjectJarDependencyTest` verifies the
actual Gradle manifest contains this overlap, that equivalent class definitions
are supplied to Graphite only once, and that production inventories retain the
original packaged resources and class placements.

Engine pairs cover controlled and surviving Java changes and folded/live
ResourceBundle keys. Resource-mode graph expansion must not reintroduce the
project JAR after compile-classpath deduplication. Independent adapter tests keep
unknown, transformed or additional archive classes from being silently dropped.
