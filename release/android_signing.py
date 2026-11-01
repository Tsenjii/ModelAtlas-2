"""Read the public certificate emitted after apksigner verifies the real APK.

SDK 37 uses "V2 Signer:", older tools use numbered or SDK-range labels.
Fingerprint the standard PEM/DER bytes, independently of those display labels.
The fixture contains only a public CI debug certificate, never a private key.
"""
import hashlib
import re
import ssl

def certificate_fingerprint(output):
    counts = re.findall(r"^Number of signers: ([0-9]+)\s*$", output, re.MULTILINE)
    if counts != ["1"]:
        raise ValueError("APK preview must have one verified signer")
    pems = re.findall(r"-----BEGIN CERTIFICATE-----[\sA-Za-z0-9+/=]+-----END CERTIFICATE-----", output)
    if not pems:
        raise ValueError("APK verifier did not emit its public certificate")
    certificates = {hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest() for pem in pems}
    reported = set()
    profiles = []
    for line in output.splitlines():
        label, separator, value = line.partition(" certificate SHA-256 digest: ")
        if separator:
            if not re.fullmatch(r"[a-fA-F0-9]{64}", value.strip()):
                raise ValueError("Invalid APK certificate SHA256")
            reported.add(value.strip().lower())
            profiles.append(label.strip())
    if len(certificates) != 1 or reported != certificates:
        raise ValueError("APK certificate bytes and verifier SHA256 must identify one certificate")
    if not profiles or all(label.startswith("Source Stamp") for label in profiles):
        raise ValueError("A source stamp cannot replace the APK signing certificate")
    return next(iter(certificates)), sorted(set(profiles))

