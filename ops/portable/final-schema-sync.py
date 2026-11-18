"""Refresh five Azure schemas after an explicit, verified source write freeze.

Keeps previous target schemas as backups. Never drops source data or rotates roles.
The caller owns app/controller pause, restore of services, and traffic cutover.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--kubeconfig', required=True)
p.add_argument('--execute', action='store_true')
a = p.parse_args()
directory = Path(__file__).parent
spec = importlib.util.spec_from_file_location('stream', directory/'stream-schema-rehearsal.py')
s = importlib.util.module_from_spec(spec); spec.loader.exec_module(s)
azure = ['kubectl', '--kubeconfig', a.kubeconfig, '--context', 'aio-azure-creative']
gcp = ['kubectl', '--context', s.SOURCE_CONTEXT]
target = azure + ['-n','aio-validation','exec','postgres-rehearsal-0','--']
interactive = target.copy(); interactive.insert(interactive.index('exec')+1, '-i')
roles = {'sarvaikam':'learn_rehearsal','sarvaikam_reels':'reels_rehearsal',
         'profile_manager':'profile_azure','sarvaika_blog':'blog_azure','aio_platform':'aio_azure'}

def get(base, namespace, kind, name):
    return json.loads(subprocess.check_output(base+['-n',namespace,'get',kind,name,'-o','json']))

def stopped(base, namespace, kind, name):
    doc=get(base,namespace,kind,name)
    if doc['spec'].get('replicas',1) or doc.get('status',{}).get('replicas',0):
        raise RuntimeError('Write-freeze guard failed: '+namespace+'/'+name)

for namespace,kind,name in [('argocd','statefulset','argocd-application-controller'),
                            ('aio-platform','deployment','aio-platform'),
                            *[('dev','deployment',n) for n in ['sarvaikam','user-profile-manager','sarvaika-blog']]]:
    stopped(gcp,namespace,kind,name)
for namespace,name in [('aio-platform','aio-platform'),
                        *[('dev',n) for n in ['sarvaikam','user-profile-manager','sarvaika-blog']]]:
    stopped(azure,namespace,'deployment',name)
stopped(azure,'aio-validation','deployment','sarvaikam-learn')
connections=int(s.query(target,s.TARGET_DATABASE,
    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename<>'rehearsal_admin'",True))
if connections: raise RuntimeError('Target still has application connections')
if int(s.query(target,s.TARGET_DATABASE,
    "SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace",True)):
    raise RuntimeError('Nonempty public staging schema')
if not a.execute:
    print('Freeze guards passed; no schemas changed.'); sys.exit(0)

stamp=datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')
original_source=s.source
clients=[]
try:
    subprocess.run(gcp+['apply','-f',str(directory/'gcp-learn-export-client.yaml')],check=True,stdout=subprocess.DEVNULL)
    subprocess.run(gcp+['-n','dev','wait','pod/aio-learn-export-client','--for=condition=Ready','--timeout=90s'],check=True,stdout=subprocess.DEVNULL)
    clients=json.loads(subprocess.check_output([sys.executable,str(directory/'audit-remaining-databases.py'),
                                               '--keep-clients-for-export'],text=True))
    mapping={row['sourceDatabase']:row for row in clients}
    def source(database):
        if database in ('sarvaikam','sarvaikam_reels'): return original_source(database)
        row=mapping[database]
        return gcp+['-n',row['sourceNamespace'],'exec',row['sourceClient'],'--']
    s.source=source
    databases=['sarvaikam','sarvaikam_reels',*mapping]
    audits={db:s.audit(db) for db in databases}
    # The extension must be in the fresh public staging schema when pg_restore
    # evaluates public.uuid_generate_v4 defaults. PostgreSQL tracks old dependencies.
    sql=['BEGIN;']
    extension=s.query(target,s.TARGET_DATABASE,
        "SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='uuid-ossp'",True)
    if extension: sql.append('ALTER EXTENSION "uuid-ossp" SET SCHEMA public;')
    for schema in roles:
        sql.append(f'ALTER SCHEMA {s.ident(schema)} RENAME TO {s.ident(schema+"_before_"+stamp)};')
    sql.append('COMMIT;')
    s.query(target,s.TARGET_DATABASE,'\n'.join(sql),True)
    result=[]
    for database in databases:
        schema=mapping[database]['proposedTargetSchema'] if database in mapping else database
        result.append(s.restore_schema(database,audits[database]['tables'],target,interactive,schema))
        role=roles[schema]
        s.query(target,s.TARGET_DATABASE,f"""BEGIN;
          ALTER SCHEMA {schema} OWNER TO {role};
          REVOKE ALL ON SCHEMA {schema} FROM PUBLIC;
          DO $$ DECLARE obj record; BEGIN
            FOR obj IN SELECT c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
              WHERE n.nspname='{schema}' AND c.relkind IN ('r','p','v','m','S') ORDER BY (c.relkind='S') LOOP
              EXECUTE format('ALTER %s %I.%I OWNER TO %I',CASE obj.relkind WHEN 'S' THEN 'SEQUENCE'
                WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END,'{schema}',obj.relname,'{role}');
            END LOOP; END $$; COMMIT;""",True)
    print(json.dumps({'observedAt':datetime.now(timezone.utc).isoformat(),'backupSchemaSuffix':'_before_'+stamp,
                      'schemas':result,'trafficCutoverComplete':False},indent=2))
finally:
    for namespace,name in [('dev','aio-learn-export-client'),('dev','aio-profile-manager-audit'),
                           ('dev','aio-sarvaika-blog-audit'),('aio-platform','aio-aio-platform-audit')]:
        subprocess.run(gcp+['-n',namespace,'delete','pod',name,'--ignore-not-found','--wait=false'],stdout=subprocess.DEVNULL)
