FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
ARG ALLOW_NET="true"

ENV TZ=UTC \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools

# Tools needed by ffmpeg-kit and deps
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless ca-certificates curl unzip zip git \
    build-essential autoconf automake libtool pkg-config \
    meson ninja-build nasm yasm gperf gettext cmake python3 file \
    gawk groff texinfo ccache autogen m4 bison flex ragel swig \
    patch perl rsync wget \
    autopoint \
    libtasn1-bin \
    gnulib gtk-doc-tools \
 && rm -rf /var/lib/apt/lists/*

# Use ccache via CMake compiler launchers only. Do NOT set CC/CXX here,
# so the Android CMake toolchain chooses the correct NDK compilers.

ENV CCACHE_DIR=/cache/ccache
ENV CMAKE_C_COMPILER_LAUNCHER=ccache
ENV CMAKE_CXX_COMPILER_LAUNCHER=ccache

# "make" shim: if there's no Makefile but build.ninja exists, run ninja
RUN printf '%s\n' \
'#!/usr/bin/env bash' \
'if [ -f Makefile ]; then exec /usr/bin/make "$@"; fi' \
'if [ -f build.ninja ]; then exec /usr/bin/ninja "$@"; fi' \
'exec /usr/bin/make "$@"' > /usr/local/bin/make \
 && chmod +x /usr/local/bin/make

# Android cmdline-tools + SDK bits (online mode)
RUN if [ "$ALLOW_NET" = "true" ]; then \
      set -eux; \
      mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools; \
      curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cli-tools.zip; \
      unzip -q /tmp/cli-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools; \
      rm /tmp/cli-tools.zip; \
      mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest; \
      ln -sf ${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager /usr/local/bin/sdkmanager; \
      yes | sdkmanager --sdk_root=${ANDROID_SDK_ROOT} --licenses || true; \
      sdkmanager --sdk_root=${ANDROID_SDK_ROOT} "platform-tools" "platforms;android-33" "build-tools;33.0.1" "ndk;23.1.7779620" "cmake;3.22.1"; \
    else \
      echo "Offline mode: expecting pre-installed SDK at ${ANDROID_SDK_ROOT}"; \
    fi

ENV ANDROID_NDK_ROOT=${ANDROID_SDK_ROOT}/ndk/23.1.7779620

WORKDIR /opt

# Build runner
COPY build-ffmpegkit.sh /usr/local/bin/build-ffmpegkit.sh
RUN chmod +x /usr/local/bin/build-ffmpegkit.sh

ENTRYPOINT ["/usr/local/bin/build-ffmpegkit.sh"]
