# Knock protocol v1

How an Android phone and a Mac find each other, pair once, prove a tap, and move a file. Both apps implement this document; when code and this file disagree, this file wins and the code is the bug.

All integers are big-endian. `||` is concatenation. Public keys are P-256, uncompressed SEC1 (65 bytes, `0x04 || X || Y`).

## 1. Roles and discovery

- The **Mac** is the server. It listens on a random TCP port and advertises Bonjour service `_knock._tcp` with TXT records:
  - `v=1`
  - `id=` first 8 bytes of `SHA-256(mac_identity_pub)`, hex. Lets a paired phone pick its Mac out of several.
  - `name=` the Mac's computer name, for the pairing screen only.
- The **phone** browses with Android NSD and connects over TCP.
- Same Wi-Fi only. Nothing is ever sent to a server.

## 2. Framing

Every message on the socket is one frame:

```
length (u32, bytes that follow) || type (u8) || body
```

Frames above 1 MiB are rejected and the connection is closed. Before a session is established (sections 3 and 4) bodies are plaintext. After that, every frame is type `0x20 SEALED` and its body is an AES-GCM ciphertext (section 5).

## 3. Pairing (once per phone and Mac)

Each device has a long-term **identity key pair** (P-256), created on first launch and kept in the Keychain (Mac) or Android Keystore (phone). Pairing exchanges identity public keys and lets the person confirm there is no one in the middle by comparing a 6-digit code. It follows the commit-then-reveal shape of Bluetooth numeric comparison, so an attacker cannot grind keys until the codes collide.

| # | Dir | Type | Body |
|---|-----|------|------|
| 1 | phone → Mac | `0x01 PAIR_COMMIT` | `SHA-256(phone_pub || Np)` (32 B) |
| 2 | Mac → phone | `0x02 PAIR_MAC` | `mac_pub (65) || Nm (16) || name (UTF-8)` |
| 3 | phone → Mac | `0x03 PAIR_PHONE` | `phone_pub (65) || Np (16) || name (UTF-8)` |

`Np`, `Nm` are fresh 16-byte random nonces. The Mac checks the commitment from step 1 against step 3 and closes the connection if it fails.

Both sides then compute:

```
Z     = ECDH(own_identity_priv, peer_identity_pub)
okm   = HKDF-SHA256(ikm = Z, salt = Np || Nm, info = "knock v1 pair code", L = 4)
code  = (u32(okm) mod 1_000_000), shown as 6 digits with leading zeros
```

Both screens show the code. The person taps **Codes match** on both. Each side sends `0x04 PAIR_OK` (empty body) only after its own person confirmed, and stores the peer's identity public key and name only after it has both sent and received `PAIR_OK`. Anything else, including a timeout of 60 s, discards the attempt.

## 4. Session handshake (every connection after pairing)

Fresh ephemeral keys every connection, authenticated by the stored identity keys, so a stolen session key never decrypts another session.

| # | Dir | Type | Body |
|---|-----|------|------|
| 1 | phone → Mac | `0x10 HELLO` | `phone_id (8) || eph_phone_pub (65) || Rp (16)` |
| 2 | Mac → phone | `0x11 HELLO_MAC` | `eph_mac_pub (65) || Rm (16) || sig_mac` |
| 3 | phone → Mac | `0x12 HELLO_FIN` | `sig_phone` |

- `phone_id` is the first 8 bytes of `SHA-256(phone_identity_pub)`. The Mac drops the connection if it is not a paired phone.
- `T = SHA-256("knock v1 hs" || phone_id || eph_phone_pub || Rp || eph_mac_pub || Rm)`
- `sig_mac = ECDSA-P256-SHA256(mac_identity_priv, T || "mac")`, `sig_phone = ECDSA-P256-SHA256(phone_identity_priv, T || "phone")`, both DER-encoded. Each side verifies the other's signature with the stored identity key and closes the connection on failure.

Keys:

```
Ze      = ECDH(own_eph_priv, peer_eph_pub)
k_p2m   = HKDF-SHA256(Ze, salt = T, info = "knock v1 phone->mac", L = 32)
k_m2p   = HKDF-SHA256(Ze, salt = T, info = "knock v1 mac->phone", L = 32)
```

