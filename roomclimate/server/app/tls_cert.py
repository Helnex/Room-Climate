"""Самоподписанный сертификат для HTTPS в локальной сети (сервис api-tls).

Публичного домена у стенда нет, поэтому настоящий сертификат не выпустить. Вместо этого
приложение «прибивает» (pinning) ключ этого сертификата: отпечаток передаётся через QR-код
страницы /connect, и телефон принимает только сервер с этим ключом. Так трафик, токен и
пароль в Wi‑Fi шифруются, а подменить сервер нельзя.

Запуск: python -m app.tls_cert  — создаёт /certs/key.pem и /certs/cert.pem, если их нет.
"""
from __future__ import annotations

import base64
import hashlib
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

CERT_DIR = Path(os.environ.get("CERT_DIR", "/certs"))
KEY_FILE = CERT_DIR / "key.pem"
CERT_FILE = CERT_DIR / "cert.pem"


def ensure_cert() -> None:
    if KEY_FILE.exists() and CERT_FILE.exists():
        return
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    CERT_DIR.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "RoomClimate local server")])
    now = datetime.now(timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=3650))
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("roomclimate.local")]), critical=False)
        .sign(key, hashes.SHA256())
    )
    KEY_FILE.write_bytes(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ))
    os.chmod(KEY_FILE, 0o600)
    CERT_FILE.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    print(f"Создан сертификат {CERT_FILE}, pin {spki_pin()}", flush=True)


def spki_pin() -> str | None:
    """sha256/<base64> от SubjectPublicKeyInfo — тот же формат, что у OkHttp CertificatePinner."""
    if not CERT_FILE.exists():
        return None
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization

        cert = x509.load_pem_x509_certificate(CERT_FILE.read_bytes())
        spki = cert.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        return "sha256/" + base64.b64encode(hashlib.sha256(spki).digest()).decode("ascii")
    except Exception:  # noqa: BLE001
        return None


if __name__ == "__main__":
    ensure_cert()
