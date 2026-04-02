#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINK_NAME="tuink"
SOURCE_BIN="${1:-}"
INSTALL_DIR="/opt/tuink"
TARGET_BIN="${INSTALL_DIR}/${LINK_NAME}"
LINK_PATH="/usr/local/bin/${LINK_NAME}"

if [[ -z "${SOURCE_BIN}" ]]; then
  if [[ -x "${SCRIPT_DIR}/tuink" ]]; then
    SOURCE_BIN="tuink"
  elif [[ -x "${SCRIPT_DIR}/tuink-musl" ]]; then
    SOURCE_BIN="tuink-musl"
  else
    echo "Usage: ./install.sh [tuink|tuink-musl]"
    echo "Could not auto-detect binary in: ${SCRIPT_DIR}"
    exit 1
  fi
fi

APP_PATH="${SCRIPT_DIR}/${SOURCE_BIN}"

if [[ ! -f "${APP_PATH}" ]]; then
  echo "Binary not found: ${APP_PATH}"
  exit 1
fi

chmod +x "${APP_PATH}"
sudo mkdir -p "${INSTALL_DIR}"
sudo install -m 0755 "${APP_PATH}" "${TARGET_BIN}"

for script in install.sh uninstall.sh; do
  if [[ -f "${SCRIPT_DIR}/${script}" ]]; then
    sudo install -m 0755 "${SCRIPT_DIR}/${script}" "${INSTALL_DIR}/${script}"
  fi
done

for doc in README.md LICENSE THIRD_PARTY_NOTICES.md; do
  if [[ -f "${SCRIPT_DIR}/${doc}" ]]; then
    sudo install -m 0644 "${SCRIPT_DIR}/${doc}" "${INSTALL_DIR}/${doc}"
  fi
done

sudo ln -sfn "${TARGET_BIN}" "${LINK_PATH}"

echo "Installed binary: ${TARGET_BIN}"
echo "Installed link:   ${LINK_PATH} -> ${TARGET_BIN}"
