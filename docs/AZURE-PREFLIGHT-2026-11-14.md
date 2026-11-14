# Azure migration preflight — 2026-11-14

Authenticated browser login succeeded using Azure CLI with the Windows broker disabled for that process. Device-code login was blocked by Entra Security Defaults (530035); no security policy was changed.

Target: subscription `150909ba-4c23-44e9-b98d-e8a2d433f709`, resource group `creative`, AKS `creative`, South India. User credentials were obtained without `--admin` and stored in a separate, untracked kubeconfig. The GCP context was preserved.

Two `Standard_F2as_v6` nodes were Ready on Kubernetes 1.36.5. System pods were running; metrics-server and Azure Disk/File CSI storage were present. No ingress class, cert-manager, Tekton or Argo CD installation was observed.

## Isolated rehearsal

`ops/portable/azure-smoke.yaml` created only namespace `aio-migration-test`, a ClusterIP service, a network test pod, a 1Gi managed-csi claim and an AIO image-check pod. No external load balancer or public application endpoint was created. The AIO image-check overrides the application entrypoint with `java -version`, so it cannot start application workers or connect to production databases.

Passed: public test-image pull, pod readiness, HTTP via local-only port-forward, in-cluster service DNS, mounted disk writes, and reading the same data after deleting/recreating the test pod. The resolved test-image digest and structured results are recorded in `evidence/azure-creative-preflight.json`.

The first unauthenticated pull of the exact production AIO digest failed. Automatic approval review initially rejected the proposed credential transfer. After the user explicitly approved it, a short-lived GCP registry token was passed in memory into a Secret in `aio-migration-test`. AKS pulled the exact digest `sha256:6aeb3f6bdf3f8d21b933903aea5cc12a667a2fedbc58f9697367d880ec103805`; `java -version` completed successfully with exit code 0 on Temurin 21.0.13+9 at `2026-11-13T21:25:30Z`. The token was neither printed nor written to local files/Git. The Secret was immediately deleted and the disposable namespace removed. This validates authenticated cross-cloud image pull and the Java runtime only; durable registry identity, application startup and data migration remain pending.

After evidence capture, remove only the disposable namespace and verify the dynamically provisioned PV is deleted. The smoke manifest is a reproducible test fixture, not an application deployment or supported-cloud certification.

## Remaining sequence

1. Establish durable destination registry access and verify the exact application digest.
2. Pin and install missing controllers with independent target identities/configuration.
3. Rehearse isolated database backups/restores and define write authority and rollback.
4. Stage applications on validation routes, preserving desired replica counts; avoid duplicate active AIO workers.
5. Validate OIDC, certificates, authorization, delivery and recovery before enabling a traffic cutover.

The e2 dashboard remains a credential-free planning snapshot. Azure access and infrastructure smoke evidence do not mean the e2 node has execution access or that applications/data have migrated.
