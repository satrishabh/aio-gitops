# Test + build a static binary, run it on distroless as the built-in nonroot user (uid 65532)
# golang:1-alpine = latest Go release (older stdlib versions get flagged by the vulnerability gate)
FROM golang:1-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/app .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/app /app
USER 65532
EXPOSE 8080
ENTRYPOINT ["/app"]
