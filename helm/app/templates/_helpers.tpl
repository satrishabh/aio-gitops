{{- define "app.name" -}}
{{- default .Release.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "app.labels" -}}
app.kubernetes.io/name: {{ include "app.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "app.selectorLabels" -}}
app.kubernetes.io/name: {{ include "app.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
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
{{- if and .externalDns .host }}
external-dns.alpha.kubernetes.io/hostname: {{ if .wwwRedirect }}{{ printf "%s,www.%s" .host .host | quote }}{{ else }}{{ .host | quote }}{{ end }}
{{- end }}
{{- range $k, $v := .extra }}
{{ $k }}: {{ $v | quote }}
{{- end }}
{{- end -}}
