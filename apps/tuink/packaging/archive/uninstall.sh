#!/usr/bin/env bash
set -euo pipefail

LINK_PATH="/usr/local/bin/tuink"
INSTALL_DIR="/opt/tuink"
TARGET_BIN="${INSTALL_DIR}/tuink"
MARKER_PATH="${INSTALL_DIR}/.tuink-archive-install"
MARKER_VALUE="tuink-archive-install-v1"

refuse() {
  echo "Refusing to uninstall: $*" >&2
  exit 1
}

if [[ -L "${INSTALL_DIR}" ]]; then
  refuse "${INSTALL_DIR} is a symlink"
fi

if [[ ! -e "${INSTALL_DIR}" ]]; then
  if [[ -e "${LINK_PATH}" || -L "${LINK_PATH}" ]]; then
    refuse "${LINK_PATH} exists but the managed installation directory is missing"
  fi
  echo "TUInk archive installation not found."
  exit 0
fi

if [[ ! -d "${INSTALL_DIR}" ]]; then
  refuse "${INSTALL_DIR} exists and is not a directory"
fi
if [[ ! -f "${MARKER_PATH}" || -L "${MARKER_PATH}" ]]; then
  refuse "${INSTALL_DIR} is not marked as a TUInk archive installation"
fi
EXISTING_MARKER="$(cat -- "${MARKER_PATH}")"
if [[ "${EXISTING_MARKER}" != "${MARKER_VALUE}" ]]; then
  refuse "${INSTALL_DIR} has an unknown installation marker"
fi

LINK_PRESENT=false
if [[ -L "${LINK_PATH}" ]]; then
  LINK_TARGET="$(readlink -- "${LINK_PATH}")"
  if [[ "${LINK_TARGET}" != "${TARGET_BIN}" ]]; then
    refuse "${LINK_PATH} points to ${LINK_TARGET}, expected ${TARGET_BIN}"
  fi
  LINK_PRESENT=true
elif [[ -e "${LINK_PATH}" ]]; then
  refuse "${LINK_PATH} exists and is not the managed TUInk symlink"
fi

UNEXPECTED_ENTRY="$(
  find "${INSTALL_DIR}" -mindepth 1 -maxdepth 1 \
    ! -name '.tuink-archive-install' \
    ! -name 'tuink' \
    ! -name 'install.sh' \
    ! -name 'uninstall.sh' \
    ! -name 'README.md' \
    ! -name 'LICENSE' \
    ! -name 'THIRD_PARTY_NOTICES.md' \
    -print -quit
)"
if [[ -n "${UNEXPECTED_ENTRY}" ]]; then
  refuse "${INSTALL_DIR} contains an unexpected entry: ${UNEXPECTED_ENTRY}"
fi

if [[ "${LINK_PRESENT}" == true ]]; then
  sudo find "${LINK_PATH}" -maxdepth 0 -type l -lname "${TARGET_BIN}" -delete
  if [[ -e "${LINK_PATH}" || -L "${LINK_PATH}" ]]; then
    refuse "${LINK_PATH} changed while uninstalling"
  fi
fi

for name in tuink install.sh uninstall.sh README.md LICENSE THIRD_PARTY_NOTICES.md; do
  sudo rm -f -- "${INSTALL_DIR}/${name}"
done
sudo rm -f -- "${MARKER_PATH}"

if ! sudo rmdir -- "${INSTALL_DIR}"; then
  echo "Failed to remove ${INSTALL_DIR}; unexpected files may have appeared." >&2
  exit 1
fi

echo "Uninstalled: ${LINK_PATH}"
echo "Removed:     ${INSTALL_DIR}"
