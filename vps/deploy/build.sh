#!/bin/sh
set -eu
vps_dir=$(CDPATH= cd -P "$(dirname "$0")/.." && pwd)
if ! command -v go >/dev/null 2>&1; then
    echo 'ModelAtlas requires Go 1.26 or newer in the build environment.' >&2
    exit 1
fi
cd "$vps_dir"
output=${1:-$vps_dir/modelatlas-vps}
[ "$#" -le 1 ] || { echo 'usage: build.sh [OUTPUT_BINARY]' >&2; exit 1; }
revision=${MODELATLAS_BUILD_COMMIT:-}
if [ -z "$revision" ] && command -v git >/dev/null 2>&1; then revision=$(git rev-parse HEAD 2>/dev/null || true); fi
revision=${revision:-development}
build_time=${MODELATLAS_BUILD_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}
case "$revision" in development) ;; *[!a-f0-9]*) echo 'invalid source revision' >&2; exit 1 ;; esac
case "$build_time" in *[!0-9TZ:-]*) echo 'invalid build time' >&2; exit 1 ;; esac
# Limit concurrent compiler work on small builders; runtime needs only the binary.
GOMEMLIMIT="${GOMEMLIMIT:-256MiB}" GOMAXPROCS="${GOMAXPROCS:-2}" CGO_ENABLED=0 \
    go build -mod=readonly -p=1 -trimpath -ldflags="-s -w -X main.buildCommit=$revision -X main.buildTime=$build_time" -o "$output" .
