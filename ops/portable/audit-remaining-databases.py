"""Metadata-only audit of remaining source DBs; never exports application records.

Temporary PG18 clients reference only existing source-namespace DB password keys.
All clients are deleted in finally. No source credentials are sent to Azure.
"""
import base64
import argparse
import json
import subprocess
from urllib.parse import parse_qs, urlparse

context = 'gke_project-d28840cd-be2f-4901-85e_asia-south1_cluster-1'
applications = [
    ('profile_manager', 'dev', 'profile-manager-runtime', 'KC_DB_URL', 'KC_DB_USERNAME', 'KC_DB_PASSWORD'),
    ('sarvaika_blog', 'dev', 'sarvaika-blog-runtime', 'DB_URL', 'DB_USER', 'DB_PASSWORD'),
    ('aio_platform', 'aio-platform', 'aio-platform-secrets', 'DB_URL', 'DB_USER', 'DB_PASSWORD'),
]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--keep-clients-for-export', action='store_true')
args = parser.parse_args()
results = []
for schema, namespace, secret, url_key, user_key, password_key in applications:
    base = ['kubectl', '--context', context, '-n', namespace]

    def field(key):
        return base64.b64decode(subprocess.check_output(base + ['get', 'secret', secret,
                     '-o', 'jsonpath={.data.' + key + '}'])).decode()

    parsed = urlparse(field(url_key).removeprefix('jdbc:'))
    user = field(user_key)
    hostname = parsed.hostname
    if not hostname and parse_qs(parsed.query).get('cloudSqlInstance') == ['project-d28840cd-be2f-4901-85e:asia-south1:aio-002']:
        hostname = 'profile-manager-cloudsql-proxy.aio-platform.svc.cluster.local'
    if not hostname or not parsed.path.strip('/'):
        raise RuntimeError('Non-standard source database URL requires manual review: ' + schema)
    name = 'aio-' + schema.replace('_', '-') + '-audit'
    pod = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': name, 'namespace': namespace,
           'labels': {'app.kubernetes.io/part-of': 'aio-migration-validation'}}, 'spec': {
        'automountServiceAccountToken': False, 'restartPolicy': 'Never', 'activeDeadlineSeconds': 900,
        'securityContext': {'runAsNonRoot': True, 'runAsUser': 999, 'runAsGroup': 999,
                            'seccompProfile': {'type': 'RuntimeDefault'}}, 'containers': [{
            'name': 'audit', 'image': 'postgres@sha256:a197bcc7150b0a7e35796ef0c110693cba6edd41e92b564cc50b976f17532423',
            'command': ['sleep', '900'], 'env': [
                {'name': 'PGHOST', 'value': hostname}, {'name': 'PGPORT', 'value': str(parsed.port or 5432)},
                {'name': 'PGDATABASE', 'value': parsed.path.strip('/')}, {'name': 'PGUSER', 'value': user},
                {'name': 'PGPASSWORD', 'valueFrom': {'secretKeyRef': {'name': secret, 'key': password_key}}}],
            'securityContext': {'allowPrivilegeEscalation': False, 'capabilities': {'drop': ['ALL']}},
            'resources': {'requests': {'cpu': '25m', 'memory': '64Mi'}, 'limits': {'cpu': '250m', 'memory': '256Mi'}}}]}}
    try:
        subprocess.run(base + ['create', '-f', '-'], input=json.dumps(pod).encode(), check=True, stdout=subprocess.DEVNULL)
        subprocess.run(base + ['wait', 'pod/' + name, '--for=condition=Ready', '--timeout=90s'], check=True, stdout=subprocess.DEVNULL)
        sql = """SELECT json_build_object('sourceDatabase',current_database(),'bytes',pg_database_size(current_database()),
          'schemas',(SELECT json_agg(nspname) FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname<>'information_schema'),
          'tables',(SELECT count(*) FROM pg_tables WHERE schemaname='public'),
          'extensions',(SELECT json_agg(extname) FROM pg_extension),
          'userFunctions',(SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.objid=p.oid AND d.deptype='e')),
          'largeObjects',(SELECT count(*) FROM pg_largeobject_metadata));"""
        result = json.loads(subprocess.check_output(base + ['exec', name, '--', 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql], text=True))
        result.update(proposedTargetDatabase='aio_rehearsal', proposedTargetSchema=schema, dataTransferred=False)
        result.update(sourceNamespace=namespace, sourceClient=name)
        results.append(result)
    finally:
        if not args.keep_clients_for_export:
            subprocess.run(base + ['delete', 'pod', name, '--ignore-not-found', '--wait=false'], stdout=subprocess.DEVNULL)
print(json.dumps(results, indent=2))
