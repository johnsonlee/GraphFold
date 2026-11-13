# Project JAR dependencies

Both variants explicitly select a sibling library's JAR variant while exporting
compiler class directories for source attribution:

- `build.gradle` retains the application-plus-library case. The selected application
  JAR and its declared library dependency form one static archive scope.
- `library-only.gradle` applies only `java-library`. The two published library JARs
  form separate static archive scopes without applying the application plugin.
  The consumer scope includes its declared sibling JAR.

`ProjectJarDependencyTest` checks the scope identities, packaged resources and exact
class sets, and verifies that producer classes and compiler directories are not
added again as graph dependencies. Resource-only dependency JARs remain available.
Both variants cover controlled and surviving Java changes and folded/live ResourceBundle keys. Compiler outputs supply provenance
only; selected packaged bytes determine the graph inputs.
