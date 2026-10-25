# Spring Boot starter (AIO)

Spring Boot 3 REST API (Java 21) on port 8080.

| | |
|---|---|
| Run locally | `mvn spring-boot:run` |
| Tests | `mvn test` (MockMvc, `src/test/java`) |
| Health | `GET /health` → `{"status":"UP"}` |
| Image | multi-stage `Dockerfile`: `mvn package` (runs tests), then a JRE Alpine image as uid 10001 |

Every push to the default branch is tested, built, scanned by Trivy and deployed to **dev** by the AIO platform.
