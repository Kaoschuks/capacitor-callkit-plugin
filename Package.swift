// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorCallkitPlugin",
    platforms: [.iOS(.v13)],
    products: [
        .library(
            name: "CapacitorCallkitPlugin",
            targets: ["CallKitPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", branch: "main")
    ],
    targets: [
        .target(
            name: "CallKitPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/CallKitPlugin"),
        .testTarget(
            name: "CallKitPluginTests",
            dependencies: ["CallKitPlugin"],
            path: "ios/Tests/CallKitPluginTests")
    ]
)