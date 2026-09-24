//
// Ported from livekit/react-native-callkeep (ios/RNCallKeep/RNCallKeep.m).
// Copyright 2016-2019 The CallKeep Authors (see the AUTHORS file)
// SPDX-License-Identifier: ISC, MIT
//

import AVFoundation
import CallKit
import Foundation
import UIKit

struct CallKitError: LocalizedError {
    let message: String

    init(_ message: String) {
        self.message = message
    }

    var errorDescription: String? {
        return message
    }
}

/// CallKit provider + controller, ported from callkeep's RNCallKeep.m.
///
/// Holds no reference to the plugin, so a VoIP push can report a call while the WebView is not
/// loaded. Events go through CallEventBus. All state is touched on the main queue.
///
/// Deviations from callkeep:
/// - Call ids are app strings; CallKit needs UUIDs, so ids that aren't UUIDs are mapped.
/// - Ending a ringing incoming call from the system UI emits `callRejected`, not `callEnded`.
/// - Pushes that can't show a call (not a call payload, `call_ended`, busy) still report and
///   immediately end one, because iOS 13+ terminates apps that don't report a call for a VoIP push.
final class CallKitManager: NSObject, CXProviderDelegate {
    static let shared = CallKitManager()

    private static let settingsKey = "IonicCallkitSettings"

    struct Call {
        let callId: String
        let uuid: UUID
        var callerName: String
        var handle: String
        let hasVideo: Bool
        let outgoing: Bool
        var answered: Bool
        var state: String
    }

    private let provider: CXProvider
    private let controller = CXCallController()
    private var calls: [UUID: Call] = [:]
    private let events: CallEventBus
    private let recentlyEnded = RecentCallIds()

    private override init() {
        events = CallEventBus.shared
        events.setStore(UserDefaultsEventStore())
        provider = CXProvider(configuration: CallKitManager.configuration(from: CallKitManager.settings))
        super.init()
        provider.setDelegate(self, queue: nil)

        AudioSessionManager.shared.onRouteChange = { [weak self] output, speaker in
            guard let self = self else {
                return
            }
            let callId = self.calls.values.first?.callId
            var route: [String: Any] = ["output": output]
            var speakerEvent: [String: Any] = ["on": speaker]
            if let callId = callId {
                route["callId"] = callId
                speakerEvent["callId"] = callId
            }
            self.events.emit("audioRouteChanged", route)
            self.events.emit("speakerChanged", speakerEvent)
        }
        PushKitManager.shared.onToken = { [weak self] token in
            self?.events.emit("voipToken", ["token": token])
        }
    }

    // MARK: - Settings

    static var settings: [String: Any] {
        return UserDefaults.standard.dictionary(forKey: settingsKey) ?? [:]
    }

    func setup(_ settings: [String: Any]) {
        UserDefaults.standard.set(settings, forKey: CallKitManager.settingsKey)
        provider.configuration = CallKitManager.configuration(from: settings)
    }

    func setCanMakeMultipleCalls(_ allow: Bool) {
        var settings = CallKitManager.settings
        settings["canMakeMultipleCalls"] = allow
        UserDefaults.standard.set(settings, forKey: CallKitManager.settingsKey)
    }

    /// callkeep's getProviderConfiguration. The provider name is the app's display name
    /// (`CXProviderConfiguration(localizedName:)` is deprecated since iOS 14).
    static func configuration(from settings: [String: Any]) -> CXProviderConfiguration {
        let config = CXProviderConfiguration()
        config.supportsVideo = settings["supportsVideo"] as? Bool ?? false
        config.maximumCallGroups = settings["maximumCallGroups"] as? Int ?? 1
        config.maximumCallsPerCallGroup = settings["maximumCallsPerCallGroup"] as? Int ?? 1
        config.includesCallsInRecents = settings["includesCallsInRecents"] as? Bool ?? false
        config.supportedHandleTypes = [handleType(settings)]
        if let imageName = settings["imageName"] as? String, let image = UIImage(named: imageName) {
            config.iconTemplateImageData = image.pngData()
        }
        if let ringtone = settings["ringtoneSound"] as? String {
            config.ringtoneSound = ringtone
        }
        return config
    }

