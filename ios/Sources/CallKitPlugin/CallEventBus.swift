import Foundation

/// Persistent storage for the pending event queue (UserDefaults in production).
public protocol CallEventStore: AnyObject {
    func read() -> String?
    func write(_ json: String)
}

/// Queues call events until the plugin (and so the JS layer) is ready. Mirrors Android's
/// `CallEventBus`.
///
/// A VoIP push can wake a killed app and CallKit can answer the call before the WebView has
/// loaded. Events raised then are queued in order, persisted, and flushed when `CallKitPlugin`
/// attaches.
/// - Events restored in a new process are not replayed (the system ended those calls when the
///   old process died); they are exposed through `initialEvents()` with `restored: true`.
/// - Every event that happened before JS listened for it is also kept for `initialEvents()`
///   until `clearInitialEvents()` (callkeep's getInitialEvents / clearInitialEvents).
public final class CallEventBus {
    public struct Event {
        public let name: String
        public let data: [String: Any]
        /// Epoch milliseconds.
        public let timestamp: Double
        public let restored: Bool

        public func toDictionary() -> [String: Any] {
            return ["name": name, "data": data, "timestamp": timestamp, "restored": restored]
        }
    }

    /// Returns true when JS already had a listener for the event.
    public typealias Listener = (_ name: String, _ data: [String: Any]) -> Bool

    public static let shared = CallEventBus()

    static let maxPending = 50
    static let restoredMaxAgeMs: Double = 5 * 60 * 1000

    private let lock = NSLock()
    private var pending: [Event] = []
    private var initial: [Event] = []
    private var listener: Listener?
    private var owner: ObjectIdentifier?
    private var store: CallEventStore?
    var clock: () -> Double

    init(clock: @escaping () -> Double = { Date().timeIntervalSince1970 * 1000 }) {
        self.clock = clock
    }

    /// Install the persistent store. The first store installed restores what a previous
    /// process queued but never delivered.
    public func setStore(_ newStore: CallEventStore) {
        lock.lock()
        defer { lock.unlock() }
        let firstStore = store == nil
        store = newStore
        if firstStore {
            restore()
        }
    }

    public func emit(_ name: String, _ data: [String: Any] = [:]) {
        lock.lock()
        guard let target = listener else {
            if pending.count >= CallEventBus.maxPending {
                pending.removeFirst()
            }
            pending.append(Event(name: name, data: data, timestamp: clock(), restored: false))
            persist()
            lock.unlock()
            return
        }
        lock.unlock()

        if !target(name, data) {
            lock.lock()
            initial.append(Event(name: name, data: data, timestamp: clock(), restored: false))
            lock.unlock()
        }
    }

    /// Attach the listener and deliver everything queued while nobody was listening.
    public func attach(owner newOwner: AnyObject, _ newListener: @escaping Listener) {
        lock.lock()
        listener = newListener
        owner = ObjectIdentifier(newOwner)
        let toFlush = pending
        initial.append(contentsOf: toFlush)
        pending.removeAll()
        persist()
        lock.unlock()

        for event in toFlush {
            _ = newListener(event.name, event.data)
        }
    }

    public func detach(owner oldOwner: AnyObject) {
        lock.lock()
        defer { lock.unlock() }
        if owner == ObjectIdentifier(oldOwner) {
            listener = nil
            owner = nil
        }
    }

    public func initialEvents() -> [Event] {
        lock.lock()
        defer { lock.unlock() }
        return initial
    }

    public func clearInitialEvents() {
        lock.lock()
        defer { lock.unlock() }
        initial.removeAll()
    }

    func pendingSnapshot() -> [Event] {
        lock.lock()
        defer { lock.unlock() }
        return pending
    }

    // MARK: - Persistence (call with lock held)

    private func restore() {
        guard let json = store?.read(), let raw = json.data(using: .utf8) else {
            return
        }
        let now = clock()
        if let items = (try? JSONSerialization.jsonObject(with: raw)) as? [[String: Any]] {
            for item in items {
                guard let name = item["name"] as? String, let timestamp = (item["timestamp"] as? NSNumber)?.doubleValue else {
                    continue
                }
                if now - timestamp > CallEventBus.restoredMaxAgeMs {
                    continue
                }
                let data = item["data"] as? [String: Any] ?? [:]
                initial.append(Event(name: name, data: data, timestamp: timestamp, restored: true))
            }
        }
        store?.write("[]")
    }

    private func persist() {
        guard let store = store else {
            return
        }
        let items: [[String: Any]] = pending.compactMap { event in
            let item: [String: Any] = ["name": event.name, "data": event.data, "timestamp": event.timestamp]
            return JSONSerialization.isValidJSONObject(item) ? item : nil
        }
        if let raw = try? JSONSerialization.data(withJSONObject: items), let json = String(data: raw, encoding: .utf8) {
            store.write(json)
        }
    }
}

/// UserDefaults-backed store used by the plugin.
final class UserDefaultsEventStore: CallEventStore {
    private let key = "IonicCallkitPendingEvents"

    func read() -> String? {
        return UserDefaults.standard.string(forKey: key)
    }

    func write(_ json: String) {
        UserDefaults.standard.set(json, forKey: key)
    }
}
