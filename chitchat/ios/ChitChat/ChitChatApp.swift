import SwiftUI

@main
struct ChitChatApp: App {
    @StateObject private var model = ChatModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            ContentView(model: model).preferredColorScheme(.dark).tint(.white)
                .onChange(of: scenePhase) { phase in if phase == .background { model.leave() } }
        }
    }
}
