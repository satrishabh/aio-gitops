"""Pause only migration-owned writers, or restart the selected side.

Nonsecret original replica counts are saved before changes for explicit rollback.
Never starts both sides or resumes source reconciliation after Azure takes writes.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('action',choices=['freeze','start-native','rollback-source'])
p.add_argument('--kubeconfig',required=True)
p.add_argument('--state',required=True)
a=p.parse_args()
source=['kubectl','--context','gke_project-d28840cd-be2f-4901-85e_asia-south1_cluster-1']
target=['kubectl','--kubeconfig',a.kubeconfig,'--context','aio-azure-creative']
apps=[('aio-platform','deployment','aio-platform'),*[('dev','deployment',n) for n in
      ['sarvaikam','user-profile-manager','sarvaika-blog']]]
source_objects=[('argocd','statefulset','argocd-application-controller'),*apps]
state_path=Path(a.state)

def get(base,obj):
    ns,kind,name=obj
    return json.loads(subprocess.check_output(base+['-n',ns,'get',kind,name,'-o','json']))

def scale(base,obj,count):
    ns,kind,name=obj
    subprocess.run(base+['-n',ns,'scale',kind+'/'+name,'--replicas='+str(count)],check=True)

def drain(base,objects):
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        if all(not get(base,o).get('status',{}).get('replicas',0) for o in objects):return
        time.sleep(3)
    raise RuntimeError('Writer drain timed out; do not sync or switch traffic')

if a.action=='freeze':
    if state_path.exists(): raise RuntimeError('Freeze state exists; inspect it instead of overwriting rollback counts')
    state={'source':[{'object':o,'replicas':get(source,o)['spec'].get('replicas',1)} for o in source_objects],
           'target':[{'object':o,'replicas':get(target,o)['spec'].get('replicas',1)} for o in apps]}
    state_path.parent.mkdir(parents=True,exist_ok=True)
    state_path.write_text(json.dumps(state,indent=2))
    # Stop the source hub before its controller, preventing automated resurrection.
    scale(source,apps[0],0)
    scale(source,source_objects[0],0)
    drain(source,[apps[0],source_objects[0]])
    for o in apps[1:]:scale(source,o,0)
    for o in apps:scale(target,o,0)
    drain(source,apps);drain(target,apps)
    print('Both application sides drained; source reconciliation paused. Ready for guarded final sync.')
elif a.action=='start-native':
    for o in apps:scale(target,o,1)
    for ns,kind,name in apps:
        subprocess.run(target+['-n',ns,'rollout','status',kind+'/'+name,'--timeout=240s'],check=True)
    print('Native Azure applications Ready. Source remains paused.')
else:
    raise RuntimeError('Rollback after Azure writes requires a reverse data sync. Use saved replica counts only before native traffic cutover.')
