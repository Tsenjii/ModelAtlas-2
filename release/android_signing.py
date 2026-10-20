"""Parse certificates only after apksigner successfully verifies the real APK.

Android's official ApkSignerTool prints either numbered signers or min/max SDK
signers for v3.1. A preview APK must still have exactly one signing identity:
https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/apksigner/java/com/android/apksigner/ApkSignerTool.java
"""
import re

LABEL = re.compile(r"Signer (?:#[1-9][0-9]*|\(minSdkVersion=[0-9]+(?: \(dev release=true\))?, maxSdkVersion=[0-9]+\))")

def certificate_fingerprint(output):
    counts = re.findall(r"^Number of signers: ([0-9]+)\s*$", output, re.MULTILINE)
    if counts != ["1"]:
        raise ValueError("APK preview must have one verified signer")
    certificates = set()
    profiles = []
    for line in output.splitlines():
        label, separator, value = line.partition(" certificate SHA-256 digest: ")
        if separator and LABEL.fullmatch(label):
            if not re.fullmatch(r"[a-fA-F0-9]{64}", value.strip()):
                raise ValueError("Invalid APK certificate SHA256")
            certificates.add(value.strip().lower())
            profiles.append(label)
    if len(certificates) != 1:
        raise ValueError("APK preview must have one verified signing certificate across SDK ranges")
    return next(iter(certificates)), sorted(set(profiles))

