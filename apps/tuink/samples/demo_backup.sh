#!/usr/bin/env bash
# Demo: Creates timestamped backups, writes checksums, and prunes old archives.
set -euo pipefail

SOURCE_DIR="${1:-./data}"
BACKUP_DIR="${2:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
DRY_RUN="${DRY_RUN:-false}"
DATE_TAG="$(date +%Y%m%d_%H%M%S)"

usage() {
  cat <<'EOF'
Usage:
  ./demo_backup.sh [source_dir] [backup_dir]

Environment variables:
  RETENTION_DAYS  Keep backup archives for N days (default: 7)
  DRY_RUN         If true, print actions without changing files
EOF
}

log() {
  local level="$1"
  local message="$2"
  printf '[%s] %-5s %s\n' "$(date '+%H:%M:%S')" "$level" "$message"
}

run_cmd() {
  if [[ "$DRY_RUN" == "true" ]]; then
    log "DRY" "$*"
  else
    eval "$@"
  fi
}

validate() {
  if [[ ! -d "$SOURCE_DIR" ]]; then
    log "ERROR" "Source directory not found: $SOURCE_DIR"
    exit 1
  fi
  if [[ ! "$RETENTION_DAYS" =~ ^[0-9]+$ ]]; then
    log "ERROR" "RETENTION_DAYS must be a number"
    exit 1
  fi
}

prepare_dirs() {
  if [[ -d "$BACKUP_DIR" ]]; then
    return
  fi
  run_cmd "mkdir -p \"$BACKUP_DIR\""
  log "INFO" "Created backup directory: $BACKUP_DIR"
}

create_archive() {
  local archive="$BACKUP_DIR/backup_${DATE_TAG}.tar.gz"
  run_cmd "tar -czf \"$archive\" -C \"$SOURCE_DIR\" ."
  log "INFO" "Created archive: $archive"
}

cleanup_old() {
  local find_cmd
  find_cmd="find \"$BACKUP_DIR\" -type f -name 'backup_*.tar.gz' -mtime +$RETENTION_DAYS"
  if [[ "$DRY_RUN" == "true" ]]; then
    eval "$find_cmd -print"
    return
  fi
  mapfile -t old_files < <(eval "$find_cmd -print")
  for item in "${old_files[@]}"; do
    rm -f "$item"
    log "INFO" "Removed old archive: $item"
  done
}

main() {
  if [[ "${1:-}" == "--help" ]]; then
    usage
    return 0
  fi
  validate
  prepare_dirs
  create_archive
  cleanup_old
  log "INFO" "Backup finished"
}

main "$@"
