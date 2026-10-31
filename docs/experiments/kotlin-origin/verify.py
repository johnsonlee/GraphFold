from pathlib import Path
import subprocess,os,zipfile,json,hashlib,tempfile,shutil
root=Path.home()/'.gradle/caches/modules-2/files-2.1'
repo=Path(__file__).resolve().parents[3]
d=Path(tempfile.mkdtemp(prefix='graphfold-kotlin-origin-'))
shutil.copyfile(Path(__file__).with_name('Example.kt'),d/'Example.kt')
print('ARTIFACTS',d,flush=True)
def classpath(version):
 coords=[('org.jetbrains.kotlin','kotlin-compiler-embeddable',version),('org.jetbrains.kotlin','kotlin-stdlib',version),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0')]
 return os.pathsep.join(str(next(p for p in (root/g/a/v).glob('*/*.jar') if not p.name.endswith('-sources.jar'))) for g,a,v in coords)
cp=classpath('2.1.20');compiled=d/'production-plugin'
compiled.mkdir()
protocol=repo/'engine/src/main/java/io/johnsonlee/graphfold/engine/origin/protocol'
subprocess.run(['javac','--release','17','-d',str(compiled)]+list(map(str,protocol.glob('*.java'))),check=True)
source=repo/'engine/src/main/kotlin/io/johnsonlee/graphfold/engine/origin/kotlin'
subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp+os.pathsep+str(compiled),'-jvm-target','17','-d',str(compiled)]+list(map(str,source.glob('*.kt'))),check=True)
plugin=d/'production-plugin.jar'
with zipfile.ZipFile(plugin,'w') as z:
 for p in compiled.rglob('*'):
  if p.is_file():z.write(p,p.relative_to(compiled))
 prefix='io.johnsonlee.graphfold.engine.origin.kotlin.'
 z.writestr('META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar',prefix+'KotlinOriginRegistrar\n')
 z.writestr('META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor',prefix+'KotlinOriginCommandLineProcessor\n')
print('SINGLE_PLUGIN_SHA256',hashlib.sha256(plugin.read_bytes()).hexdigest())
source_text=(d/'Example.kt').read_bytes().decode('utf-8')
for version,language in [('1.9.20','1.9'),('2.0.21','2.0'),('2.1.20','2.1'),('2.2.10','2.2'),('2.1.20','1.9')]:
 reference=None
 for label,ending,bom in [('LF','\n',''),('CRLF','\r\n',''),('CR','\r',''),('BOM-CRLF','\r\n','\ufeff')]:
  (d/'Example.kt').write_bytes((bom+source_text.replace('\n',ending)).encode('utf-8'))
  cp=classpath(version);out=d/('production-'+version+'-'+language+'-'+label);out.mkdir();record=out/'origins';record.mkdir()
  result=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-jvm-target','17','-language-version',language,'-Xplugin='+str(plugin),'-P','plugin:io.johnsonlee.graphfold.origin.kotlin:output='+str(record),'-P','plugin:io.johnsonlee.graphfold.origin.kotlin:runId=matrix-run','-d',str(out/'classes'),str(d/'Example.kt')],capture_output=True,text=True)
  assert result.returncode == 0, result.stderr
  data=json.loads(next(record.glob('*.json')).read_text());assert data['complete'];assert not data['unresolved'],data['unresolved']
  assert data['producer']['frontend']==('K1' if language=='1.9' else 'K2')
  actual={r['owner']:r for r in data['records']};assert len(actual)==len(data['records'])==15
  emitted={r['owner']:r for r in data['outputs']}
  class_owners={str(p.relative_to(out/'classes')).removesuffix('.class').replace(os.sep,'.') for p in (out/'classes').rglob('*.class')}
  assert len(emitted)==len(data['outputs']) and emitted.keys()==class_owners,(version,language,label,emitted.keys(),class_owners)
  for output in emitted.values():
   assert output['sources'],output
   for source in output['sources']:
    assert Path(source['path']).resolve()==(d/'Example.kt').resolve(),source
    assert source['hash']==hashlib.sha256(Path(source['path']).read_bytes()).hexdigest(),source
  if reference is None: reference=actual
  assert actual.keys()==reference.keys()
  copies=[r for r in data['records'] if r['provenance']=='INLINE_COPY'];assert len(copies)==1,copies
  assert not any(r['owner'].endswith('$run$action$1') and r['originKind']=='ANONYMOUS_OBJECT' for r in data['records'])
  for r in data['records']:
   # read_text() translates CRLF and would conceal the physical-coordinate bug.
   raw=Path(r['source']).read_bytes();text=raw.decode('utf-8')
   for kind in ['allocation','declaration']:
    expected={}
    for key,value in reference[r['owner']][kind].items():
     prefix=source_text.encode('utf-16-le')[:value*2].decode('utf-16-le')
     expected[key]=len((bom+prefix.replace('\n',ending)).encode('utf-16-le'))//2
    assert r[kind]==expected,(version,language,label,r['owner'],r[kind],expected)
   span=r['allocation'];substr=text.encode('utf-16-le')[span['start']*2:span['end']*2].decode('utf-16-le')
   if r['owner'].endswith('$first$1') or r['owner'].endswith('$second$1') or r['provenance']=='INLINE_COPY':assert substr.startswith('object : Callback'),substr
   assert r['sourceHash']==hashlib.sha256(raw).hexdigest()
   assert (out/'classes'/(r['owner'].replace('.','/')+'.class')).is_file()
  print(version,language,label,data['producer'],'records',len(data['records']),'unresolved',len(data['unresolved']),'physical UTF16 ranges verified; emitted class/source ownership verified',len(emitted),flush=True)

# Version rejection is a compiler invocation, using the same plugin binary.
cp=classpath('1.9.0')
rejected=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib','-no-reflect','-classpath',cp,'-Xplugin='+str(plugin),
    '-P','plugin:io.johnsonlee.graphfold.origin.kotlin:output='+str(d/'unsupported'),
    '-P','plugin:io.johnsonlee.graphfold.origin.kotlin:runId=matrix-run',
    '-d',str(d/'unsupported-classes'),str(d/'Example.kt')],capture_output=True,text=True)
assert rejected.returncode != 0
assert 'Unsupported GraphFold Kotlin provenance compiler' in rejected.stderr, rejected.stderr
print('1.9.0 rejected explicitly; plugin SHA256',hashlib.sha256(plugin.read_bytes()).hexdigest())