Ephemeral private keys are deleted right after this step.

## 5. Encrypted frames

```
body of SEALED = ciphertext || tag (16)
plaintext      = inner_type (u8) || inner_body
key            = k_p2m or k_m2p for the sending direction
nonce (12 B)   = 0x00000000 || counter (u64)
AAD            = 0x20 || u32(length)
```

Each direction keeps its own counter, starting at 0 and increasing by 1 per frame. The receiver expects exactly the next counter value; a replayed, dropped or reordered frame fails decryption and closes the connection. A session never sends 2^32 frames; reconnect instead.

## 6. Tap round

The Mac runs the round because only the Mac can see the sensor. The phone only changes its screen when told to.

| # | Dir | Inner type | Body |
|---|-----|------------|------|
| 1 | phone → Mac | `0x30 ROUND_START` | `round_id (u32)` |
| 2 | Mac → phone | `0x31 SHOW` | `round_id || color (u8: 0 black, 1 white)` |
| 3 | phone → Mac | `0x32 SHOWN` | `round_id || color` |
| 4 | Mac → phone | `0x33 ROUND_RESULT` | `round_id || ok (u8) || reason (u8)` |

The phone sends `ROUND_START` when its proximity sensor reports near, or when the person taps the in-app Knock button while holding the phone on the notch. Then:

1. **Covered:** the Mac requires at least 2 consecutive sensor readings (≥ 400 ms) at or below `DARK_MAX` before it sends `SHOW white`. A phone held away cannot pass this step, because room light reaches the sensor around it.
2. **White:** after `SHOWN white`, the Mac requires at least one reading at or above `WHITE_MIN` within 800 ms.
3. **Dark again:** it then sends `SHOW black` and requires a reading at or below `DARK_MAX` within 800 ms.
4. Pass → `ROUND_RESULT ok=1`. The phone buzzes and starts the transfer (section 7).

Starting values from the spike measurements (MacBook Pro, Galaxy S23): `DARK_MAX = 10`, `WHITE_MIN = max(120, 0.55 × median of the last 12 passing white peaks)`. Measured: covered 0 to 8, room 61, white pressed 605 to 661 at full brightness.

`reason` codes: `0` none, `1` notch never went dark (not pressed on), `2` white not seen, `3` did not go dark again, `4` timeout. Both apps show the same plain sentence for each code.

## 7. File transfer

Only after `ROUND_RESULT ok=1` for the same session.

| Dir | Inner type | Body |
|-----|------------|------|
| phone → Mac | `0x40 FILE_HEADER` | UTF-8 JSON, see below |
| phone → Mac | `0x41 FILE_CHUNK` | up to 256 KiB of file bytes |
| phone → Mac | `0x42 FILE_END` | `SHA-256(file) (32)` |
| Mac → phone | `0x43 FILE_SAVED` | `ok (u8) || saved name (UTF-8)` |

```json
{ "name": "Resume (1).pdf", "size": 49152, "mime": "application/pdf",
  "kind": "original", "from": "Download/" }
```

`kind` is `original` when the phone found the file the viewer had open, or `screenshot` when it did not. `from` is the folder the original came from, shown on the Mac so the person can spot a wrong match; it is empty for screenshots.

The Mac writes to `~/Knock data/`, creating it if needed. It strips any path components from `name`, appends ` (2)`, ` (3)` … on a name clash, writes to a temporary file, checks size and SHA-256, then renames it into place. On any mismatch it deletes the temporary file and sends `FILE_SAVED ok=0`.

## 8. What the phone may read

The phone grabs the screen (screenshot plus on-screen text) once, right before it sends `ROUND_START`, because during the round its own white/black overlay covers the app. It keeps both in memory only. If the round fails, it throws them away. It uses the on-screen text only to look for a file name, and never stores, logs or sends that text. Apps that block screenshots are skipped, and the phone keeps its own skip list (payment and banking apps) where it sends nothing at all.

## 9. Constants

| Name | Value |
|------|-------|
| Bonjour type | `_knock._tcp` |
| Max frame | 1 MiB |
| Max chunk | 256 KiB |
| Pairing timeout | 60 s |
| Round timeout | 3 s from `ROUND_START` |
| Sensor poll | 50 Hz (sensor updates at about 5 Hz) |
