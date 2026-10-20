#!/bin/sh
# Download one explicit, verified release. Does not install or stop a service.
set -eu
[ "$#" -eq 2 ] || { echo 'usage: download.sh vVERSION NEW_OUTPUT_DIRECTORY' >&2; exit 1; }
tag=$1
printf '%s\n' "$tag" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?$' || {
    echo 'an explicit release tag is required' >&2; exit 1;
}
[ "$(uname -s)" = Linux ] || { echo 'this download is for Linux VPS only' >&2; exit 1; }
case "$(uname -m)" in
    x86_64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) echo 'unsupported VPS architecture' >&2; exit 1 ;;
esac
for tool in curl sha256sum tar mktemp; do
    command -v "$tool" >/dev/null 2>&1 || { echo "required download tool: $tool" >&2; exit 1; }
done
destination=$2
[ ! -e "$destination" ] && [ ! -L "$destination" ] || { echo 'output already exists; choose a new directory' >&2; exit 1; }
parent=$(dirname "$destination")
mkdir -p "$parent"
parent=$(CDPATH= cd -P "$parent" && pwd)
name=$(basename "$destination")
case "$name" in ''|.|..|*[!A-Za-z0-9._-]*) echo 'use a simple new output directory name' >&2; exit 1 ;; esac
destination=$parent/$name
tmp=$(mktemp -d "$parent/.modelatlas-download.XXXXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
bundle=modelatlas-vps-linux-$arch
url=https://github.com/Tsenjii/ModelAtlas-2/releases/download/$tag
for file in "$bundle.tar.gz" "$bundle.tar.gz.sha256"; do
    curl --disable --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
        --connect-timeout 15 --max-time 300 --retry 3 --output "$tmp/$file" "$url/$file"
done
(cd "$tmp" && sha256sum -c "$bundle.tar.gz.sha256")
tar -xzf "$tmp/$bundle.tar.gz" -C "$tmp"
(cd "$tmp/$bundle" && sha256sum -c SHA256SUMS.txt)
"$tmp/$bundle/bin/$bundle" ops bundle-check --directory "$tmp/$bundle"
[ ! -e "$destination" ] && [ ! -L "$destination" ] || { echo 'output appeared during download; nothing installed' >&2; exit 1; }
# -T rejects an output directory that appears concurrently; never nest/overwrite it.
mv -T -n "$tmp/$bundle" "$destination"
[ ! -e "$tmp/$bundle" ] || { echo 'output already exists; nothing installed' >&2; exit 1; }
printf '已下載並校驗 %s（%s）\n套件位置：%s\n首次安裝：sudo sh "%s/deploy/install.sh" "%s"\n更新：sudo sh "%s/deploy/update.sh" "%s"\n' \
    "$tag" "$arch" "$destination" "$destination" "$destination" "$destination" "$destination"
