"""Create fresh Azure-only app roles/secrets for the approved schema rehearsal.

No production password, JWT signing key or identity client secret is reused.
Refuses to overwrite an existing runtime Secret or rotate credentials implicitly.
"""
import argparse
import base64
import json
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig', required=True)
    args = parser.parse_args()
    base = ['kubectl', '--kubeconfig', args.kubeconfig, '--context', 'aio-azure-creative', '-n', 'aio-validation']
    existing = subprocess.check_output(base + ['get', 'secret', 'learn-rehearsal-runtime', '--ignore-not-found', '-o', 'name'], text=True).strip()
    if existing:
        raise RuntimeError('Runtime Secret already exists; refusing implicit credential rotation')
    passwords = {role: secrets.token_urlsafe(36) for role in ['learn_rehearsal', 'reels_rehearsal']}
    sql = ['BEGIN;']
    for role, schema in [('learn_rehearsal', 'sarvaikam'), ('reels_rehearsal', 'sarvaikam_reels')]:
        sql += [f"CREATE ROLE {role} LOGIN PASSWORD '{passwords[role]}';",
                f'GRANT CONNECT ON DATABASE aio_rehearsal TO {role};',
                f'ALTER SCHEMA {schema} OWNER TO {role};',
                f'ALTER ROLE {role} IN DATABASE aio_rehearsal SET search_path={schema},pg_catalog;',
                f"DO $$ DECLARE obj record; BEGIN FOR obj IN SELECT c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='{schema}' AND c.relkind IN ('r','p','v','m','S') ORDER BY (c.relkind='S') LOOP EXECUTE format('ALTER %s %I.%I OWNER TO %I',CASE obj.relkind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END,'{schema}',obj.relname,'{role}'); END LOOP; END $$;"]
    sql += ['COMMIT;']
    subprocess.run(base + ['exec', '-i', 'postgres-rehearsal-0', '--', 'psql', '-X', '-q', '-v',
                          'ON_ERROR_STOP=1', '-U', 'rehearsal_admin', '-d', 'aio_rehearsal'],
                   input='\n'.join(sql).encode(), check=True)
    host = 'postgres-rehearsal.aio-validation.svc.cluster.local'
    data = {'POSTGRES_PASSWORD': passwords['learn_rehearsal'],
            'REELS_DB_PASSWORD': passwords['reels_rehearsal'], 'JWT_SECRET': secrets.token_urlsafe(48),
            'DATABASE_URL': f"host={host} port=5432 dbname=aio_rehearsal user=learn_rehearsal password={passwords['learn_rehearsal']} sslmode=verify-full sslrootcert=/etc/aio-db/ca.crt search_path=sarvaikam"}
    manifest = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'learn-rehearsal-runtime', 'namespace': 'aio-validation'},
                'type': 'Opaque', 'data': {key: base64.b64encode(value.encode()).decode() for key, value in data.items()}}
    subprocess.run(base + ['create', '-f', '-'], input=json.dumps(manifest).encode(), check=True)
    certificate = base64.b64decode(subprocess.check_output(base + ['get', 'secret', 'postgres-rehearsal-tls',
                                   '-o', r'jsonpath={.data.tls\.crt}']))
    trust = {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'rehearsal-db-trust', 'namespace': 'aio-validation'},
             'data': {'ca.crt': certificate.decode()}}
    subprocess.run(base + ['create', '-f', '-'], input=json.dumps(trust).encode(), check=True)
    print('Azure-only schema roles, application runtime Secret and public TLS trust configured.')


if __name__ == '__main__':
    main()
