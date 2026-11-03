#!/usr/bin/env bash
# Install ExternalDNS for a delegated public Cloud DNS zone.
# Example:
#   PROJECT_ID=... DNS_ZONE=app.example.com DNS_ZONE_NAME=app-example \
#     bash bootstrap/install-external-dns.sh
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-project-d28840cd-be2f-4901-85e}"
DNS_ZONE="${DNS_ZONE:?set DNS_ZONE to the delegated DNS suffix, e.g. app.example.com}"
DNS_ZONE_NAME="${DNS_ZONE_NAME:?set DNS_ZONE_NAME to the Cloud DNS managed-zone name}"
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
DNS_GSA="${DNS_GSA:-aio-platform-dns@${PROJECT_ID}.iam.gserviceaccount.com}"
EXTERNAL_DNS_VERSION="${EXTERNAL_DNS_VERSION:-v0.24.0}"
TXT_OWNER_ID="${TXT_OWNER_ID:-aio-platform-${DNS_ZONE//./-}}"
DNS_ROLE="${DNS_ROLE:-aioDnsZoneEditor}"
DNS_PERMISSIONS='dns.changes.create,dns.changes.get,dns.changes.list,dns.managedZones.get,dns.managedZones.list,dns.resourceRecordSets.create,dns.resourceRecordSets.delete,dns.resourceRecordSets.get,dns.resourceRecordSets.list,dns.resourceRecordSets.update'

[[ "$PROJECT_ID" =~ ^[a-z][a-z0-9-]+$ ]] || { echo "invalid project ID"; exit 1; }
[[ "$DNS_ZONE" =~ ^[a-z0-9.-]+$ && "$DNS_ZONE" != .* && "$DNS_ZONE" != *. && "$DNS_ZONE" != *..* ]] || {
  echo "invalid DNS_ZONE: $DNS_ZONE"; exit 1;
}
[[ "$DNS_ZONE_NAME" =~ ^[a-z][a-z0-9-]{0,62}$ ]] || { echo "invalid DNS_ZONE_NAME"; exit 1; }
[[ "$TXT_OWNER_ID" =~ ^[a-zA-Z0-9.-]+$ ]] || { echo "invalid TXT_OWNER_ID"; exit 1; }

ZONE_FQDN=$(gcloud dns managed-zones describe "$DNS_ZONE_NAME" --project="$PROJECT_ID" --format='value(dnsName)')
[[ "${ZONE_FQDN%.}" == "$DNS_ZONE" ]] || {
  echo "Cloud DNS zone $DNS_ZONE_NAME is $ZONE_FQDN, expected ${DNS_ZONE}."; exit 1;
}
if ! gcloud iam service-accounts describe "$DNS_GSA" --project="$PROJECT_ID" >/dev/null 2>&1; then
  gcloud iam service-accounts create "${DNS_GSA%@*}" --project="$PROJECT_ID" \
    --display-name='AIO delegated Cloud DNS editor'
fi

ROLE_NAMES=$(gcloud iam roles list --project="$PROJECT_ID" --format='value(name)')
if ! grep -qx "projects/${PROJECT_ID}/roles/${DNS_ROLE}" <<<"$ROLE_NAMES"; then
  gcloud iam roles create "$DNS_ROLE" --project="$PROJECT_ID" --title='AIO delegated DNS zone editor' \
    --description='Manage Cloud DNS records for AIO-managed application domains' \
    --permissions="$DNS_PERMISSIONS" --stage=GA
else
  gcloud iam roles update "$DNS_ROLE" --project="$PROJECT_ID" --permissions="$DNS_PERMISSIONS"
fi
gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:${DNS_GSA}" \
  --role="projects/${PROJECT_ID}/roles/${DNS_ROLE}" --condition=None --quiet >/dev/null

kubectl create namespace external-dns --dry-run=client -o yaml | kubectl apply -f -
gcloud iam service-accounts add-iam-policy-binding "$DNS_GSA" --project="$PROJECT_ID" \
  --role=roles/iam.workloadIdentityUser \
  --member="serviceAccount:${PROJECT_ID}.svc.id.goog[external-dns/external-dns]" --quiet
gcloud iam service-accounts add-iam-policy-binding "$DNS_GSA" --project="$PROJECT_ID" \
  --role=roles/iam.workloadIdentityUser \
  --member="serviceAccount:${PROJECT_ID}.svc.id.goog[cert-manager/cert-manager]" --quiet
gcloud iam service-accounts add-iam-policy-binding "$DNS_GSA" --project="$PROJECT_ID" \
  --role=roles/iam.workloadIdentityUser \
  --member="principal://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${PROJECT_ID}.svc.id.goog/subject/ns/external-dns/sa/external-dns" --quiet
gcloud iam service-accounts add-iam-policy-binding "$DNS_GSA" --project="$PROJECT_ID" \
  --role=roles/iam.workloadIdentityUser \
  --member="principal://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${PROJECT_ID}.svc.id.goog/subject/ns/cert-manager/sa/cert-manager" --quiet
kubectl -n external-dns create serviceaccount external-dns --dry-run=client -o yaml | kubectl apply -f -
kubectl -n external-dns annotate serviceaccount external-dns --overwrite \
  "iam.gke.io/gcp-service-account=${DNS_GSA}"
kubectl -n cert-manager annotate serviceaccount cert-manager --overwrite \
  "iam.gke.io/gcp-service-account=${DNS_GSA}"

kubectl apply -f - <<EOF
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: external-dns
rules:
  - apiGroups: [""]
    resources: ["services", "pods", "nodes"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["discovery.k8s.io"]
    resources: ["endpointslices"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["networking.k8s.io"]
    resources: ["ingresses"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: external-dns
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: external-dns
subjects:
  - kind: ServiceAccount
    name: external-dns
    namespace: external-dns
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: external-dns
  namespace: external-dns
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: external-dns
  template:
    metadata:
      labels:
        app: external-dns
    spec:
      serviceAccountName: external-dns
      securityContext:
        runAsNonRoot: true
        runAsUser: 65534
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: external-dns
          image: registry.k8s.io/external-dns/external-dns:${EXTERNAL_DNS_VERSION}
          args:
            - --source=ingress
            - --provider=google
            - --google-project=${PROJECT_ID}
            - --domain-filter=${DNS_ZONE}
            - --registry=txt
            - --txt-owner-id=${TXT_OWNER_ID}
            - --policy=upsert-only
            - --interval=1m
            - --log-level=info
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          resources:
            requests:
              cpu: 25m
              memory: 64Mi
            limits:
              cpu: 200m
              memory: 128Mi
EOF

kubectl -n external-dns rollout status deployment/external-dns --timeout=300s
