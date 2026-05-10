#!/usr/bin/env sh
# notification-hub stub — billing.* 토픽을 구독해서 stdout 에 dump.
# 실제 notification-hub 는 템플릿 / 라우팅 / 발송 큐가 붙지만, 여기서는
# "billing-platform 의 outbox relay 가 발사한 메시지가 정말 외부로 흘러나간다" 는
# 사실만 시연 목적으로 보여줌.
#
# 한 consumer 가 multi-topic 을 잡으려면 `--include` 정규식을 씀.
set -eu

BOOTSTRAP="${BOOTSTRAP:-kafka:9092}"
TOPIC_REGEX="${TOPIC_REGEX:-billing[.].*}"

echo "[notification-stub] waiting for kafka..."
until kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do
  sleep 2
done
echo "[notification-stub] kafka ready. subscribing to /$TOPIC_REGEX/"

exec kafka-console-consumer.sh \
  --bootstrap-server "$BOOTSTRAP" \
  --include "$TOPIC_REGEX" \
  --from-beginning \
  --consumer-property metadata.max.age.ms=5000  \
  --consumer-property auto.offset.reset=earliest \
  --property print.timestamp=true \
  --property print.key=true \
  --property print.headers=false \
  --property print.value=true \
  --property key.separator=" | " \
  --formatter kafka.tools.DefaultMessageFormatter
