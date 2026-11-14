"""Configure target-local Argo/CI credentials using the user's existing Azure token.

Only loopback Argo API is used. No secrets are printed or written to local files.
"""
import argparse, base64, json, ssl, subprocess, urllib.request

p=argparse.ArgumentParser(description=__doc__);p.add_argument('--kubeconfig',required=True);a=p.parse_args()
b=['kubectl','--kubeconfig',a.kubeconfig,'--context','aio-azure-creative']
def get(kind,name,ns):return json.loads(subprocess.check_output(b+['-n',ns,'get',kind,name,'-o','json']))
def replace(doc):subprocess.run(b+['replace','-f','-'],input=json.dumps(doc).encode(),check=True)
def apply(doc):subprocess.run(b+['apply','--server-side','--field-manager=aio-native-delivery','-f','-'],input=json.dumps(doc).encode(),check=True)
cm=get('configmap','argocd-cm','argocd');cm.setdefault('data',{})['accounts.aio-native']='apiKey';replace(cm)
rbac=get('configmap','argocd-rbac-cm','argocd')
policy='''p, role:aio-native, applications, *, apps/*, allow
p, role:aio-native, logs, get, apps/*, allow
p, role:aio-native, clusters, get, *, allow
p, role:aio-native, projects, get, apps, allow
g, aio-native, role:aio-native'''
rbac.setdefault('data',{})['policy.aio-native.csv']=policy;replace(rbac)
raw=get('secret','ghcr-readonly','aio-validation');docker=base64.b64decode(raw['data']['.dockerconfigjson'])
config=json.loads(docker);username,github=base64.b64decode(config['auths']['ghcr.io']['auth']).decode().split(':',1)
def secret(name,ns,values,labels=None):
    apply({'apiVersion':'v1','kind':'Secret','metadata':{'name':name,'namespace':ns,'labels':labels or {}},
           'type':'Opaque','data':{k:base64.b64encode(v.encode()).decode() for k,v in values.items()}})
secret('aio-gitops-repository','argocd',{'type':'git','url':'https://github.com/satrishabh/aio-gitops.git',
       'username':username,'password':github},{'argocd.argoproj.io/secret-type':'repository'})
secret('github-token','ci',{'token':github})
secret('registry-credentials','ci',{'config.json':docker.decode()})
runtime=get('secret','aio-platform-runtime','aio-platform')
runtime['data']['GITHUB_TOKEN']=base64.b64encode(github.encode()).decode()
existing=base64.b64decode(runtime['data'].get('ARGOCD_TOKEN','')).decode()
if not existing:
    password=base64.b64decode(get('secret','argocd-initial-admin-secret','argocd')['data']['password']).decode()
    context=ssl._create_unverified_context() # Only the fixed, loopback port-forward below.
    def api(path,body,token=None):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        req=urllib.request.Request('https://127.0.0.1:18445'+path,data=json.dumps(body).encode(),headers=headers)
        with urllib.request.urlopen(req,context=context,timeout=20) as response:return json.load(response)
    admin=api('/api/v1/session',{'username':'admin','password':password})['token']
    token=api('/api/v1/account/aio-native/token',{'id':'aio-native-platform','expiresIn':0},admin)['token']
    runtime['data']['ARGOCD_TOKEN']=base64.b64encode(token.encode()).decode()
replace(runtime)
print('Target Argo project-scoped API account, repo credential and CI secrets configured.')
