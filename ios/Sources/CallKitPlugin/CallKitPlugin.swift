import AVFoundation
import Capacitor
import Foundation
import UserNotifications

/// Capacitor bridge over CallKitManager / PushKitManager / AudioSessionManager.
/// Same method and event names as the Android plugin (see src/definitions.ts).
@objc(CallKitPlugin)
public class CallKitPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CallKitPlugin"
    public let jsName = "CallKit"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "setup", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "registerVoipToken", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "unregisterVoipToken", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "displayIncomingCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "answerCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "rejectCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setCallActive", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setCallState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "endCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "endAllCalls", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reportEndCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateDisplay", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setMuted", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setSpeaker", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setOnHold", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sendDTMF", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAudioRoutes", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setAudioRoute", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setReachable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setCanMakeMultipleCalls", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInitialEvents", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearInitialEvents", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hasPhoneAccount", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openPhoneAccountSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "canUseFullScreenIntent", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openFullScreenIntentSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "backToForeground", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getActiveCalls", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise)
    ]

    private var manager: CallKitManager {
        return CallKitManager.shared
    }

    // MARK: - Lifecycle

    override public func load() {
        DispatchQueue.main.async {
            _ = CallKitManager.shared
            // Register as early as possible so VoIP pushes reach us (as the original plugin did).
            PushKitManager.shared.register()
            CallEventBus.shared.attach(owner: self) { [weak self] name, data in
                guard let self = self else {
                    return false
                }
                let listening = self.hasListeners(name)
                // Retain until a JS listener is added, so events from a cold start aren't lost.
                self.notifyListeners(name, data: data, retainUntilConsumed: true)
                return listening
            }
        }
    }

    deinit {
        CallEventBus.shared.detach(owner: self)
    }

    // MARK: - Setup / tokens

    @objc func setup(_ call: CAPPluginCall) {
        var settings: [String: Any] = [:]
        if let supportsVideo = call.getBool("supportsVideo") {
            settings["supportsVideo"] = supportsVideo
        }
        if let imageName = call.getString("imageName") {
            settings["imageName"] = imageName
        }
        let ios = call.getObject("ios") ?? [:]
        if let ringtone = ios["ringtoneSound"] as? String {
            settings["ringtoneSound"] = ringtone
        }
        for key in ["maximumCallGroups", "maximumCallsPerCallGroup"] {
            if let value = ios[key] as? NSNumber {
                settings[key] = value.intValue
            }
        }
        if let recents = ios["includesCallsInRecents"] as? Bool {
            settings["includesCallsInRecents"] = recents
        }
        if let handleType = ios["handleType"] as? String {
            settings["handleType"] = handleType
        }
        if let audio = ios["audioSession"] as? JSObject {
            var audioSettings: [String: Any] = [:]
            if let autoConfigure = audio["autoConfigure"] as? Bool {
                audioSettings["autoConfigure"] = autoConfigure
            }
            if let options = audio["categoryOptions"] as? NSNumber {
                audioSettings["categoryOptions"] = options.uintValue
            }
            if let mode = audio["mode"] as? String {
                audioSettings["mode"] = mode
            }
            settings["audioSession"] = audioSettings
        }
        // Shared with Android: `android.canMakeMultipleCalls`.
        if let android = call.getObject("android"), let allow = android["canMakeMultipleCalls"] as? Bool {
            settings["canMakeMultipleCalls"] = allow
        }

        DispatchQueue.main.async {
            self.manager.setup(settings)
            PushKitManager.shared.register()
            call.resolve()
        }
    }

    @objc func registerVoipToken(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            PushKitManager.shared.whenToken { token in
                call.resolve(["token": token])
            }
        }
    }

    @objc func unregisterVoipToken(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            PushKitManager.shared.unregister()
            call.resolve()
        }
    }

    // MARK: - Incoming

    @objc func displayIncomingCall(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId"), let callerName = required(call, "callerName") else {
            return
        }
        let handle = call.getString("handle") ?? callerName
        let hasVideo = call.getBool("hasVideo") ?? false
        let payload = call.getObject("payload")?.mapValues { $0 as Any }
        DispatchQueue.main.async {
            self.manager.reportIncomingCall(callId: callId, callerName: callerName, handle: handle, hasVideo: hasVideo, payload: payload) {
                self.finish(call, $0)
            }
        }
    }

    @objc func answerCall(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId") else {
            return
        }
        DispatchQueue.main.async {
            self.manager.answer(callId) { self.finish(call, $0) }
        }
    }

    @objc func rejectCall(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId") else {
            return
        }
        DispatchQueue.main.async {
            self.manager.reject(callId) { self.finish(call, $0) }
        }
    }

    // MARK: - Outgoing

    @objc func startCall(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId"), let calleeName = required(call, "calleeName") else {
            return
        }
        let handle = call.getString("handle") ?? calleeName
        let hasVideo = call.getBool("hasVideo") ?? false
        DispatchQueue.main.async {
            self.manager.startCall(callId: callId, calleeName: calleeName, handle: handle, hasVideo: hasVideo) {
                self.finish(call, $0)
            }
        }
    }

    @objc func setCallActive(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId") else {
            return
        }
        run(call) { try self.manager.setCallActive(callId) }
    }

    @objc func setCallState(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId"), let state = required(call, "state") else {
            return
        }
        DispatchQueue.main.async {
            self.manager.setCallState(callId, state: state) { self.finish(call, $0) }
        }
    }

    @objc func endCall(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId") else {
            return
        }
        DispatchQueue.main.async {
            self.manager.endCall(callId) { self.finish(call, $0) }
        }
    }

    @objc func endAllCalls(_ call: CAPPluginCall) {
        run(call) { self.manager.endAllCalls() }
    }

    @objc func reportEndCall(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId") else {
            return
        }
        let reason = call.getInt("reason") ?? 2
        run(call) { try self.manager.reportEndCall(callId, reason: reason) }
    }

    @objc func updateDisplay(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId"), let callerName = required(call, "callerName") else {
            return
        }
        let handle = call.getString("handle") ?? callerName
        run(call) { try self.manager.updateDisplay(callId, callerName: callerName, handle: handle) }
    }

    // MARK: - In-call controls

    @objc func setMuted(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId") else {
            return
        }
        let muted = call.getBool("muted") ?? false
        DispatchQueue.main.async {
            self.manager.setMuted(callId, muted: muted) { self.finish(call, $0) }
        }
    }

    @objc func setSpeaker(_ call: CAPPluginCall) {
        let on = call.getBool("on") ?? false
        // The route-change notification emits speakerChanged / audioRouteChanged.
        run(call) { try AudioSessionManager.shared.setSpeaker(on) }
    }

    @objc func setOnHold(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId") else {
            return
        }
        let hold = call.getBool("hold") ?? false
        DispatchQueue.main.async {
            self.manager.setOnHold(callId, hold: hold) { self.finish(call, $0) }
        }
    }

    @objc func sendDTMF(_ call: CAPPluginCall) {
        guard let callId = required(call, "callId"), let digits = required(call, "digits") else {
            return
        }
        DispatchQueue.main.async {
            self.manager.sendDTMF(callId, digits: digits) { self.finish(call, $0) }
        }
    }

    @objc func getAudioRoutes(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            do {
                call.resolve(["routes": try AudioSessionManager.shared.audioRoutes()])
            } catch {
                call.reject("GetAudioRoutes Error: \(error.localizedDescription)")
            }
        }
    }

    @objc func setAudioRoute(_ call: CAPPluginCall) {
        guard let route = required(call, "route") else {
            return
        }
        run(call) { try AudioSessionManager.shared.setRoute(route) }
    }

    // MARK: - Platform helpers

    @objc func setAvailable(_ call: CAPPluginCall) {
        call.resolve() // Android only.
    }

    @objc func setReachable(_ call: CAPPluginCall) {
        call.resolve() // Android only.
    }

    @objc func setCanMakeMultipleCalls(_ call: CAPPluginCall) {
        let allow = call.getBool("allow") ?? true
        run(call) { self.manager.setCanMakeMultipleCalls(allow) }
    }

    @objc func getInitialEvents(_ call: CAPPluginCall) {
        call.resolve(["events": CallEventBus.shared.initialEvents().map { $0.toDictionary() }])
    }

    @objc func clearInitialEvents(_ call: CAPPluginCall) {
        CallEventBus.shared.clearInitialEvents()
        call.resolve()
    }

    @objc func hasPhoneAccount(_ call: CAPPluginCall) {
        call.resolve(["value": true]) // No calling account on iOS.
    }

    @objc func openPhoneAccountSettings(_ call: CAPPluginCall) {
        call.unimplemented("Not available on iOS.")
    }

    @objc func canUseFullScreenIntent(_ call: CAPPluginCall) {
        call.resolve(["value": true]) // CallKit always shows the full-screen call UI.
    }

    @objc func openFullScreenIntentSettings(_ call: CAPPluginCall) {
        call.unimplemented("Not available on iOS.")
    }

    @objc func backToForeground(_ call: CAPPluginCall) {
        call.unimplemented("iOS does not let apps bring themselves to the foreground.")
    }

    @objc func getActiveCalls(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(["calls": self.manager.activeCalls()])
        }
    }

    // MARK: - Permissions

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        permissionStatus { call.resolve($0) }
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        let wanted = call.getArray("permissions", String.self) ?? ["microphone", "notifications"]
        let requestNotifications = {
            guard wanted.contains("notifications") else {
                self.permissionStatus { call.resolve($0) }
                return
            }
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
                self.permissionStatus { call.resolve($0) }
            }
        }
        if wanted.contains("microphone") {
            AVAudioSession.sharedInstance().requestRecordPermission { _ in
                requestNotifications()
            }
        } else {
            requestNotifications()
        }
    }

    private func permissionStatus(_ done: @escaping ([String: Any]) -> Void) {
        let microphone: String
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            microphone = "granted"
        case .denied:
            microphone = "denied"
        default:
            microphone = "prompt"
        }
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let notifications: String
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                notifications = "granted"
            case .denied:
                notifications = "denied"
            default:
                notifications = "prompt"
            }
            done(["microphone": microphone, "notifications": notifications, "phone": "granted"])
        }
    }

    // MARK: - Helpers

    private func required(_ call: CAPPluginCall, _ key: String) -> String? {
        guard let value = call.getString(key), !value.isEmpty else {
            call.reject("Missing required option: \(key)")
            return nil
        }
        return value
    }

    private func run(_ call: CAPPluginCall, _ action: @escaping () throws -> Void) {
        DispatchQueue.main.async {
            do {
                try action()
                call.resolve()
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    private func finish(_ call: CAPPluginCall, _ error: Error?) {
        if let error = error {
            call.reject(error.localizedDescription)
        } else {
            call.resolve()
        }
    }
}
