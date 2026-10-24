#!/bin/bash
set -euo pipefail
mac_dir=$(cd "$(dirname "$0")/.." && pwd)
repo_dir=$(cd "$mac_dir/.." && pwd)
dist="$mac_dir/dist"
app="$dist/ModelAtlas.app"
export MACOSX_DEPLOYMENT_TARGET=12.0
revision=$(git -C "$repo_dir" rev-parse HEAD)
export MODELATLAS_BUILD_COMMIT="$revision"
export MODELATLAS_BUILD_TIME=$(date -u +%Y-%m-%dT%H:%M:%SZ)
swift build --package-path "$mac_dir" -c release --arch arm64 --arch x86_64
bin_dir=$(swift build --package-path "$mac_dir" -c release --arch arm64 --arch x86_64 --show-bin-path)
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" "$dist/engine-build"
cp "$mac_dir/Resources/Info.plist" "$app/Contents/Info.plist"
python3 "$repo_dir/release/mac-metadata.py" "$app"
cp "$bin_dir/ModelAtlas" "$app/Contents/MacOS/ModelAtlas"
for arch in arm64 amd64; do
    GOOS=darwin GOARCH="$arch" sh "$repo_dir/vps/deploy/build.sh" "$dist/engine-build/$arch"
done
lipo -create "$dist/engine-build/arm64" "$dist/engine-build/amd64" -output "$app/Contents/MacOS/ModelAtlasEngine"
iconset="$dist/AppIcon.iconset"
mkdir -p "$iconset"
swift "$mac_dir/scripts/icon.swift" "$dist/icon.png"
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$dist/icon.png" --out "$iconset/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z "$double" "$double" "$dist/icon.png" --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$iconset" -o "$app/Contents/Resources/AppIcon.icns"
chmod 755 "$app/Contents/MacOS/ModelAtlas" "$app/Contents/MacOS/ModelAtlasEngine"
codesign --force --sign - --timestamp=none "$app/Contents/MacOS/ModelAtlasEngine"
codesign --force --sign - --timestamp=none --options runtime "$app"
codesign --verify --deep --strict "$app"
lipo "$app/Contents/MacOS/ModelAtlas" -verify_arch arm64 x86_64
lipo "$app/Contents/MacOS/ModelAtlasEngine" -verify_arch arm64 x86_64
ditto -c -k --sequesterRsrc --keepParent "$app" "$dist/ModelAtlas-macOS-Universal.zip"
mkdir -p "$dist/dmg-root"
ditto "$app" "$dist/dmg-root/ModelAtlas.app"
ln -sfn /Applications "$dist/dmg-root/Applications"
hdiutil create -volname ModelAtlas -srcfolder "$dist/dmg-root" -ov -format UDZO "$dist/ModelAtlas-macOS-Universal.dmg"
(cd "$dist" && shasum -a 256 ModelAtlas-macOS-Universal.zip ModelAtlas-macOS-Universal.dmg > SHA256SUMS.txt)
