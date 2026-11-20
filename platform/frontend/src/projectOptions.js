// Shared by the New project form and the project Settings tab.
export const PRESETS = {
  '': { testImage: '', testCommand: '' },
  java: { testImage: 'maven:3.9-eclipse-temurin-21', testCommand: 'mvn -B verify' },
  gradle: { testImage: 'gradle:8-jdk21', testCommand: 'gradle test --no-daemon' },
  node: { testImage: 'node:20', testCommand: 'npm ci && npm test' },
  python: { testImage: 'python:3.12', testCommand: 'pip install -r requirements.txt && pytest' },
  go: { testImage: 'golang:1.22', testCommand: 'go test ./...' },
  dotnet: { testImage: 'mcr.microsoft.com/dotnet/sdk:8.0', testCommand: 'dotnet test' },
}

// How the image is built. buildah is offered only while an admin has switched on Podman / Buildah (phase 6);
// GET /api/projects/build-strategies says which can be chosen now.
export const BUILD_STRATEGIES = [
  ['auto', 'Auto: Dockerfile if present, otherwise buildpacks (default)'],
  ['dockerfile', 'Dockerfile with BuildKit'],
  ['buildpacks', 'Cloud Native Buildpacks (no Dockerfile needed)'],
  ['buildah', 'Dockerfile with Buildah'],
]

export const SEVERITIES = [
  ['HIGH,CRITICAL', 'Block on HIGH or CRITICAL (default)'],
  ['CRITICAL', 'Block on CRITICAL only'],
  ['NONE', 'Report only (never block)'],
]
