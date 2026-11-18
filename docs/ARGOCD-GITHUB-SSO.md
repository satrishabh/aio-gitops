# Argo CD on Azure — GitHub SSO (14 November 2026)

Argo CD UI: **https://argocd.sarvaikam.com** (sign in with GitHub). The local `admin`
password account is disabled and `argocd-initial-admin-secret` is deleted.

## How it is wired

| Piece | Where |
| --- | --- |
| DNS | Hostinger A `argocd` → `52.140.55.184` (TTL 300) |
| TLS | cert-manager `letsencrypt-azure`, Secret `argocd/argocd-sarvaikam-com-tls` |
| Ingress | `ops/portable/azure-argocd-ingress.yaml` (Traefik class `aio-azure`, HTTPS redirect) |
| Backend | `argocd-server` keeps its own self-signed TLS; Traefik re-encrypts via ServersTransport `argocd-server-tls` (Service annotations `service.serversscheme: https`, `service.serverstransport`) |
| Traefik | watches namespace `argocd` (`ops/portable/azure-ingress-values.yaml`) |
| SSO | Dex GitHub connector; OAuth App "AIO Argo CD" on GitHub user `satrishabh`, callback `https://argocd.sarvaikam.com/api/dex/callback` |
| Secret | `argocd/argocd-github-sso` (`clientID`, `clientSecret`), source file `D:/2026/AIO/.tools/argocd-github-oauth.env` (git-ignored) |
| Config | `ops/portable/azure-argocd-sso-config.yaml` |

AIO still calls Argo in-cluster (`https://argocd-server.argocd.svc`) with its scoped
`aio-native` API token; `TOOLS_ARGOCD_URL` only sets the UI links.

## Access control

Both GitHub accounts involved are personal (no organization), so Dex cannot restrict by
org: any GitHub user can authenticate, but `policy.default` is empty, so they get nothing.
Admin is granted to the **Dex subject** of GitHub user `satrishabh` (id 49035345), not an
email: GitHub reported `mr.rishiu@gmail.com` as that account's primary email, and emails can
change. Verified with `argocd admin settings rbac can`: satrishabh → admin; other users → no.

To add a person, sign them in once, read their subject from the argocd-server log line
`Web login successful` (`"sub":...`), then add `g, <sub>, role:readonly` (or admin) to
`policy.github-sso.csv` in `argocd-rbac-cm`. Move to a GitHub organization + team groups
if more people need access.

## Operations

- **Rotate the OAuth secret:** generate a new one in the GitHub OAuth App, update the .env
  file, recreate `argocd-github-sso` from it, restart `argocd-dex-server`.
- **Locked out (GitHub/Dex down):** use the cluster kubeconfig: `kubectl -n argocd port-forward
  svc/argocd-server 8080:443` still requires a login, so temporarily set
  `admin.enabled: "true"` and reset the admin password with `argocd admin initial-password`
  / `argocd account bcrypt`, then disable it again.
