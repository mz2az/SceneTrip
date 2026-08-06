import SwiftUI

@main
struct SceneTripApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    var body: some View {
        Color.clear
            .ignoresSafeArea()
    }
}
