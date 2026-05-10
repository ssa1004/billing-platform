{{/*
공통 helper. 표준 Bitnami / Helm 컨벤션 — name / fullname / labels / selectorLabels.
이름 truncate 는 K8s DNS limit 63 자 때문 (앞에서 자르고 trailing dash 제거).
*/}}

{{- define "billing-platform.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "billing-platform.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "billing-platform.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
공통 labels — recommended labels (https://kubernetes.io/docs/concepts/overview/working-with-objects/common-labels/).
managed-by 와 chart 까지 박아두면 helm release 추적이 단순.
*/}}
{{- define "billing-platform.labels" -}}
helm.sh/chart: {{ include "billing-platform.chart" . }}
{{ include "billing-platform.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: billing-platform
{{- end -}}

{{/*
selector labels 는 *변하면 안 됨* — Deployment.spec.selector.matchLabels 는 immutable.
그래서 version / chart 같은 변하는 label 은 selectorLabels 에 넣지 않음.
*/}}
{{- define "billing-platform.selectorLabels" -}}
app.kubernetes.io/name: {{ include "billing-platform.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "billing-platform.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "billing-platform.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
Image — tag 가 비어있으면 Chart.AppVersion 으로 fallback. CI 가 항상 tag 를
명시하는 게 권장이지만, 실수로 안 넣어도 chart 가 알 수 있는 값으로 떨어짐.
*/}}
{{- define "billing-platform.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{/*
공통 env block — Deployment 와 CronJob 둘 다 같은 환경변수를 받아야 하니
helper 로 추출. 변경 시 두 군데 동시 수정 실수 방지.
*/}}
{{- define "billing-platform.commonEnv" -}}
- name: SPRING_PROFILES_ACTIVE
  value: {{ .Values.springProfile | quote }}
- name: SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE
  value: {{ .Values.gracefulShutdown.springTimeout | quote }}
# ConfigMap 에서 비-기밀 설정 일괄 주입
- name: DB_HOST
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: DB_HOST } }
- name: DB_PORT
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: DB_PORT } }
- name: DB_NAME
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: DB_NAME } }
- name: DB_REPLICA_HOST
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: DB_REPLICA_HOST } }
- name: DB_REPLICA_PORT
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: DB_REPLICA_PORT } }
- name: REDIS_HOST
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: REDIS_HOST } }
- name: REDIS_PORT
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: REDIS_PORT } }
- name: KAFKA_BOOTSTRAP
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: KAFKA_BOOTSTRAP } }
- name: PG_BASE_URL
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: PG_BASE_URL } }
- name: OAUTH_ISSUER_URI
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: OAUTH_ISSUER_URI } }
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: OTEL_EXPORTER_OTLP_ENDPOINT } }
- name: OTEL_SERVICE_NAME
  valueFrom: { configMapKeyRef: { name: {{ include "billing-platform.fullname" . }}-config, key: OTEL_SERVICE_NAME } }
# Secret — DB
- name: DB_USER
  valueFrom:
    secretKeyRef:
      name: {{ default (printf "%s-db" (include "billing-platform.fullname" .)) .Values.postgres.credentials.existingSecret }}
      key: {{ .Values.postgres.credentials.usernameKey }}
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ default (printf "%s-db" (include "billing-platform.fullname" .)) .Values.postgres.credentials.existingSecret }}
      key: {{ .Values.postgres.credentials.passwordKey }}
{{- if or .Values.redis.credentials.existingSecret .Values.redis.credentials.password }}
- name: REDIS_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ default (printf "%s-redis" (include "billing-platform.fullname" .)) .Values.redis.credentials.existingSecret }}
      key: {{ .Values.redis.credentials.passwordKey }}
{{- end }}
{{- if or .Values.pg.credentials.existingSecret .Values.pg.credentials.apiKey }}
- name: PG_API_KEY
  valueFrom:
    secretKeyRef:
      name: {{ default (printf "%s-pg" (include "billing-platform.fullname" .)) .Values.pg.credentials.existingSecret }}
      key: {{ .Values.pg.credentials.apiKeyKey }}
- name: PG_WEBHOOK_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ default (printf "%s-pg" (include "billing-platform.fullname" .)) .Values.pg.credentials.existingSecret }}
      key: {{ .Values.pg.credentials.webhookSecretKey }}
{{- end }}
{{- range $k, $v := .Values.extraEnv }}
- name: {{ $k }}
  value: {{ $v | quote }}
{{- end }}
{{- end -}}
