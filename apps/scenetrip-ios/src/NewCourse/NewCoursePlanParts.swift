import SceneApiClient
import SwiftUI

/// 일정 묶음 — 기간 칩(3열, 당일치기~5박 6일) + 「떠나는 날」 한 줄(펼치는 달력, 「날짜 지우기」) (MZ2AZ-547 A1·A4, 웹 `TripGroup`).
/// 작품으로·담은 곳으로가 같이 쓴다. 날짜는 정하지 않아도 된다.
struct TripScheduleGroup: View {
    @Binding var span: RouteSpan
    @Binding var date: Date?
    var footnote: String?

    @State private var calendarOpen = false

    var body: some View {
        NewCourseGroup(label: tr("일정"), footnote: footnote) {
            chips.padding(.horizontal, 14).padding(.vertical, 12)
            hairline
            dateRow
            if calendarOpen {
                hairline
                DatePicker(
                    tr("떠나는 날"),
                    selection: Binding(get: { date ?? Date() }, set: { picked in
                        date = picked
                        calendarOpen = false
                    }),
                    in: Calendar.current.startOfDay(for: Date())...,
                    displayedComponents: .date
                )
                .datePickerStyle(.graphical)
                .labelsHidden()
                .tint(NewCourseStyle.purple)
                .padding(.horizontal, 6)
            }
            if date != nil {
                hairline
                Button { date = nil } label: {
                    Text("날짜 지우기")
                        .font(.system(size: 15))
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .padding(.horizontal, 14)
                        .contentShape(.rect)
                }
                .buttonStyle(.press)
            }
        }
    }

    private var hairline: some View {
        Rectangle().fill(Color(.separator)).frame(height: 0.5)
    }

    private var chips: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
            ForEach(RouteSpan.allCases) { each in
                let on = each == span
                Button { span = each } label: {
                    Text(verbatim: each.label)
                        .font(.system(size: 15, weight: on ? .bold : .semibold))
                        .foregroundStyle(on ? .white : .primary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(RoundedRectangle(cornerRadius: NewCourseStyle.radiusS)
                            .fill(on ? NewCourseStyle.purple : Color(.systemGray6)))
                        .contentShape(.rect)
                }
                .buttonStyle(.press)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }

    private var dateRow: some View {
        Button {
            withAnimation(.easeOut(duration: 0.2)) { calendarOpen.toggle() }
        } label: {
            HStack(spacing: 10) {
                Text("떠나는 날").font(.system(size: 15)).foregroundStyle(.primary)
                Spacer()
                Text(verbatim: dateLabel)
                    .font(.system(size: 15))
                    .foregroundStyle(date == nil ? .secondary : .primary)
                Image(systemName: calendarOpen ? "chevron.up" : "chevron.down")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .contentShape(.rect)
        }
        .buttonStyle(.press)
    }

    private var dateLabel: String {
        guard let date else { return tr("정하지 않음") }
        guard span.nights > 0, let back = Calendar.current.date(byAdding: .day, value: span.nights, to: date) else {
            return RouteFormat.day(date)
        }
        return "\(RouteFormat.day(date)) – \(RouteFormat.day(back))"
    }
}

/// 다니는 속도 — 묶음 안 두 줄, 고른 줄 오른쪽에 체크(앱 설정 화면처럼) (A1).
struct PaceRows: View {
    @Binding var pace: RoutePace

    var body: some View {
        ForEach(Array([RoutePace.loose, .tight].enumerated()), id: \.element) { index, each in
            let on = each == pace
            Button { pace = each } label: {
                HStack(spacing: 12) {
                    Image(systemName: each.symbol)
                        .font(.system(size: 17))
                        .foregroundStyle(NewCourseStyle.purple)
                        .frame(width: 22)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(verbatim: tr(each.rawValue)).font(.system(size: 15, weight: on ? .semibold : .regular))
                        Text(verbatim: each.caption).font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if on {
                        Image(systemName: "checkmark").font(.system(size: 16, weight: .bold)).foregroundStyle(NewCourseStyle.purple)
                    }
                }
                .foregroundStyle(.primary)
                .padding(.horizontal, 14).padding(.vertical, 11)
                .overlay(alignment: .top) {
                    if index > 0 {
                        Rectangle().fill(Color(.separator)).frame(height: 0.5)
                    }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.press)
            .accessibilityAddTraits(on ? .isSelected : [])
        }
    }
}
