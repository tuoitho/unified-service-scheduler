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
  elif [ -r /proc/sys/kernel/random/uuid ]; then
    cat /proc/sys/kernel/random/uuid
  elif [ -r /dev/urandom ]; then
    local hex
    hex=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
    printf '%s-%s-4%s-a%s-%s\n' \
      "${hex:0:8}" "${hex:8:4}" "${hex:13:3}" "${hex:17:3}" "${hex:20:12}"
  else
    printf '%04x%04x-%04x-4%03x-%04x-%04x%04x%04x\n' \
      $RANDOM $RANDOM $RANDOM $((RANDOM & 0x0fff)) \
      $(((RANDOM & 0x3fff) | 0x8000)) $RANDOM $RANDOM $$
  fi
}

extract_id() {
  sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([0-9a-fA-F-]\{36\}\)".*/\1/p' | head -n 1
}

TMP_DIR=$(mktemp -d)
CREATED_APPOINTMENT_IDS=()

cleanup() {
  for appt_id in "${CREATED_APPOINTMENT_IDS[@]:-}"; do
    if [ -n "${appt_id}" ]; then
      curl -s -X POST "${BASE_URL}/api/v1/appointments/${appt_id}/cancel" >/dev/null 2>&1 || true
    fi
  done
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

echo ""
echo "=== Unified Service Scheduler: End-to-End & Concurrency Demo ==="
echo "Target Base URL : ${BASE_URL}"
echo "Demo Date (UTC) : ${TARGET_DATE}"
echo ""

echo "[1/7] Checking service health (/actuator/health)..."
HEALTH_JSON=$(curl -fsS "${BASE_URL}/actuator/health")
printf '      %.120s...\n\n' "${HEALTH_JSON}"

echo "[2/7] Discovering seeded dealerships & service types..."
DEALERSHIP_JSON=$(curl -fsS "${BASE_URL}/api/v1/dealerships/${LONDON_DEALERSHIP_ID}")
SERVICE_TYPES_JSON=$(curl -fsS "${BASE_URL}/api/v1/service-types")
printf '      Dealership : %.140s...\n' "${DEALERSHIP_JSON}"
printf '      Catalog    : %.140s...\n\n' "${SERVICE_TYPES_JSON}"

echo "[3/7] Checking real-time availability for OIL_CHANGE at ${MORNING_SLOT}..."
AVAIL_JSON=$(curl -fsS "${BASE_URL}/api/v1/availability/check?dealershipId=${LONDON_DEALERSHIP_ID}&serviceTypeId=${OIL_CHANGE_ID}&startTime=${MORNING_SLOT}")
echo "      ${AVAIL_JSON}"
echo ""

echo "[4/7] Booking single OIL_CHANGE appointment at ${MORNING_SLOT}..."
SINGLE_RESP=$(curl -fsS -X POST "${BASE_URL}/api/v1/appointments" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: demo-bash-single" \
  -d "{
    \"dealershipId\": \"${LONDON_DEALERSHIP_ID}\",
    \"customerId\": \"$(gen_uuid)\",
    \"vehicleId\": \"$(gen_uuid)\",
    \"serviceTypeId\": \"${OIL_CHANGE_ID}\",
    \"desiredStartTime\": \"${MORNING_SLOT}\",
    \"notes\": \"Routine annual synthetic oil service\"
  }")
SINGLE_ID=$(printf '%s' "${SINGLE_RESP}" | extract_id)
CREATED_APPOINTMENT_IDS+=("${SINGLE_ID}")
echo "      201 Created -> Appointment=${SINGLE_ID}"
echo ""

echo "[5/7] Firing 12 simultaneous EV_BATTERY_DIAG requests for ${BURST_SLOT}..."
echo "      (London Flagship has 3 active bays, but only 2 EV_HIGH_VOLTAGE technicians: Priya & Liam)"
for i in $(seq 1 12); do
  CUST_UUID=$(gen_uuid)
  VEH_UUID=$(gen_uuid)
  (
    STATUS=$(curl -s -o "${TMP_DIR}/body_${i}.json" -w "%{http_code}" \
      -X POST "${BASE_URL}/api/v1/appointments" \
      -H "Content-Type: application/json" \
      -H "X-Correlation-ID: demo-bash-burst-${i}" \
      -d "{
        \"dealershipId\": \"${LONDON_DEALERSHIP_ID}\",
        \"customerId\": \"${CUST_UUID}\",
        \"vehicleId\": \"${VEH_UUID}\",
        \"serviceTypeId\": \"${EV_DIAG_ID}\",
        \"desiredStartTime\": \"${BURST_SLOT}\",
        \"notes\": \"Concurrent EV burst #${i}\"
      }")
    echo "${STATUS}" > "${TMP_DIR}/status_${i}.txt"
  ) &
done
wait

CREATED_COUNT=0
CONFLICT_COUNT=0
UNEXPECTED_COUNT=0
BURST_IDS=()

for i in $(seq 1 12); do
  STATUS=$(cat "${TMP_DIR}/status_${i}.txt")
  if [ "${STATUS}" = "201" ]; then
    CREATED_COUNT=$((CREATED_COUNT + 1))
    APPT_ID=$(extract_id < "${TMP_DIR}/body_${i}.json")
    BURST_IDS+=("${APPT_ID}")
    CREATED_APPOINTMENT_IDS+=("${APPT_ID}")
  elif [ "${STATUS}" = "409" ]; then
    CONFLICT_COUNT=$((CONFLICT_COUNT + 1))
  else
    UNEXPECTED_COUNT=$((UNEXPECTED_COUNT + 1))
  fi
done

echo "      Burst Results -> 201 Created: ${CREATED_COUNT} | 409 Conflict: ${CONFLICT_COUNT} | Unexpected: ${UNEXPECTED_COUNT}"
if [ "${CREATED_COUNT}" -ne 2 ] || [ "${CONFLICT_COUNT}" -ne 10 ] || [ "${UNEXPECTED_COUNT}" -ne 0 ]; then
  echo "ERROR: Expected 2 created, 10 conflicts, and 0 unexpected!" >&2
  exit 1
fi
echo ""

TO_CANCEL_ID="${BURST_IDS[0]}"
echo "[6/7] Cancelling EV appointment ${TO_CANCEL_ID} and re-booking freed slot..."
curl -fsS -X POST "${BASE_URL}/api/v1/appointments/${TO_CANCEL_ID}/cancel" >/dev/null
REBOOK_RESP=$(curl -fsS -X POST "${BASE_URL}/api/v1/appointments" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: demo-bash-rebook" \
  -d "{
    \"dealershipId\": \"${LONDON_DEALERSHIP_ID}\",
    \"customerId\": \"$(gen_uuid)\",
    \"vehicleId\": \"$(gen_uuid)\",
    \"serviceTypeId\": \"${EV_DIAG_ID}\",
    \"desiredStartTime\": \"${BURST_SLOT}\",
    \"notes\": \"Re-booked immediately after cancellation\"
  }")
REBOOK_ID=$(printf '%s' "${REBOOK_RESP}" | extract_id)
CREATED_APPOINTMENT_IDS+=("${REBOOK_ID}")
echo "      Re-book succeeded -> Appointment=${REBOOK_ID}"
echo ""

echo "[7/7] Inspecting Prometheus domain metrics (/actuator/prometheus)..."
PROM_OUT=$(curl -fsS "${BASE_URL}/actuator/prometheus")
printf '%s\n' "${PROM_OUT}" | grep -E "^scheduler_booking_(requests_total|duration_seconds_count)" | sed 's/^/      /' || true
echo ""
echo "Demo completed successfully with zero double-bookings!"
