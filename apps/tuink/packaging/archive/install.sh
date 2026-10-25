#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINK_NAME="tuink"
DOCUMENTATION_REF='@TUINK_DOCUMENTATION_REF@'
SOURCE_BIN="${1:-}"
INSTALL_DIR="/opt/tuink"
TARGET_BIN="${INSTALL_DIR}/${LINK_NAME}"
LINK_PATH="/usr/local/bin/${LINK_NAME}"
MARKER_PATH="${INSTALL_DIR}/.tuink-archive-install"
MARKER_VALUE="tuink-archive-install-v1"
LICENSE_SOURCE_DIR="${SCRIPT_DIR}/licenses"
LICENSE_INSTALL_DIR="${INSTALL_DIR}/licenses"
LICENSE_MANIFEST_PATH="${INSTALL_DIR}/.tuink-license-files"
LICENSE_MANIFEST_VALUE="# tuink-license-files-v1"
MANAGED_FILES=("${LINK_NAME}" install.sh uninstall.sh README.md LICENSE THIRD_PARTY_NOTICES.md)

refuse() {
  echo "Refusing to install: $*" >&2
  exit 1
}

validate_managed_file() {
  local path="$1"
  # install -T replaces a symlink itself; never adopt a directory or special file.
  # A missing file (including a dangling symlink) can be restored by this install.
  if [[ -e "${path}" && ! -f "${path}" ]]; then
    refuse "managed file destination is not a regular file: ${path}"
  fi
}

