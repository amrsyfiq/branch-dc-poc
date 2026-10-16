#!/bin/bash
# Shows what happens when a branch is down during a holiday rush:
#   the events wait in Kafka -> the listener retries (one WARN line per attempt) -> the branch is back ->
#   every pass arrives -> nothing went to the dead-letter topic.
# Run with the stack up:  bash scripts/demo-outage.sh       (about 2 minutes)
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite /opt/... paths
set -u
COUNT=20

dlt_records() {   # records ever written to dc.branch-a.DLT (sum of its partitions' end offsets)
  docker exec bdc-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9094 \
    --topic dc.branch-a.DLT 2>/dev/null | awk -F: '{ s += $3 } END { print s + 0 }'
}
delivered() {     # "Delivered" lines in listener A's log since the demo started
  docker logs --since "$since" bdc-listener-a 2>&1 | grep -c "Delivered"
}

since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
dlt_before=$(dlt_records)

echo "1. Stopping branch A"
docker stop bdc-branch-a >/dev/null

echo "2. DC: holiday rush at checkpoint A, $COUNT new passes"
curl -s -X POST "localhost:8090/rush/A?count=$COUNT" | grep -oE '"(eventCount|status)":[^,]*' | sed 's/^/   /'

echo "3. Listener A cannot deliver; it retries (the events wait in Kafka, in order):"
sleep 25
docker logs --since "$since" bdc-listener-a 2>&1 | grep "failed (attempt" | tail -3 \
  | sed -E 's/^.*(Delivery of)/   \1/' | cut -c1-200

echo "4. Starting branch A again"
docker start bdc-branch-a >/dev/null
until [ "$(docker inspect -f '{{.State.Health.Status}}' bdc-branch-a)" = healthy ]; do sleep 2; done

echo "5. Waiting for the listener's next retry (WSO2 may keep the endpoint suspended for a few seconds):"
for _ in $(seq 1 90); do
  [ "$(delivered)" -ge "$COUNT" ] && break
  sleep 2
done
echo "   delivered: $(delivered) of $COUNT"

dlt_after=$(dlt_records)
echo "6. Dead-letter topic: $((dlt_after - dlt_before)) new record(s) (expected 0: a branch being down is not the events' fault)"
