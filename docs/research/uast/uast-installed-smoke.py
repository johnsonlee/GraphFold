#!/usr/bin/env python3
"""Exercise the installed UAST candidate CLI; native Git is used only to create test fixtures."""
from pathlib import Path
import argparse
import subprocess
import shutil
import json
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--candidate", type=Path, required=True, help="Isolated candidate checkout with cli:installDist built")
parser.add_argument("--output", type=Path, help="New directory for disposable fixture repositories and evidence")
args = parser.parse_args()
root = args.candidate.resolve()
if args.output:
    work = args.output.resolve()
    work.mkdir(parents=True, exist_ok=False)
else:
    work = Path(tempfile.mkdtemp(prefix="graphfold-uast-installed-smoke-"))
cli=root/'cli/build/install/graphfold/bin/graphfold'
help_result=subprocess.run([str(cli),'--help'],capture_output=True,text=True)
(work/'help.txt').write_text(help_result.stdout+help_result.stderr)
assert help_result.returncode==0 and '--repository' in help_result.stdout,help_result
cases=[('java','callback_lambda',False,4,74),('java','gated_callback_lambda',True,5,78),('kotlin','noncapturing_lambda',False,5,27),('kotlin','controlled_lambda',True,4,54)]
results=[]
for language,name,controlled,line,column in cases:
 repo=work/name
 repo.mkdir()
 def git(*args):
  return subprocess.check_output(['git','-c','core.hooksPath=/dev/null','-c','commit.gpgSign=false',*args],cwd=repo,text=True).strip()
 git('init','-q');git('config','user.name','Fixture');git('config','user.email','fixture@example.invalid')
 commits=[]
 for revision in ['base','head']:
  fixture=root/'fixtures/source-locations'/language/name/revision
  if language=='java':shutil.copytree(fixture,repo,dirs_exist_ok=True,copy_function=shutil.copyfile)
  else:
   target=repo/'src/main/kotlin/sample/Example.kt';target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(fixture/'Example.kt',target)
   target=repo/'src/main/java/sample/Ab.java';target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(root/'fixtures/common/Ab.java',target)
  git('add','.');git('commit','-qm',revision);commits.append(git('rev-parse','HEAD'))
 result=subprocess.run([str(cli),'--repository',str(repo),'--base',commits[0],'--head',commits[1],'--fold',str(root/'fixtures/fold.yml'),'--cache-dir',str(work/'cache'),'--format','json'],capture_output=True,text=True)
 (work/(name+'.stdout.json')).write_text(result.stdout);(work/(name+'.stderr.txt')).write_text(result.stderr)
 assert result.returncode==(0 if controlled else 1),(name,result.returncode,result.stderr,result.stdout)
 report=json.loads(result.stdout);assert not report['diagnostics'],(name,report['diagnostics'])
 assert report['verdict']==('PASS' if controlled else 'FAIL'),(name,report)
 locs=[l for item in (report['reasons'] if controlled else report['findings']) for l in item['locations']]
 assert len(locs)>=2,(name,report)
 expected_path='src/main/java/sample/Example.java' if language=='java' else 'src/main/kotlin/sample/Example.kt'
 assert {loc['revision'] for loc in locs}=={'BASE','HEAD'},(name,locs)
 for loc in locs:
  assert loc['path']==expected_path,(name,loc)
  assert loc['line']=={'start':line,'end':line} and loc['column']=={'start':column,'end':column+1},(name,loc)
 results.append({'case':name,'language':language,'exit':result.returncode,'verdict':report['verdict'],'diagnostics':len(report['diagnostics']),'locations':locs})
(work/'results.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
