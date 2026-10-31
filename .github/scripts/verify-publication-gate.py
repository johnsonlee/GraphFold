"""Exercise the upload guard using isolated signed publications and a local Sonatype stand-in."""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / ".github/scripts/verified-publication.gradle"


def run(command, cwd, expected_success=True):
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=180)
    if (result.returncode == 0) != expected_success:
        raise AssertionError(result.stdout + result.stderr)
    return result.stdout + result.stderr


with tempfile.TemporaryDirectory(prefix="gfpub-", dir="/tmp") as temporary:
    fixture = Path(temporary)
    keys = fixture / "keys"
    keys.mkdir(mode=0o700)
    gpg = ["gpg", "--batch", "--homedir", str(keys)]
    run(gpg + ["--pinentry-mode", "loopback", "--passphrase", "", "--quick-generate-key",
               "Release Fixture <release-fixture@example.invalid>", "rsa2048", "sign", "0"], fixture)
    secret = subprocess.check_output(gpg + ["--armor", "--export-secret-keys"], stderr=subprocess.DEVNULL)
    (fixture / "fixture-key.asc").write_bytes(secret)
    (fixture / "settings.gradle").write_text("rootProject.name = 'fixture'; include('publisher')\n")
    (fixture / "publisher/src/main/java").mkdir(parents=True)
    (fixture / "publisher/src/main/java/Example.java").write_text("public class Example {}\n")
    (fixture / "publisher/build.gradle").write_text('''
plugins { id 'java-library'; id 'java-test-fixtures'; id 'maven-publish'; id 'signing' }
group = 'fixture.release'
version = '1.0'
java { withSourcesJar(); withJavadocJar() }
tasks.withType(Javadoc).configureEach { options.addBooleanOption('notimestamp', true) }
components.java.withVariantsFromConfiguration(configurations.testFixturesApiElements) { skip() }
components.java.withVariantsFromConfiguration(configurations.testFixturesRuntimeElements) { skip() }
tasks.withType(AbstractArchiveTask).configureEach {
    preserveFileTimestamps = false
    reproducibleFileOrder = true
}
tasks.withType(org.gradle.api.publish.tasks.GenerateModuleMetadata).configureEach {
    inputs.property('alterMetadata', providers.gradleProperty('alterMetadata').orElse('false'))
    doLast { if (project.hasProperty('alterMetadata')) outputFile.get().asFile.append(' ') }
}
jar {
    inputs.property('alterJar', providers.gradleProperty('alterJar').orElse('false'))
    doLast { if (project.hasProperty('alterJar')) archiveFile.get().asFile.append('changed after packaging') }
}
publishing {
    publications {
        demoTest(MavenPublication) {
            artifactId = 'fixture'
            from components.java
            pom { description = project.hasProperty('alterPom') ? 'changed' : 'verified' }
        }
        marker(MavenPublication) { artifactId = 'fixture.marker' }
    }
    repositories {
        maven { name = 'verified'; url = rootProject.layout.projectDirectory.dir('verified') }
        maven { name = 'sonatype'; url = rootProject.layout.projectDirectory.dir('uploaded') }
    }
}
if (project.hasProperty('signed')) {
    signing { useInMemoryPgpKeys(rootProject.file('fixture-key.asc').text, ''); sign publishing.publications }
}
''')
    gradle = [str(ROOT / "gradlew"), "-p", str(fixture), "--console=plain", "--offline"]
    run(gradle + ["publishAllPublicationsToVerifiedRepository"], fixture)
    guarded = gradle + ["publishAllPublicationsToSonatypeRepository", "-Psigned",
                        "--init-script", str(GATE), "-Dgraphfold.verifiedRepository=" + str(fixture / "verified")]
    run(gradle + ["clean"] + guarded[len(gradle):], fixture)
    uploaded = fixture / "uploaded"
    payloads = [p for p in uploaded.rglob('*') if p.suffix in ('.jar', '.pom', '.module')]
    assert payloads, "No primary artifacts uploaded"
    assert not any('test-fixtures' in p.name for p in payloads), "Test fixture artifact leaked into publication"
    for payload in payloads:
        expected = fixture / "verified" / payload.relative_to(uploaded)
        assert payload.read_bytes() == expected.read_bytes(), payload
        signature = Path(str(payload) + '.asc')
        assert signature.is_file(), f"Missing signature for {payload}"
        run(gpg + ['--verify', str(signature), str(payload)], fixture)
    for mutation in ('alterJar', 'alterPom', 'alterMetadata'):
        if uploaded.exists():
            shutil.rmtree(uploaded)
        log = run(guarded + ['-P' + mutation], fixture, expected_success=False)
        assert 'Upload differs from verified release' in log, log
        implementation = uploaded / 'fixture/release/fixture/1.0'
        assert not implementation.exists(), "Mismatched implementation was uploaded"
    print("Publication gate: matching signed payloads accepted; altered JAR, POM and module metadata blocked before upload; fixtures excluded")
