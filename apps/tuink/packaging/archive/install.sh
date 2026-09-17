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

refuse() {
  echo "Refusing to install: $*" >&2
  exit 1
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

for name in "${LINK_NAME}" install.sh uninstall.sh; do
  [[ ! -d "${INSTALL_DIR}/${name}" ]] || refuse "managed file destination is a directory: ${INSTALL_DIR}/${name}"
done

README_INSTALL_SOURCE="${SCRIPT_DIR}/README.md"
if [[ -f "${README_INSTALL_SOURCE}" ]]; then
  README_TEMP="$(mktemp)"
  trap 'rm -f -- "${README_TEMP}"' EXIT
  # The system installation contains only the executable and top-level docs.
  # Keep its README links usable after the extracted archive is removed.
  sed -E \
    -e "s@(\]\()(docs/assets/[^)]*)(\))@\1https://raw.githubusercontent.com/kosatchev/tuink/${DOCUMENTATION_REF}/\2\3@g" \
    -e "s@(\]\()(docs/[^)]*/|verification/performance/[^)]*/)(\))@\1https://github.com/kosatchev/tuink/tree/${DOCUMENTATION_REF}/\2\3@g" \
    -e "s@(\]\()(docs/[^)]*|verification/performance/[^)]*|CONTRIBUTING[.]md|SECURITY[.]md)(\))@\1https://github.com/kosatchev/tuink/blob/${DOCUMENTATION_REF}/\2\3@g" \
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
