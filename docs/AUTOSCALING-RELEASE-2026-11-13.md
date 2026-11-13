# AIO autoscaling release — 2026-11-13

Source revision: `1ae9f576633207f3e529f0dbcf0a8a9caf923624`.

The shared application chart adds opt-in app-container CPU and Istio request-rate autoscaling, bounded scaling behavior, Stop/Start handling, and Rollout targeting. All 89 backend tests, the frontend build, 22 autoscaling contract checks, both chart lints, and rendering of 35 existing environment files passed. Kubernetes accepted the hybrid HPA in a server-side dry-run.

The user explicitly authorized publication, build, and deployment after the initial automatic review rejection. Source publication succeeded, and Argo CD reported Synced/Healthy at the source revision. Cloud Build `d0287a60-9840-436a-957c-b935435b1603` succeeded, including frontend and backend build/test stages, and published image tag `autoscaling-1ae9f5766332` with digest `sha256:f3144e475777b11ce94fe48823e995fca06ce44c57913b651da755d18a64d028`. GitOps pins that digest. Live rollout verification follows publication.

Autoscaling remains disabled by default; AIO remains at one replica. The cluster has CPU metrics but no custom-metrics API. No live HPA or mesh/metrics infrastructure is installed by this release. Independent delivery and tool SSO remain disabled pending their documented prerequisites. The previous platform digest is `sha256:db7d5b0304d29f36d81429823e4c4af5cc657f930ff45a47bcca6b1d3bcb6ffe`.

Production rollout completed at GitOps revision `e60f069415957d90c22481c66bbca6c01923917c`. Pod `aio-platform-7c7dc59d5b-7kk65` became Ready with zero restarts and the exact published digest. Internal readiness returned `{"status":"UP"}`; Kubernetes reported the Deployment successfully rolled out. Public HTTPS login returned HTTP 200 during rollout. JVM startup took approximately 61 seconds; the previous pod remained available until readiness passed.

## UI follow-up

The public frontend bundle was verified to contain registry/delivery settings, the manual deploy checkbox, GitOps release retry, artifact trust, and the Helm values editor. Source `b18905df6ad4` adds visible autoscaling guidance under each environment's CD Config tab: CPU and hybrid examples, prerequisite checks, HPA replica ownership, Stop/Start, and fixed-count rollback. This is configuration guidance rather than a claim that live scaling is enabled. The frontend production build passed. Cloud Build `57aebb1f-0eda-4895-b2ce-195af17b03ea` builds the follow-up image `ui-b18905df6ad4`.

UI rollout verified at revision `955954b66d2e5055609901f3c92c432d7f299fbc`: Argo CD Synced/Healthy; pod `aio-platform-54c468cfdf-7v7bn` Ready with zero restarts; readiness UP. The successful build produced digest `sha256:748eb23d1b7bb070f58046a28b0621cbfbd2df0c5ba29f973a96b395a8c3ccf1`. Public login returned HTTP 200 and served `/assets/index-wxJ3B42l.js`, verified to contain the autoscaling section and both examples alongside registry settings and release retry. Verification covers the served production asset and readiness; an authenticated browser interaction was not performed.
