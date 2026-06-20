{{/*
Expand the name of the chart.
*/}}
{{- define "forvum.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name (release-qualified, DNS-safe, <=63 chars).
*/}}
{{- define "forvum.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Chart name and version, as used by the helm.sh/chart label.
*/}}
{{- define "forvum.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "forvum.labels" -}}
helm.sh/chart: {{ include "forvum.chart" . }}
{{ include "forvum.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels.
*/}}
{{- define "forvum.selectorLabels" -}}
app.kubernetes.io/name: {{ include "forvum.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
The name of the ServiceAccount to use.
*/}}
{{- define "forvum.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "forvum.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
The name of the state PVC for this release (per-namespace isolated SQLite store).
*/}}
{{- define "forvum.pvcName" -}}
{{- if .Values.persistence.existingClaim }}
{{- .Values.persistence.existingClaim }}
{{- else }}
{{- printf "%s-state" (include "forvum.fullname" .) }}
{{- end }}
{{- end }}

{{/*
The resolved container image reference (digest wins over tag; tag defaults to <appVersion>-native).
*/}}
{{- define "forvum.image" -}}
{{- $repo := .Values.image.repository -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" $repo .Values.image.digest -}}
{{- else -}}
{{- $tag := .Values.image.tag | default (printf "%s-native" .Chart.AppVersion) -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end -}}
{{- end }}
