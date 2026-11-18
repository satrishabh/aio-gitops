"""Publish only the target database's public certificate to its application trust ConfigMap.

After certificate rotation, restart/reload PostgreSQL and restart Java/Node clients.
Does not retrieve the private key or alter application passwords.
"""
import argparse
import base64
import json
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--kubeconfig', required=True)
args = parser.parse_args()
base = ['kubectl', '--kubeconfig', args.kubeconfig, '--context', 'aio-azure-creative', '-n', 'aio-validation']
certificate = base64.b64decode(subprocess.check_output(base + ['get', 'secret', 'postgres-rehearsal-tls',
                               '-o', r'jsonpath={.data.tls\.crt}']))
manifest = json.loads(subprocess.check_output(base + ['get', 'configmap', 'rehearsal-db-trust', '-o', 'json']))
manifest.setdefault('data', {})['ca.crt'] = certificate.decode()
# Preserve resourceVersion to reject a concurrent edit rather than force field ownership.
subprocess.run(base + ['replace', '-f', '-'],
               input=json.dumps(manifest).encode(), check=True)
