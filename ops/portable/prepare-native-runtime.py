"""Prepare native Azure namespaces, fresh DB roles and target-local runtime Secrets.

OIDC client secrets come from the already migrated Azure identity schema, not a new
source credential export. Secret contents are neither printed nor written locally.
"""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--kubeconfig', required=True)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('stream', Path(__file__).with_name('stream-schema-rehearsal.py'))
s = importlib.util.module_from_spec(spec); spec.loader.exec_module(s)
base = ['kubectl', '--kubeconfig', args.kubeconfig, '--context', 'aio-azure-creative']
target = base + ['-n', 'aio-validation', 'exec', 'postgres-rehearsal-0', '--']
host = 'postgres-rehearsal.aio-validation.svc.cluster.local'


def create(document):
    subprocess.run(base + ['create', '-f', '-'], input=json.dumps(document).encode(), check=True)


def exists(kind, name, namespace):
    return bool(subprocess.check_output(base + ['-n', namespace, 'get', kind, name,
                           '--ignore-not-found', '-o', 'name'], text=True).strip())


def secret(name, namespace, data):
    create({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': name, 'namespace': namespace},
            'type': 'Opaque', 'data': {key: base64.b64encode(value.encode()).decode() for key, value in data.items()}})


def role(schema, name):
    if s.query(target, 'aio_rehearsal', 'SELECT 1 FROM pg_roles WHERE rolname='+s.literal(name), True):
        raise RuntimeError('Role exists but runtime setup is incomplete; refusing implicit rotation: '+name)
    password = secrets.token_urlsafe(36)
    sql = f"""BEGIN;
      CREATE ROLE {name} LOGIN PASSWORD '{password}';
      GRANT CONNECT ON DATABASE aio_rehearsal TO {name};
      ALTER SCHEMA {schema} OWNER TO {name};
      ALTER ROLE {name} IN DATABASE aio_rehearsal SET search_path={schema},pg_catalog;
      DO $$ DECLARE obj record; BEGIN
        FOR obj IN SELECT c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          WHERE n.nspname='{schema}' AND c.relkind IN ('r','p','v','m','S') ORDER BY (c.relkind='S') LOOP
          EXECUTE format('ALTER %s %I.%I OWNER TO %I', CASE obj.relkind WHEN 'S' THEN 'SEQUENCE'
            WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END,'{schema}',obj.relname,'{name}');
        END LOOP; END $$;
      REVOKE ALL ON SCHEMA {schema} FROM PUBLIC;
      COMMIT;"""
    s.query(target, 'aio_rehearsal', sql, True)
    return password


def client_secret(client, realm):
    value = s.query(target, 'aio_rehearsal', "SELECT c.secret FROM profile_manager.client c JOIN profile_manager.realm r ON r.id=c.realm_id WHERE c.client_id="+s.literal(client)+" AND r.name="+s.literal(realm), True)
    if not value:
        raise RuntimeError('Migrated OIDC client secret not found for '+client)
    return value


for namespace in ['dev', 'aio-platform', 'ci']:
    if not subprocess.check_output(base + ['get','namespace',namespace,'--ignore-not-found','-o','name'],text=True).strip():
        create({'apiVersion':'v1','kind':'Namespace','metadata':{'name':namespace}})
    for pull_name in ['ghcr-pull', 'ghcr-readonly']:
        if not exists('secret',pull_name,namespace):
            source = json.loads(subprocess.check_output(base+['-n','aio-validation','get','secret','ghcr-readonly','-o','json']))
            create({'apiVersion':'v1','kind':'Secret','metadata':{'name':pull_name,'namespace':namespace},
                    'type':source['type'],'data':source['data']})
    if not exists('configmap','rehearsal-db-trust',namespace):
        trust = json.loads(subprocess.check_output(base+['-n','aio-validation','get','configmap','rehearsal-db-trust','-o','json']))
        create({'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':'rehearsal-db-trust','namespace':namespace},'data':trust['data']})

suffix = '?sslmode=verify-full&sslrootcert=/etc/aio-db/ca.crt&currentSchema='
if not exists('secret','profile-manager-runtime','dev'):
    password = role('profile_manager','profile_azure')
    secret('profile-manager-runtime','dev',{'KC_DB_PASSWORD':password,'KC_DB_USERNAME':'profile_azure',
           'KC_DB_URL':'jdbc:postgresql://'+host+':5432/aio_rehearsal'+suffix+'profile_manager'})
if not exists('secret','sarvaika-blog-runtime','dev'):
    password = role('sarvaika_blog','blog_azure')
    secret('sarvaika-blog-runtime','dev',{'DB_PASSWORD':password,'DB_USER':'blog_azure',
           'DB_URL':'jdbc:postgresql://'+host+':5432/aio_rehearsal'+suffix+'sarvaika_blog',
           'OIDC_CLIENT_ID':'sarvaika-blog','OIDC_CLIENT_SECRET':client_secret('sarvaika-blog','sarvaikam'),
           'OIDC_ISSUER':'https://profile.sarvaikam.com/realms/sarvaikam','PUBLIC_URL':'https://blog.sarvaikam.com',
           'COOKIE_SECURE':'true'})
if not exists('secret','aio-platform-runtime','aio-platform'):
    password = role('aio_platform','aio_azure')
    secret('aio-platform-runtime','aio-platform',{'DB_PASSWORD':password,'DB_USER':'aio_azure',
           'DB_URL':'jdbc:postgresql://'+host+':5432/aio_rehearsal'+suffix+'aio_platform',
           'OIDC_CLIENT_SECRET':client_secret('aio-platform','aio'),'GITHUB_TOKEN':'','ARGOCD_TOKEN':''})
if not exists('secret','sarvaikam-db','dev'):
    original = json.loads(subprocess.check_output(base+['-n','aio-validation','get','secret','learn-rehearsal-runtime','-o','json']))
    values = {key:base64.b64decode(value).decode() for key,value in original['data'].items()}
    secret('sarvaikam-db','dev',values)
if not exists('secret','sarvaikam-profile-runtime','dev'):
    secret('sarvaikam-profile-runtime','dev',{'PROFILE_API_CLIENT_ID':'sarvaikam-learn-api',
        'PROFILE_API_CLIENT_SECRET':client_secret('sarvaikam-learn-api','sarvaikam'),
        'PROFILE_REGISTRATION_CLIENT_ID':'sarvaikam-learn-registration',
        'PROFILE_REGISTRATION_CLIENT_SECRET':client_secret('sarvaikam-learn-registration','sarvaikam'),
        'PROFILE_ISSUER':'https://profile.sarvaikam.com/realms/sarvaikam'})
print('Native Azure namespaces, schema roles and runtime references prepared; no public route changed.')
