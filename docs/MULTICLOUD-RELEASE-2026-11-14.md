# AIO product release — 2026-11-14

Source merge: `1ef488c7b541`, preserving latest main workload values. Cloud Build `426b93c9-f450-4e52-8e85-d9eb2dbae946` builds `multicloud-744aba3` from this source.

Included: project Certificates tab; multi-cloud target catalog; portable Helm package and provider preflight generator; separate managed workload-pull credential source with GHCR-only legacy fallback; Azure/multi-cloud runbooks. Local verification passed 95 backend tests, eight provider-plan tests, 13 portable chart checks, Helm lint and frontend build. The image build also runs backend tests.

This rollout updates AIO on its existing GKE cluster. It does not provision/register other cloud clusters, create a VM, change environment destination mappings, enable independent delivery or install cert-manager/DNS integrations. Existing registry behavior remains compatible for the current GHCR configuration.

Previous image digest: `sha256:748eb23d1b7bb070f58046a28b0621cbfbd2df0c5ba29f973a96b395a8c3ccf1`. No database migrations are added by this release. Image and live verification results will follow build completion.

Cloud Build succeeded and published digest `sha256:6aeb3f6bdf3f8d21b933903aea5cc12a667a2fedbc58f9697367d880ec103805`. The platform GitOps values pin this digest. Live acceptance follows publication.
