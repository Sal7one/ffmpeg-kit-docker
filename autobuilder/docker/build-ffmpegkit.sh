#!/usr/bin/env bash
set -euo pipefail

# Inputs
OUTPUT_DIR="${OUTPUT_DIR:-/out}"
CACHE_ROOT="${CACHE_ROOT:-/cache}"             # persistent host-mounted cache
PROJECT_PATHS="${PROJECT_PATHS:-}"
PROJECT_DEST="${PROJECT_DEST:-dist/aar}"       # where to copy in each project
BUILD_MODE="${BUILD_MODE:-full-gpl}"           # minimal | full | full-gpl
API_LEVEL="${API_LEVEL:-28}"
ABIS="${ABIS:-arm64-v8a,armeabi-v7a}"
LTS="${LTS:-false}"
GIT_REF="${GIT_REF:-}"
ALLOW_NET="${ALLOW_NET:-true}"
LIBS="${LIBS:-}"
REQ_CODECS="${REQ_CODECS:-libmp3lame,libx264,libx265}"
CLEAN="${CLEAN:-false}"

# Optional: prebuild x265 (8-bit only)
PREBUILD_X265="${PREBUILD_X265:-true}"

# CMake safety for cross-compile + ccache launchers
export CMAKE_GENERATOR="${CMAKE_GENERATOR:-Ninja}"
export CMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
export CMAKE_SYSTEM_PROCESSOR="${CMAKE_SYSTEM_PROCESSOR:-aarch64}"
export CMAKE_C_COMPILER_LAUNCHER=ccache
export CMAKE_CXX_COMPILER_LAUNCHER=ccache
export LANG=C.UTF-8


# Do NOT override CC/CXX; let Android toolchain choose target compilers.

# ccache config (persistent)
export CCACHE_DIR="${CCACHE_DIR:-${CACHE_ROOT}/ccache}"
mkdir -p "${CCACHE_DIR}"
ccache --set-config=cache_dir="${CCACHE_DIR}" || true
ccache --set-config=max_size=8G || true
ccache --zero-stats || true

# Capture user list and clean env
USER_LIBS="${ENABLE_LIBS:-${LIBS:-}}"
unset LIBS ENABLE_LIBS
unset MAKE || true

mkdir -p "$OUTPUT_DIR"
LOG_DIR="$OUTPUT_DIR/logs"
mkdir -p "$LOG_DIR"

echo "== FFmpegKit build =="
echo "OUTPUT_DIR=$OUTPUT_DIR"
echo "CACHE_ROOT=$CACHE_ROOT"
echo "BUILD_MODE=$BUILD_MODE"
echo "API_LEVEL=$API_LEVEL"
echo "ABIS=$ABIS"
echo "LTS=$LTS"
echo "GIT_REF=$GIT_REF"
echo "ALLOW_NET=$ALLOW_NET"
echo "ENABLE_LIBS=$USER_LIBS"
echo "REQ_CODECS=$REQ_CODECS"
echo "PREBUILD_X265=$PREBUILD_X265"

dump_logs() {
  echo "Collecting build logs into $LOG_DIR" >&2
  if [ -d "$SRC_DIR" ]; then
    cp -f "$SRC_DIR"/build*.log "$LOG_DIR/" 2>/dev/null || true
    find "$SRC_DIR/src" -type f -name 'config.log' -print -exec sh -c 'dst="$1"; dst=${dst#'"$SRC_DIR"'/src/}; dst_dir=$(dirname "$dst"); mkdir -p "'"$LOG_DIR"'/$dst_dir"; cp -f "$1" "'"$LOG_DIR"'/$dst"' sh {} \; 2>/dev/null || true
  fi
  ccache --show-stats || true
}
trap dump_logs EXIT

# Clone or reuse ffmpeg-kit under cache
SRC_DIR_DEFAULT="${CACHE_ROOT}/ffmpeg-kit"
if [ -d "${SRC_DIR_DEFAULT}/.git" ] || [ ! -d /opt/ffmpeg-kit ]; then
  mkdir -p "$(dirname "$SRC_DIR_DEFAULT")"
  SRC_DIR="$SRC_DIR_DEFAULT"
