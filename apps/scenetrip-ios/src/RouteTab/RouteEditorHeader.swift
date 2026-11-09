import SwiftUI

/// 큰 글자에서는 동작과 제목을 두 줄에 둔다(MZ2AZ-388).
extension RouteEditorView {
    var stackedTopBar: some View {
        VStack(spacing: 8) {
            HStack {
                cancelButton
                Spacer()
                saveButton
            }
            courseTitleField
        }
    }

    var cancelButton: some View {
        // 새 초안은 닫고, 저장된 코스는 편집 전으로 되돌려 보기로, 방금 만든 코스는 지울지 묻는다(MZ2AZ-547 E3·E16).
        Button("취소") { cancelTapped() }
            .lineLimit(1).fixedSize(horizontal: true, vertical: true)
            .frame(minHeight: 44)
    }

    var saveButton: some View {
        Button(isNew ? tr("만들기") : tr("저장")) {
            Task { await saveAndClose() }
        }
        .font(.body.weight(.semibold))
        .lineLimit(1).fixedSize(horizontal: true, vertical: true)
        .frame(minHeight: 44)
        .disabled(!course.canSave)
    }

    var courseTitleField: some View {
        HStack(spacing: 4) {
            // 편집 가능한 제목은 최대 200pt. 긴 이름은 칸 안에서 넘겨 본다.
            TextField("코스 이름", text: $course.title)
                .font(.headline).multilineTextAlignment(.center).lineLimit(1).submitLabel(.done)
                .frame(width: min(titleWidth + 4, 200))
                .background {
                    Text(course.title.isEmpty ? tr("코스 이름") : course.title)
                        .font(.headline).fixedSize().hidden()
                        .background(GeometryReader { geo in
                            Color.clear.preference(key: EditorTitleWidthKey.self, value: geo.size.width)
                        })
                }
                .onPreferenceChange(EditorTitleWidthKey.self) { titleWidth = $0 }
            Image(systemName: "pencil")
                .font(.caption.weight(.semibold)).foregroundStyle(.secondary).accessibilityHidden(true)
        }
        .frame(maxWidth: 220)
    }
}