    private static func handleType(_ settings: [String: Any]) -> CXHandle.HandleType {
        switch settings["handleType"] as? String {
        case "number", "phone":
            return .phoneNumber
        case "email":
            return .emailAddress
        default:
            return .generic
        }
    }

    private var isBusy: Bool {
        let allowMultiple = CallKitManager.settings["canMakeMultipleCalls"] as? Bool ?? true
        return !allowMultiple && !calls.isEmpty
    }

    // MARK: - Incoming

    /// Called from PushKit. `completion` must run after the call is reported.
    func handlePush(_ payload: [AnyHashable: Any], completion: @escaping () -> Void) {
        guard let push = CallPush.parse(payload) else {
            // Not a call payload. iOS 13+ kills apps that don't report a call for a VoIP push.
            reportAndEnd(callId: UUID().uuidString, callerName: "Unknown", reason: .failed, completion: completion)
            return
        }

        if push.type == CallPush.typeCallEnded {
            if let call = call(for: push.callId) {
                // Every VoIP push must report a call; re-reporting the existing UUID satisfies that
                // (CallKit answers callUUIDAlreadyExists) without showing anything new.
                provider.reportNewIncomingCall(with: call.uuid, update: CXCallUpdate()) { [weak self] _ in
                    DispatchQueue.main.async {
                        self?.reportRemoteEnded(call)
                        completion()
                    }
                }
            } else {
                reportAndEnd(callId: push.callId, callerName: push.callerName, reason: .unanswered, completion: completion)
            }
            return
        }

        if let existing = call(for: push.callId) {
            // Duplicate push: satisfy the "report a call" rule by re-reporting the same UUID
            // (callUUIDAlreadyExists) and leave the live call alone.
            provider.reportNewIncomingCall(with: existing.uuid, update: CXCallUpdate()) { _ in
                completion()
            }
            return
        }

        if recentlyEnded.recentlyEnded(push.callId) {
            // Late push for a call that already ended (e.g. call_ended arrived first).
            reportAndEnd(callId: push.callId, callerName: push.callerName, reason: .unanswered, completion: completion)
            return
        }

        if isBusy {
            emitBusy(callId: push.callId, callerName: push.callerName, handle: push.handle)
            reportAndEnd(callId: push.callId, callerName: push.callerName, reason: .unanswered, completion: completion)
            return
        }

        reportIncomingCall(
            callId: push.callId,
            callerName: push.callerName,
            handle: push.handle,
            hasVideo: push.hasVideo,
            payload: CallKitManager.jsonSafe(payload)
        ) { _ in
            completion()
        }
    }

    func reportIncomingCall(
        callId: String,
        callerName: String,
        handle: String,
        hasVideo: Bool,
        payload: [String: Any]?,
        completion: @escaping (Error?) -> Void
    ) {
        if call(for: callId) != nil {
            completion(CallKitError("displayIncomingCall ignored: call \(callId) already exists"))
            return
        }
        if recentlyEnded.recentlyEnded(callId) {
            completion(CallKitError("displayIncomingCall ignored: call \(callId) already ended"))
            return
        }
        if isBusy {
            emitBusy(callId: callId, callerName: callerName, handle: handle)
            completion(CallKitError("displayIncomingCall ignored: busy (canMakeMultipleCalls is false and a call is in progress)"))
            return
        }

        let uuid = uuidFor(callId)
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: CallKitManager.handleType(CallKitManager.settings), value: handle)
        update.localizedCallerName = callerName
        update.hasVideo = hasVideo
        update.supportsHolding = true
        update.supportsDTMF = true
        update.supportsGrouping = false
        update.supportsUngrouping = false

        calls[uuid] = Call(
            callId: callId,
            uuid: uuid,
            callerName: callerName,
            handle: handle,
            hasVideo: hasVideo,
            outgoing: false,
            answered: false,
            state: "ringing"
        )

        provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            DispatchQueue.main.async {
                guard let self = self else {
                    return
                }
                if let error = error {
                    self.calls[uuid] = nil
                    self.recentlyEnded.markEnded(callId)
                    self.events.emit("incomingCallFailed", [
                        "callId": callId,
                        "callerName": callerName,
                        "handle": handle,
                        "error": CallKitManager.incomingCallErrorCode(error)
                    ])
                    completion(error)
                    return
                }
                var event: [String: Any] = ["callId": callId, "callerName": callerName, "handle": handle, "hasVideo": hasVideo]
                if let payload = payload {
                    event["payload"] = payload
                }
                self.events.emit("incomingCall", event)
                self.events.emit("callStateChanged", ["callId": callId, "state": "ringing"])
                completion(nil)
            }
        }
    }

    func answer(_ callId: String, completion: @escaping (Error?) -> Void) {
        guard let call = call(for: callId) else {
            completion(noCall("answerCall", callId))
            return
        }
        request(CXAnswerCallAction(call: call.uuid), completion: completion)
    }

    func reject(_ callId: String, completion: @escaping (Error?) -> Void) {
        guard let call = call(for: callId) else {
            completion(noCall("rejectCall", callId))
            return
        }
        request(CXEndCallAction(call: call.uuid), completion: completion)
    }

    // MARK: - Outgoing

    func startCall(callId: String, calleeName: String, handle: String, hasVideo: Bool, completion: @escaping (Error?) -> Void) {
        if isBusy {
            completion(CallKitError("startCall ignored: busy (canMakeMultipleCalls is false and a call is in progress)"))
            return
        }
        let uuid = uuidFor(callId)
        calls[uuid] = Call(
            callId: callId,
            uuid: uuid,
            callerName: calleeName,
            handle: handle,
            hasVideo: hasVideo,
            outgoing: true,
            answered: false,
            state: "dialing"
        )

        let action = CXStartCallAction(call: uuid, handle: CXHandle(type: CallKitManager.handleType(CallKitManager.settings), value: handle))
        action.isVideo = hasVideo
        action.contactIdentifier = calleeName

        request(action) { [weak self] error in
            guard let self = self else {
                return
            }
            if let error = error {
                self.calls[uuid] = nil
                completion(error)
                return
            }
            // callkeep's requestTransaction: show the callee's name in the system UI.
            let update = CXCallUpdate()
            update.remoteHandle = action.handle
            update.localizedCallerName = calleeName
            update.hasVideo = hasVideo
            update.supportsHolding = true
            update.supportsDTMF = true
            self.provider.reportCall(with: uuid, updated: update)
            completion(nil)
        }
    }

    /// The remote side picked up an outgoing call.
    func setCallActive(_ callId: String) throws {
        guard var call = call(for: callId) else {
            throw noCall("setCallActive", callId)
        }
        if call.outgoing {
            provider.reportOutgoingCall(with: call.uuid, connectedAt: nil)
        }
        call.state = "active"
        calls[call.uuid] = call
        events.emit("callStateChanged", ["callId": callId, "state": "active"])
    }

    /// callkeep's setConnectionState / reportConnectingOutgoingCallWithUUID.
    func setCallState(_ callId: String, state: String, completion: @escaping (Error?) -> Void) {
        guard var call = call(for: callId) else {
            completion(noCall("setCallState", callId))
            return
        }
        switch state {
        case "dialing":
            if call.outgoing {
                provider.reportOutgoingCall(with: call.uuid, startedConnectingAt: nil)
            }
        case "active":
            do {
                try setCallActive(callId)
                completion(nil)
            } catch {
                completion(error)
            }
            return
        case "held":
            request(CXSetHeldCallAction(call: call.uuid, onHold: true), completion: completion)
            return
        case "ringing", "initializing":
            break
        default:
            completion(CallKitError("setCallState: unknown state '\(state)'"))
            return
        }
        call.state = state
        calls[call.uuid] = call
        events.emit("callStateChanged", ["callId": callId, "state": state])
        completion(nil)
    }

    // MARK: - Ending

    func endCall(_ callId: String, completion: @escaping (Error?) -> Void) {
        guard let call = call(for: callId) else {
            completion(noCall("endCall", callId))
            return
        }
        request(CXEndCallAction(call: call.uuid), completion: completion)
    }

    func endAllCalls() {
        for call in controller.callObserver.calls {
            request(CXEndCallAction(call: call.uuid)) { _ in }
        }
    }

    /// callkeep's endCallWithUUID:reason. Does not emit `callEnded` (the app initiated it).
    func reportEndCall(_ callId: String, reason: Int) throws {
        guard let call = call(for: callId) else {
            throw noCall("reportEndCall", callId)
        }
        provider.reportCall(with: call.uuid, endedAt: nil, reason: CallKitManager.endedReason(reason))
        calls[call.uuid] = nil
        recentlyEnded.markEnded(callId)
    }

    func updateDisplay(_ callId: String, callerName: String, handle: String) throws {
        guard var call = call(for: callId) else {
            throw noCall("updateDisplay", callId)
        }
        let update = CXCallUpdate()
        update.localizedCallerName = callerName
        update.remoteHandle = CXHandle(type: CallKitManager.handleType(CallKitManager.settings), value: handle)
        provider.reportCall(with: call.uuid, updated: update)
        call.callerName = callerName
        call.handle = handle
        calls[call.uuid] = call
    }

    // MARK: - In-call controls

    func setMuted(_ callId: String, muted: Bool, completion: @escaping (Error?) -> Void) {
        guard let call = call(for: callId) else {
            completion(noCall("setMuted", callId))
            return
        }
        request(CXSetMutedCallAction(call: call.uuid, muted: muted), completion: completion)
    }

    func setOnHold(_ callId: String, hold: Bool, completion: @escaping (Error?) -> Void) {
        guard let call = call(for: callId) else {
            completion(noCall("setOnHold", callId))
            return
        }
        request(CXSetHeldCallAction(call: call.uuid, onHold: hold), completion: completion)
    }

    func sendDTMF(_ callId: String, digits: String, completion: @escaping (Error?) -> Void) {
        guard let call = call(for: callId) else {
            completion(noCall("sendDTMF", callId))
            return
        }
        request(CXPlayDTMFCallAction(call: call.uuid, digits: digits, type: .singleTone), completion: completion)
    }

    func activeCalls() -> [[String: Any]] {
        return calls.values.map { call in
            ["callId": call.callId, "callerName": call.callerName, "handle": call.handle, "state": call.state]
        }
    }

    // MARK: - CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        // Something big changed (callkeep: "the JS should probably hang up all calls").
        let ended = calls.values.map { $0.callId }
        calls.removeAll()
        for callId in ended {
            recentlyEnded.markEnded(callId)
            events.emit("callEnded", ["callId": callId])
        }
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        // Do this first, audio sessions are flakey (callkeep).
        AudioSessionManager.shared.configure(settings: CallKitManager.settings)
        if let call = calls[action.callUUID] {
            events.emit("callStarted", ["callId": call.callId, "calleeName": call.callerName, "handle": call.handle])
            events.emit("callStateChanged", ["callId": call.callId, "state": "dialing"])
        }
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: nil)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        AudioSessionManager.shared.configure(settings: CallKitManager.settings)
        if var call = calls[action.callUUID] {
            call.answered = true
            call.state = "active"
            calls[action.callUUID] = call
            events.emit("callAnswered", ["callId": call.callId, "hasVideo": call.hasVideo])
            events.emit("callStateChanged", ["callId": call.callId, "state": "active"])
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        if let call = calls.removeValue(forKey: action.callUUID) {
            recentlyEnded.markEnded(call.callId)
            // Declining a ringing incoming call is a rejection, as on Android.
            let rejected = !call.outgoing && !call.answered
            events.emit("callStateChanged", ["callId": call.callId, "state": "disconnected"])
            events.emit(rejected ? "callRejected" : "callEnded", ["callId": call.callId])
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        if var call = calls[action.callUUID] {
            call.state = action.isOnHold ? "held" : "active"
            calls[action.callUUID] = call
            events.emit("held", ["callId": call.callId, "hold": action.isOnHold])
            events.emit("callStateChanged", ["callId": call.callId, "state": call.state])
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        if let call = calls[action.callUUID] {
            events.emit("muted", ["callId": call.callId, "muted": action.isMuted])
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        if let call = calls[action.callUUID] {
            events.emit("dtmf", ["callId": call.callId, "digits": action.digits])
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {}

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        AudioSessionManager.shared.didActivate(settings: CallKitManager.settings)
        events.emit("audioSessionActivated", [:])
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {}

    // MARK: - Helpers

    private func call(for callId: String) -> Call? {
        return calls.values.first { $0.callId == callId }
    }

    /// Reuses the id when it is a UUID (so it matches what the server sent), else maps it.
    private func uuidFor(_ callId: String) -> UUID {
        if let existing = call(for: callId) {
            return existing.uuid
        }
        return UUID(uuidString: callId) ?? UUID()
    }

    private func request(_ action: CXAction, completion: @escaping (Error?) -> Void) {
        controller.request(CXTransaction(action: action)) { error in
            DispatchQueue.main.async {
                completion(error)
            }
        }
    }

    private func reportRemoteEnded(_ call: Call) {
        let reason: CXCallEndedReason = call.answered || call.outgoing ? .remoteEnded : .unanswered
        provider.reportCall(with: call.uuid, endedAt: nil, reason: reason)
        calls[call.uuid] = nil
        recentlyEnded.markEnded(call.callId)
        events.emit("callStateChanged", ["callId": call.callId, "state": "disconnected"])
        // Same `reason` codes as Android: 6 = missed, 2 = remote ended.
        events.emit("callEnded", ["callId": call.callId, "reason": reason == .unanswered ? 6 : 2])
    }

    /// Satisfies the "every VoIP push reports a call" rule without leaving a call on screen.
    private func reportAndEnd(callId: String, callerName: String, reason: CXCallEndedReason, completion: @escaping () -> Void) {
        recentlyEnded.markEnded(callId)
        let uuid = UUID(uuidString: callId) ?? UUID()
        let update = CXCallUpdate()
        update.localizedCallerName = callerName
        provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] _ in
            self?.provider.reportCall(with: uuid, endedAt: nil, reason: reason)
            completion()
        }
    }

    private func emitBusy(callId: String, callerName: String, handle: String) {
        events.emit("incomingCallFailed", ["callId": callId, "callerName": callerName, "handle": handle, "error": "busy"])
    }

    private func noCall(_ method: String, _ callId: String) -> CallKitError {
        return CallKitError("\(method) ignored because no call found, callId: \(callId)")
    }

    /// Same numbering as the JS `CallEndReason` / callkeep.
    private static func endedReason(_ reason: Int) -> CXCallEndedReason {
        switch reason {
        case 1:
            return .failed
        case 3, 6:
            return .unanswered
        case 4:
            return .answeredElsewhere
        case 5:
            return .declinedElsewhere
        default:
            return .remoteEnded
        }
    }

    private static func incomingCallErrorCode(_ error: Error) -> String {
        guard let callKitError = error as? CXErrorCodeIncomingCallError else {
            return error.localizedDescription
        }
        switch callKitError.code {
        case .unentitled:
            return "Unentitled"
        case .callUUIDAlreadyExists:
            return "CallUUIDAlreadyExists"
        case .filteredByDoNotDisturb:
            return "FilteredByDoNotDisturb"
        case .filteredByBlockList:
            return "FilteredByBlockList"
        default:
            return "Unknown"
        }
    }

    /// PushKit payloads may contain non-JSON values; keep only what JSON can carry.
    private static func jsonSafe(_ payload: [AnyHashable: Any]) -> [String: Any] {
        var result: [String: Any] = [:]
        for (key, value) in payload {
            guard let key = key as? String else {
                continue
            }
            if JSONSerialization.isValidJSONObject([key: value]) {
                result[key] = value
            }
        }
        return result
    }
}
