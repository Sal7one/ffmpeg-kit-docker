#!/usr/bin/env bash
set -euo pipefail

IMAGE_TAG="${IMAGE_TAG:-ffmpegkit-aar-cache:latest}"
DOCKER_DIR="${DOCKER_DIR:-docker}"
OUT_DIR="${OUT_DIR:-out}"

PLATFORM_DEFAULT="${PLATFORM_DEFAULT:-linux/amd64}"

API_LEVEL="${API_LEVEL:-28}"
ABIS_DEFAULT="${ABIS_DEFAULT:-arm64-v8a,armeabi-v7a}"
BUILD_MODE_DEFAULT="${BUILD_MODE_DEFAULT:-full-gpl}"
LIBS_DEFAULT="${LIBS_DEFAULT:-}"
REQ_CODECS_DEFAULT="${REQ_CODECS_DEFAULT:-libmp3lame,libx264,libx265}"
ALLOW_NET_DEFAULT="${ALLOW_NET_DEFAULT:-true}"
GIT_REF_DEFAULT="${GIT_REF_DEFAULT:-}"
LTS_DEFAULT="${LTS_DEFAULT:-false}"
PREBUILD_X265_DEFAULT="${PREBUILD_X265_DEFAULT:-true}"

PROJECT_PATHS_DEFAULT="${PROJECT_PATHS_DEFAULT:-}"
PROJECT_DEST_DEFAULT="${PROJECT_DEST_DEFAULT:-dist/aar}"

HOST_CACHE_DEFAULT="${HOST_CACHE_DEFAULT:-./_cache}"

if [ -f .ffmpegkitrc ]; then
  echo "Loading config: .ffmpegkitrc"
  # shellcheck source=/dev/null
  source .ffmpegkitrc
fi

die() { echo "Error: $*" >&2; exit 1; }
has() { command -v "$1" >/dev/null 2>&1; }

usage() {
  cat <<'EOF'
FFmpegKit Android Docker Helper (with caching)
----------------------------------------------
Usage:
  ./init.sh build
  ./init.sh run
  ./init.sh gpl64
  ./init.sh gplboth
  ./init.sh minimal64
  ./init.sh lts64
  ./init.sh doctor

Set HOST_CACHE to isolate parallel builds:
  HOST_CACHE=./_cache_run1 ./init.sh run
  HOST_CACHE=./_cache_run2 ./init.sh run
EOF
}

ensure() {
  has docker || die "Docker not found. Install Docker."
  mkdir -p "$OUT_DIR"
}

_build_image() {
  ensure
  local PLATFORM="${PLATFORM:-$PLATFORM_DEFAULT}"
  echo ">> Building image $IMAGE_TAG (platform: $PLATFORM)"
  docker build --platform "$PLATFORM" -t "$IMAGE_TAG" "$DOCKER_DIR"
}

