import XCTest
@testable import CallKitPlugin

private final class MemoryStore: CallEventStore {
    var json: String?

    func read() -> String? {
        return json
    }

    func write(_ json: String) {
        self.json = json
    }
}

private final class Owner {}

final class CallEventBusTests: XCTestCase {
    private var received: [String] = []
    private let owner = Owner()

    private func listener(_ name: String, _ data: [String: Any]) -> Bool {
        received.append("\(name):\(data["callId"] as? String ?? "")")
        return true
    }

    override func setUp() {
        super.setUp()
        received = []
    }

    func testQueuesEventsUntilAListenerAttaches() {
        let bus = CallEventBus()
        bus.emit("incomingCall", ["callId": "a"])
        bus.emit("callAnswered", ["callId": "a"])
        XCTAssertTrue(received.isEmpty)
        XCTAssertEqual(bus.pendingSnapshot().count, 2)

        bus.attach(owner: owner, listener)
        XCTAssertEqual(received, ["incomingCall:a", "callAnswered:a"])
        XCTAssertTrue(bus.pendingSnapshot().isEmpty)
    }

    func testDeliversDirectlyWhileAttachedAndQueuesAgainAfterDetach() {
        let bus = CallEventBus()
        bus.attach(owner: owner, listener)
        bus.emit("callEnded", ["callId": "b"])
        XCTAssertEqual(received, ["callEnded:b"])

        bus.detach(owner: Owner()) // not the owner: ignored
        bus.emit("callEnded", ["callId": "c"])
        XCTAssertEqual(received.count, 2)

        bus.detach(owner: owner)
        bus.emit("callEnded", ["callId": "d"])
        XCTAssertEqual(received.count, 2)
        XCTAssertEqual(bus.pendingSnapshot().count, 1)
    }

    func testDropsOldestWhenQueueIsFull() {
        let bus = CallEventBus()
        for index in 0..<(CallEventBus.maxPending + 5) {
            bus.emit("incomingCall", ["callId": String(index)])
        }
        let pending = bus.pendingSnapshot()
        XCTAssertEqual(pending.count, CallEventBus.maxPending)
        XCTAssertEqual(pending.first?.data["callId"] as? String, "5")
    }

    func testInitialEventsIncludeQueuedAndNotYetListenedEvents() {
        let bus = CallEventBus()
        bus.emit("incomingCall", ["callId": "i"])
        bus.attach(owner: owner) { _, _ in false } // plugin loaded, JS not listening yet
        bus.emit("callAnswered", ["callId": "i"])
        bus.attach(owner: owner, listener)
        bus.emit("callEnded", ["callId": "i"])

        XCTAssertEqual(bus.initialEvents().map { $0.name }, ["incomingCall", "callAnswered"])
        bus.clearInitialEvents()
        XCTAssertTrue(bus.initialEvents().isEmpty)
    }

    func testPersistsPendingEventsAndClearsThemOnceDelivered() throws {
        let bus = CallEventBus()
        let store = MemoryStore()
        bus.setStore(store)
        bus.emit("incomingCall", ["callId": "p"])
        bus.emit("callAnswered", ["callId": "p"])

        let saved = try XCTUnwrap(JSONSerialization.jsonObject(with: Data((store.json ?? "").utf8)) as? [[String: Any]])
        XCTAssertEqual(saved.map { $0["name"] as? String }, ["incomingCall", "callAnswered"])

        bus.attach(owner: owner, listener)
        XCTAssertEqual(store.json, "[]")
    }

    func testEventsFromADeadProcessAreRestoredButNotReplayed() {
        let first = CallEventBus(clock: { 1_000_000 })
        let store = MemoryStore()
        first.setStore(store)
        first.emit("incomingCall", ["callId": "d1"])
        first.emit("callAnswered", ["callId": "d1"])

        // New process, 30 s later.
        let second = CallEventBus(clock: { 1_030_000 })
        let reopened = MemoryStore()
        reopened.json = store.json
        second.setStore(reopened)
        second.attach(owner: owner, listener)

        XCTAssertTrue(received.isEmpty, "restored events must not reach listeners")
        let initial = second.initialEvents()
        XCTAssertEqual(initial.map { $0.name }, ["incomingCall", "callAnswered"])
        XCTAssertTrue(initial.allSatisfy { $0.restored })
        XCTAssertEqual(initial.first?.timestamp, 1_000_000)
        XCTAssertEqual(reopened.json, "[]")
    }

    func testStaleAndCorruptStoresAreDropped() {
        let first = CallEventBus(clock: { 0 })
        let store = MemoryStore()
        first.setStore(store)
        first.emit("incomingCall", ["callId": "old"])

        let later = CallEventBus(clock: { CallEventBus.restoredMaxAgeMs + 1 })
        let reopened = MemoryStore()
        reopened.json = store.json
        later.setStore(reopened)
        XCTAssertTrue(later.initialEvents().isEmpty)

        let corrupt = CallEventBus()
        let bad = MemoryStore()
        bad.json = "not json"
        corrupt.setStore(bad)
        XCTAssertTrue(corrupt.initialEvents().isEmpty)
        XCTAssertEqual(bad.json, "[]")
    }
}
