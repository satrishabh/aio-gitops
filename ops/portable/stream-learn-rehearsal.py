"""Stream an authorized GCP Learn snapshot into a new isolated Azure database.

No dump file or source password is stored locally or transferred to Azure.
Requires the source-only PG18 client and pre-provisioned rehearsal StatefulSet.
Never targets a production database; existing destination databases are refused.
"""
import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone

SOURCE_CONTEXT = 'gke_project-d28840cd-be2f-4901-85e_asia-south1_cluster-1'
TARGET_CONTEXT = 'aio-azure-creative'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('database', choices=['sarvaikam', 'sarvaikam_reels'])
    parser.add_argument('--kubeconfig', required=True)
    parser.add_argument('--execute', action='store_true', help='Create a NEW rehearsal DB and stream data')
    args = parser.parse_args()
    destination = 'rehearsal_' + args.database
    source = ['kubectl', '--context', SOURCE_CONTEXT, '-n', 'dev', 'exec', 'aio-learn-export-client', '--']
    if args.database == 'sarvaikam_reels':
        source += ['sh', '-c', 'export PGPASSWORD="$REELS_PGPASSWORD"; exec "$@"', '--']
    target = ['kubectl', '--kubeconfig', args.kubeconfig, '--context', TARGET_CONTEXT,
              '-n', 'aio-validation', 'exec', 'postgres-rehearsal-0', '--']

    def query(command, database, sql, target_side=False):
        invocation = command + ['psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1']
        if target_side:
            invocation += ['-U', 'rehearsal_admin']
        return subprocess.check_output(invocation + ['-d', database, '-c', sql], text=True).strip()

    size = int(query(source, args.database, 'SELECT pg_database_size(current_database())'))
    extensions = query(source, args.database, 'SELECT extname FROM pg_extension ORDER BY extname').splitlines()
    unsupported = set(extensions) - {'plpgsql', 'pgcrypto', 'uuid-ossp', 'citext', 'hstore', 'btree_gin', 'btree_gist'}
    if unsupported:
        raise RuntimeError('Review source extensions before restore: ' + ', '.join(sorted(unsupported)))
    # Reserve room for source snapshots, indexes, WAL and synthetic probes on the 4Gi target.
    if size > 512 * 1024 * 1024:
        raise RuntimeError('Source DB exceeds 512Mi rehearsal allowance; size the target before copying')
    existing = query(target, 'postgres', f"SELECT 1 FROM pg_database WHERE datname='{destination}'", True)
    if existing:
        raise RuntimeError('Destination already exists; refusing to overwrite ' + destination)
    result = {'sourceDatabase': args.database, 'targetDatabase': destination,
              'sourceBytes': size, 'extensions': extensions, 'executed': False}
    if not args.execute:
        print(json.dumps(result, indent=2))
        return
    subprocess.run(target + ['createdb', '-U', 'rehearsal_admin', destination], check=True)
    # Pipes remain binary end-to-end, including on Windows. Never print the archive.
    producer = subprocess.Popen(source + ['pg_dump', '-d', args.database, '-Fc', '--no-owner', '--no-acl'],
                                stdout=subprocess.PIPE)
    try:
        consumer = subprocess.Popen(target[:-3] + ['exec', '-i', 'postgres-rehearsal-0', '--',
                                    'pg_restore', '-U', 'rehearsal_admin', '-d', destination,
                                    '--exit-on-error', '--single-transaction', '--no-owner', '--no-acl'],
                                    stdin=producer.stdout)
        producer.stdout.close()
        restore_code = consumer.wait()
        dump_code = producer.wait()
    except BaseException:
        producer.terminate()
        producer.wait()
        raise
    if dump_code or restore_code:
        raise RuntimeError(f'Stream failed (dump={dump_code}, restore={restore_code}); '
                           'destination is not accepted and was retained for inspection')
    result.update(executed=True, observedAt=datetime.now(timezone.utc).isoformat(),
                  targetBytes=int(query(target, destination, 'SELECT pg_database_size(current_database())', True)),
                  tableCount=int(query(target, destination,
                      "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema')", True)),
                  sourceRemainsWriteAuthority=True, applicationAcceptance=False)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
