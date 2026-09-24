import XCTest
@testable import CallKitPlugin

final class CallPushTests: XCTestCase {
    func testParsesDocumentedIncomingCall() {
        let push = CallPush.parse([
            "type": "incoming_call", "callId": "abc-123", "callerName": "Alice", "handle": "alice@x", "hasVideo": "true"
        ])
        XCTAssertEqual(push, CallPush(type: "incoming_call", callId: "abc-123", callerName: "Alice", handle: "alice@x", hasVideo: true))
    }

    func testMissingTypeDefaultsToIncomingCall() {
        XCTAssertEqual(CallPush.parse(["callId": "abc"])?.type, CallPush.typeIncomingCall)
    }

    func testAcceptsLegacyKeysOfTheOriginalPlugin() {
        let push = CallPush.parse(["ConnectionId": "c-1", "Username": "Bob", "hasVideo": "false"])
        XCTAssertEqual(push?.callId, "c-1")
        XCTAssertEqual(push?.callerName, "Bob")
        XCTAssertEqual(push?.handle, "Bob")
        XCTAssertEqual(push?.hasVideo, false)
    }

    func testHandleAndNameFallBackToEachOther() {
        XCTAssertEqual(CallPush.parse(["callId": "1", "callerName": "Carol"])?.handle, "Carol")
        XCTAssertEqual(CallPush.parse(["callId": "1", "handle": "+123"])?.callerName, "+123")
        XCTAssertEqual(CallPush.parse(["callId": "1"])?.callerName, "Unknown")
    }

    func testHasVideoAcceptsBoolsAndNumbers() {
        XCTAssertEqual(CallPush.parse(["callId": "1", "hasVideo": true])?.hasVideo, true)
        XCTAssertEqual(CallPush.parse(["callId": "1", "hasVideo": NSNumber(value: 1)])?.hasVideo, true)
        XCTAssertEqual(CallPush.parse(["callId": "1", "hasVideo": "1"])?.hasVideo, true)
    }

    func testParsesCallEnded() {
        XCTAssertEqual(CallPush.parse(["type": "call_ended", "callId": "abc"])?.type, CallPush.typeCallEnded)
    }

    func testIgnoresNonCallPayloads() {
        XCTAssertNil(CallPush.parse(["type": "chat", "callId": "abc"]))
        XCTAssertNil(CallPush.parse(["aps": ["alert": "hello"]]))
        XCTAssertNil(CallPush.parse(["callId": ""]))
    }
}