else
  SRC_DIR="/opt/ffmpeg-kit"
fi

if [ -d "$SRC_DIR/.git" ]; then
  echo "Using cached ffmpeg-kit at $SRC_DIR"
  (cd "$SRC_DIR" && git fetch --depth 1 origin || true)
else
  if [ "$ALLOW_NET" = "true" ]; then
    echo "Cloning ffmpeg-kit into $SRC_DIR"
    rm -rf "$SRC_DIR"
    git clone --depth 1 https://github.com/arthenica/ffmpeg-kit.git "$SRC_DIR"
    (cd "$SRC_DIR" && git submodule update --init --recursive || true)
  else
    echo "ERROR: ffmpeg-kit not present in cache and ALLOW_NET=false" >&2
    exit 2
  fi
fi

if [ -n "$GIT_REF" ]; then
  (cd "$SRC_DIR" && git fetch --depth 1 origin "$GIT_REF" && git checkout "$GIT_REF")
fi

cd "$SRC_DIR"

# Clean if requested (keep repo for cache)
if [ "$CLEAN" = "true" ]; then
  echo "Cleaning previous prebuilt and tmp directories in $SRC_DIR"
  rm -rf prebuilt .tmp src || true
fi

# NDK env
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-/opt/android-sdk}
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-/opt/android-sdk/ndk/23.1.7779620}

# NDK host-tag
HOST_TAG=""
if [ -d "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64" ]; then
  HOST_TAG="linux-x86_64"
elif [ -d "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-aarch64" ]; then
  HOST_TAG="linux-aarch64"
fi
if [ -n "$HOST_TAG" ]; then
  export TOOLCHAIN="$HOST_TAG"
  export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH"
  echo "Using NDK host-tag: $HOST_TAG"
fi

# ---------- Optional: Prebuild x265 (single-ABI best) ----------
prebuilt_root="${CACHE_ROOT}/prebuilt"
src_cache_root="${CACHE_ROOT}/src"
mkdir -p "$prebuilt_root" "$src_cache_root"
need_prebuild=true
if [[ "$ABIS" == *","* ]]; then
  need_prebuild=false
  echo "NOTE: Multiple ABIs requested. Skipping x265 prebuild."
fi

build_x265_for_abi() {
  local abi="$1"
  local plat="android-${API_LEVEL}"
  local prefix="${prebuilt_root}/x265-${abi}"
  local workdir="${src_cache_root}/x265-${abi}"
  echo "== Prebuilding x265 (8-bit) for ABI ${abi} =="
  mkdir -p "${workdir}"
  pushd "${workdir}" >/dev/null

  if [ ! -d x265 ]; then
    if [ "$ALLOW_NET" = "true" ]; then
      git clone --depth 1 https://github.com/videolan/x265.git
    else
      echo "ERROR: x265 sources not cached and ALLOW_NET=false" >&2
      exit 2
    fi
  fi

  mkdir -p build && cd build

  cmake -G Ninja ../x265/source \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${abi}" \
    -DANDROID_PLATFORM="${plat}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
    -DCMAKE_INSTALL_PREFIX="${prefix}" \
    -DENABLE_CLI=OFF \
    -DENABLE_SHARED=OFF \
    -DENABLE_PIC=ON \
    -DHIGH_BIT_DEPTH=OFF \
    -DMAIN12=OFF \
    -DENABLE_HDR10_PLUS=OFF \
    -DENABLE_ASSEMBLY=OFF

  ninja -v
  ninja install

  if [ -f "${prefix}/lib/pkgconfig/x265.pc" ]; then
    echo "x265.pc installed at: ${prefix}/lib/pkgconfig/x265.pc"
  else
    echo "WARNING: x265.pc not found under ${prefix}."
  fi

  popd >/dev/null
}

