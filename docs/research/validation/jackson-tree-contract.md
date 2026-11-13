# Default JSON tree observation contract

Scope: exact official Jackson annotations, core and databind 2.19.2 effective class families. All three packages are hashed, not only ObjectMapper/JsonNode. Dependency labels and manifests cannot establish this contract. Application shadows, duplicate definitions and altered bytes fail the gate. All runtime expression and exception behavior remains represented in IR. This contract excludes application-object observation by the four proven default tree operations; it does not assert purity or allow DTO deserialization, configured modules, injected subclasses or arbitrary callbacks.

Official Maven Central artifacts:
- https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.19.2/jackson-annotations-2.19.2.jar
  SHA256 e516743a316dcf83c572ffc9cb6e8c5e8c134880c8c5155b02f7b34e9c5dc3cf
- https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.19.2/jackson-core-2.19.2.jar
  SHA256 aa77eaf29293a868c47372194f7c5287d77d9370b04ea25d3fffc1e4904b5880
- https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.19.2/jackson-databind-2.19.2.jar
  SHA256 0a1bd4e9b0d670e632d40ee8c625ad376233502f03c2f5889baea95d025b47a7

Reproducible independent derivation (Python 3; downloads only official artifacts):

```python
from urllib.request import urlopen
import hashlib, io, struct, zipfile
modules = ['jackson-annotations', 'jackson-core', 'jackson-databind']
archives = []
for module in modules:
    url = f'https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/{module}/2.19.2/{module}-2.19.2.jar'
    data = urlopen(url).read()
    print(module, hashlib.sha256(data).hexdigest())
    archives.append(zipfile.ZipFile(io.BytesIO(data)))
for feature in [8, 9, 11, 17, 21]:
    classes = {}
    for archive in archives:
        for name in archive.namelist():
            if name.endswith('.class') and not name.startswith('META-INF/') and name != 'module-info.class':
                classes[name] = archive.read(name)
        # Both versioned artifacts declare Multi-Release: true; variants replace logical root names.
        for version in range(9, feature + 1):
            prefix = f'META-INF/versions/{version}/'
            for name in archive.namelist():
                if name.startswith(prefix) and name.endswith('.class') and name[len(prefix):] != 'module-info.class':
                    classes[name[len(prefix):]] = archive.read(name)
    digest = hashlib.sha256()
    for name, value in sorted(classes.items()):
        for payload in [name.encode('utf-8'), value]:
            digest.update(struct.pack('>I', len(payload)))
            digest.update(payload)
    print(feature, len(classes), digest.hexdigest())
```

Output has 1091 classes in each family image:
- 8/9: 610260a760adef1ec35b75da079b75fdbe52cbc398ae7767377d29045bf5e150
- 11: 65bf4ed5ab474f9ccd417d7722ed1bb6d6b1a6f4b161c13b52f76382643a03d8
- 17: 75bd21347accabccc33d7d7c2447af949390d84a4146838a249fe14d4055d7e3
- 21: a47edf4622f48185dd2c8e6db07ff777c2a3d350fbbc6004097d63172a17e82f

These are class-content identities, not claimed coverage across JVM runtimes. Actual engine JarFile version selection determines which bytes enter its effective classpath. The gate accepts this verified identity set after runtime selection; it does not infer trusted bytes from a runtime-version label. This preserves byte-level identity checking without claiming validation on every JVM runtime.
