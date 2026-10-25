# React + Vite starter (AIO)

A dashboard-style React UI built with Vite and served by unprivileged nginx on port 8080.

| | |
|---|---|
| Dev server | `npm install && npm run dev` |
| Tests | `npm test` (Node's built-in test runner, `src/*.test.js`) |
| Health | `GET /health` → `{"status":"UP"}` (served by nginx) |
| Image | multi-stage `Dockerfile`: tests + build, then `nginx-unprivileged` (uid 101) |

Every push to the default branch is tested, built, scanned by Trivy and deployed to **dev** by the AIO platform.
Promote to staging and prod (approval-gated) from the portal.
