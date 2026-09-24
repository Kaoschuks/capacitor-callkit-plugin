import XCTest
@testable import CallKitPlugin

final class RecentCallIdsTests: XCTestCase {
    func testRemembersEndedCallsForTheTtl() {
        var now: TimeInterval = 0
        let ids = RecentCallIds(clock: { now })
        ids.markEnded("a")
        XCTAssertTrue(ids.recentlyEnded("a"))
        XCTAssertFalse(ids.recentlyEnded("b"))

        now = RecentCallIds.ttl
        XCTAssertTrue(ids.recentlyEnded("a"))
        now = RecentCallIds.ttl + 1
        XCTAssertFalse(ids.recentlyEnded("a"))
    }

    func testMarkingAgainRefreshesTheTimestamp() {
        var now: TimeInterval = 0
        let ids = RecentCallIds(clock: { now })
        ids.markEnded("a")
        now = RecentCallIds.ttl
        ids.markEnded("a")
        now = RecentCallIds.ttl * 2
        XCTAssertTrue(ids.recentlyEnded("a"))
    }

    func testStaysBounded() {
        let ids = RecentCallIds()
        for index in 0..<150 {
            ids.markEnded("c\(index)")
        }
        XCTAssertFalse(ids.recentlyEnded("c0"))
        XCTAssertTrue(ids.recentlyEnded("c149"))
    }
}
