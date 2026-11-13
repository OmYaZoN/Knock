"""Reference implementation of the Knock v1 protocol crypto (protocol/PROTOCOL.md).

Not used by the apps. It exists so the Swift (Mac) and Kotlin (Android) code can be
checked against the same numbers:

    python3 knock_ref.py --vectors > vectors.json   # regenerate test vectors
    python3 knock_ref.py --selftest                 # full phone<->Mac run in memory

Requires: pip install cryptography
"""
from __future__ import annotations

import hashlib
import json
import os
import struct
import sys

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

# Frame types (outer)
PAIR_COMMIT, PAIR_MAC, PAIR_PHONE, PAIR_OK = 0x01, 0x02, 0x03, 0x04
HELLO, HELLO_MAC, HELLO_FIN = 0x10, 0x11, 0x12
SEALED = 0x20
# Inner types
ROUND_START, SHOW, SHOWN, ROUND_RESULT = 0x30, 0x31, 0x32, 0x33
FILE_HEADER, FILE_CHUNK, FILE_END, FILE_SAVED = 0x40, 0x41, 0x42, 0x43

MAX_FRAME = 1 << 20


def sha256(*parts: bytes) -> bytes:
    h = hashlib.sha256()
    for p in parts:
        h.update(p)
    return h.digest()


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


def priv_from_scalar(d: int) -> ec.EllipticCurvePrivateKey:
    return ec.derive_private_key(d, ec.SECP256R1())


def pub_bytes(priv_or_pub) -> bytes:
    pub = priv_or_pub.public_key() if hasattr(priv_or_pub, "public_key") else priv_or_pub
    return pub.public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)


def pub_from_bytes(b: bytes) -> ec.EllipticCurvePublicKey:
    return ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), b)


def ecdh(priv: ec.EllipticCurvePrivateKey, peer_pub: bytes) -> bytes:
    return priv.exchange(ec.ECDH(), pub_from_bytes(peer_pub))


def short_id(identity_pub: bytes) -> bytes:
    return sha256(identity_pub)[:8]


# ---- Framing ---------------------------------------------------------------

def frame(ftype: int, body: bytes) -> bytes:
    n = 1 + len(body)
    if n > MAX_FRAME:
        raise ValueError("frame too large")
    return struct.pack(">I", n) + bytes([ftype]) + body


def unframe(data: bytes) -> tuple[int, bytes, bytes]:
    (n,) = struct.unpack(">I", data[:4])
    if n > MAX_FRAME or n < 1:
        raise ValueError("bad frame length")
    return data[4], data[5:4 + n], data[4 + n:]


# ---- Pairing ---------------------------------------------------------------

def pair_commit(phone_pub: bytes, np_: bytes) -> bytes:
    return sha256(phone_pub, np_)


def pair_code(own_priv, peer_pub: bytes, np_: bytes, nm: bytes) -> str:
    z = ecdh(own_priv, peer_pub)
    okm = hkdf(z, np_ + nm, b"knock v1 pair code", 4)
    return f"{struct.unpack('>I', okm)[0] % 1_000_000:06d}"


# ---- Session handshake -----------------------------------------------------

def transcript(phone_id: bytes, eph_phone_pub: bytes, rp: bytes, eph_mac_pub: bytes, rm: bytes) -> bytes:
    return sha256(b"knock v1 hs", phone_id, eph_phone_pub, rp, eph_mac_pub, rm)


def sign(identity_priv, t: bytes, role: bytes) -> bytes:
    return identity_priv.sign(t + role, ec.ECDSA(hashes.SHA256()))


