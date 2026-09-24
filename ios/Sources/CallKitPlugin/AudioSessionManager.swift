//
// Ported from livekit/react-native-callkeep (ios/RNCallKeep/RNCallKeep.m).
// Copyright 2016-2019 The CallKeep Authors (see the AUTHORS file)
// SPDX-License-Identifier: ISC, MIT
//

import AVFoundation
import Foundation

/// Audio session handling, ported from callkeep's RNCallKeep.m (`configureAudioSession`,
/// `getAudioInputs`, `formatAudioInputs`, `setAudioRoute`, `onAudioRouteChange`).
final class AudioSessionManager {
    static let shared = AudioSessionManager()

    /// (output port type, is speaker)
    var onRouteChange: ((String, Bool) -> Void)?

    private var routeObserver: NSObjectProtocol?

    private init() {
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self = self, let output = self.currentOutput() else {
                return
            }
            self.onRouteChange?(output, output == AVAudioSession.Port.builtInSpeaker.rawValue)
        }
    }

    /// callkeep's configureAudioSession. Settings (`ios.audioSession`): `autoConfigure`
    /// (default true), `categoryOptions` (raw AVAudioSession.CategoryOptions), `mode`.
    func configure(settings: [String: Any]) {
        let audioSettings = settings["audioSession"] as? [String: Any] ?? [:]
        if let autoConfigure = audioSettings["autoConfigure"] as? Bool, !autoConfigure {
            return
        }

        var options: AVAudioSession.CategoryOptions = [.allowBluetooth, .allowBluetoothA2DP]
        if let raw = audioSettings["categoryOptions"] as? NSNumber {
            options = AVAudioSession.CategoryOptions(rawValue: raw.uintValue)
        }
        var mode = AVAudioSession.Mode.default
        if let rawMode = audioSettings["mode"] as? String {
            mode = AVAudioSession.Mode(rawValue: rawMode)
        }

        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: mode, options: options)
        try? session.setPreferredSampleRate(44100.0)
        try? session.setPreferredIOBufferDuration(0.005)
        try? session.setActive(true)
    }

    /// CallKit activated the session (callkeep's didActivateAudioSession). Media stacks such as
    /// WebRTC pause on interruption; tell them it ended so they resume.
    func didActivate(settings: [String: Any]) {
        NotificationCenter.default.post(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: [
                AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.ended.rawValue,
                AVAudioSessionInterruptionOptionKey: AVAudioSession.InterruptionOptions.shouldResume.rawValue
            ]
        )
        configure(settings: settings)
    }

    func currentOutput() -> String? {
        return AVAudioSession.sharedInstance().currentRoute.outputs.first?.portType.rawValue
    }

    func isSpeakerOn() -> Bool {
        return currentOutput() == AVAudioSession.Port.builtInSpeaker.rawValue
    }

    func setSpeaker(_ on: Bool) throws {
        try AVAudioSession.sharedInstance().overrideOutputAudioPort(on ? .speaker : .none)
    }

    /// callkeep's getAudioRoutes / formatAudioInputs, with the plugin's route types.
    func audioRoutes() throws -> [[String: Any]] {
        let selected = selectedRouteType()
        var routes: [[String: Any]] = []
        var speaker: [String: Any] = ["name": "Speaker", "type": "Speaker"]
        if selected == "Speaker" {
            speaker["selected"] = true
        }
        routes.append(speaker)

        for input in try availableInputs() {
            guard let type = routeType(input.portType) else {
                continue
            }
            var route: [String: Any] = ["name": input.portName, "type": type]
            if selected == type {
                route["selected"] = true
            }
            routes.append(route)
        }
        return routes
    }

    /// Route by type (`Speaker`, `Phone`, `Headset`, `Bluetooth`). callkeep matches on port
    /// name; the plugin API uses types, so the first input of that type is chosen.
    func setRoute(_ type: String) throws {
        let session = AVAudioSession.sharedInstance()
        if type == "Speaker" {
            try session.overrideOutputAudioPort(.speaker)
            return
        }
        try session.overrideOutputAudioPort(.none)
        guard let input = try availableInputs().first(where: { routeType($0.portType) == type }) else {
            throw CallKitError("No audio route of type \(type)")
        }
        try session.setPreferredInput(input)
    }

    private func availableInputs() throws -> [AVAudioSessionPortDescription] {
        let session = AVAudioSession.sharedInstance()
        if session.category != .playAndRecord {
            try session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth])
        }
        try session.setActive(true)
        return session.availableInputs ?? []
    }

    private func selectedRouteType() -> String? {
        guard let output = AVAudioSession.sharedInstance().currentRoute.outputs.first else {
            return nil
        }
        if output.portType == .builtInReceiver {
            return "Phone"
        }
        return routeType(output.portType)
    }

    private func routeType(_ port: AVAudioSession.Port) -> String? {
        switch port {
        case .builtInMic, .builtInReceiver:
            return "Phone"
        case .headsetMic, .headphones:
            return "Headset"
        case .bluetoothHFP, .bluetoothA2DP:
            return "Bluetooth"
        case .builtInSpeaker:
            return "Speaker"
        case .carAudio:
            return "CarAudio"
        default:
            return nil
        }
    }
}
