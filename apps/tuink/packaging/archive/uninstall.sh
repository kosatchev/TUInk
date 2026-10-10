#!/usr/bin/env bash
set -euo pipefail

LINK_PATH="/usr/local/bin/tuink"
INSTALL_DIR="/opt/tuink"
TARGET_BIN="${INSTALL_DIR}/tuink"
MARKER_PATH="${INSTALL_DIR}/.tuink-archive-install"
MARKER_VALUE="tuink-archive-install-v1"
LICENSE_INSTALL_DIR="${INSTALL_DIR}/licenses"
LICENSE_MANIFEST_PATH="${INSTALL_DIR}/.tuink-license-files"
LICENSE_MANIFEST_VALUE="# tuink-license-files-v1"

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

[[ -d "${LICENSE_INSTALL_DIR}" && ! -L "${LICENSE_INSTALL_DIR}" ]] || refuse "installed licenses directory is missing or symlinked"
[[ -f "${LICENSE_MANIFEST_PATH}" && ! -L "${LICENSE_MANIFEST_PATH}" ]] || refuse "installed licenses have no regular ownership manifest"
declare -a LICENSE_FILES=()
{
  IFS= read -r manifest_format || refuse "license ownership manifest is empty"
  [[ "${manifest_format}" == "${LICENSE_MANIFEST_VALUE}" ]] || refuse "unknown license ownership manifest format"
  while IFS= read -r relative || [[ -n "${relative}" ]]; do
    [[ "${relative}" =~ ^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$ ]] || refuse "invalid license path: ${relative}"
    case "/${relative}/" in */../*|*/./*) refuse "invalid license path: ${relative}" ;; esac
    current="${LICENSE_INSTALL_DIR}"
    IFS='/' read -r -a components <<< "${relative}"
    for index in "${!components[@]}"; do
      component="${components[${index}]}"
      current="${current}/${component}"
      [[ ! -L "${current}" ]] || refuse "license path is a symlink: ${current}"
      if [[ ${index} -lt $((${#components[@]} - 1)) && -e "${current}" && ! -d "${current}" ]]; then
        refuse "license parent is not a directory: ${current}"
      fi
    done
    [[ ! -d "${current}" ]] || refuse "managed license file became a directory: ${current}"
    LICENSE_FILES+=("${relative}")
  done
} < "${LICENSE_MANIFEST_PATH}"
[[ ${#LICENSE_FILES[@]} -gt 0 ]] || refuse "installed licenses ownership manifest is empty"

UNEXPECTED_ENTRY="$(
  find "${INSTALL_DIR}" -mindepth 1 -maxdepth 1 \
    ! -name '.tuink-archive-install' \
    ! -name 'tuink' \
    ! -name 'install.sh' \
    ! -name 'uninstall.sh' \
    ! -name 'README.md' \
    ! -name 'LICENSE' \
    ! -name 'THIRD_PARTY_NOTICES.md' \
    ! -name 'licenses' \
    ! -name '.tuink-license-files' \
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
declare -A LICENSE_DIRECTORIES=()
for relative in "${LICENSE_FILES[@]}"; do
  sudo rm -f -- "${LICENSE_INSTALL_DIR}/${relative}"
  while [[ "${relative}" == */* ]]; do
    relative="${relative%/*}"
    LICENSE_DIRECTORIES["${relative}"]=true
  done
done
sudo rm -f -- "${LICENSE_MANIFEST_PATH}"
if [[ ${#LICENSE_DIRECTORIES[@]} -gt 0 ]]; then
  # Children sort after their path prefix, so reverse order removes them first.
  while IFS= read -r relative; do
    sudo rmdir -- "${LICENSE_INSTALL_DIR}/${relative}" 2>/dev/null || true
  done < <(printf '%s\n' "${!LICENSE_DIRECTORIES[@]}" | LC_ALL=C sort -r)
fi
sudo rmdir -- "${LICENSE_INSTALL_DIR}" 2>/dev/null || true
sudo rm -f -- "${MARKER_PATH}"

if [[ -d "${LICENSE_INSTALL_DIR}" ]]; then
  echo "Preserved foreign entries in ${LICENSE_INSTALL_DIR}."
elif ! sudo rmdir -- "${INSTALL_DIR}"; then
  echo "Failed to remove ${INSTALL_DIR}; unexpected files may have appeared." >&2
  exit 1
fi

echo "Uninstalled: ${LINK_PATH}"
echo "Removed managed TUInk files from: ${INSTALL_DIR}"
