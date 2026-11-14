{{- define "app.name" -}}
{{- default .Release.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "app.labels" -}}
app.kubernetes.io/name: {{ include "app.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | replace ":" "-" | trunc 63 | trimSuffix "-" | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "app.selectorLabels" -}}
app.kubernetes.io/name: {{ include "app.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "app.autoscalingActive" -}}
{{- if and .Values.autoscaling.enabled (not (default false .Values.scaledDown)) (ne .Values.image.tag "0.0.0") -}}
true
{{- else -}}
false
{{- end -}}
{{- end -}}

{{- define "app.validateAutoscaling" -}}
{{- if .Values.autoscaling.enabled -}}
{{- if gt (int .Values.autoscaling.minReplicas) (int .Values.autoscaling.maxReplicas) -}}
{{- fail "autoscaling.minReplicas cannot exceed maxReplicas" -}}
{{- end -}}
{{- $resources := default dict .Values.resources -}}
{{- $requests := default dict $resources.requests -}}
{{- $cpu := toString (default "" $requests.cpu) -}}
{{- if or (not (regexMatch "^[0-9]+(\\.[0-9]+)?m?$" $cpu)) (le (float64 (trimSuffix "m" $cpu)) 0.0) -}}
{{- fail "autoscaling requires a positive resources.requests.cpu for the app container" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "app.image" -}}
{{- if hasPrefix "sha256:" .Values.image.tag -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" .Values.image.tag) -}}
{{- fail "invalid image digest" -}}
{{- end -}}
{{- printf "%s@%s" .Values.image.repository .Values.image.tag -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag -}}
{{- end -}}
{{- end -}}

{{- /*
Ingress annotations for TLS (cert-manager), redirects, path rewrite and external-dns.
Input dict: tls, issuer, certOwner (only the owner asks cert-manager for the host's certificate),
forceHttps, wwwRedirect, strip (rewrite /prefix/x -> /x), externalDns, host, extra (map passthrough).
Renders nothing when no annotation applies, so default values produce the same manifests as before.
*/ -}}
{{- define "app.ingressAnnotations" -}}
{{- if and .tls .issuer .certOwner }}
cert-manager.io/cluster-issuer: {{ .issuer | quote }}
{{- end }}
{{- if eq (include "app.ingressController" .controller) "traefik" }}
{{- with (include "app.traefikMiddlewareRefs" .) }}
traefik.ingress.kubernetes.io/router.middlewares: {{ . | quote }}
{{- end }}
{{- else }}
{{- if and .tls .forceHttps }}
nginx.ingress.kubernetes.io/force-ssl-redirect: "true"
{{- end }}
{{- if .wwwRedirect }}
nginx.ingress.kubernetes.io/from-to-www-redirect: "true"
{{- end }}
{{- if .strip }}
nginx.ingress.kubernetes.io/use-regex: "true"
nginx.ingress.kubernetes.io/rewrite-target: /$2
{{- end }}
{{- end }}
{{- if and .externalDns .host }}
external-dns.alpha.kubernetes.io/hostname: {{ if .wwwRedirect }}{{ printf "%s,www.%s" .host .host | quote }}{{ else }}{{ .host | quote }}{{ end }}
{{- end }}
{{- range $k, $v := .extra }}
{{ $k }}: {{ $v | quote }}
{{- end }}
{{- end -}}

{{- /*
Ingress controller flavour: "nginx" (default, ingress-nginx annotations) or "traefik" (Traefik
Middleware CRDs). Redirects and prefix stripping are controller-specific; Traefik ignores nginx
annotations silently, so a cluster running Traefik must set ingress.controller: traefik.
*/ -}}
{{- define "app.ingressController" -}}
{{- $c := default "nginx" . | lower -}}
{{- if not (has $c (list "nginx" "traefik")) -}}
{{- fail (printf "ingress.controller must be nginx or traefik, got %q" $c) -}}
{{- end -}}
{{- $c -}}
{{- end -}}

{{- /* Traefik middleware names for one Ingress (dict: name tls forceHttps wwwRedirect strip). */ -}}
{{- define "app.traefikMiddlewareNames" -}}
{{- $names := list -}}
{{- if and .tls .forceHttps }}{{ $names = append $names (printf "%s-https" .name) }}{{ end -}}
{{- if .wwwRedirect }}{{ $names = append $names (printf "%s-www" .name) }}{{ end -}}
{{- if .strip }}{{ $names = append $names (printf "%s-strip" .name) }}{{ end -}}
{{- join "," $names -}}
{{- end -}}

{{- /* router.middlewares annotation value: <namespace>-<middleware>@kubernetescrd, comma-separated. */ -}}
{{- define "app.traefikMiddlewareRefs" -}}
{{- $ns := .namespace -}}
{{- $refs := list -}}
{{- range (splitList "," (include "app.traefikMiddlewareNames" .)) }}{{ if . }}{{ $refs = append $refs (printf "%s-%s@kubernetescrd" $ns .) }}{{ end }}{{ end -}}
{{- join "," $refs -}}
{{- end -}}

{{- /* Traefik Middleware objects for one Ingress (dict: name namespace labels tls forceHttps wwwRedirect host strip path). */ -}}
{{- define "app.traefikMiddlewares" -}}
{{- if and .tls .forceHttps }}
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: {{ .name }}-https
  labels: {{- .labels | nindent 4 }}
spec:
  redirectScheme: { scheme: https, permanent: true }
---
{{- end }}
{{- if .wwwRedirect }}
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: {{ .name }}-www
  labels: {{- .labels | nindent 4 }}
spec:
  redirectRegex:
    regex: {{ printf `^https?://www\.%s/(.*)` (.host | replace "." `\.`) | quote }}
    replacement: {{ printf "https://%s/${1}" .host | quote }}
    permanent: true
---
{{- end }}
{{- if .strip }}
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: {{ .name }}-strip
  labels: {{- .labels | nindent 4 }}
spec:
  stripPrefix:
    prefixes: [{{ trimSuffix "/" .path | quote }}]
---
{{- end }}
{{- end -}}
