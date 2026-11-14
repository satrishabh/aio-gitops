"""Mirror the native AIO image to the user's GHCR registry without Docker daemon.

Registry credentials are scoped by host in a private temporary directory and removed
after the copy. Neither credentials nor image archives are written into the repo.
"""
import argparse
import base64
import csv
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', required=True)
parser.add_argument('--tag', required=True)
parser.add_argument('--kubeconfig', required=True)
parser.add_argument('--crane', required=True)
args = parser.parse_args()
prefix = 'asia-south1-docker.pkg.dev/project-d28840cd-be2f-4901-85e/aio/aio-platform@sha256:'
if not args.source.startswith(prefix) or len(args.source.removeprefix(prefix)) != 64:
    raise RuntimeError('Expected a pinned image from the known AIO source registry')
if not args.tag.replace('-', '').isalnum():
    raise RuntimeError('Invalid destination tag')
destination = 'ghcr.io/satrishabh/aio-platform:' + args.tag
raw = json.loads(subprocess.check_output(['kubectl', '--kubeconfig', args.kubeconfig, '--context',
    'aio-azure-creative', '-n', 'aio-validation', 'get', 'secret', 'ghcr-readonly', '-o', 'json']))
docker = json.loads(base64.b64decode(raw['data']['.dockerconfigjson']))
google = subprocess.check_output([r'D:\gcloud\google-cloud-sdk\bin\gcloud.cmd', 'auth', 'print-access-token'], text=True).strip()
configuration = {'auths': {'ghcr.io': docker['auths']['ghcr.io'],
    'asia-south1-docker.pkg.dev': {'auth': base64.b64encode(('oauth2accesstoken:' + google).encode()).decode()}}}
temp_root = Path(tempfile.gettempdir()).resolve()
directory = Path(tempfile.mkdtemp(prefix='aio-registry-copy-', dir=temp_root)).resolve()
try:
    if directory.parent != temp_root or not directory.name.startswith('aio-registry-copy-'):
        raise RuntimeError('Unexpected temporary credential path')
    sid = next(csv.reader(subprocess.check_output(['whoami','/user','/fo','csv','/nh'], text=True).splitlines()))[1]
    subprocess.run(['icacls', str(directory), '/inheritance:r', '/grant:r',
                    '*' + sid + ':(OI)(CI)F', '*S-1-5-18:(OI)(CI)F'], check=True, stdout=subprocess.DEVNULL)
    (directory / 'config.json').write_text(json.dumps(configuration))
    environment = os.environ.copy(); environment['DOCKER_CONFIG'] = str(directory)
    subprocess.run([args.crane, 'copy', args.source, destination], env=environment, check=True)
    digest = subprocess.check_output([args.crane, 'digest', destination], env=environment, text=True).strip()
    if digest != 'sha256:' + args.source.removeprefix(prefix):
        raise RuntimeError('Mirrored manifest digest did not match the source')
    print(json.dumps({'repository':'ghcr.io/satrishabh/aio-platform','tag':args.tag,'digest':digest,'matched':True}))
finally:
    # Explicitly verify the absolute cleanup target stays in the named temp root.
    if directory.exists():
        resolved = directory.resolve()
        if resolved.parent != temp_root or not resolved.name.startswith('aio-registry-copy-'):
            raise RuntimeError('Refusing unsafe recursive credential cleanup')
        shutil.rmtree(resolved)
