import Foundation
import Capacitor
import UIKit
import CallKit
import PushKit

@objc(CallKitPlugin)
public class CallKitPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CallKitPlugin"
    public let jsName = "CallKit"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "register", returnType: CAPPluginReturnPromise),
    ]
    private let callController = CXCallController()
    private var provider: CXProvider?
    private let voipRegistry = PKPushRegistry(queue: nil)
    private var connectionIdRegistry: [UUID: CallConfig] = [:]
    private let implementation = CallKit()
    private var voipToken: String?

    public override func load() {
        super.load()
        guard let bridge = self.bridge else { return }
    }

    @objc func register(_ call: CAPPluginCall) {
        // config PushKit
        voipRegistry.delegate = self
        voipRegistry.desiredPushTypes = [.voIP]
        
        let config = CXProviderConfiguration(localizedName: "Call Plugin")
        config.supportsVideo = true
        config.supportedHandleTypes = [.emailAddress]
        
        provider = CXProvider(configuration: config)
        provider?.setDelegate(self, queue: DispatchQueue.main)

        if let token = voipToken {
            call.resolve([
                "status": "Plugin Registered",
                "token": token
            ])
        }
    }

    public func notifyEvent(eventName: String, uuid: UUID?, error: String? = nil, token: String? = nil) {
        if let uuid = uuid, let config = connectionIdRegistry[uuid], error == nil {
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
        provider?.reportNewIncomingCall(with: uuid, update: update, completion: { [weak self] error in
            if let error = error {
                self?.notifyEvent(eventName: "error", uuid: uuid, error: error.localizedDescription)
                self?.showLocalNotification("accepted", error.localizedDescription)
            }
        })
    }

    public func endCall(uuid: UUID) {
        let transaction = CXTransaction(action: CXEndCallAction(call: uuid))
        callController.request(transaction, completion: { error in
            if let error = error {
                print("Error ending call: \(error.localizedDescription)")
            }
        })
    }
}

extension CallKitPlugin: CXProviderDelegate {

    public func providerDidReset(_ provider: CXProvider) {
        print("providerDidReset called")
        notifyEvent(eventName: "on_reset", uuid: nil, error: "Provider did reset")
        
        let calls = callController.callObserver.calls
        if calls.isEmpty {
            print("No active calls")
        } else {
            for call in calls {
                print("Active call: \(call.uuid), hasConnected: \(call.hasConnected), hasEnded: \(call.hasEnded)")
            }
        }
        
        connectionIdRegistry.removeAll()
    }

    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        print("call answered")
        notifyEvent(eventName: "on_call_accepted", uuid: action.callUUID)
        showLocalNotification("accepted", "Call answered")

        let answerCallAction = CXAnswerCallAction(call: action.callUUID)
        let transaction = CXTransaction(action: answerCallAction)

        callController.request(transaction, completion: { error in
            if let error = error {
                print("Error answering call: \(error.localizedDescription)")
            }
        })

        endCall(uuid: action.callUUID)
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        print("call declined")
        notifyEvent(eventName: "on_call_rejected", uuid: action.callUUID)
        endCall(uuid: action.callUUID)
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        notifyEvent(eventName: "on_registration", uuid: action.callUUID)
        action.fulfill()
    }

    func openApp() {
        if let url = URL(string: "agentpath://"), UIApplication.shared.canOpenURL(url) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        } else {
            print("Ionic app is not installed")
        }
    }

    func showLocalNotification(_ eventName: String, _ username: String) {
        let content = UNMutableNotificationContent()
        content.title = "Call Status"
        content.body = "\(username) has \(eventName.lowercased()) the call."
        content.sound = UNNotificationSound.default

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 0.1, repeats: false)
        let request = UNNotificationRequest(identifier: "Call Status", content: content, trigger: trigger)

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Error displaying local notification: \(error.localizedDescription)")
            }
        }
    }
}

extension CallKitPlugin: PKPushRegistryDelegate {

    public func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        let parts = pushCredentials.token.map { String(format: "%02.2hhx", $0) }
        let token = parts.joined()
        voipToken = token
        notifyEvent(eventName: "on_token", uuid: nil, token: token)
    }

    public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        guard let connectionId = payload.dictionaryPayload["ConnectionId"] as? String else {
            return
        }

        let username = (payload.dictionaryPayload["Username"] as? String) ?? "Anonymous"
        self.incomingCall(from: username, connectionId: connectionId)
        completion()
    }
}

extension CallKitPlugin {
    struct CallConfig {
        let connectionId: String
        let username: String
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    public func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        // Handle notification interaction here
        completionHandler()
    }

    func setupNotifications() {
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { (granted, error) in
            if granted {
                print("Notification permissions granted")
            } else {
                print("Notification permissions denied")
            }
        }
    }
}
