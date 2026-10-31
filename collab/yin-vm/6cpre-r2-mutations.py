from pathlib import Path
import subprocess,re
src=Path('src/cljc/yin/vm/debruijn/stack.cljc')
backup=Path('collab/6cpre-r2-stack.backup.cljc')
subprocess.run(['git','diff','--quiet','HEAD','--','src/'],check=True)
backup.write_bytes(src.read_bytes())
subprocess.run(['cmp',str(src),str(backup)],check=True)
s=backup.read_text()
halt='(assoc vm :halted? true :stack [val] :value val :store-of nil)'
seam='(return-transition restored val)'
mutants=[
 ('M08',halt,'(assoc vm :halted? true :stack [(peek (:stack vm))] :value (peek (:stack vm)) :store-of nil)','empty-k-halts-with-non-nil-result-and-clears-store'),
 ('N04',seam,'(if (seq (:continuation restored)) (return-transition restored val) (return-transition restored nil))','empty-k-halts-with-non-nil-result-and-clears-store'),
 ('M09',halt,'(assoc vm :halted? true :stack [val] :value val)','empty-k-halts-with-non-nil-result-and-clears-store'),
 ('N05',seam,'(if (seq (:continuation restored)) (return-transition restored val) (assoc (return-transition restored val) :store-of (:store-of restored)))','empty-k-halts-with-non-nil-result-and-clears-store'),
 ('N08',seam,'(assoc (return-transition restored val) :pc (:pc restored))','return-pops-only-inner-frame')]
try:
 for name,a,b,test in mutants:
  assert s.count(a)==1
  src.write_text(s.replace(a,b,1))
  try:
   r=subprocess.run(['clojure','-M:test','-n','yin.vm.debruijn.stack-tail-completion-test'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=600)
   out=r.stdout.decode();rc=r.returncode
   Path(f'collab/6cpre-r2-mutant-{name}.log').write_text(out)
   assert rc!=0 and f'FAIL in ({test})' in out,(name,rc,out)
   counts=re.search(r'Ran .*\n\d+ failures, \d+ errors\.',out)[0]
   print(f'{name}: KILLED by {test}; exit {rc}; {counts}',flush=True)
  finally:
   src.write_bytes(backup.read_bytes())
   subprocess.run(['cmp',str(src),str(backup)],check=True)
   subprocess.run(['git','diff','--quiet','HEAD','--','src/'],check=True)
   print(f'{name}: restored; cmp and git diff --quiet HEAD -- src/ PASS',flush=True)
finally:
 src.write_bytes(backup.read_bytes())
 subprocess.run(['cmp',str(src),str(backup)],check=True)
 subprocess.run(['git','diff','--quiet','HEAD','--','src/'],check=True)
