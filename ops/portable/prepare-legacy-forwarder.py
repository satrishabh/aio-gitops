"""Stage GCP legacy-IP -> native Azure HTTPS forwarding, without changing routes.

Uses an existing source-local image pull Secret. No credential export.
"""
from pathlib import Path
import subprocess

manifest=Path(__file__).with_name('azure-transition-edge.yaml').read_text().split('---\napiVersion: cert-manager.io')[0]
manifest=manifest.replace('namespace: aio-validation','namespace: dev')
manifest=manifest.replace('transition-edge','legacy-azure-edge').replace('ghcr-readonly','ghcr-pull')
manifest=manifest.replace('https://34.180.3.18','https://52.140.55.184')
manifest=manifest.replace('server_name learn.sarvaikam.com','server_name sarvaikam.com learn.sarvaikam.com')
subprocess.run(['kubectl','--context','gke_project-d28840cd-be2f-4901-85e_asia-south1_cluster-1',
                'apply','-f','-'],input=manifest.encode(),check=True)
