import SwiftUI
import HanppieShared

@main
struct HanppieApp: App {
    private let services = ApplePlatformServices()
    var body: some Scene {
        WindowGroup {
            Workbench(services: services)
                .ignoresSafeArea()
        }
    }
}

private struct Workbench: UIViewControllerRepresentable {
    let services: ApplePlatformServices
    func makeUIViewController(context: Context) -> UIViewController {
        IosWorkbenchKt.MainViewController(platform: services)
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