PKG_PATHS=()
if $need_prebuild && [ "${PREBUILD_X265}" = "true" ]; then
  IFS=',' read -r -a ABI_ARR <<< "$ABIS"
  if [ "${#ABI_ARR[@]}" -eq 1 ]; then
    build_x265_for_abi "${ABI_ARR[0]}"
    PKG_PATHS+=("${prebuilt_root}/x265-${ABI_ARR[0]}/lib/pkgconfig")
  fi
fi

# Build flags for ffmpeg-kit android.sh
FLAGS=("--api-level=${API_LEVEL}")
[ "$LTS" = "true" ] && FLAGS+=("--lts")

case "$ABIS" in *"arm64-v8a"*) : ;; *) FLAGS+=("--disable-arm64-v8a");; esac
case "$ABIS" in *"armeabi-v7a"*) : ;; *) FLAGS+=("--disable-arm-v7a" "--disable-arm-v7a-neon");; esac
case "$ABIS" in *"x86"*) : ;; *) FLAGS+=("--disable-x86");; esac
case "$ABIS" in *"x86_64"*) : ;; *) FLAGS+=("--disable-x86-64");; esac

if [ -n "$USER_LIBS" ]; then
  FLAGS+=("--enable-gpl")
  IFS=',' read -r -a LIB_ARR <<< "$USER_LIBS"
  for lib in "${LIB_ARR[@]}"; do
    lib_trim="$(echo "$lib" | xargs)"
    [ -z "$lib_trim" ] && continue
    FLAGS+=("--enable-${lib_trim}")
  done
else
  case "$BUILD_MODE" in
    minimal) ;;
    full) FLAGS+=("--full");;
    full-gpl) FLAGS+=("--full" "--enable-gpl");;
    *) echo "Unknown BUILD_MODE=$BUILD_MODE" >&2; exit 3;;
  esac
fi

if [ "${#PKG_PATHS[@]}" -gt 0 ]; then
  export PKG_CONFIG_PATH="$(IFS=:; echo "${PKG_PATHS[*]}")${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
  echo "Using prebuilt x265 via PKG_CONFIG_PATH:"
  echo "PKG_CONFIG_PATH=$PKG_CONFIG_PATH"
fi


# --- Force Ninja only for SRT; disable autotools safely ---
SRT_SCRIPT="$SRC_DIR/scripts/android/srt.sh"

if [ -f "$SRT_SCRIPT" ]; then
  echo "Patching SRT script at: $SRT_SCRIPT"

  # 1) If our previous bad ':# ...' line exists, replace it with a comment-only line.
  sed -i 's@^:# disabled ./configure.*$@# disabled: ./configure (Android uses CMake)@' "$SRT_SCRIPT"

  # 2) Comment out any './configure ...' line (no shell executed)
  sed -i 's@^[[:space:]]*\./configure\b.*$@# disabled: ./configure (Android uses CMake)@' "$SRT_SCRIPT"

  # 3) Ensure CMake has a make tool and uses Ninja for SRT
  if grep -nE '^\s*set -e' "$SRT_SCRIPT" >/dev/null; then
    sed -i '/^\s*set -e/a \
# Force Ninja just for SRT\
export CMAKE_GENERATOR=Ninja\
export CMAKE_MAKE_PROGRAM=/usr/bin/ninja\
export CMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY\
unset MAKE' "$SRT_SCRIPT"
  else
    sed -i '1a \
# Force Ninja just for SRT\
export CMAKE_GENERATOR=Ninja\
export CMAKE_MAKE_PROGRAM=/usr/bin/ninja\
export CMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY\
unset MAKE' "$SRT_SCRIPT"
  fi

  # 4) Make the FIRST 'cmake' call explicitly use Ninja
  sed -i '0,/cmake[[:space:]]/s//cmake -G Ninja -DCMAKE_MAKE_PROGRAM=\/usr\/bin\/ninja /' "$SRT_SCRIPT"
fi

