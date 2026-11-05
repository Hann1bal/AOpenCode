#!/usr/bin/env python3
"""Generate the signing key for sideloaded AOpenCode builds.

Why this exists at all: with no committed key, every CI runner creates its own
fresh Android debug keystore, so each APK is signed by a *different* key.
Android then refuses to install a new build over the old one
(INSTALL_FAILED_UPDATE_INCOMPATIBLE), which is exactly the "uninstall first,
then install" dance. One committed key means every build is signed by the same
key and updates land in place.

This key is not a secret and is not meant to be. The app is sideloaded, never
published through Play, and the alternative — a different key per build — is
strictly worse for the person installing it. The passwords live next to the
key on purpose so the build is reproducible without secret management.

Usage:
    pip install cryptography
    python3 scripts/make-keystore.py
"""

import datetime
import pathlib
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

# Kept in sync with app/build.gradle.kts.
STORE_PATH = pathlib.Path(__file__).resolve().parent.parent / "keystore" / "aopencode.p12"
STORE_PASSWORD = "aopencode"
KEY_ALIAS = "aopencode"
KEY_PASSWORD = "aopencode"


def main() -> int:
    if STORE_PATH.exists():
        print(f"ERROR: {STORE_PATH} already exists.", file=sys.stderr)
        print(
            "Refusing to overwrite it: replacing the key invalidates every install "
            "made with the old one. Delete it deliberately if that is intended.",
            file=sys.stderr,
        )
        return 1

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name(
        [
            x509.NameAttribute(NameOID.COMMON_NAME, "AOpenCode"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "AOpenCode"),
        ]
    )
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        # Long validity: an expired signing cert bricks updates for a sideloaded
        # app with no way to rotate without uninstalling.
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365 * 30))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True,
                content_commitment=False,
                key_encipherment=False,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=False,
                crl_sign=False,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .sign(key, hashes.SHA256())
    )

    STORE_PATH.parent.mkdir(parents=True, exist_ok=True)
    blob = pkcs12.serialize_key_and_certificates(
        name=KEY_ALIAS.encode(),
        key=key,
        cert=cert,
        cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(STORE_PASSWORD.encode()),
    )
    STORE_PATH.write_bytes(blob)

    print(f"==> {STORE_PATH} ({STORE_PATH.stat().st_size} bytes)")
    print(f"    storeType    = PKCS12")
    print(f"    storePassword= {STORE_PASSWORD}")
    print(f"    keyAlias     = {KEY_ALIAS}")
    print(f"    keyPassword  = {KEY_PASSWORD}")
    print(f"    valid until  = {cert.not_valid_after_utc:%Y-%m-%d}")
    print(f"    fingerprint  = {cert.fingerprint(hashes.SHA256()).hex()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
