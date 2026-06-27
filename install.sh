#!/bin/sh
# Forvum installer — downloads the single-binary GraalVM native build for this
# platform from the latest GitHub Release, verifies its sha256, and installs it.
#
# The release tag is resolved from the GitHub API: the latest STABLE release if one
# exists, otherwise the newest pre-release (so the one-liner works even before the
# first stable release is cut). Pin an exact tag with FORVUM_VERSION.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/eldermoraes/forvum/main/install.sh | sh
#
# Environment overrides:
#   FORVUM_INSTALL_DIR   target bin directory (default: $HOME/.local/bin, else /usr/local/bin)
#   FORVUM_VERSION       release tag to install (default: auto-resolved, e.g. FORVUM_VERSION=v0.5.0-rc.1)
#
# The native binary is ~130 MB (one executable, no JVM, no Docker, no Node).
# POSIX sh, set -eu, shellcheck-clean.

set -eu

REPO="eldermoraes/forvum"
BINARY_NAME="forvum"

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------
info() { printf '%s\n' "forvum: $1"; }
warn() { printf '%s\n' "forvum: warning: $1" >&2; }
err()  { printf '%s\n' "forvum: error: $1" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Platform detection
#
# Maps uname os/arch to the release asset name — all four targets
# (linux-x64, linux-arm64, macos-x64, macos-arm64) are published; keep the
# asset names in sync with .github/workflows/release.yml.
# ---------------------------------------------------------------------------
detect_asset() {
  os=$(uname -s)
  arch=$(uname -m)

  case "$os" in
    Linux)  os_tag="linux" ;;
    Darwin) os_tag="macos" ;;
    *)      err "unsupported OS '$os' (supported: Linux, macOS)" ;;
  esac

  case "$arch" in
    x86_64 | amd64)  arch_tag="x64" ;;
    arm64 | aarch64) arch_tag="arm64" ;;
    *)               err "unsupported architecture '$arch' (supported: x86_64, arm64)" ;;
  esac

  platform="${os_tag}-${arch_tag}"

  # The release pipeline publishes all four native targets; fail clearly on any
  # other platform rather than 404 on a missing asset.
  case "$platform" in
    linux-x64 | linux-arm64 | macos-x64 | macos-arm64) : ;;
    *) err "no published native binary for '$platform' (available: linux-x64, linux-arm64, macos-x64, macos-arm64); build from source — see the README" ;;
  esac

  ASSET="${BINARY_NAME}-${platform}"
}

# ---------------------------------------------------------------------------
# Tools
# ---------------------------------------------------------------------------
require() {
  command -v "$1" >/dev/null 2>&1 || err "'$1' is required but not found in PATH"
}

# Download $1 (URL) to $2 (path) using curl or wget.
download() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$1" -o "$2"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$2" "$1"
  else
    err "either 'curl' or 'wget' is required to download Forvum"
  fi
}

# Compute the sha256 of $1, emit the bare hex digest.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    err "no sha256 tool found (need 'sha256sum' or 'shasum')"
  fi
}

# ---------------------------------------------------------------------------
# Release-tag resolution (GitHub API)
#
# `releases/latest` (web shortcut + API) EXCLUDES pre-releases, so a repo whose only
# release is a pre-release would 404 on the `latest/download` URL. Resolve the tag
# explicitly: prefer the latest stable release, fall back to the newest release of any
# kind (including pre-releases), then download from `releases/download/<tag>/<asset>`.
# ---------------------------------------------------------------------------

# Emit the body of URL $1 to stdout (curl or wget). Non-zero on failure.
http_get() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$1" 2>/dev/null
  elif command -v wget >/dev/null 2>&1; then
    wget -qO- "$1" 2>/dev/null
  else
    return 1
  fi
}

# Read the first GitHub-release `tag_name` from a JSON payload on stdin.
first_tag() {
  grep -m1 '"tag_name"' | sed -e 's/.*"tag_name"[[:space:]]*:[[:space:]]*"//' -e 's/".*//'
}