# 5) Clear only SRT’s CMake cache so generator change takes effect
rm -rf "$SRC_DIR/src/srt/CMakeCache.txt" "$SRC_DIR/src/srt/CMakeFiles" 2>/dev/null || true



# ---- hard-fix GnuTLS gnulib submodule with robust cached mirror ----
GNUTLS_SRC="${SRC_DIR:?}/src/gnutls"
GNULIB_UPSTREAM="${GNULIB_UPSTREAM:-https://git.savannah.gnu.org/git/gnulib.git}"
# use a *bare* mirror under a dedicated mirrors/ folder
GNULIB_MIRROR="${CACHE_ROOT:-/opt/cache}/mirrors/gnulib.git"

fix_gnutls_gnulib() {
  [ -d "$GNUTLS_SRC" ] || return 0
  (
    set -euo pipefail
    cd "$GNUTLS_SRC"

    # Fast skip if already bootstrapped
    if [ -f configure ]; then
      echo "[gnutls] configure present; skipping bootstrap"
      exit 0
    fi

    # Commit pinned by superproject (gitlink)
    target_rev="$(git ls-tree HEAD gnulib 2>/dev/null | awk '{print $3}' || true)"

    # If gnulib dir exists but isn't a git checkout, quarantine it
    if [ -d gnulib ] && ! git -C gnulib rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      mv gnulib "gnulib._stale_$(date +%s)" || true
    fi

    # Prepare/update the *bare* mirror atomically
    if [ "${ALLOW_NET:-true}" = "true" ]; then
      mkdir -p "$(dirname "$GNULIB_MIRROR")"
      if ! git -C "$GNULIB_MIRROR" rev-parse --git-dir >/dev/null 2>&1; then
        rm -rf "${GNULIB_MIRROR}.tmp" "$GNULIB_MIRROR"
        git -c protocol.version=2 clone --mirror --no-tags --filter=blob:none \
          "$GNULIB_UPSTREAM" "${GNULIB_MIRROR}.tmp"
        mv "${GNULIB_MIRROR}.tmp" "$GNULIB_MIRROR"
      else
        # refresh; if fetch fails (corrupt mirror), nuke & recreate
        git -C "$GNULIB_MIRROR" fetch --prune --no-tags --filter=blob:none || {
          rm -rf "$GNULIB_MIRROR"
          git -c protocol.version=2 clone --mirror --no-tags --filter=blob:none \
            "$GNULIB_UPSTREAM" "${GNULIB_MIRROR}.tmp"
          mv "${GNULIB_MIRROR}.tmp" "$GNULIB_MIRROR"
        }
        git -C "$GNULIB_MIRROR" gc --prune=now --quiet || true
      fi
    fi

    # Sync submodule url and init using the mirror if available
    git submodule sync gnulib || true
    if [ "${ALLOW_NET:-true}" = "true" ]; then
      git -c protocol.version=2 submodule update --init --recommend-shallow \
        ${GNULIB_MIRROR:+--reference-if-able "$GNULIB_MIRROR"} gnulib || {
        # Fallback: shallow direct clone into ./gnulib (handles non-empty dir errors too)
        rm -rf gnulib
        git -c protocol.version=2 clone --depth 1 "$GNULIB_UPSTREAM" gnulib
      }
    else
      [ -d gnulib/.git ] || { echo "[-] Offline and no gnulib checkout"; exit 1; }
    fi

    # Pin to superproject commit if known
    if [ -n "${target_rev:-}" ]; then
      ( cd gnulib && git fetch --all --quiet || true; git reset --hard -q "$target_rev" || true )
    fi

    # Tool sanity (clearer errors than bootstrap’s)
    for t in gnulib-tool autopoint; do
      command -v "$t" >/dev/null 2>&1 || {
        echo "[-] Required tool '$t' not on PATH" >&2
        exit 1
      }
    done

    # Quiet gtk-doc (not needed for Android)
    : "${GTKDOCIZE:=/bin/true}"

    echo "[gnutls] running bootstrap (skip-po)…"
    NOCONFIGURE=1 GNULIB_TOOL="${GNULIB_TOOL:-/usr/bin/gnulib-tool}" GTKDOCIZE="$GTKDOCIZE" \
      ./bootstrap --skip-po \
        ${GNULIB_MIRROR:+--gnulib-srcdir="$GNULIB_MIRROR"} || true

    git submodule absorbgitdirs || true
    echo "[gnutls] bootstrap OK"
  )
}
fix_gnutls_gnulib

