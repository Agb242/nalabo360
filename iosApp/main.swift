import Foundation
import UIKit
import Nalabo360Kit

/// Minimal application delegate: the whole job is to host the shared
/// Compose UI (`MainKt.MainViewController()`) in a full-screen window.
/// There is no storyboard — the window is built here, and the Info.plist
/// ships an empty `UILaunchScreen` so the app fills modern devices.
final class AppDelegate: NSObject, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = MainKt.MainViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}

// UIApplicationMain instantiates the principal class through the Objective-C
// runtime, by name. Holding the reference first forces Swift to register the
// class metadata before that lookup happens.
let principalClass: AnyClass = AppDelegate.self
UIApplicationMain(
    CommandLine.argc,
    CommandLine.unsafeArgv,
    nil,
    NSStringFromClass(principalClass)
)
