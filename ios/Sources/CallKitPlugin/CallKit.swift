import Foundation

@objc public class CallKit: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
