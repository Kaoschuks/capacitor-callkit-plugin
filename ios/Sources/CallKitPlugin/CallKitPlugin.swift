import Foundation
import Capacitor
import UIKit
import CallKit
import PushKit

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(CallKitPlugin)
public class CallKitPlugin: CAPPlugin, CAPBridgedPlugin, PKPushRegistryDelegate {
    struct CallConfig {
        let connectionId: String
        let username: String
    }
    public let identifier = "CallKitPlugin"
    public let jsName = "CallKit"
    public let pluginMethods: [CAPPluginMethod] = [
        // CAPPluginMethod(name: "register", returnType: CAPPluginReturnPromise),
    ]
    private let callController = CXCallController()
    private var provider: CXProvider?
    private let voipRegistry = PKPushRegistry(queue: nil)
    private var connectionIdRegistry: [UUID: CallConfig] = [:]
    private let implementation = CallKit()

    // MARK: - Plugin Lifecycle
    public override func load() {
        super.load()
        setupVoIP()
    }
    
    // MARK: - Plugin Background Call
    static func performBackgroundTask(completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        DispatchQueue.global().async {
            CallKitPlugin().setupVoIP()
            completionHandler(.newData)
        }
    }

    private func setupVoIP() {
        voipRegistry.delegate = self
        voipRegistry.desiredPushTypes = [.voIP]

        let config = CXProviderConfiguration(localizedName: "Call Plugin")
        config.supportsVideo = true
        config.supportedHandleTypes = [.emailAddress]

        provider = CXProvider(configuration: config)
        provider?.setDelegate(self, queue: DispatchQueue.main)
    }

    public func notifyEvent(eventName: String, uuid: UUID, error: String? = nil, token: String? = nil) {
        if let config = connectionIdRegistry[uuid], error == nil {
            self.notifyListeners("plugin_events", data: [
                "status": eventName,
                "connectionId": config.connectionId,
                "username": config.username,
            ])
            connectionIdRegistry[uuid] = nil
        }
        if let error = error {
            self.notifyListeners("plugin_events", data: [
                "status": eventName,
                "error": error,
            ])
        }
        if let token = token {
            self.notifyListeners("plugin_events", data: [
                "status": eventName,
                "token": token,
            ])
        }
    }

    public func incomingCall(from: String, connectionId: String) {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: from)
        update.hasVideo = true
        update.supportsDTMF = false
        update.supportsHolding = true
        update.supportsGrouping = false
        update.supportsUngrouping = false

        let uuid = UUID()
        connectionIdRegistry[uuid] = CallConfig(connectionId: connectionId, username: from)
        provider?.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            if let self = self, let error = error {
                self.notifyEvent(eventName: "error", uuid: uuid, error: error.localizedDescription)
                self.showLocalNotification(eventName: "accepted", message: error.localizedDescription)
            }
        }
    }

    public func endCall(uuid: UUID) {
        let transaction = CXTransaction(action: CXEndCallAction(call: uuid))
        callController.request(transaction) { error in
            if let error = error {
                print("Error ending call: \(error.localizedDescription)")
            }
        }
    }
}

// MARK: CallKit events handler
extension CallKitPlugin: CXProviderDelegate {

    public func providerDidReset(_ provider: CXProvider) {
        notifyEvent(eventName: "on_error", uuid: UUID(), error: "Provider did reset")
    }

    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        notifyEvent(eventName: "on_call_accepted", uuid: action.callUUID)
        showLocalNotification(eventName: "accepted", message: "Call accepted")

        let answerCallAction = CXAnswerCallAction(call: action.callUUID)
        let transaction = CXTransaction(action: answerCallAction)

        callController.request(transaction) { error in
            if let error = error {
                print("Error answering call: \(error.localizedDescription)")
            }
        }

        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        notifyEvent(eventName: "on_call_rejected", uuid: action.callUUID)
        endCall(uuid: action.callUUID)
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        notifyEvent(eventName: "on_registration", uuid: action.callUUID)
        action.fulfill()
    }

    private func openApp() {
        if let url = URL(string: "agentpath://"), UIApplication.shared.canOpenURL(url) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        } else {
            print("Ionic app is not installed")
        }
    }

    private func showLocalNotification(eventName: String, message: String) {
        let content = UNMutableNotificationContent()
        content.title = "Call Status"
        content.body = "\(message) has \(eventName.lowercased()) the call."
        content.sound = UNNotificationSound.default

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 0.1, repeats: false)
        let request = UNNotificationRequest(identifier: "Call Status", content: content, trigger: trigger)

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Error displaying local notification: \(error.localizedDescription)")
            }
        }
    }
    
    // MARK: PushKit events handler
    public func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        let token = pushCredentials.token.map { String(format: "%02.2hhx", $0) }.joined()
        notifyEvent(eventName: "on_token", uuid: UUID(), token: token)
    }

    public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        guard let connectionId = payload.dictionaryPayload["ConnectionId"] as? String else {
            completion()
            return
        }

        let username = (payload.dictionaryPayload["Username"] as? String) ?? "Anonymous"
        incomingCall(from: username, connectionId: connectionId)
        completion()
    }
}
