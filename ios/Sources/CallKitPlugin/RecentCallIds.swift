import Foundation

/// Remembers call ids that recently ended, so a VoIP push delivered late or twice (APNs retries,
/// `call_ended` overtaking `incoming_call`) doesn't ring a call that is already over.
/// Mirrors Android's RecentCallIds. Pure Foundation so it can be unit tested.
final class RecentCallIds {
    static let ttl: TimeInterval = 5 * 60
    private static let maxSize = 100

    private var ended: [(callId: String, at: TimeInterval)] = []
    private let clock: () -> TimeInterval

    init(clock: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 }) {
        self.clock = clock
    }

    func markEnded(_ callId: String) {
        prune()
        ended.removeAll { $0.callId == callId }
        ended.append((callId, clock()))
        if ended.count > RecentCallIds.maxSize {
            ended.removeFirst()
        }
    }

    func recentlyEnded(_ callId: String) -> Bool {
        prune()
        return ended.contains { $0.callId == callId }
    }

    private func prune() {
        let now = clock()
        ended.removeAll { now - $0.at > RecentCallIds.ttl }
    }
}