def verify(identity_pub: bytes, sig: bytes, t: bytes, role: bytes) -> bool:
    try:
        pub_from_bytes(identity_pub).verify(sig, t + role, ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


def session_keys(own_eph_priv, peer_eph_pub: bytes, t: bytes) -> tuple[bytes, bytes]:
    ze = ecdh(own_eph_priv, peer_eph_pub)
    return (hkdf(ze, t, b"knock v1 phone->mac", 32), hkdf(ze, t, b"knock v1 mac->phone", 32))


# ---- Sealed frames ---------------------------------------------------------

class Channel:
    """One direction of encrypted frames, with its own counter."""

    def __init__(self, key: bytes):
        self.aead = AESGCM(key)
        self.counter = 0

    def _nonce(self) -> bytes:
        return b"\x00\x00\x00\x00" + struct.pack(">Q", self.counter)

    def seal(self, inner_type: int, inner_body: bytes) -> bytes:
        plaintext = bytes([inner_type]) + inner_body
        length = 1 + len(plaintext) + 16  # type byte + ciphertext + tag
        aad = bytes([SEALED]) + struct.pack(">I", length)
        ct = self.aead.encrypt(self._nonce(), plaintext, aad)
        self.counter += 1
        return frame(SEALED, ct)

    def open(self, data: bytes) -> tuple[int, bytes, bytes]:
        ftype, body, rest = unframe(data)
        if ftype != SEALED:
            raise ValueError("expected SEALED")
        aad = bytes([SEALED]) + struct.pack(">I", 1 + len(body))
        pt = self.aead.decrypt(self._nonce(), body, aad)
        self.counter += 1
        return pt[0], pt[1:], rest


# ---- Test vectors ----------------------------------------------------------

def vectors() -> dict:
    phone_id_priv = priv_from_scalar(0x1111111111111111111111111111111111111111111111111111111111111111)
    mac_id_priv = priv_from_scalar(0x2222222222222222222222222222222222222222222222222222222222222222)
    phone_eph = priv_from_scalar(0x3333333333333333333333333333333333333333333333333333333333333333)
    mac_eph = priv_from_scalar(0x4444444444444444444444444444444444444444444444444444444444444444)
    np_, nm = bytes(range(16)), bytes(range(16, 32))
    rp, rm = bytes(range(32, 48)), bytes(range(48, 64))

    p_pub, m_pub = pub_bytes(phone_id_priv), pub_bytes(mac_id_priv)
    pe_pub, me_pub = pub_bytes(phone_eph), pub_bytes(mac_eph)
    pid = short_id(p_pub)
    t = transcript(pid, pe_pub, rp, me_pub, rm)
    k_p2m, k_m2p = session_keys(phone_eph, me_pub, t)

    p2m = Channel(k_p2m)
    sealed0 = p2m.seal(ROUND_START, struct.pack(">I", 7))
    sealed1 = p2m.seal(FILE_END, sha256(b"hello"))

    h = lambda b: b.hex()
    return {
        "note": "All hex. Scalars are private keys; *_pub are uncompressed SEC1. ECDSA is randomized, so sig_* are for verify-only checks.",
        "phone_identity_scalar": "11" * 32,
        "mac_identity_scalar": "22" * 32,
        "phone_eph_scalar": "33" * 32,
        "mac_eph_scalar": "44" * 32,
        "phone_identity_pub": h(p_pub),
        "mac_identity_pub": h(m_pub),
        "phone_id": h(pid),
        "mac_id": h(short_id(m_pub)),
        "Np": h(np_), "Nm": h(nm), "Rp": h(rp), "Rm": h(rm),
        "pair_commit": h(pair_commit(p_pub, np_)),
        "pair_shared_secret": h(ecdh(phone_id_priv, m_pub)),
        "pair_code": pair_code(phone_id_priv, m_pub, np_, nm),
        "phone_eph_pub": h(pe_pub),
        "mac_eph_pub": h(me_pub),
        "transcript": h(t),
        "k_p2m": h(k_p2m),
        "k_m2p": h(k_m2p),
        "sig_mac": h(sign(mac_id_priv, t, b"mac")),
        "sig_phone": h(sign(phone_id_priv, t, b"phone")),
        "sealed_frame_0_ROUND_START_7": h(sealed0),
        "sealed_frame_1_FILE_END_sha256_hello": h(sealed1),
    }


# ---- Self-test: full phone <-> Mac run in memory ---------------------------

def selftest() -> None:
    phone_id = ec.generate_private_key(ec.SECP256R1())
    mac_id = ec.generate_private_key(ec.SECP256R1())

    # Pairing
    np_, nm = os.urandom(16), os.urandom(16)
    commit = pair_commit(pub_bytes(phone_id), np_)
    assert sha256(pub_bytes(phone_id), np_) == commit, "commit mismatch"
    code_phone = pair_code(phone_id, pub_bytes(mac_id), np_, nm)
    code_mac = pair_code(mac_id, pub_bytes(phone_id), np_, nm)
    assert code_phone == code_mac and len(code_phone) == 6
    # A man in the middle with his own key gets a different code (with overwhelming odds)
    mitm = ec.generate_private_key(ec.SECP256R1())
    assert pair_code(phone_id, pub_bytes(mitm), np_, nm) != code_mac

    # Handshake
    pe, me = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
    rp, rm = os.urandom(16), os.urandom(16)
    pid = short_id(pub_bytes(phone_id))
    t = transcript(pid, pub_bytes(pe), rp, pub_bytes(me), rm)
    sig_m, sig_p = sign(mac_id, t, b"mac"), sign(phone_id, t, b"phone")
    assert verify(pub_bytes(mac_id), sig_m, t, b"mac")
    assert verify(pub_bytes(phone_id), sig_p, t, b"phone")
    assert not verify(pub_bytes(phone_id), sig_m, t, b"phone"), "role binding broken"
    phone_keys = session_keys(pe, pub_bytes(me), t)
    mac_keys = session_keys(me, pub_bytes(pe), t)
    assert phone_keys == mac_keys

    # Encrypted round + file
    phone_tx, mac_rx = Channel(phone_keys[0]), Channel(mac_keys[0])
    data = os.urandom(600_000)
    msgs = [phone_tx.seal(ROUND_START, struct.pack(">I", 1)),
            phone_tx.seal(FILE_HEADER, json.dumps({"name": "a.pdf", "size": len(data)}).encode())]
    for i in range(0, len(data), 256 * 1024):
        msgs.append(phone_tx.seal(FILE_CHUNK, data[i:i + 256 * 1024]))
    msgs.append(phone_tx.seal(FILE_END, sha256(data)))
    got = b""
    for m in msgs:
        t_, body, rest = mac_rx.open(m)
        assert rest == b""
        if t_ == FILE_CHUNK:
            got += body
        if t_ == FILE_END:
            assert body == sha256(got)
    assert got == data

    # Replay and reorder must fail
    replay_rx = Channel(mac_keys[0])
    replay_rx.open(msgs[0])
    for bad in (msgs[0], msgs[2]):
        try:
            replay_rx.open(bad)
            raise AssertionError("replayed/reordered frame was accepted")
        except AssertionError:
            raise
        except Exception:
            pass

    print("selftest OK: pairing code", code_phone, "| handshake | 600 KB file in",
          len(msgs) - 3, "chunks | replay and reorder rejected")


if __name__ == "__main__":
    if "--vectors" in sys.argv:
        print(json.dumps(vectors(), indent=2))
    else:
        selftest()
