"""Switch verified native Azure services live and retire Azure->GCP forwarding.

Legacy GCP domain ingress forwards only to Azure while DNS caches expire.
Source writers must remain stopped after this step; rollback needs reverse sync.
"""
import argparse
import json
from pathlib import Path
import subprocess

p=argparse.ArgumentParser(description=__doc__);p.add_argument('--kubeconfig',required=True)
p.add_argument('--identity-first',action='store_true',help='Publish identity first so dependent OAuth clients can start')
a=p.parse_args()
azure=['kubectl','--kubeconfig',a.kubeconfig,'--context','aio-azure-creative']
source=['kubectl','--context','gke_project-d28840cd-be2f-4901-85e_asia-south1_cluster-1']

def get(base,ns,kind,name):return json.loads(subprocess.check_output(base+['-n',ns,'get',kind,name,'-o','json']))

for ns,name in [('dev','sarvaikam'),('dev','user-profile-manager'),('dev','sarvaika-blog'),('aio-platform','aio-platform')]:
    if a.identity_first and name!='user-profile-manager':continue
    doc=get(azure,ns,'deployment',name)
    if doc['status'].get('readyReplicas',0)!=1:raise RuntimeError('Native application not Ready: '+name)
    if get(source,ns,'deployment',name)['spec'].get('replicas',1):raise RuntimeError('Source writer is enabled: '+name)
for ns,name in [('dev','tls-learn-sarvaikam-com'),('dev','tls-profile-sarvaikam-com'),
                ('dev','tls-blog-sarvaikam-com'),('aio-platform','aio-native-public')]:
    doc=get(azure,ns,'certificate',name)
    if not any(c['type']=='Ready' and c['status']=='True' for c in doc['status']['conditions']):
        raise RuntimeError('Native certificate not Ready: '+name)
subprocess.run(azure+['apply','-f',str(Path(__file__).with_name('azure-native-routes.yaml'))],check=True)
if a.identity_first:
    doc=get(source,'dev','ingress','user-profile-manager-r-8fe3ae39')
    for rule in doc['spec']['rules']:
        for path in rule['http']['paths']:
            path['backend']={'service':{'name':'legacy-azure-edge','port':{'number':8080}}}
    subprocess.run(source+['replace','-f','-'],input=json.dumps(doc).encode(),check=True)
    print('Native identity published first; dependent OAuth clients may now start.')
    raise SystemExit(0)
checks=[]
for host,path in [('learn.sarvaikam.com','/ready'),('profile.sarvaikam.com','/realms/sarvaikam/.well-known/openid-configuration'),
                   ('blog.sarvaikam.com','/'),('app.sarvaikam.com','/login')]:
    status=subprocess.check_output(['curl.exe','--silent','--show-error','--retry','5','--retry-all-errors',
        '--retry-delay','2','--max-time','20','--resolve',host+':443:52.140.55.184',
        '-o','NUL','-w','%{http_code}','https://'+host+path],text=True)
    if status!='200':raise RuntimeError('Native public host failed: '+host+' '+status)
    checks.append({'host':host,'path':path,'status':200,'tlsVerified':True})
# This local DNS alias contains no credentials and only reaches the fixed source-local proxy.
service={'apiVersion':'v1','kind':'Service','metadata':{'name':'legacy-azure-edge','namespace':'aio-platform'},
         'spec':{'type':'ExternalName','externalName':'legacy-azure-edge.dev.svc.cluster.local',
                 'ports':[{'name':'http','port':8080,'targetPort':8080}]}}
subprocess.run(source+['apply','-f','-'],input=json.dumps(service).encode(),check=True)
for ns,name in [('dev','sarvaikam-r-7adebf74'),('dev','user-profile-manager-r-8fe3ae39'),
                ('dev','sarvaika-blog-r-8a8fc78c'),('dev','sarvaikam-home-r-84593198'),
                ('aio-platform','aio-platform')]:
    doc=get(source,ns,'ingress',name)
    for rule in doc['spec']['rules']:
        for path in rule['http']['paths']:
            path['backend']={'service':{'name':'legacy-azure-edge','port':{'number':8080}}}
    subprocess.run(source+['replace','-f','-'],input=json.dumps(doc).encode(),check=True)
subprocess.run(azure+['-n','aio-validation','delete','ingress','transition-domains-public','--ignore-not-found'],check=True)
subprocess.run(azure+['-n','aio-validation','scale','deployment/transition-edge','--replicas=0'],check=True)
print(json.dumps({'nativeAzureRouting':True,'azureToGcpForwardingRemoved':True,'checks':checks},indent=2))