_run_container() {
  ensure
  local PLATFORM="${PLATFORM:-$PLATFORM_DEFAULT}"

  local API="${API_LEVEL}"
  local ABIS="${ABIS:-$ABIS_DEFAULT}"
  local BUILD_MODE="${BUILD_MODE:-$BUILD_MODE_DEFAULT}"
  local LIBS="${LIBS:-$LIBS_DEFAULT}"
  local REQ="${REQ_CODECS:-$REQ_CODECS_DEFAULT}"
  local ALLOW_NET="${ALLOW_NET:-$ALLOW_NET_DEFAULT}"
  local GIT_REF="${GIT_REF:-$GIT_REF_DEFAULT}"
  local LTS="${LTS:-$LTS_DEFAULT}"
  local PROJECT_PATHS="${PROJECT_PATHS:-${PROJECT_PATHS_DEFAULT}}"
  local PROJECT_DEST="${PROJECT_DEST:-${PROJECT_DEST_DEFAULT}}"
  local PREBUILD_X265="${PREBUILD_X265:-$PREBUILD_X265_DEFAULT}"
  local HOST_CACHE="${HOST_CACHE:-$HOST_CACHE_DEFAULT}"

  echo ">> Running build:"
  echo "   IMAGE_TAG=$IMAGE_TAG (platform: $PLATFORM)"
  echo "   API_LEVEL=$API"
  echo "   ABIS=$ABIS"
  echo "   BUILD_MODE=$BUILD_MODE"
  echo "   LIBS=$LIBS"
  echo "   LTS=$LTS  ALLOW_NET=$ALLOW_NET  GIT_REF=$GIT_REF"
  echo "   PROJECT_PATHS=$PROJECT_PATHS"
  echo "   PROJECT_DEST=$PROJECT_DEST"
  echo "   PREBUILD_X265=$PREBUILD_X265"
  echo "   HOST_CACHE=$HOST_CACHE"

  DOCKER_MOUNTS=(-v "$(pwd)/$OUT_DIR:/out")
  mkdir -p "$HOST_CACHE"
  DOCKER_MOUNTS+=(-v "$HOST_CACHE:/cache")
  local HOSTS="${HOST_PROJECTS:-${HOST_PROJECTS_DEFAULT:-}}"
  if [ -n "$HOSTS" ] && [ -n "$PROJECT_PATHS" ]; then
    IFS=',' read -r -a HARR <<< "$HOSTS"
    IFS=',' read -r -a CARR <<< "$PROJECT_PATHS"
    for i in "${!HARR[@]}"; do
      local h="$(echo "${HARR[$i]}" | xargs)"
      local c="$(echo "${CARR[$i]:-}" | xargs)"
      [ -z "$h" ] || [ -z "$c" ] && continue
      [ -d "$h" ] || echo "WARN: host project does not exist: $h"
      DOCKER_MOUNTS+=(-v "$h:$c")
    done
  fi

  if ! docker image inspect "$IMAGE_TAG" >/dev/null 2>/dev/null; then
    echo "Image $IMAGE_TAG not found. Building it first..."
    _build_image
  fi

  set -x
  docker run --rm --platform "$PLATFORM" \
    -e CMAKE_GENERATOR="Ninja" \
    -e CMAKE_TRY_COMPILE_TARGET_TYPE="STATIC_LIBRARY" \
    -e BUILD_MODE="$BUILD_MODE" \
    -e API_LEVEL="$API" \
    -e ABIS="$ABIS" \
    -e ENABLE_LIBS="$LIBS" \
    -e REQ_CODECS="$REQ" \
    -e LTS="$LTS" \
    -e ALLOW_NET="$ALLOW_NET" \
    -e GIT_REF="$GIT_REF" \
    -e PROJECT_PATHS="$PROJECT_PATHS" \
    -e PROJECT_DEST="$PROJECT_DEST" \
    -e PREBUILD_X265="$PREBUILD_X265" \
    -e CACHE_ROOT="/cache" \
    "${DOCKER_MOUNTS[@]}" \
    "$IMAGE_TAG"
  { set +x; } 2>/dev/null

  mkdir -p dist/aar
  if [ -f "$OUT_DIR/ffmpeg-kit.aar" ]; then
    cp -f "$OUT_DIR/ffmpeg-kit.aar" "dist/aar/ffmpeg-kit.aar"
    echo ">> Copied AAR to dist/aar/ffmpeg-kit.aar"
  fi

  echo ">> Done. AAR at: $OUT_DIR/ffmpeg-kit.aar"
}

_doctor() {
  echo "== Doctor =="
  echo "Host arch: $(uname -m)"
  echo "Docker version: $(docker --version 2>/dev/null || echo 'not found')"
  echo "Default platform: ${PLATFORM_DEFAULT}"
  echo "Image tag: ${IMAGE_TAG}"
  echo "Dockerfile dir: ${DOCKER_DIR}"
  echo "Out dir: ${OUT_DIR}"
  echo "API_LEVEL=${API_LEVEL}"
  echo "ABIS_DEFAULT=${ABIS_DEFAULT}"
  echo "BUILD_MODE_DEFAULT=${BUILD_MODE_DEFAULT}"
  echo "LIBS_DEFAULT=${LIBS_DEFAULT}"
  echo "PROJECT_PATHS_DEFAULT=${PROJECT_PATHS_DEFAULT}"
  echo "HOST_PROJECTS_DEFAULT=${HOST_PROJECTS_DEFAULT}"
  echo "PROJECT_DEST_DEFAULT=${PROJECT_DEST_DEFAULT}"
  echo "PREBUILD_X265_DEFAULT=${PREBUILD_X265_DEFAULT}"
  echo "HOST_CACHE_DEFAULT=${HOST_CACHE_DEFAULT}"
  echo
  echo "Tip: To run builds in parallel safely, use different HOST_CACHE directories."
}

cmd="${1:-help}"
case "$cmd" in
  help|-h|--help) usage ;;
  build) _build_image ;;
  run) _run_container ;;
  gpl64)
    ABIS="arm64-v8a"
    BUILD_MODE="full-gpl"
    _run_container
    ;;
  gplboth)
    ABIS="arm64-v8a,armeabi-v7a"
    BUILD_MODE="full-gpl"
    _run_container
    ;;
  minimal64)
    ABIS="arm64-v8a"
    BUILD_MODE="minimal"
    LIBS=""
    _run_container
    ;;
  lts64)
    ABIS="arm64-v8a"
    BUILD_MODE="full-gpl"
    LTS="true"
    _run_container
    ;;
  doctor) _doctor ;;
  *) usage; exit 1 ;;
esac
