#!/bin/sh
set -eu
vps_dir=$(CDPATH= cd -P "$(dirname "$0")/.." && pwd)
if [ ! -x "$vps_dir/modelatlas-vps" ]; then
    echo 'ModelAtlas binary missing. Run sh vps/deploy/build.sh from the repository root first.' >&2
    exit 1
fi
# Keep the caller's working directory and replace the shell for SIGTERM delivery.
exec "$vps_dir/modelatlas-vps"
