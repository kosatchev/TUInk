#!/usr/bin/env bash
set -euo pipefail

LINK_PATH="/usr/local/bin/tuink"
INSTALL_DIR="/opt/tuink"
TARGET_BIN="${INSTALL_DIR}/tuink"

if [[ ! -L "${LINK_PATH}" ]]; then
  echo "No symlink found at ${LINK_PATH}"
else
  ACTUAL_TARGET="$(readlink -f "${LINK_PATH}")"
  if [[ "${ACTUAL_TARGET}" != "${TARGET_BIN}" ]]; then
    echo "Refusing to remove ${LINK_PATH}: target is ${ACTUAL_TARGET}, expected ${TARGET_BIN}"
    exit 1
  fi
fi

if ! sudo rm -f "${LINK_PATH}"; then
  echo "Failed to remove ${LINK_PATH}"
  exit 1
fi

if [[ -d "${INSTALL_DIR}" ]]; then
  if ! sudo rm -rf "${INSTALL_DIR}"; then
    echo "Failed to remove ${INSTALL_DIR}"
    exit 1
  fi
fi

echo "Uninstalled: ${LINK_PATH}"
echo "Removed:     ${INSTALL_DIR}"
