#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -f requirements.txt ]]; then
  REQ_FILE="requirements.txt"
elif [[ -f reqiurements.txt ]]; then
  REQ_FILE="reqiurements.txt"
else
  echo "[ERROR] requirements file not found in: $ROOT_DIR"
  echo "[INFO] existing files:"
  ls -lah
  exit 1
fi

echo "[INFO] using $REQ_FILE from $ROOT_DIR"
python -m pip install -r "$REQ_FILE"
