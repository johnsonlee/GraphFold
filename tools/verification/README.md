# Maven lifecycle verification

`verify_maven_lifecycle.py` runs the published GraphFold Maven plugin through actual
Maven 3.9.9 package, snapshot and check goals on JDK 17. The Maven distribution comes
from Maven Central and must match its pinned SHA-512 before extraction.

Three committed fixture repositories exercise ordinary library JARs, Spring Boot
3.5.7 repackaged JARs, and WARs containing a servlet class and a packaged dependency.
Each captures BASE, then checks a controlled price edit and an uncontrolled fallback
edit against BASE. The business code uses an exposure condition before a Flagr-style
control. The expected verdict follows that static control coverage; no application,
HTTP endpoint, Flagr service or Tomcat container is started.

A fourth repository is a normal two-module Maven reactor. Both modules use the
same business class names with different BASE policies. One shared snapshot
root retains both module manifests; a shared BASE directory must pair each HEAD
with its own module. Both controlled edits must pass, then an uncontrolled edit
in the second module must fail while the first still passes. The checks also use
one shared report destination and require two distinct module reports. Baseline
snapshot bytes must remain unchanged. These four module verdicts supplement the
six standalone JAR/Boot/WAR cases.

The gate checks actual archive paths, nested dependencies, JDK evidence, source
associations, both rule selections, proved obligations, and source locations.
A failed Maven command alone does not establish detection: the negative must produce
a complete FAIL report with a finding on the edited business source. Diagnostics or
missing reports fail verification.

This harness builds and exports both revisions itself. In a production gate, both
BASE and HEAD snapshots must likewise come from trusted, controlled build jobs and
protected artifact storage. Do not substitute bundles supplied with a PR: hashes
stored in their own manifest detect corruption, not an attacker replacing both
the assets and their hashes. See the [snapshot trust boundary](../../README.md#snapshot-trust-boundary).

Publish `gfc`, `engine` and `graphfold-maven-plugin` to a local file repository first, using one shared
version. For the repository's `releaseVerification` publication repository:

```sh
./gradlew -Pversion=0.0.0-lifecycle \
  :gfc:publishAllPublicationsToReleaseVerificationRepository \
  :engine:publishAllPublicationsToReleaseVerificationRepository \
  :plugin:maven:publishAllPublicationsToReleaseVerificationRepository
python3 tools/verification/verify_maven_lifecycle.py \
  --publication-repository build/repository \
  --version 0.0.0-lifecycle \
  --output /tmp/graphfold-maven-lifecycle
```

The output directory must be new. It holds downloaded Maven, an isolated Maven
artifact cache, fixture checkouts, immutable snapshots, per-layout Maven logs,
controlled/uncontrolled JSON reports and `summary.json`. Keep these generated files
outside the PR. CI can upload this directory as a verification artifact.

Maven must resolve plugin dependencies, including Graphite, from Maven Central and
GitHub Packages. Both normal repositories and plugin repositories use the same
`graphitePackages` server ID. The generated settings contain only environment
references to `GITHUB_ACTOR` and `GITHUB_TOKEN`; the script never prints token values
or resolved settings. Locally use the authorized `johnsonlee` identity; CI uses its
workflow identity and a package-readable token. This harness does not obtain or
switch GitHub credentials. It is verification tooling, not production engine code.

The acceptance assertions are also checked by
`.github/scripts/test_maven_lifecycle.py`, which is part of the existing Python
workflow-test discovery.

For an existing dependency cache, `--seed-repository /path/to/maven-layout-cache`
copies those exact files into the isolated Maven cache and records their SHA-256
hashes in the output summary. This supports reuse of downloaded publication bytes
when fresh registry access is unavailable; it does not rebuild or replace dependencies.
Keep such caches outside the release publication directory and source tree.
