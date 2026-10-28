#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

cargo ndk \
    -t arm64-v8a \
    -t x86_64 \
    -o android/app/src/main/jniLibs \
    build -p service
