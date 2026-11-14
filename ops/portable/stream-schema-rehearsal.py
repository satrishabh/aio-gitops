"""Copy the approved Learn/reels snapshots into two schemas of ONE new Azure DB.

Binary streaming only: no local archive, data output, or source credential transfer.
Source remains authoritative. Existing destinations and unsafe schema moves fail closed.
"""
import argparse
import json
import subprocess
from datetime import datetime, timezone

SOURCE_CONTEXT = 'gke_project-d28840cd-be2f-4901-85e_asia-south1_cluster-1'
TARGET_DATABASE = 'aio_rehearsal'
DATABASES = ('sarvaikam', 'sarvaikam_reels')


def ident(value):
    return '"' + value.replace('"', '""') + '"'


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def source(database):
    command = ['kubectl', '--context', SOURCE_CONTEXT, '-n', 'dev', 'exec', 'aio-learn-export-client', '--']
    if database == 'sarvaikam_reels':
        command += ['sh', '-c', 'export PGPASSWORD="$REELS_PGPASSWORD"; exec "$@"', '--']
    return command


def query(command, database, sql, admin=False):
    command = command.copy()
    if '-i' not in command:
        command.insert(command.index('exec') + 1, '-i')
    return subprocess.check_output(command + ['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'] +
        (['-U', 'rehearsal_admin'] if admin else []) + ['-d', database], input=sql, text=True).strip()


def audit(database):
    sql = """SELECT json_build_object(
      'schemas',(SELECT json_agg(nspname) FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema'),
      'tables',(SELECT coalesce(json_agg(tablename ORDER BY tablename),'[]') FROM pg_tables WHERE schemaname='public'),
      'userFunctions',(SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.objid=p.oid AND d.deptype='e')),
      'largeObjects',(SELECT count(*) FROM pg_largeobject_metadata),
      'extensions',(SELECT json_agg(extname) FROM pg_extension),
      'bytes',pg_database_size(current_database()));"""
    result = json.loads(query(source(database), database, sql))
    if result['schemas'] != ['public'] or result['userFunctions'] or result['largeObjects']:
        raise RuntimeError('Manual schema compatibility review required for ' + database)
    if set(result['extensions']) - {'plpgsql', 'uuid-ossp'}:
        raise RuntimeError('Unsupported extension for schema consolidation: ' + database)
    if result['bytes'] > 512 * 1024 * 1024:
        raise RuntimeError('Source exceeds the bounded rehearsal capacity')
    return result


def fingerprint_sql(schema, tables):
    parts = []
    for table in tables:
        parts.append(f"SELECT {literal(table)} AS name, json_build_object('rows',count(*),"
            "'md5',md5(coalesce(string_agg(md5(row_to_json(t)::text),'' ORDER BY md5(row_to_json(t)::text)),''))) AS detail "
            f'FROM {ident(schema)}.{ident(table)} t')
    return 'SELECT coalesce(json_object_agg(name,detail),\'{}\') FROM (' + ' UNION ALL '.join(parts) + ') checks'


def restore_schema(database, tables, target, target_interactive, target_schema=None):
    destination_schema = target_schema or database
    # Keep a read-only transaction open so pg_dump and validation see the SAME snapshot.
    holder_command = source(database)
    holder_command.insert(holder_command.index('exec') + 1, '-i')
    holder = subprocess.Popen(holder_command + ['stdbuf', '-oL', 'psql', '-X', '-qAt',
        '-v', 'ON_ERROR_STOP=1', '-d', database], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        holder.stdin.write('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT pg_export_snapshot();\n')
        holder.stdin.flush()
        snapshot = holder.stdout.readline().strip()
        if not snapshot or any(c not in '0123456789ABCDEFabcdef-' for c in snapshot):
            raise RuntimeError('Could not establish an exported source snapshot')
        source_checks = json.loads(query(source(database), database,
            'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT ' + literal(snapshot) + ';' +
            fingerprint_sql('public', tables) + '; COMMIT;'))
        producer = subprocess.Popen(source(database) + ['pg_dump', '-d', database, '-Fc',
            '--no-owner', '--no-acl', '--snapshot=' + snapshot], stdout=subprocess.PIPE)
        try:
            consumer = subprocess.Popen(target_interactive + ['pg_restore', '-U', 'rehearsal_admin',
                '-d', TARGET_DATABASE, '--exit-on-error', '--single-transaction', '--no-owner', '--no-acl'],
                stdin=producer.stdout)
            producer.stdout.close()
            restore_code = consumer.wait()
            dump_code = producer.wait()
        except BaseException:
            producer.terminate()
            producer.wait()
            raise
        if dump_code or restore_code:
            raise RuntimeError(f'Restore failed (dump={dump_code}, restore={restore_code}); target retained, not accepted')
        # Rename database objects, never rewrite SQL text or COPY payloads.
        query(target, TARGET_DATABASE, f'ALTER SCHEMA public RENAME TO {ident(destination_schema)};'
              'CREATE SCHEMA public; REVOKE ALL ON SCHEMA public FROM PUBLIC;', True)
        restored_checks = json.loads(query(target, TARGET_DATABASE, fingerprint_sql(destination_schema, tables), True))
        if source_checks != restored_checks:
            raise RuntimeError('Snapshot row counts/content fingerprints differ for ' + database)
        return {'sourceDatabase': database, 'targetSchema': destination_schema, 'snapshot': snapshot,
                'tableCount': len(tables), 'rows': sum(x['rows'] for x in source_checks.values()),
                'snapshotFingerprintsMatched': True,
                'tables': {name: {'rows': value['rows'], 'contentMatched': True}
                           for name, value in restored_checks.items()}}
    finally:
        try:
            holder.stdin.write('ROLLBACK;\n\\q\n')
            holder.stdin.flush()
            holder.communicate(timeout=20)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            holder.kill()
            holder.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig', required=True)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    target = ['kubectl', '--kubeconfig', args.kubeconfig, '--context', 'aio-azure-creative',
              '-n', 'aio-validation', 'exec', 'postgres-rehearsal-0', '--']
    target_interactive = target.copy()
    target_interactive.insert(target_interactive.index('exec') + 1, '-i')
    if query(target, 'postgres', f'SELECT 1 FROM pg_database WHERE datname={literal(TARGET_DATABASE)}', True):
        raise RuntimeError('Destination already exists; refusing to overwrite ' + TARGET_DATABASE)
    audits = {database: audit(database) for database in DATABASES}
    result = {'targetDatabase': TARGET_DATABASE, 'sourceMetadata': audits, 'executed': False}
    if args.execute:
        subprocess.run(target + ['createdb', '-U', 'rehearsal_admin', TARGET_DATABASE], check=True)
        query(target, TARGET_DATABASE, 'REVOKE ALL ON DATABASE aio_rehearsal FROM PUBLIC;'
              'REVOKE ALL ON SCHEMA public FROM PUBLIC;', True)
        result['schemas'] = [restore_schema(database, audits[database]['tables'], target, target_interactive)
                             for database in DATABASES]
        query(target, TARGET_DATABASE, 'REVOKE ALL ON SCHEMA sarvaikam,sarvaikam_reels FROM PUBLIC;'
              'ALTER DATABASE aio_rehearsal SET search_path=pg_catalog;', True)
        result.update(executed=True, observedAt=datetime.now(timezone.utc).isoformat(),
                      sourceRemainsWriteAuthority=True, applicationAcceptance=False)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
