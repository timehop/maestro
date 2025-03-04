import Foundation
import FlyingFox
import os
import XCTest
import Network

@MainActor
struct DeviceInfoHandler: HTTPHandler {

    let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )

    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        let springboardBundleId = "com.apple.springboard"
        let springboardApp = XCUIApplication(bundleIdentifier: springboardBundleId)
        let screenSize = springboardApp.frame.size

        let deviceInfo = DeviceInfoResponse(
            widthPoints: Int(screenSize.width),
            heightPoints: Int(screenSize.height),
            widthPixels: Int(screenSize.width * UIScreen.main.scale),
            heightPixels: Int(screenSize.height * UIScreen.main.scale)
        )

        let responseBody = try! JSONEncoder().encode(deviceInfo)
        return HTTPResponse(statusCode: .ok, body: responseBody)
    }

    private func handleError(message: String) -> Data {
        logger.error("Failed - \(message)")
        let jsonString = """
         { "errorMessage" : \(message) }
        """
        return Data(jsonString.utf8)
    }
}
