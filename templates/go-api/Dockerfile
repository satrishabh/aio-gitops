# Test + build a static binary, run it on Microsoft Azure Linux distroless (MCR) as uid 65532
# golang:1-alpine = latest Go release (older stdlib versions get flagged by the vulnerability gate)
FROM golang:1-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/app .

FROM mcr.microsoft.com/azurelinux/distroless/minimal:3.0
COPY --from=build /out/app /app
USER 65532
EXPOSE 8080
ENTRYPOINT ["/app"]
