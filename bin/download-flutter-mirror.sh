#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-3.41.7-stable}"
MISE_ROOT="${MISE_DATA_DIR:-$HOME/.local/share/mise}"
MIRROR_BASE="${FLUTTER_MIRROR_BASE:-https://storage.flutter-io.cn}"
RELEASES_BASE="$MIRROR_BASE/flutter_infra_release/releases"
DOWNLOAD_ROOT="$MISE_ROOT/downloads/flutter/$VERSION"
MANUAL_INSTALL_ROOT="$MISE_ROOT/manual-installs/flutter/$VERSION"
TMP_ROOT="${TMPDIR:-/tmp}"

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

platform() {
  case "$(uname -s)" in
    Darwin) echo "macos" ;;
    Linux) echo "linux" ;;
    *)
      echo "Unsupported platform: $(uname -s)" >&2
      exit 1
      ;;
  esac
}

machine_architecture() {
  case "$(uname -m)" in
    arm64) echo "arm64" ;;
    x86_64) echo "x64" ;;
    *)
      echo "Unsupported architecture: $(uname -m)" >&2
      exit 1
      ;;
  esac
}

release_channel() {
  case "$VERSION" in
    *-stable) echo "stable" ;;
    *-beta) echo "beta" ;;
    *-dev) echo "dev" ;;
    *) echo "stable" ;;
  esac
}

release_version() {
  echo "$VERSION" | sed -E 's/-(stable|beta|dev)$//'
}

releases_json_url() {
  echo "$RELEASES_BASE/releases_$(platform).json"
}

tmp_unpack_dir="$(mktemp -d "$TMP_ROOT/flutter-install.XXXXXX")"
tmp_json="$tmp_unpack_dir/releases.json"

cleanup() {
  rm -rf "$tmp_unpack_dir"
}
trap cleanup EXIT

need_cmd curl
need_cmd jq
need_cmd mise

mkdir -p "$DOWNLOAD_ROOT"
mkdir -p "$(dirname "$MANUAL_INSTALL_ROOT")"

echo "Fetching Flutter release index"
echo "  $(releases_json_url)"
curl --progress-bar -fL -o "$tmp_json" "$(releases_json_url)"

channel="$(release_channel)"
version_without_suffix="$(release_version)"
arch="$(machine_architecture)"

query='.releases
  | map(select(.channel == $channel))
  | map(select((.version | sub("^v"; "")) == $version))
  | map(select((.dart_sdk_arch // "x64") == $arch))
  | first'

release_json="$(jq -er \
  --arg channel "$channel" \
  --arg version "$version_without_suffix" \
  --arg arch "$arch" \
  "$query" \
  "$tmp_json")" || {
  echo "Could not find Flutter release $VERSION for $(platform)/$arch in $(releases_json_url)" >&2
  exit 1
}

archive_path="$(printf '%s' "$release_json" | jq -r '.archive')"
archive_name="$(basename "$archive_path")"
archive_file="$DOWNLOAD_ROOT/$archive_name"
url="$RELEASES_BASE/$archive_path"

echo "Downloading Flutter $VERSION"
echo "  $url"
echo "  -> $archive_file"
curl --progress-bar -fL -C - -o "$archive_file" "$url"

echo "Unpacking archive"
rm -rf "$MANUAL_INSTALL_ROOT"

case "$archive_file" in
  *.zip)
    unzip -q "$archive_file" -d "$tmp_unpack_dir"
    ;;
  *.tar.xz)
    tar -xJf "$archive_file" -C "$tmp_unpack_dir"
    ;;
  *)
    echo "Unsupported archive type: $archive_file" >&2
    exit 1
    ;;
esac

if [ ! -x "$tmp_unpack_dir/flutter/bin/flutter" ]; then
  echo "Expected executable $tmp_unpack_dir/flutter/bin/flutter was not found" >&2
  exit 1
fi

mv "$tmp_unpack_dir/flutter" "$MANUAL_INSTALL_ROOT"

echo "Registering with mise"
mise link -f "flutter@$VERSION" "$MANUAL_INSTALL_ROOT"
mise reshim >/dev/null 2>&1 || true

echo "Installed Flutter to $MANUAL_INSTALL_ROOT"
echo "Verify with:"
echo "  mise exec -- flutter --version"
