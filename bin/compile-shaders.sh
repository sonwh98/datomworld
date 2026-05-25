#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FLUTTER_BIN="${FLUTTER_BIN:-$(command -v flutter)}"
FLUTTER_ROOT="$(cd "$(dirname "$FLUTTER_BIN")/.." && pwd)"
BUNDLE_SPEC="$ROOT/src/shaders/simple_mesh.shaderbundle.json"
OUTPUT="$ROOT/assets/shaders/simple_mesh.shaderbundle"

case "$(uname -s)" in
  Darwin) ENGINE_HOSTS=("darwin-arm64" "darwin-x64") ;;
  Linux) ENGINE_HOSTS=("linux-x64") ;;
  *) echo "Unsupported host for Flutter impellerc: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac

IMPELLERC=""
for ENGINE_HOST in "${ENGINE_HOSTS[@]}"; do
  CANDIDATE="$FLUTTER_ROOT/bin/cache/artifacts/engine/$ENGINE_HOST/impellerc"
  if [ -x "$CANDIDATE" ]; then
    IMPELLERC="$CANDIDATE"
    break
  fi
done

if [ ! -x "$IMPELLERC" ]; then
  echo "impellerc not found under: $FLUTTER_ROOT/bin/cache/artifacts/engine" >&2
  echo "Run 'flutter precache' for this Flutter install, or set FLUTTER_BIN." >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"

cd "$ROOT"

"$IMPELLERC" \
  --runtime-stage-metal \
  --runtime-stage-gles \
  --runtime-stage-gles3 \
  --runtime-stage-vulkan \
  "--sl=$OUTPUT" \
  "--shader-bundle=$(cat "$BUNDLE_SPEC")"

echo "Compiled shader bundle: $OUTPUT"
