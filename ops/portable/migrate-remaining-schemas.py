"""Native Azure migration: add profile/blog/AIO snapshots as schemas, no proxy substitute.

Source credentials remain source-local. Data streams directly into existing aio_rehearsal.
Existing schemas are never overwritten. Runtime/authority cutover is a separate stage.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--kubeconfig', required=True)
parser.add_argument('--execute', action='store_true')
args = parser.parse_args()
directory = Path(__file__).parent
spec = importlib.util.spec_from_file_location('schema_stream', directory / 'stream-schema-rehearsal.py')
stream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stream)
target = ['kubectl', '--kubeconfig', args.kubeconfig, '--context', 'aio-azure-creative',
          '-n', 'aio-validation', 'exec', 'postgres-rehearsal-0', '--']
target_i = target.copy()
target_i.insert(target_i.index('exec') + 1, '-i')
for schema in ['profile_manager', 'sarvaika_blog', 'aio_platform']:
    if stream.query(target, stream.TARGET_DATABASE,
                    'SELECT 1 FROM pg_namespace WHERE nspname=' + stream.literal(schema), True):
        raise RuntimeError('Existing destination schema must not be overwritten: ' + schema)
if int(stream.query(target, stream.TARGET_DATABASE,
                    "SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace", True)):
    raise RuntimeError('Public staging schema is not empty; refusing restore')
clients = []
try:
    clients = json.loads(subprocess.check_output([sys.executable, str(directory / 'audit-remaining-databases.py'),
                         '--keep-clients-for-export'], text=True))
    mapping = {row['sourceDatabase']: row for row in clients}
    def source(database):
        client = mapping[database]
        return ['kubectl', '--context', stream.SOURCE_CONTEXT, '-n', client['sourceNamespace'],
                'exec', client['sourceClient'], '--']
    stream.source = source
    audits = {database: stream.audit(database) for database in mapping}
    result = {'targetDatabase': stream.TARGET_DATABASE, 'preflight': clients, 'executed': False}
    if args.execute:
        result['schemas'] = [stream.restore_schema(database, audits[database]['tables'], target, target_i,
                              mapping[database]['proposedTargetSchema']) for database in mapping]
        stream.query(target, stream.TARGET_DATABASE,
                     'REVOKE ALL ON SCHEMA profile_manager,sarvaika_blog,aio_platform FROM PUBLIC;', True)
        result.update(executed=True, observedAt=datetime.now(timezone.utc).isoformat(),
                      sourceRemainsWriteAuthority=True, nativeRuntimeCutoverComplete=False)
    print(json.dumps(result, indent=2))
finally:
    # Fixed own-client names also clean up an interrupted metadata audit.
    for namespace, name in [('dev','aio-profile-manager-audit'), ('dev','aio-sarvaika-blog-audit'),
                            ('aio-platform','aio-aio-platform-audit')]:
        subprocess.run(['kubectl', '--context', stream.SOURCE_CONTEXT, '-n', namespace,
                        'delete', 'pod', name, '--ignore-not-found', '--wait=false'], stdout=subprocess.DEVNULL)
