#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${1:-.env}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found" >&2
  exit 1
fi

echo "Stopping any existing agentspan server..."
agentspan server stop 2>/dev/null || true

echo "Starting agentspan server..."
agentspan server start

echo "Waiting for server to be ready..."
for i in $(seq 1 20); do
  if curl -sf http://localhost:6767/health > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -sf http://localhost:6767/health > /dev/null 2>&1; then
  echo "ERROR: server did not become ready in time" >&2
  exit 1
fi

echo "Setting agentspan credentials from $ENV_FILE..."
while IFS= read -r line || [[ -n "$line" ]]; do
  # Skip blank lines and comments
  [[ -z "$line" || "$line" == \#* ]] && continue
  key="${line%%=*}"
  value="${line#*=}"
  # Skip entries with no value or keys not meant for the credential store
  [[ -z "$value" || "$key" == "MODEL" ]] && continue
  echo "  agentspan credentials set $key ..."
  agentspan credentials set "$key" "$value"
done < "$ENV_FILE"

echo "Done. Server running at http://localhost:6767"
