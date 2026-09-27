#!/usr/bin/env bash
# End-to-end check against a running service (docker compose up): the path a
# reader of the README takes, through the real HTTP API and database.
#
# Quotes need the ECB rates the service fetches at startup. If the rate API
# is unreachable, the quote and transfer steps are skipped with a notice
# rather than failed: an outage elsewhere is not a defect here, and the
# test suite covers quotes and transfers without the network.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
JSON='Content-Type: application/json'

fail() { echo "FAIL: $*" >&2; exit 1; }
field() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }

echo "waiting for $BASE"
for _ in $(seq 1 60); do
  curl -sf "$BASE/actuator/health" > /dev/null && break
  sleep 2
done
curl -sf "$BASE/actuator/health" > /dev/null || fail "the service did not become healthy"

alice=$(curl -sf -X POST "$BASE/customers" -H "$JSON" -d '{"name":"Alice"}' | field "['id']")
bob=$(curl -sf -X POST "$BASE/customers" -H "$JSON" -d '{"name":"Bob"}' | field "['id']")
alice_eur=$(curl -sf -X POST "$BASE/customers/$alice/accounts" -H "$JSON" -d '{"currency":"EUR"}' | field "['id']")
bob_gbp=$(curl -sf -X POST "$BASE/customers/$bob/accounts" -H "$JSON" -d '{"currency":"GBP"}' | field "['id']")
echo "customers and accounts: ok"

key="smoke-$(date +%s)-$RANDOM"
deposit() {
  curl -s -D - -o /dev/null -X POST "$BASE/accounts/$alice_eur/deposits" \
    -H "$JSON" -H "Idempotency-Key: $key" -d '{"amount":"250.00","currency":"EUR"}'
}
deposit | grep -q "^HTTP/1.1 201" || fail "deposit"
deposit | grep -qi "^Idempotent-Replayed: true" || fail "a retried deposit was not replayed"
balance=$(curl -sf "$BASE/customers/$alice/accounts" | field "[0]['balance']")
[ "$balance" = "250.00" ] || fail "balance after a deposit and its retry is $balance, not 250.00"
echo "deposit and idempotent retry: ok"

lines=$(curl -sf "$BASE/accounts/$alice_eur/statement" | field "['items'].__len__()")
[ "$lines" = "1" ] || fail "statement has $lines lines, not 1"
echo "statement: ok"

status=$(curl -s -o /tmp/quote.json -w '%{http_code}' -X POST "$BASE/quotes" -H "$JSON" \
  -d '{"sourceCurrency":"EUR","targetCurrency":"GBP","sourceAmount":"200.00"}')
if [ "$status" = "503" ] && grep -q RATES_UNAVAILABLE /tmp/quote.json; then
  echo "NOTICE: no exchange rates (the rate API may be unreachable); quote and transfer skipped"
  exit 0
fi
[ "$status" = "201" ] || fail "quote returned $status: $(cat /tmp/quote.json)"
quote=$(field "['id']" < /tmp/quote.json)
target=$(field "['targetAmount']" < /tmp/quote.json)

transfer=$(curl -sf -X POST "$BASE/transfers" -H "$JSON" -H "Idempotency-Key: $key-transfer" \
  -d "{\"quoteId\":\"$quote\",\"sourceAccountId\":\"$alice_eur\",\"targetAccountId\":\"$bob_gbp\"}" | field "['status']")
[ "$transfer" = "COMPLETED" ] || fail "transfer status $transfer"
received=$(curl -sf "$BASE/customers/$bob/accounts" | field "[0]['balance']")
[ "$received" = "$target" ] || fail "Bob received $received, the quote said $target"
echo "quote and transfer: ok (200.00 EUR -> $target GBP)"
