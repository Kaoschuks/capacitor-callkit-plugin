import Foundation

/// Parses a call push payload (PushKit `dictionaryPayload`). Mirrors Android's `CallPush`.
///
/// Accepts the documented keys (`type`, `callId`, `callerName`, `handle`, `hasVideo`) and the
/// legacy keys of the original iOS plugin (`ConnectionId`, `Username`).
public struct CallPush: Equatable {
    public static let typeIncomingCall = "incoming_call"
    public static let typeCallEnded = "call_ended"

    public let type: String
    public let callId: String
    public let callerName: String
    public let handle: String
    public let hasVideo: Bool

    /// - Returns: `nil` when the payload is not a call push.
    public static func parse(_ payload: [AnyHashable: Any]) -> CallPush? {
        guard let callId = first(payload, ["callId", "uuid", "ConnectionId"]) else {
            return nil
        }
        let type = string(payload["type"]) ?? typeIncomingCall
        guard type == typeIncomingCall || type == typeCallEnded else {
            return nil
        }
        let rawHandle = first(payload, ["handle", "number"])
        let callerName = first(payload, ["callerName", "name", "Username"]) ?? rawHandle ?? "Unknown"
        let handle = rawHandle ?? callerName
        return CallPush(type: type, callId: callId, callerName: callerName, handle: handle, hasVideo: bool(payload["hasVideo"]))
    }

    static func string(_ value: Any?) -> String? {
        if let text = value as? String {
            return text.isEmpty ? nil : text
        }
        if let number = value as? NSNumber {
            return number.stringValue
        }
        return nil
    }

    static func bool(_ value: Any?) -> Bool {
        if let flag = value as? Bool {
            return flag
        }
        if let text = value as? String {
            return text.lowercased() == "true" || text == "1"
        }
        if let number = value as? NSNumber {
            return number.boolValue
        }
        return false
    }

    private static func first(_ payload: [AnyHashable: Any], _ keys: [String]) -> String? {
        for key in keys {
            if let value = string(payload[key]) {
                return value
            }
        }
        return nil
    }
}
