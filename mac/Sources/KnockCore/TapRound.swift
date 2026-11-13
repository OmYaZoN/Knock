import CALS
import Foundation

/// The notch light sensor.
public final class LightSensor {
    public static let shared = LightSensor()
    public let available: Bool

    private init() { available = als_open() == 1 }

    public func read() -> Double? {
        var ok: Int32 = 0
        let v = als_read(&ok)
        return ok == 1 ? v : nil
    }
}

/// Tap round (protocol/PROTOCOL.md §6). The Mac decides; the phone only changes colour when told.
public final class TapRound {
    public enum Reason: UInt8 {
        case none = 0, neverDark = 1, whiteNotSeen = 2, notDarkAgain = 3, timeout = 4

        public var sentence: String {
            switch self {
            case .none: return "Sent."
            case .neverDark: return "Press the top of your phone's screen flat on the notch."
            case .whiteNotSeen: return "Your Mac didn't see your screen. Turn it around and press it on the notch."
            case .notDarkAgain: return "Keep your phone on the notch until it buzzes."
            case .timeout: return "That tap didn't count. Try again."
            }
        }
    }

    public static let darkMax = 10.0
    static let coveredFor = 0.4, phaseLimit = 0.8, roundLimit = 3.0

    private enum Phase { case waitingDark, awaitingShownWhite, white, awaitingShownBlack, darkAgain, done }

    public let id: UInt32
    private let sensor: LightSensor
    private let show: (UInt8) -> Void                  // 0 black, 1 white
    private let finish: (Bool, Reason) -> Void
    private var phase = Phase.waitingDark
    private var started = Date(), phaseStarted = Date(), darkSince: Date?
    private var peak = 0.0
    private var timer: Timer?

    public init(id: UInt32, sensor: LightSensor = .shared,
                show: @escaping (UInt8) -> Void, finish: @escaping (Bool, Reason) -> Void) {
        self.id = id; self.sensor = sensor; self.show = show; self.finish = finish
    }

    public func start() {
        started = Date()
        timer = Timer.scheduledTimer(withTimeInterval: 0.02, repeats: true) { [weak self] _ in self?.tick() }
    }

    public func cancel() { timer?.invalidate(); timer = nil; phase = .done }

    /// The phone confirms it changed colour.
    public func shown(_ color: UInt8) {
        if phase == .awaitingShownWhite, color == 1 { phase = .white; phaseStarted = Date(); peak = 0 }
        else if phase == .awaitingShownBlack, color == 0 { phase = .darkAgain; phaseStarted = Date() }
    }

    private func end(_ ok: Bool, _ reason: Reason) {
        guard phase != .done else { return }
        cancel()
        if ok { WhiteLevel.record(peak) }
        finish(ok, reason)
    }

    private func tick() {
        let now = Date()
        if now.timeIntervalSince(started) > Self.roundLimit {
            return end(false, phase == .waitingDark ? .neverDark : .timeout)
        }
        guard let v = sensor.read() else { return }
        switch phase {
        case .waitingDark:
            if v <= Self.darkMax {
                if darkSince == nil { darkSince = now }
                if now.timeIntervalSince(darkSince!) >= Self.coveredFor { phase = .awaitingShownWhite; show(1) }
            } else {
                darkSince = nil
            }
        case .white:
            peak = max(peak, v)
            if v >= WhiteLevel.minimum { phase = .awaitingShownBlack; show(0) }
            else if now.timeIntervalSince(phaseStarted) > Self.phaseLimit { end(false, .whiteNotSeen) }
        case .darkAgain:
            if v <= Self.darkMax { end(true, .none) }
            else if now.timeIntervalSince(phaseStarted) > Self.phaseLimit { end(false, .notDarkAgain) }
        case .awaitingShownWhite, .awaitingShownBlack, .done:
            break
        }
    }
}

/// Learns how bright this phone looks on this notch: 55% of the median of the last 12 passing taps.
public enum WhiteLevel {
    static let key = "whitePeaks", floor = 120.0

    public static var minimum: Double {
        let peaks = (UserDefaults.standard.array(forKey: key) as? [Double]) ?? []
        guard peaks.count >= 3 else { return floor }
        let median = peaks.sorted()[peaks.count / 2]
        return max(floor, 0.55 * median)
    }

    static func record(_ peak: Double) {
        guard peak > 0 else { return }
        var peaks = (UserDefaults.standard.array(forKey: key) as? [Double]) ?? []
        peaks.append(peak)
        UserDefaults.standard.set(Array(peaks.suffix(12)), forKey: key)
    }
}
