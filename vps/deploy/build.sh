#!/bin/sh
set -eu
vps_dir=$(CDPATH= cd -P "$(dirname "$0")/.." && pwd)
if ! command -v go >/dev/null 2>&1; then
    echo 'ModelAtlas requires Go 1.26 or newer in the build environment.' >&2
    exit 1
fi
cd "$vps_dir"
# Limit concurrent compiler work on small builders; runtime needs only the binary.
GOMEMLIMIT="${GOMEMLIMIT:-256MiB}" GOMAXPROCS="${GOMAXPROCS:-2}" CGO_ENABLED=0 \
    go build -mod=readonly -p=1 -trimpath -ldflags="-s -w" -o "$vps_dir/modelatlas-vps" .
