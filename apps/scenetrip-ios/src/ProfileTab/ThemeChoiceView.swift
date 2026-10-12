import SwiftUI

struct ThemeChoiceView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppTheme.key) private var stored = AppTheme.system.rawValue

    var body: some View {
        NavigationStack {
            Form {
                Picker(tr("화면 모드"), selection: Binding(
                    get: { AppTheme.resolve(stored) },
                    set: { $0.store() }
                )) {
                    ForEach(AppTheme.allCases) { theme in
                        Text(verbatim: theme.title).tag(theme)
                    }
                }
                .pickerStyle(.inline)
            }
            .navigationTitle(tr("화면 모드"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(tr("완료")) { dismiss() }
                }
            }
        }
    }
}