validate_license_path() {
  local relative="$1" current="${LICENSE_INSTALL_DIR}" component
  [[ "${relative}" =~ ^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$ ]] || refuse "invalid license path: ${relative}"
  case "/${relative}/" in */../*|*/./*) refuse "invalid license path: ${relative}" ;; esac
  local -a components
  IFS='/' read -r -a components <<< "${relative}"
  local index
  for index in "${!components[@]}"; do
    component="${components[${index}]}"
    current="${current}/${component}"
    [[ ! -L "${current}" ]] || refuse "license path is a symlink: ${current}"
    if [[ ${index} -lt $((${#components[@]} - 1)) && -e "${current}" && ! -d "${current}" ]]; then
      refuse "license parent is not a directory: ${current}"
    fi
  done
}

# Packaging pins online documentation to this archive source revision.
[[ "${DOCUMENTATION_REF}" =~ ^([0-9a-f]{40}|v[0-9]+[.][0-9]+[.][0-9]+)$ ]] || refuse "invalid or unset archive documentation reference"

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

LINK_ALREADY_MANAGED=false
if [[ -L "${LINK_PATH}" ]]; then
  LINK_TARGET="$(readlink -- "${LINK_PATH}")"
  if [[ "${LINK_TARGET}" != "${TARGET_BIN}" ]]; then
    refuse "${LINK_PATH} points to ${LINK_TARGET}, expected ${TARGET_BIN}"
  fi
  LINK_ALREADY_MANAGED=true
elif [[ -e "${LINK_PATH}" ]]; then
  refuse "${LINK_PATH} already exists and is not the managed TUInk symlink"
fi

if [[ -L "${INSTALL_DIR}" ]]; then
  refuse "${INSTALL_DIR} is a symlink"
fi

NEW_INSTALL=false
if [[ -e "${INSTALL_DIR}" ]]; then
  if [[ ! -d "${INSTALL_DIR}" ]]; then
    refuse "${INSTALL_DIR} exists and is not a directory"
  fi
  if [[ -e "${MARKER_PATH}" || -L "${MARKER_PATH}" ]]; then
    if [[ ! -f "${MARKER_PATH}" || -L "${MARKER_PATH}" ]]; then
      refuse "${INSTALL_DIR} has an invalid installation marker"
    fi
    EXISTING_MARKER="$(cat -- "${MARKER_PATH}")"
    if [[ "${EXISTING_MARKER}" != "${MARKER_VALUE}" ]]; then
      refuse "${INSTALL_DIR} has an unknown installation marker"
    fi
  else
    refuse "${INSTALL_DIR} has no recognized installation marker"
  fi
else
  NEW_INSTALL=true
fi

for name in "${MANAGED_FILES[@]}"; do
  validate_managed_file "${INSTALL_DIR}/${name}"
done

[[ -d "${LICENSE_SOURCE_DIR}" && ! -L "${LICENSE_SOURCE_DIR}" ]] || refuse "archive licenses directory is missing or symlinked"
INVALID_LICENSE_ENTRY="$(find "${LICENSE_SOURCE_DIR}" -mindepth 1 ! -type f ! -type d -print -quit)"
[[ -z "${INVALID_LICENSE_ENTRY}" ]] || refuse "archive licenses contain an unsupported entry: ${INVALID_LICENSE_ENTRY}"
[[ ! -L "${LICENSE_INSTALL_DIR}" ]] || refuse "${LICENSE_INSTALL_DIR} is a symlink"
if [[ -e "${LICENSE_INSTALL_DIR}" && ! -d "${LICENSE_INSTALL_DIR}" ]]; then
  refuse "${LICENSE_INSTALL_DIR} is not a directory"
fi

declare -A PREVIOUS_LICENSE_FILES=()
declare -A PREVIOUS_LICENSE_DIRECTORIES=()
if [[ -e "${LICENSE_INSTALL_DIR}" || -e "${LICENSE_MANIFEST_PATH}" || -L "${LICENSE_MANIFEST_PATH}" ]]; then
  [[ -f "${LICENSE_MANIFEST_PATH}" && ! -L "${LICENSE_MANIFEST_PATH}" ]] || refuse "installed licenses have no regular ownership manifest"
  {
    IFS= read -r manifest_format || refuse "license ownership manifest is empty"
    [[ "${manifest_format}" == "${LICENSE_MANIFEST_VALUE}" ]] || refuse "unknown license ownership manifest format"
    while IFS= read -r relative || [[ -n "${relative}" ]]; do
      validate_license_path "${relative}"
      [[ ! -d "${LICENSE_INSTALL_DIR}/${relative}" ]] || refuse "managed license file became a directory: ${relative}"
      PREVIOUS_LICENSE_FILES["${relative}"]=true
      parent="${relative}"
      while [[ "${parent}" == */* ]]; do
        parent="${parent%/*}"
        PREVIOUS_LICENSE_DIRECTORIES["${parent}"]=true
      done
    done
  } < "${LICENSE_MANIFEST_PATH}"
  [[ ${#PREVIOUS_LICENSE_FILES[@]} -gt 0 ]] || refuse "license ownership manifest has no files"
fi

README_TEMP=""
LICENSE_FILES_TEMP="$(mktemp)"
cleanup() {
  [[ -z "${README_TEMP}" ]] || rm -f -- "${README_TEMP}"
  rm -f -- "${LICENSE_FILES_TEMP}"
}
trap cleanup EXIT
printf '%s\n' "${LICENSE_MANIFEST_VALUE}" > "${LICENSE_FILES_TEMP}"
LICENSE_FILE_COUNT=0
while IFS= read -r -d '' relative; do
  validate_license_path "${relative}"
  destination="${LICENSE_INSTALL_DIR}/${relative}"
  if [[ -e "${destination}" && -z "${PREVIOUS_LICENSE_FILES[${relative}]+present}" ]]; then
    refuse "license destination is not managed: ${destination}"
  fi
  [[ ! -d "${destination}" ]] || refuse "license file destination is a directory: ${destination}"
  printf '%s\n' "${relative}" >> "${LICENSE_FILES_TEMP}"
  LICENSE_FILE_COUNT=$((LICENSE_FILE_COUNT + 1))
done < <(find "${LICENSE_SOURCE_DIR}" -type f -printf '%P\0')
[[ ${LICENSE_FILE_COUNT} -gt 0 ]] || refuse "archive licenses directory is empty"
LC_ALL=C sort -o "${LICENSE_FILES_TEMP}" "${LICENSE_FILES_TEMP}"

README_INSTALL_SOURCE="${SCRIPT_DIR}/README.md"
if [[ -f "${README_INSTALL_SOURCE}" ]]; then
  README_TEMP="$(mktemp)"
  # The system installation contains the executable, top-level docs, and licenses.
  # Keep its README links usable after the extracted archive is removed.
  sed -E \
    -e "s@(\]\()(docs/assets/[^)]*)(\))@\1https://raw.githubusercontent.com/kosatchev/tuink/${DOCUMENTATION_REF}/\2\3@g" \
    -e "s@(\]\()(docs/[^)]*/|apps/[^)]*/|verification/[^)]*/)(\))@\1https://github.com/kosatchev/tuink/tree/${DOCUMENTATION_REF}/\2\3@g" \
    -e "s@(\]\()(docs/[^)]*|apps/[^)]*|verification/[^)]*|CONTRIBUTING[.]md|SECURITY[.]md)(\))@\1https://github.com/kosatchev/tuink/blob/${DOCUMENTATION_REF}/\2\3@g" \
    "${README_INSTALL_SOURCE}" > "${README_TEMP}"
  README_INSTALL_SOURCE="${README_TEMP}"
fi

sudo mkdir -p "${INSTALL_DIR}"
if [[ "${NEW_INSTALL}" == true ]]; then
  printf '%s\n' "${MARKER_VALUE}" | sudo tee "${MARKER_PATH}" >/dev/null
  sudo chmod 0644 "${MARKER_PATH}"
fi
sudo install -m 0755 -T -- "${APP_PATH}" "${TARGET_BIN}"

for script in install.sh uninstall.sh; do
  if [[ -f "${SCRIPT_DIR}/${script}" ]]; then
    sudo install -m 0755 -T -- "${SCRIPT_DIR}/${script}" "${INSTALL_DIR}/${script}"
  fi
done

for relative in "${!PREVIOUS_LICENSE_FILES[@]}"; do
  sudo rm -f -- "${LICENSE_INSTALL_DIR}/${relative}"
done
if [[ ${#PREVIOUS_LICENSE_DIRECTORIES[@]} -gt 0 ]]; then
  # Only former managed file parents are candidates. Nonempty directories,
  # including foreign descendants, survive; siblings are never traversed.
  while IFS= read -r relative; do
    sudo rmdir -- "${LICENSE_INSTALL_DIR}/${relative}" 2>/dev/null || true
  done < <(printf '%s\n' "${!PREVIOUS_LICENSE_DIRECTORIES[@]}" | LC_ALL=C sort -r)
fi
sudo install -d -m 0755 -- "${LICENSE_INSTALL_DIR}"
declare -A LICENSE_PARENTS=()
{
  IFS= read -r manifest_format
  while IFS= read -r relative; do
    parent="${LICENSE_INSTALL_DIR}"
    if [[ "${relative}" == */* ]]; then
      parent="${LICENSE_INSTALL_DIR}/${relative%/*}"
    fi
    if [[ -z "${LICENSE_PARENTS[${parent}]+present}" ]]; then
      sudo install -d -m 0755 -- "${parent}"
      LICENSE_PARENTS["${parent}"]=true
    fi
    sudo install -m 0644 -T -- "${LICENSE_SOURCE_DIR}/${relative}" "${LICENSE_INSTALL_DIR}/${relative}"
  done
} < "${LICENSE_FILES_TEMP}"
sudo install -m 0644 -T -- "${LICENSE_FILES_TEMP}" "${LICENSE_MANIFEST_PATH}"

for doc in README.md LICENSE THIRD_PARTY_NOTICES.md; do
  DOC_SOURCE="${SCRIPT_DIR}/${doc}"
  if [[ "${doc}" == 'README.md' ]]; then
    DOC_SOURCE="${README_INSTALL_SOURCE}"
  fi
  if [[ -f "${DOC_SOURCE}" ]]; then
    sudo install -m 0644 -T "${DOC_SOURCE}" "${INSTALL_DIR}/${doc}"
  fi
done

if [[ "${LINK_ALREADY_MANAGED}" == false ]]; then
  sudo ln -s "${TARGET_BIN}" "${LINK_PATH}"
fi

echo "Installed binary: ${TARGET_BIN}"
echo "Installed link:   ${LINK_PATH} -> ${TARGET_BIN}"
echo "Managed marker:   ${MARKER_PATH}"
