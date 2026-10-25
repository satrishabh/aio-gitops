# Next.js starter (AIO)

Next.js 15 (App Router) with a standalone production server on port 8080.

| | |
|---|---|
| Dev server | `npm install && npm run dev` |
| Tests | `npm test` (Node's built-in test runner, `lib/*.test.mjs`) |
| Health | `GET /health` → `{"status":"UP"}` (`app/health/route.js`) |
| Image | multi-stage `Dockerfile`: tests + `next build`, then `node server.js` as uid 10001 |

Every push to the default branch is tested, built, scanned by Trivy and deployed to **dev** by the AIO platform.
