from pathlib import Path
import subprocess, shutil, json, hashlib
root=Path.cwd()
frame='src/cljc/yin/vm/semantic_register/frame.cljc'
rows='src/cljc/yin/vm/ucf/rows.cljc'
backup=root/'collab/6a-verified-backups'
backup.mkdir(exist_ok=True)
for path in [frame,rows]:
    dst=backup/Path(path).name
    shutil.copyfile(root/path,dst)
    subprocess.run(['cmp',str(root/path),str(dst)],check=True)
    print('Verified backup:',path,hashlib.sha256(dst.read_bytes()).hexdigest(),flush=True)
mutants=[
('M1',frame,'(some #(not (contains? w %)) wanted)','false','yin.vm.semantic-register.frame-test/nil-valued-register-is-a-member'),
('M2',frame,'(every? #(contains? bindings %) names)','true','yin.vm.semantic-register.frame-test/free-env-mismatch'),
('M3',frame,'(= (apply dissoc bindings names)\n                                      (apply dissoc (:free-env task) names))','true','yin.vm.semantic-register.frame-test/free-env-mismatch'),
('M4',frame,'(when-not (= rd (:yin.k/rd d))','(when-not true','yin.vm.semantic-register.frame-test/each-rule-refuses-its-tamper'),
('M5',frame,'(analysis/saved (analysis/analyze v) pc rd)','(analysis/live (analysis/analyze v) pc)','yin.vm.semantic-register.frame-test/worked-example-saved'),
('M6',frame,'(problem :pc-body (conj p :yin.k/pc))','nil','yin.vm.semantic-register.frame-test/each-rule-refuses-its-tamper'),
('M7',rows,'(not= root (:yin.k/root canonical))','(not= root root)','yin.vm.ucf.rows-test/numbering-precedes-frame-defect'),
('M8',rows,'(when-not (contains? correspondence id)','(when-not true','yin.vm.ucf.rows-test/canonical-under-is-renaming-invariant'),
('M9',rows,'(spend! counter budget :search 1)','nil','yin.vm.ucf.rows-test/refinement-search-preserves-unordered-multiplicity'),
]
results=[]
for ident,path,old,new,var in mutants:
    target=root/path
    original=(backup/Path(path).name).read_bytes()
    text=original.decode()
    assert text.count(old)==1,(ident,text.count(old))
    print('Running',ident,var,flush=True)
    try:
        target.write_text(text.replace(old,new,1))
        log=root/f'collab/6a-{ident}.log'
        command=['clojure','-M:test','-n',var.split('/')[0],'-v',var]
        with log.open('w') as output:
            result=subprocess.run(command,stdout=output,stderr=subprocess.STDOUT)
        content=log.read_text()
        failed=('FAIL in ('+var.split('/')[1]+')') in content
        entry={'id':ident,'path':path,'test':var,'exit':result.returncode,'named_failure':failed,'command':command,'log':str(log.relative_to(root))}
        print(ident,'exit',result.returncode,'named failure',failed,flush=True)
    finally:
        shutil.copyfile(backup/Path(path).name,target)
        cmp_result=subprocess.run(['cmp',str(target),str(backup/Path(path).name)])
        diff_result=subprocess.run(['git','diff','--quiet','HEAD','--',path])
        assert cmp_result.returncode==0
        assert target.read_bytes()==original
        assert diff_result.returncode==0
    entry.update(cmp=cmp_result.returncode,git_diff_quiet=diff_result.returncode)
    results.append(entry)
    (root/'collab/6a-mutations.json').write_text(json.dumps(results,indent=2)+'\n')
print('Complete',len(results),'mutants;',sum(r['named_failure'] for r in results),'named failures',flush=True)
