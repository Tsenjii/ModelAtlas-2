#!/bin/sh
# Build one complete, versioned architecture bundle. Run on a Go build host.
set -eu
[ "$#" -eq 1 ] || { echo 'usage: package.sh OUTPUT_DIRECTORY' >&2; exit 1; }
vps_dir=$(CDPATH= cd -P "$(dirname "$0")/.." && pwd)
mkdir -p "$1"
output=$(CDPATH= cd -P "$1" && pwd)
arch=${GOARCH:-$(go env GOARCH)}
case "$arch" in amd64|arm64) ;; *) echo 'unsupported release architecture' >&2; exit 1 ;; esac
name=modelatlas-vps-linux-$arch
bundle=$output/$name
[ ! -e "$bundle" ] || { echo 'release output already exists' >&2; exit 1; }
mkdir -p "$bundle/bin" "$bundle/deploy"
export MODELATLAS_BUILD_TIME=${MODELATLAS_BUILD_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}
GOOS=linux GOARCH=$arch sh "$vps_dir/deploy/build.sh" "$bundle/bin/$name"
revision=$(git -C "$vps_dir" rev-parse HEAD)
schema=$(sed -n 's/^const schemaVersion = \([0-9][0-9]*\)$/\1/p' "$vps_dir/migrations.go")
[ -n "$schema" ] || { echo 'missing schema version' >&2; exit 1; }
printf '{"commit":"%s","built_at":"%s","os":"linux","arch":"%s","schema":%s}\n' \
  "$revision" "$MODELATLAS_BUILD_TIME" "$arch" "$schema" >"$bundle/VERSION.json"
for file in install.sh update.sh manage.sh modelatlas-vps.service; do cp "$vps_dir/deploy/$file" "$bundle/deploy/$file"; done
for file in README.md UPDATE.md PLATFORM_DEPLOY.md; do cp "$vps_dir/$file" "$bundle/$file"; done
chmod 755 "$bundle/bin/$name" "$bundle/deploy/"*.sh
(cd "$bundle" && sha256sum VERSION.json "bin/$name" deploy/install.sh deploy/update.sh deploy/manage.sh deploy/modelatlas-vps.service README.md UPDATE.md PLATFORM_DEPLOY.md >SHA256SUMS.txt)
tar -C "$output" -czf "$output/$name.tar.gz" "$name"
(cd "$output" && sha256sum "$name.tar.gz" >"$name.tar.gz.sha256")
echo "$output/$name.tar.gz"
