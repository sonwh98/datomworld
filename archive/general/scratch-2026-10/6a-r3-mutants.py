from pathlib import Path
import subprocess,json,shutil
source=Path('src/cljc/yin/vm/ucf/rows.cljc'); backup=Path('collab/6a-r3-backups/rows.cljc'); baseline=backup.read_bytes()
assert source.read_bytes()==baseline
mutants=[
 ('guest', '(check-literal-markers! x)\n  [:lit x])', '(cond (and (marker-shaped? x) (map? (:yin.k/row x))) [:row (:yin.k/row x)]\n        (and (marker-shaped? x) (keyword? (:yin.k/cell x))) [:cell (:yin.k/cell x)]\n        :else (do (check-literal-markers! x) [:lit x])))','well-formed-guest-markers-are-refused'),
 ('literal-both', '(check-literal-markers! (if (contains? #{:V :optional-V} d)', '(identity (if (contains? #{:V :optional-V} d)', 'every-literal-context-refuses-markers'),
 ('equality', ':lit (literal-value (second v))', ':lit (second v)', 'cell-and-identity-shaped-literal-are-distinct'),
 ('bytes', '(when (> n remaining)', '(when false', 'intermediate-label-bytes-are-bounded')]
results=[]
for name,old,new,test in mutants:
 assert source.read_bytes()==baseline
 try:
  if name=='pre-final-tests':
   source.write_bytes(Path('collab/6a-r3-backups/pre/rows.cljc').read_bytes())
   tests=['well-formed-guest-markers-are-refused','every-literal-context-refuses-markers','cell-and-identity-shaped-literal-are-distinct','intermediate-label-bytes-are-bounded']
  else:
   text=baseline.decode()
   if name=='literal-both':
    assert text.count(old)==2; text=text.replace(old,new)
   else:
    assert old in text; text=text.replace(old,new,1)
   source.write_text(text);tests=[test]
  cmd=['clojure','-J-Xmx256m','-M:test','-n','yin.vm.ucf.rows-test']
  for t in tests:cmd+=['-v','yin.vm.ucf.rows-test/'+t]
  log=Path('collab/6a-r3-'+name+'.log')
  with log.open('w') as out:
   run=subprocess.run(cmd,stdout=out,stderr=subprocess.STDOUT,timeout=90)
  result={'name':name,'command':cmd,'exit':run.returncode,'log':str(log)}
  assert run.returncode!=0 and 'Ran ' in log.read_text(),result
 finally:
  shutil.copyfile(backup,source);assert source.read_bytes()==baseline
 result['restored_bytes']=True;results.append(result)
 print(json.dumps(result),flush=True)
Path('collab/6a-r3-mutants.json').write_text(json.dumps(results,indent=2)+'\n')
