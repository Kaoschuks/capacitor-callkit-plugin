import Foundation
import PushKit

/// PushKit (VoIP push) registration, extracted from the original CallKitPlugin.swift.
/// The token formatting and push handling are unchanged; the incoming push is handed to
/// CallKitManager, which reports it to CallKit before calling `completion` (required on iOS 13+).
final class PushKitManager: NSObject, PKPushRegistryDelegate {
    static let shared = PushKitManager()

    private var registry: PKPushRegistry?
    private(set) var token: String?
    private var tokenWaiters: [(String) -> Void] = []

    /// Called on every new token (including refreshes).
    var onToken: ((String) -> Void)?

    /// Starts PushKit registration. Safe to call repeatedly. Must run on the main thread.
    func register() {
        if let registry = registry {
            registry.desiredPushTypes = [.voIP]
            return
        }
        let voipRegistry = PKPushRegistry(queue: .main)
        voipRegistry.delegate = self
        voipRegistry.desiredPushTypes = [.voIP]
        registry = voipRegistry
    }

    func unregister() {
        registry?.desiredPushTypes = []
        token = nil
    }

    /// Resolves with the token now, or as soon as PushKit delivers it. Must run on the main thread.
    func whenToken(_ completion: @escaping (String) -> Void) {
        if let token = token {
            completion(token)
        } else {
            tokenWaiters.append(completion)
            register()
        }
    }

    // MARK: - PKPushRegistryDelegate

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        let newToken = pushCredentials.token.map { String(format: "%02.2hhx", $0) }.joined()
        token = newToken
        let waiters = tokenWaiters
        tokenWaiters.removeAll()
        waiters.forEach { $0(newToken) }
        onToken?(newToken)
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        token = nil
    }

    func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        guard type == .voIP else {
            completion()
            return
        }
        CallKitManager.shared.handlePush(payload.dictionaryPayload, completion: completion)
    }
}

/// Optional native entry point. Call `CallKitVoip.start()` from
/// `application(_:didFinishLaunchingWithOptions:)` so PushKit is registered as early as Apple
/// recommends — a VoIP push that launches the app then doesn't wait for the WebView to load.
/// The plugin registers PushKit on load anyway, so this is only needed for faster cold starts.
@objc public class CallKitVoip: NSObject {
    @objc public static func start() {
        let register = {
            _ = CallKitManager.shared
            PushKitManager.shared.register()
        }
        if Thread.isMainThread {
            register()
        } else {
            DispatchQueue.main.async(execute: register)
        }
    }
}
