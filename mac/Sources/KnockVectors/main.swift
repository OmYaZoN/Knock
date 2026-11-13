import CryptoKit
import Foundation
import KnockCore

// Checks KnockCore against protocol/reference/vectors.json.
// Run from the mac/ folder:  swift run KnockVectors ../protocol/reference/vectors.json

let path = CommandLine.arguments.dropFirst().first ?? "../protocol/reference/vectors.json"
guard let raw = FileManager.default.contents(atPath: path),
      let v = try? JSONSerialization.jsonObject(with: raw) as? [String: String] else {
    print("Could not read \(path)"); exit(2)
}
func hx(_ k: String) -> Data { Data(hex: v[k]!)! }
func key(_ k: String) -> P256.KeyAgreement.PrivateKey { try! .init(rawRepresentation: hx(k)) }

var failures = 0
func check(_ name: String, _ got: Data, _ want: Data) {
    let ok = got == want
    if !ok { failures += 1 }
    print(ok ? "PASS" : "FAIL", name, ok ? "" : "\n  got  \(got.hex)\n  want \(want.hex)")
}
func check(_ name: String, _ ok: Bool) {
    if !ok { failures += 1 }
    print(ok ? "PASS" : "FAIL", name)
}

let phoneID = key("phone_identity_scalar"), macID = key("mac_identity_scalar")
let phoneEph = key("phone_eph_scalar"), macEph = key("mac_eph_scalar")

check("phone_identity_pub", phoneID.publicKey.x963Representation, hx("phone_identity_pub"))
check("mac_identity_pub", macID.publicKey.x963Representation, hx("mac_identity_pub"))
check("phone_id", KnockCrypto.shortID(hx("phone_identity_pub")), hx("phone_id"))
check("mac_id", KnockCrypto.shortID(hx("mac_identity_pub")), hx("mac_id"))
check("pair_commit", KnockCrypto.pairCommit(phonePub: hx("phone_identity_pub"), np: hx("Np")), hx("pair_commit"))
check("pair_shared_secret", try! KnockCrypto.ecdh(phoneID, hx("mac_identity_pub")), hx("pair_shared_secret"))
let code = try! KnockCrypto.pairCode(own: macID, peerPub: hx("phone_identity_pub"), np: hx("Np"), nm: hx("Nm"))
check("pair_code \(code)", code == v["pair_code"])

let t = KnockCrypto.transcript(phoneID: hx("phone_id"), ephPhonePub: hx("phone_eph_pub"), rp: hx("Rp"),
                               ephMacPub: hx("mac_eph_pub"), rm: hx("Rm"))
check("phone_eph_pub", phoneEph.publicKey.x963Representation, hx("phone_eph_pub"))
check("mac_eph_pub", macEph.publicKey.x963Representation, hx("mac_eph_pub"))
check("transcript", t, hx("transcript"))
let (p2m, m2p) = try! KnockCrypto.sessionKeys(ownEph: macEph, peerEphPub: hx("phone_eph_pub"), t: t)
check("k_p2m", p2m, hx("k_p2m"))
check("k_m2p", m2p, hx("k_m2p"))

check("verify sig_mac", KnockCrypto.verify(identityPub: hx("mac_identity_pub"), sig: hx("sig_mac"), t, role: "mac"))
check("verify sig_phone", KnockCrypto.verify(identityPub: hx("phone_identity_pub"), sig: hx("sig_phone"), t, role: "phone"))
check("sig_mac rejected as phone", !KnockCrypto.verify(identityPub: hx("mac_identity_pub"), sig: hx("sig_mac"), t, role: "phone"))

let ch = Channel(key: p2m)
var roundID = Data(count: 4); roundID[3] = 7
check("sealed frame 0", try! ch.seal(Frame.roundStart, roundID), hx("sealed_frame_0_ROUND_START_7"))
check("sealed frame 1", try! ch.seal(Frame.fileEnd, KnockCrypto.sha256(Data("hello".utf8))), hx("sealed_frame_1_FILE_END_sha256_hello"))

let rx = Channel(key: p2m)
let f0 = hx("sealed_frame_0_ROUND_START_7")
let (t0, b0) = try! rx.open(f0.dropFirst(5))
check("open frame 0", t0 == Frame.roundStart && b0 == roundID)
check("replay rejected", (try? rx.open(f0.dropFirst(5))) == nil)

print(failures == 0 ? "\nAll vectors pass." : "\n\(failures) failure(s).")
exit(failures == 0 ? 0 : 1)
