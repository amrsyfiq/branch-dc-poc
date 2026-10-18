#!/bin/bash
# Shows what happens when a branch is down during a holiday rush (on Kubernetes):
#   the events wait in Kafka -> the listener retries (one WARN line per attempt) -> the branch is back ->
#   every pass arrives -> nothing went to the dead-letter topic.
# Run with the stack deployed and  node k8s/port-forward.mjs  running:  bash scripts/demo-outage.sh   (~2 min)
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite /opt/... paths
set -u
COUNT=20
LISTENER=(-n dc -l app.kubernetes.io/name=branch-listener,branch=a)

dlt_records() {   # records ever written to dc.branch-a.DLT (sum of its partitions' end offsets)
  kubectl exec -n platform kafka-0 -- /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic dc.branch-a.DLT 2>/dev/null | awk -F: '{ s += $3 } END { print s + 0 }'
}
delivered() {     # "Delivered" lines in listener A's logs (all its pods) since the demo started
  kubectl logs "${LISTENER[@]}" --since-time="$since" --tail=-1 2>/dev/null | grep -c '"Delivered '
}
message() {       # the "message" field of JSON log lines
  sed -E 's/.*"message":"([^"]*)".*/\1/'
}

since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
dlt_before=$(dlt_records)

echo "1. Taking branch A down (scaling its Deployment to 0)"
kubectl -n branch-a scale deploy/branch-a --replicas=0 >/dev/null
kubectl -n branch-a wait --for=delete pod -l app.kubernetes.io/name=branch-service --timeout=60s >/dev/null 2>&1

echo "2. DC: holiday rush at checkpoint A, $COUNT new passes"
curl -s -X POST "localhost:8090/rush/A?count=$COUNT" | grep -oE '"(eventCount|status)":[^,]*' | sed 's/^/   /'

echo "3. Listener A cannot deliver; it retries (the events wait in Kafka, in order):"
sleep 25
kubectl logs "${LISTENER[@]}" --since-time="$since" --tail=-1 2>/dev/null | grep 'failed (attempt' | tail -3 | message \
  | sed 's/^/   /' | cut -c1-200

echo "4. Bringing branch A back (1 replica)"
kubectl -n branch-a scale deploy/branch-a --replicas=1 >/dev/null
kubectl -n branch-a rollout status deploy/branch-a --timeout=180s >/dev/null

echo "5. Waiting for the listener's next retry (WSO2 may keep the endpoint suspended for a few seconds):"
for _ in $(seq 1 90); do
  [ "$(delivered)" -ge "$COUNT" ] && break
  sleep 2
done
echo "   delivered: $(delivered) of $COUNT"

dlt_after=$(dlt_records)
echo "6. Dead-letter topic: $((dlt_after - dlt_before)) new record(s) (expected 0: a branch being down is not the events' fault)"
