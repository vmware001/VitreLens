import SwiftUI

@main
struct VitreLensApp: App {
    @StateObject private var model = AppModel()

    init() {
        // CI 无人值守渲染：设置了环境变量就直接出图并退出，不进入界面
        Snapshot.runIfRequested()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
        }
    }
}
