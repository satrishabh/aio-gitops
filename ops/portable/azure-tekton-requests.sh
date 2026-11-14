#!/usr/bin/env bash
# Single-node AKS (one B2as_v2, 1900m allocatable): upstream Tekton reserves 100m CPU per component
# but idles at ~1m. Re-run after every Tekton release apply, which resets these requests.
# Without it, waking the AIO group (ops/portable/azure-hibernation.yaml) needs a 2nd node.
set -euo pipefail
K=${K:-kubectl}
$K -n tekton-pipelines-resolvers set resources deploy/tekton-pipelines-remote-resolvers -c controller --requests=cpu=20m,memory=100Mi
# the webhook has an HPA at 100% of its CPU request; 30m still leaves it room to scale on bursts
$K -n tekton-pipelines set resources deploy/tekton-pipelines-webhook -c webhook --requests=cpu=30m,memory=100Mi
