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
