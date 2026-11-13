// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Knock",
    platforms: [.macOS(.v13)],
    targets: [
        // Reads the notch ambient light sensor (private IOKit HID API).
        .target(
            name: "CALS",
            linkerSettings: [.linkedFramework("IOKit"), .linkedFramework("CoreFoundation")]
        ),
        // Protocol, crypto, tap round and file receiving. No UI.
        .target(name: "KnockCore", dependencies: ["CALS"]),
        // The menu-bar app.
        .executableTarget(name: "Knock", dependencies: ["KnockCore"]),
        // Checks KnockCore against protocol/reference/vectors.json.
        .executableTarget(name: "KnockVectors", dependencies: ["KnockCore"]),
    ]
)