# Resolve the tag to install: latest stable, else newest (incl. pre-release). Echoes the tag.
resolve_tag() {
  api="https://api.github.com/repos/${REPO}"
  tag=$(http_get "${api}/releases/latest" | first_tag || true)
  if [ -n "${tag:-}" ]; then
    printf '%s' "$tag"
    return 0
  fi
  tag=$(http_get "${api}/releases" | first_tag || true)
  if [ -n "${tag:-}" ]; then
    warn "no stable release yet; installing the latest pre-release ${tag}"
    printf '%s' "$tag"
    return 0
  fi
  return 1
}

# ---------------------------------------------------------------------------
# Install target
# ---------------------------------------------------------------------------
choose_install_dir() {
  if [ -n "${FORVUM_INSTALL_DIR:-}" ]; then
    INSTALL_DIR="$FORVUM_INSTALL_DIR"
    mkdir -p "$INSTALL_DIR" 2>/dev/null || true
    return
  fi

  # Prefer a per-user bin dir (no sudo); fall back to /usr/local/bin with a note.
  INSTALL_DIR="$HOME/.local/bin"
  if [ ! -d "$INSTALL_DIR" ]; then
    if mkdir -p "$INSTALL_DIR" 2>/dev/null; then
      :
    else
      INSTALL_DIR="/usr/local/bin"
      warn "could not create \$HOME/.local/bin; falling back to $INSTALL_DIR (may require sudo)"
    fi
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
detect_asset
choose_install_dir

if [ -n "${FORVUM_VERSION:-}" ]; then
  TAG="$FORVUM_VERSION"
else
  TAG=$(resolve_tag) || err "could not resolve the latest release from the GitHub API; pin one with FORVUM_VERSION=<tag> (see https://github.com/${REPO}/releases)"
fi

BASE_URL="https://github.com/${REPO}/releases/download/${TAG}"
info "installing $BINARY_NAME $TAG for $platform"

BIN_URL="${BASE_URL}/${ASSET}"
SUM_URL="${BIN_URL}.sha256"

# Stage the download in a temp dir; clean up on any exit.
TMPDIR_FORVUM=$(mktemp -d 2>/dev/null || mktemp -d -t forvum)
trap 'rm -rf "$TMPDIR_FORVUM"' EXIT INT TERM

TMP_BIN="${TMPDIR_FORVUM}/${ASSET}"
TMP_SUM="${TMPDIR_FORVUM}/${ASSET}.sha256"

info "downloading $BIN_URL"
download "$BIN_URL" "$TMP_BIN" || err "download failed (no asset '$ASSET' on the $TAG release?)"

info "verifying checksum"
download "$SUM_URL" "$TMP_SUM" || err "could not download the checksum ($SUM_URL)"

# The .sha256 file is `<hex>  <asset>` (sha256sum format). Take the first field.
expected=$(cut -d' ' -f1 "$TMP_SUM")
[ -n "$expected" ] || err "the checksum file is empty"
actual=$(sha256_of "$TMP_BIN")
if [ "$expected" != "$actual" ]; then
  err "checksum mismatch (expected $expected, got $actual) — refusing to install"
fi

# Install: chmod +x, then move into place (mv is atomic on the same filesystem).
chmod +x "$TMP_BIN"
TARGET="${INSTALL_DIR}/${BINARY_NAME}"
if mv "$TMP_BIN" "$TARGET" 2>/dev/null; then
  :
elif command -v sudo >/dev/null 2>&1 && sudo mv "$TMP_BIN" "$TARGET"; then
  :
else
  err "could not install to $TARGET (try setting FORVUM_INSTALL_DIR to a writable directory)"
fi

info "installed to $TARGET"

# PATH hint if the install dir is not already on PATH.
case ":${PATH}:" in
  *":${INSTALL_DIR}:"*) : ;;
  *) info "add it to your PATH:  export PATH=\"${INSTALL_DIR}:\$PATH\"" ;;
esac

# Show the installed version (best-effort — a PATH or libc surprise must not fail the install).
if "$TARGET" --version >/dev/null 2>&1; then
  info "$("$TARGET" --version 2>/dev/null | head -n1)"
else
  warn "installed, but '$TARGET --version' did not run — check your platform/PATH"
fi

info "done. Run 'forvum init' to scaffold ~/.forvum, then 'forvum' to start."
