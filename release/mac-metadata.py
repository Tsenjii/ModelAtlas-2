#!/usr/bin/env python3
"""Stamp the app and packaged engine with the same release identity."""
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
app = Path(sys.argv[1])
properties = dict(line.split("=", 1) for line in (root / "release/version.properties").read_text().splitlines() if line and not line.startswith("#"))
commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
if commit != os.environ["MODELATLAS_BUILD_COMMIT"]:
    raise SystemExit("macOS checkout does not match the engine source")
plist_path = app / "Contents/Info.plist"
with plist_path.open("rb") as source:
    plist = plistlib.load(source)
plist["CFBundleShortVersionString"] = properties["version"]
plist["CFBundleVersion"] = properties["macBuild"]
with plist_path.open("wb") as output:
    plistlib.dump(plist, output)
(app / "Contents/Resources/BuildInfo.json").write_text(json.dumps({
    "schema": 1, "commit": commit, "tag": properties["tag"],
    "version": properties["version"], "build": properties["macBuild"],
    "built_at": os.environ["MODELATLAS_BUILD_TIME"],
    "architectures": ["arm64", "x86_64"], "minimum_macos": "12.0",
    "signing": "ad-hoc", "notarized": False
}, ensure_ascii=False, indent=2) + "\n")
