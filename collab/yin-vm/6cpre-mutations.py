import pathlib, subprocess
p=pathlib.Path('src/cljc/yin/vm/debruijn/stack.cljc')
backup=pathlib.Path('collab/6cpre-stack-implemented.backup.cljc');backup.write_bytes(p.read_bytes())
subprocess.run(['cmp',str(p),str(backup)],check=True)
head=subprocess.check_output(['git','show','HEAD:'+str(p)])
hb=pathlib.Path('collab/6cpre-stack-head.backup.cljc');hb.write_bytes(head)
original=p.read_bytes();s=original.decode()
mutants=[
('tail-park-is-marked','(assoc :resume-mode :return-result)','(assoc :resume-mode :write-result)'),
('empty-k-halts-with-result',':halted? true :stack [val]',':halted? false :stack [val]'),
('return-pops-only-inner-frame','(pop (:continuation vm))','(:continuation vm)'),
('return-truncates-at-caller-base','(subvec (:stack vm) 0 (:stack-base frame))','(:stack vm)'),
('return-restores-caller-environment',':frames (:frames frame)',':frames (:frames vm)'),
('tail-in-operand-position-skips-layout','(return-transition restored val)','(assoc restored :stack (conj (:stack restored) val))'),
('retained-ffi-reparks-before-return','(if (:request-sent entry)','(if false'),
('ffi-response-decodes-before-return','(ffi/call-result val call-id)','val')]
try:
 for i,(name,a,b) in enumerate(mutants,1):
  assert a in s
  p.write_text(s.replace(a,b,1))
  r=subprocess.run(['clojure','-M:test','-n','yin.vm.debruijn.stack-tail-completion-test'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
  log=r.stdout.decode();pathlib.Path(f'collab/6cpre-mutation-{i}.log').write_text(log)
  failed=f'FAIL in ({name})' in log or f'ERROR in ({name})' in log
  print(i,name,r.returncode,'NAMED FAILURE' if failed else 'SURVIVED',flush=True)
  p.write_bytes(backup.read_bytes());subprocess.run(['cmp',str(p),str(backup)],check=True)
  # HEAD check on the original tracked source, then restore implemented bytes.
  p.write_bytes(hb.read_bytes());subprocess.run(['cmp',str(p),str(hb)],check=True)
  subprocess.run(['git','diff','--quiet','HEAD','--',str(p)],check=True)
  p.write_bytes(backup.read_bytes());subprocess.run(['cmp',str(p),str(backup)],check=True)
finally:p.write_bytes(original)
