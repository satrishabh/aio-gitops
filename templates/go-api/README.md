# Go API starter (AIO)

Go HTTP API using only the standard library, on port 8080.

| | |
|---|---|
| Run locally | `go run .` |
| Tests | `go test ./...` |
| Health | `GET /health` → `{"status":"UP"}` |
| Image | multi-stage `Dockerfile`: tests + static build, then distroless `nonroot` (uid 65532) |

Every push to the default branch is tested, built, scanned by Trivy and deployed to **dev** by the AIO platform.
