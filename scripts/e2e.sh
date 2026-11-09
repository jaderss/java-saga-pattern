#!/usr/bin/env bash
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
TIMEOUT_S=${TIMEOUT_S:-60}

balance() { curl -sf "$BASE/api/accounts" | jq -r --arg id "$1" '.[] | select(.id == $id) | .balance'; }
total() { curl -sf "$BASE/api/accounts" | jq '[.[].balance] | add'; }

curl -sf -X POST "$BASE/api/accounts/reset" > /dev/null
curl -sf -X POST "$BASE/api/limits/reset" > /dev/null
before_total=$(total)
failures=0

wait_while() {
  local transfer=$1 filter=$2
  for _ in $(seq 1 $((TIMEOUT_S * 4))); do
    curl -sf "$BASE/api/transfers/$transfer" | jq -e "$filter" > /dev/null || return 0
    sleep 0.25
  done
}

check() {
  local label=$1 request=$2 expected=$3 decision=$4
  local from to amount from_before to_before transfer status want_from want_to got_from got_to
  from=$(jq -r .sourceAccountId <<< "$request")
  to=$(jq -r .destinationAccountId <<< "$request")
  amount=$(jq -r .amount <<< "$request")
  from_before=$(balance "$from")
  to_before=$(balance "$to")

  transfer=$(curl -sf -X POST "$BASE/api/transfers" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: e2e-$label-$(uuidgen)" -d "$request" | jq -r .transfer.id)

  if [[ -n $decision ]]; then
    wait_while "$transfer" '.steps | any(.step == "APPROVE_TRANSFER" and .status == "IN_PROGRESS") | not'
    curl -sf -X POST "$BASE/api/transfers/$transfer/$decision" > /dev/null
  fi
  wait_while "$transfer" '.transfer.status == "RUNNING" or .transfer.status == "COMPENSATING"'
  status=$(curl -sf "$BASE/api/transfers/$transfer" | jq -r .transfer.status)

  if [[ $expected == COMPLETED ]]; then
    want_from=$(jq -n "$from_before - $amount"); want_to=$(jq -n "$to_before + $amount")
  else
    want_from=$from_before; want_to=$to_before
  fi
  got_from=$(balance "$from"); got_to=$(balance "$to")

  if [[ $status == "$expected" ]] && jq -e -n "$got_from == $want_from and $got_to == $want_to" > /dev/null; then
    printf 'ok    %-26s %-12s %s %s -> %s, %s %s -> %s\n' "$label" "$status" "$from" "$from_before" "$got_from" "$to" "$to_before" "$got_to"
  else
    printf 'FAIL  %-26s got %s want %s, %s %s want %s, %s %s want %s\n' "$label" "$status" "$expected" "$from" "$got_from" "$want_from" "$to" "$got_to" "$want_to"
    failures=$((failures + 1))
  fi
}

while read -r scenario; do
  id=$(jq -r .id <<< "$scenario")
  request=$(jq -c .request <<< "$scenario")
  if jq -e .requiresApproval <<< "$request" > /dev/null; then
    check "$id (approve)" "$request" COMPLETED approve
    check "$id (decline)" "$request" COMPENSATED decline
  else
    check "$id" "$request" "$(jq -r .expected <<< "$scenario")" ""
  fi
done < <(curl -sf "$BASE/api/scenarios" | jq -c '.[]')

after_total=$(total)
reserved=$(curl -sf "$BASE/api/limits" | jq '[.[].reserved] | add // 0')

if jq -e -n "$before_total == $after_total" > /dev/null; then
  echo "ok    money conserved: total $before_total before and after"
else
  echo "FAIL  money not conserved: total $before_total before, $after_total after"; failures=$((failures + 1))
fi
if jq -e -n "$reserved == 0" > /dev/null; then
  echo "ok    no limit reservation left behind"
else
  echo "FAIL  $reserved still reserved after every saga ended"; failures=$((failures + 1))
fi

exit $failures
