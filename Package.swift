// swift-tools-version: 5.6

import PackageDescription

let package = Package(
    name: "Maestro",
    platforms: [.iOS(.v12)],
    products: [
        .library(name: "Maestro", targets: ["Maestro"]),
    ],
    targets: [
        .target(
            name: "Maestro",
            path: "maestro-sdk/ios/Maestro SDK/Maestro SDK"),
    ]
)
