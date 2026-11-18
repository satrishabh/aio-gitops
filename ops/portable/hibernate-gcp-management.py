"""Pause source management controllers and retire the obsolete public LB.

Use only after Azure cutover and explicit acceptance of old-DNS interruption.
Saves nonsecret original replica counts and Service configuration for reference.
Does not delete application data, certificates, PVCs or database instances.
The old ephemeral ingress IP is released and cannot be guaranteed on restart.
"""
import argparse
import json
from pathlib import Path
import subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--execute',action='store_true')
p.add_argument('--accept-old-dns-interruption-and-ip-release',action='store_true')
args=p.parse_args()
if not args.execute:
    print('Plan only: pause source management and change old ingress to ClusterIP; release its ephemeral IP. Data and tracker remain.')
    raise SystemExit(0)
if not args.accept_old_dns_interruption_and_ip_release:
    p.error('Execution requires explicit acceptance of cached-DNS interruption and old ephemeral IP release')

base=['kubectl','--context','gke_project-d28840cd-be2f-4901-85e_asia-south1_cluster-1']
namespaces={'aio-platform','argo-rollouts','argocd','cert-manager','ci','dev','staging','prod',
            'external-dns','ingress-nginx','tekton-pipelines','tekton-pipelines-resolvers'}
directory=Path('.deploy/gcp-hibernation');directory.mkdir(parents=True,exist_ok=True)
state=directory/'management-before.json'
if state.exists():raise RuntimeError('Saved state exists; refusing to overwrite original replica counts')
workloads=json.loads(subprocess.check_output(base+['get','deployments,statefulsets','-A','-o','json']))['items']
owned=[x for x in workloads if x['metadata']['namespace'] in namespaces]
saved=[{'kind':x['kind'],'namespace':x['metadata']['namespace'],'name':x['metadata']['name'],
        'replicas':x['spec'].get('replicas',1)} for x in owned]
state.write_text(json.dumps(saved,indent=2))
service=json.loads(subprocess.check_output(base+['-n','ingress-nginx','get','service','ingress-nginx-controller','-o','json']))
(directory/'ingress-service-before.json').write_text(json.dumps(service,indent=2))
for obj in saved:
    if obj['replicas']:
        subprocess.run(base+['-n',obj['namespace'],'scale',obj['kind']+'/'+obj['name'],'--replicas=0'],check=True)
service['spec']['type']='ClusterIP'
for key in ['allocateLoadBalancerNodePorts','externalTrafficPolicy','healthCheckNodePort','loadBalancerIP']:
    service['spec'].pop(key,None)
for port in service['spec']['ports']:port.pop('nodePort',None)
subprocess.run(base+['replace','-f','-'],input=json.dumps(service).encode(),check=True)
print('Source management paused; obsolete ingress changed to ClusterIP. Data and tracker retained.')
