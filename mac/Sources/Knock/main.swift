import AppKit
import KnockCore

/// Menu-bar app: shows status, confirms pairing codes, and tells you when a file lands.
final class AppDelegate: NSObject, NSApplicationDelegate, KnockServerDelegate {
    private var item: NSStatusItem!
    private var server: KnockServer!
    private let statusLine = NSMenuItem(title: "Starting…", action: nil, keyEquivalent: "")
    private let lightLine = NSMenuItem(title: "Notch light: –", action: nil, keyEquivalent: "")
    private let lastLine = NSMenuItem(title: "Nothing received yet", action: nil, keyEquivalent: "")
    private var lastFile: URL?
    private var lightTimer: Timer?

    func applicationDidFinishLaunching(_ note: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        setIcon("hand.tap")

        let menu = NSMenu()
        menu.delegate = self
        [statusLine, lightLine, lastLine].forEach { $0.isEnabled = false; menu.addItem($0) }
        menu.addItem(.separator())
        menu.addItem(withTitle: "Reveal last file", action: #selector(revealLast), keyEquivalent: "r").target = self
        menu.addItem(withTitle: "Open Knock data", action: #selector(openFolder), keyEquivalent: "o").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "Forget paired phones", action: #selector(forget), keyEquivalent: "").target = self
        menu.addItem(withTitle: "Quit Knock", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        item.menu = menu

        if !LightSensor.shared.available {
            statusLine.title = "No light sensor found on this Mac"
        }
        do {
            server = KnockServer(identity: try IdentityStore())
            server.delegate = self
            try server.start()
        } catch {
            statusLine.title = "Could not start: \(error)"
        }
        refreshStatus()
    }

    private func refreshStatus() {
        guard let server else { return }
        let n = server.identity.peers.count
        statusLine.title = n == 0 ? "Not paired. Open Knock on your phone to pair."
                                  : "Ready · \(n) phone\(n == 1 ? "" : "s") paired"
    }

    private func setIcon(_ symbol: String) {
        item.button?.image = NSImage(systemSymbolName: symbol, accessibilityDescription: "Knock")
    }

    private func flash(_ symbol: String) {
        setIcon(symbol)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.setIcon("hand.tap") }
    }

    // MARK: KnockServerDelegate

    func confirmPairing(code: String, phoneName: String, answer: @escaping (Bool) -> Void) {
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = "Pair with \(phoneName)?"
        a.informativeText = "Check that your phone shows the same code:\n\n\(code.prefix(3)) \(code.suffix(3))"
        a.addButton(withTitle: "Codes match")
        a.addButton(withTitle: "Cancel")
        answer(a.runModal() == .alertFirstButtonReturn)
    }

    func paired(phoneName: String) {
        refreshStatus()
        flash("checkmark.circle")
        log("Paired with \(phoneName)")
    }

    func roundFinished(ok: Bool, reason: TapRound.Reason) {
        if !ok { lastLine.title = reason.sentence; flash("exclamationmark.circle") }
    }

    func received(file: URL, kind: String, from: String) {
        lastFile = file
        let source = kind == "screenshot" ? "screenshot" : (from.isEmpty ? "original" : "from \(from)")
        lastLine.title = "\(file.lastPathComponent) (\(source))"
        flash("checkmark.circle.fill")
        NSSound(named: "Glass")?.play()
    }

    func log(_ line: String) { print("[Knock] \(line)") }

    // MARK: Menu actions

    @objc private func revealLast() {
        if let f = lastFile { NSWorkspace.shared.activateFileViewerSelecting([f]) } else { openFolder() }
    }

    @objc private func openFolder() {
        try? FileManager.default.createDirectory(at: FileReceiver.folder, withIntermediateDirectories: true)
        NSWorkspace.shared.open(FileReceiver.folder)
    }

    @objc private func forget() {
        try? server?.identity.removeAll()
        refreshStatus()
    }
}

extension AppDelegate: NSMenuDelegate {
    /// While the menu is open, show the live sensor value (handy for checking the notch).
    func menuWillOpen(_ menu: NSMenu) {
        lightTimer = Timer(timeInterval: 0.2, repeats: true) { [weak self] _ in
            let v = LightSensor.shared.read()
            self?.lightLine.title = "Notch light: " + (v.map { String(format: "%.0f", $0) } ?? "–")
        }
        RunLoop.main.add(lightTimer!, forMode: .common)
    }

    func menuDidClose(_ menu: NSMenu) { lightTimer?.invalidate(); lightTimer = nil }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
