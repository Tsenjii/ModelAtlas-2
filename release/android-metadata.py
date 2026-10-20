#!/usr/bin/env python3
"""Record the exact CI APK and its real signer; never export signing keys."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from android_signing import certificate_fingerprint

root = Path(__file__).resolve().parents[1]
properties = dict(line.split("=", 1) for line in (root / "release/version.properties").read_text().splitlines() if line and not line.startswith("#"))
apk = root / "app/build/outputs/apk/debug/app-debug.apk"
sdk = Path(os.environ["ANDROID_HOME"])
signer = sorted((sdk / "build-tools").glob("*/apksigner"))[-1]
result = subprocess.run([str(signer), "verify", "--verbose", "--print-certs", str(apk)], check=True, text=True, capture_output=True)
certificate_sha256, certificate_profiles = certificate_fingerprint(result.stdout)
print("Verified APK certificate SHA256:", certificate_sha256, "profiles:", ", ".join(certificate_profiles))
badging = subprocess.check_output([str(signer.parent / "aapt2"), "dump", "badging", str(apk)], text=True)
package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
minimum = re.search(r"^sdkVersion:'([0-9]+)'", badging, re.MULTILINE)
if not package or package.groups() != ("dev.modelatlas.app", properties["androidVersionCode"], properties["androidVersionName"]) or not minimum or minimum[1] != "26":
    raise SystemExit("Actual APK package/version/minimum SDK mismatch")
commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
if commit != os.environ["MODELATLAS_SOURCE_COMMIT"]:
    raise SystemExit("APK checkout does not match the tested source")
metadata = {
    "schema": 1, "commit": commit, "tag": properties["tag"],
    "version_name": properties["androidVersionName"],
    "version_code": int(properties["androidVersionCode"]), "minimum_android_api": 26,
    "variant": "debug-preview", "production_signing": False,
    "certificate_sha256": certificate_sha256, "certificate_profiles": certificate_profiles,
    "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    "update_notice": "預覽 APK 使用本次 CI 的 debug 憑證；與既有安裝憑證不同時 Android 會拒絕覆蓋。請先保留本機資料，不要直接移除既有 App。正式可持續更新版需要固定的私密簽署金鑰。"
}
out = root / "release-output"
out.mkdir(exist_ok=True)
(out / "android.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
print("Verified Android preview APK provenance and signing certificate.")
