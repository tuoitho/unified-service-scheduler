#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
LONDON_DEALERSHIP_ID="11111111-1111-1111-1111-111111111111"
OIL_CHANGE_ID="22222222-2222-2222-2222-222222222221"
EV_DIAG_ID="22222222-2222-2222-2222-222222222223"

if date -u -d "+14 days" +"%Y-%m-%d" >/dev/null 2>&1; then
  TARGET_DATE=$(date -u -d "+14 days" +"%Y-%m-%d")
else
  TARGET_DATE=$(date -u -v+14d +"%Y-%m-%d")
fi

MORNING_SLOT="${TARGET_DATE}T09:00:00Z"
BURST_SLOT="${TARGET_DATE}T14:00:00Z"

gen_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    cat /proc/sys/kernel/random/uuid
  fi
}

echo ""
echo "=== Unified Service Scheduler: End-to-End & Concurrency Demo ==="
echo "Target Base URL : ${BASE_URL}"
echo "Demo Date (UTC) : ${TARGET_DATE}"
echo ""

echo "[1/6] Checking service health (/actuator/health)..."
curl -fsS "${BASE_URL}/actuator/health"
echo -e "\n"

echo "[2/6] Checking real-time availability for OIL_CHANGE at ${MORNING_SLOT}..."
curl -fsS "${BASE_URL}/api/v1/availability/check?dealershipId=${LONDON_DEALERSHIP_ID}&serviceTypeId=${OIL_CHANGE_ID}&startTime=${MORNING_SLOT}"
echo -e "\n"

echo "[3/6] Booking single OIL_CHANGE appointment..."
SINGLE_RESP=$(curl -fsS -X POST "${BASE_URL}/api/v1/appointments" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: demo-bash-single" \
  -d "{
    \"dealershipId\": \"${LONDON_DEALERSHIP_ID}\",
    \"customerId\": \"$(gen_uuid)\",
    \"vehicleId\": \"$(gen_uuid)\",
    \"serviceTypeId\": \"${OIL_CHANGE_ID}\",
    \"desiredStartTime\": \"${MORNING_SLOT}\",
    \"notes\": \"Routine synthetic oil service\"
  }")
echo "${SINGLE_RESP}"
echo ""

echo "[4/6] Firing 12 concurrent EV_BATTERY_DIAG requests for ${BURST_SLOT}..."
TMP_DIR=$(mktemp -d)
trap 'rm -rf "${TMP_DIR}"' EXIT

for i in $(seq 1 12); do
  (
    STATUS=$(curl -s -o "${TMP_DIR}/body_${i}.json" -w "%{http_code}" \
      -X POST "${BASE_URL}/api/v1/appointments" \
      -H "Content-Type: application/json" \
      -H "X-Correlation-ID: demo-bash-burst-${i}" \
      -d "{
        \"dealershipId\": \"${LONDON_DEALERSHIP_ID}\",
        \"customerId\": \"$(gen_uuid)\",
        \"vehicleId\": \"$(gen_uuid)\",
        \"serviceTypeId\": \"${EV_DIAG_ID}\",
        \"desiredStartTime\": \"${BURST_SLOT}\",
        \"notes\": \"Concurrent EV burst #${i}\"
      }")
    echo "${STATUS}" > "${TMP_DIR}/status_${i}.txt"
  ) &
done
wait

CREATED=$(grep -l "^201$" "${TMP_DIR}"/status_*.txt | wc -l | tr -d ' ')
CONFLICTS=$(grep -l "^409$" "${TMP_DIR}"/status_*.txt | wc -l | tr -d ' ')
echo "Burst Summary -> 201 Created: ${CREATED} | 409 Conflict: ${CONFLICTS}"

if [ "${CREATED}" -ne 2 ] || [ "${CONFLICTS}" -ne 10 ]; then
  echo "ERROR: Expected 2 created and 10 conflicts!" >&2
  exit 1
fi
echo ""

echo "[5/6] Listing confirmed appointments on ${TARGET_DATE}..."
curl -fsS "${BASE_URL}/api/v1/appointments?dealershipId=${LONDON_DEALERSHIP_ID}&date=${TARGET_DATE}&status=CONFIRMED"
echo -e "\n"

echo "[6/6] Inspecting Prometheus scheduler metrics..."
curl -fsS "${BASE_URL}/actuator/prometheus" | grep -E "^scheduler_booking_(requests_total|duration_seconds_count)" || true
echo ""
echo "Demo completed successfully!"