CPUF="$SRC_DIR/scripts/android/cpu-features.sh"
if [ -f "$CPUF" ]; then
  echo "Patching cpu-features.sh to use Unix Makefiles…"
  # ensure the script generates a Makefile (since it runs `make`)
  if grep -nE '^\s*set -e' "$CPUF" >/dev/null; then
    sed -i '/^\s*set -e/a \
export CMAKE_GENERATOR="Unix Makefiles";\
unset CMAKE_MAKE_PROGRAM' "$CPUF"
  else
    sed -i '1a export CMAKE_GENERATOR="Unix Makefiles"; unset CMAKE_MAKE_PROGRAM' "$CPUF"
  fi
  # nuke its CMake cache so the generator switch takes effect
  rm -rf "$SRC_DIR/.tmp/cmake/build/android-arm64/cpu-features" 2>/dev/null || true
fi

echo "Running ./android.sh ${FLAGS[*]} (logging to /tmp/ffkit_build.log)"
set +e
./android.sh "${FLAGS[@]}" | tee /tmp/ffkit_build.log
STATUS=$?
set -e
cp -f /tmp/ffkit_build.log "$LOG_DIR/ffkit_build.log" 2>/dev/null || true
if [ $STATUS -ne 0 ]; then
  echo "Build failed with status $STATUS; exporting logs" >&2
  exit $STATUS
fi

# AAR output detection
AAR_PATH="prebuilt/bundle-android-aar/ffmpeg-kit/ffmpeg-kit.aar"
AAR_PATH_LTS="prebuilt/bundle-android-aar-lts/ffmpeg-kit/ffmpeg-kit.aar"
if [ -f "$AAR_PATH" ]; then
  OUT_AAR="$AAR_PATH"
elif [ -f "$AAR_PATH_LTS" ]; then
  OUT_AAR="$AAR_PATH_LTS"
else
  echo "ERROR: AAR not produced at expected paths." >&2
  find prebuilt -type f -name '*.aar' -maxdepth 6 || true
  exit 4
fi

mkdir -p "$OUTPUT_DIR"
cp -f "$OUT_AAR" "$OUTPUT_DIR/ffmpeg-kit.aar"
echo "AAR built: $OUT_AAR -> $OUTPUT_DIR/ffmpeg-kit.aar"

# Copy into consumer projects
IFS=',' read -r -a PROJECTS <<< "$PROJECT_PATHS"
for P in "${PROJECTS[@]}"; do
  P="$(echo "$P" | xargs)"
  [ -z "$P" ] && continue
  dest="$P/$PROJECT_DEST"
  mkdir -p "$dest"
  cp -f "$OUTPUT_DIR/ffmpeg-kit.aar" "$dest/ffmpeg-kit.aar"
  echo "Copied to $dest/ffmpeg-kit.aar"
done

NDK_VER="${ANDROID_NDK_ROOT##*/}"
cat > "$OUTPUT_DIR/manifest.json" <<JSON
{
  "build_mode": "$BUILD_MODE",
  "api_level": "$API_LEVEL",
  "abis": "$ABIS",
  "lts": "$LTS",
  "ndk": "$NDK_VER",
  "cmake": "3.22.1",
  "sdk_platforms": ["android-33"],
  "git_ref": "$GIT_REF",
  "host_tag": "$HOST_TAG",
  "prebuilt_x265": ${#PKG_PATHS[@]},
  "ccache_dir": "$CCACHE_DIR"
}
JSON

echo "Done."
