# Static input boundaries

GraphFold answers whether production changes are covered by the selected controls.
It does not predict execution or prove runtime behavioral equivalence.

Engine owns Git fetch/checkout/diff, static analysis and the integration input
contract. It knows no build tool. Gradle plugin reads its current in-process model;
Maven plugin reads MavenProject/MavenSession. The plugins live in `plugin/gradle`
and `plugin/maven`. Each integration depends only on engine; the plugins never
depend on each other. Build-tool-independent archive decoding and snapshot storage
compile once in engine under `engine.inputs`; plugins consume that API.

Plugins export immutable revision input bundles during their normal build. Engine
resolves the recorded revisions and computes the diff. Source roots, output
associations and compiler classpaths come from the plugin model.
They improve attribution without replacing complete bytecode control-coverage proof.

- Gradle source sets, compiler instrumentation and archive tasks supply exact source
  ownership. Unrelated module outputs are never merged into the selected input.
- Engine input decoding extracts flat JAR, Spring Boot and WAR layouts before graph analysis.
  `Main-Class` and `Start-Class` may provide entrypoint evidence; neither is an
  admission requirement. No Tomcat installation or JVM startup compatibility is required.
- A library production JAR is a valid static input. When no application archive is
  selected, Gradle published production artifacts and Maven packaged library artifacts
  supply separate scopes, without requiring a launcher.
- Multi-release JARs select one view using the JDK actually selected by each build.
  BASE and HEAD retain their own build JDK metadata. Unselected versioned entries
  do not create extra graphs or affect the selected input inventory; the analyzer
  JVM does not override the build’s selection.
- Build-declared external API dependencies supplement types for their own input scope.
  They are distinct from application bytecode actually present in the selected archive.
- Content inventories retain class/resource identities, dependency order and source
  evidence. They express input differences, not deployment or container semantics.
- Missing source mappings do not remove application classes or prevent graph construction
  and folding. They reduce attribution and proof precision; invalid claimed provenance
  cannot authorize invented source alignment.
- External calls retain explicit observation gaps when static evidence is insufficient.
  Framework lifecycle assumptions cannot discharge those gaps. Literal application
  comparison can proceed independently of external observation semantics when it
  preserves declaration and method identities without opaque normalization or method
  omission. Surviving code differences still fail; missing bytecode and resource-proof
  uncertainty remain blockers.
- An uncalled method, unused lambda or unconsumed Stream cannot excuse an uncontrolled
  production edit. Removing a proof obligation requires evidence from the selected
  control, not runtime inactivity or equal observed output.

Runtime fixture observations are independent fixture checks, never the
control-coverage oracle. Earlier Jackson runtime-contract experiments do not define
this static-analysis boundary.
