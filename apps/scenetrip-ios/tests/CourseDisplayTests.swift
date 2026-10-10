import SceneApiClient
@testable import SceneTrip
import XCTest

final class CourseDisplayTests: XCTestCase {
    private func item(_ id: Int64 = 1) -> CourseItem {
        CourseItem(id: id, source: .poi, poiId: 900_001 + id, name: "행궁당", address: "수원",
                   category: "카페", latitude: 37.28, longitude: 127.01, dwellMinutes: 60)
    }

    func testServerPoiLabelsPreserveOriginalAndCardFields() throws {
        var value = item()
        value.nameRoman = "Haenggungdang"
        value.categoryLabel = "Cafe"
        value.displayAddress = "Suwon"
        let stop = RouteBridge.stop(from: value)
        let label = stop.label(korean: false)
        XCTAssertEqual(label.title, "행궁당")
        XCTAssertEqual(label.reading, "Haenggungdang")
        XCTAssertEqual(label.category, "Cafe")
        XCTAssertEqual(label.address, "Suwon")
        let card = try XCTUnwrap(RouteGuide.Place(poiStop: stop))
        XCTAssertEqual(card.nameRoman, value.nameRoman)
        XCTAssertEqual(card.categoryLabel, value.categoryLabel)
        XCTAssertEqual(card.displayAddress, value.displayAddress)
        XCTAssertEqual(RouteBridge.item(from: stop).poiId, value.poiId)
        XCTAssertEqual(stop.place.name, value.name)
        XCTAssertEqual(stop.place.address, value.address)
        XCTAssertEqual(stop.label(korean: true).title, value.name)
        XCTAssertNil(stop.label(korean: true).reading)
    }

    func testNewPoiCarriesDisplayFieldsBeforeSaving() {
        var poi = PoiSummary(id: 20, name: "스타벅스", category: "카페", categoryGroup: .food,
                             latitude: 37.28, longitude: 127.01)
        poi.displayName = "Starbucks"
        poi.nameRoman = "Seutabeokseu"
        poi.categoryLabel = "Cafe"
        let stop = RouteGuide.Place(poi: poi).courseEntry.stop
        XCTAssertEqual(stop.label(korean: false).title, "Starbucks")
        XCTAssertEqual(stop.label(korean: false).reading, "스타벅스")
        XCTAssertEqual(stop.label(korean: false).category, "Cafe")
        XCTAssertEqual(stop.place.name, "스타벅스")
        XCTAssertEqual(RouteBridge.item(from: stop).poiId, 20)
    }

    func testTranslationDoesNotChangeOutgoingCourse() {
        let stop = RouteBridge.stop(from: item())
        var course = RouteCourse(title: "코스", days: [RouteDay(stops: [stop])])
        let opened = RouteBridge.outgoing(from: course)
        course.days[0].stops[0].poiText = .init(displayName: "Cafe", nameRoman: nil,
                                              categoryLabel: "Cafe", displayAddress: "Suwon")
        XCTAssertFalse(RouteBridge.changed(from: opened, to: course))
    }

    func testServerTotalAndDwellEditsWithoutGuessingTravel() {
        let stops = [RouteBridge.stop(from: item()), RouteBridge.stop(from: item(2))]
        let estimate = RouteDayEstimate(stops: stops, totalMinutes: 173)
        var day = RouteDay(stops: stops, estimate: estimate)
        XCTAssertEqual(day.estimatedTotalMinutes, 173)
        day.stops[0].stayMinutes += 15
        XCTAssertEqual(day.estimatedTotalMinutes, 188)
        day.stops[0].visited = true
        day.stops[0].place.name = "표시말 변경"
        XCTAssertEqual(day.estimatedTotalMinutes, 188)
    }

    func testCourseBridgeRetainsContractTotalAndWireIsUnchanged() {
        let day = CourseDay(dayNumber: 1, items: [item(), item(2)], dwellMinutes: 120,
                            travelMinutes: 53, totalMinutes: 173, travelBasis: .straightLine)
        let detail = CourseDetail(id: 1, title: "수원", dayCount: 1, status: .upcoming, origin: ._self,
                                  placeCount: 2, createdAt: Date(), updatedAt: Date(), days: [day])
        let course = RouteBridge.course(from: detail)
        XCTAssertEqual(course.days[0].estimatedTotalMinutes, day.totalMinutes)
        XCTAssertEqual(RouteBridge.replace(from: course).days[0].items.map(\.id), [1, 2])
        XCTAssertEqual(RouteBridge.replace(from: course).days[0].items.map(\.dwellMinutes), [60, 60])
        XCTAssertNil(RouteDay(stops: course.days[0].stops,
                              estimate: RouteDayEstimate(stops: course.days[0].stops, totalMinutes: 20))
            .estimatedTotalMinutes, "체류 합보다 작은 잘못된 서버 합계를 표시하지 않는다")
    }

    func testChangedRouteInvalidatesAndRestoredRouteRecoversEstimate() {
        let stops = [RouteBridge.stop(from: item()), RouteBridge.stop(from: item(2))]
        var day = RouteDay(stops: stops, estimate: RouteDayEstimate(stops: stops, totalMinutes: 173))
        day.stops.reverse()
        XCTAssertNil(day.estimatedTotalMinutes)
        day.stops.reverse()
        XCTAssertEqual(day.estimatedTotalMinutes, 173)
        day.stops[0].place.latitude += 0.01
        XCTAssertNil(day.estimatedTotalMinutes)
        day.stops = stops + [RouteBridge.stop(from: item(3))]
        XCTAssertNil(day.estimatedTotalMinutes)
        day.stops = Array(stops.dropLast())
        XCTAssertNil(day.estimatedTotalMinutes)
        XCTAssertNil(RouteDay(stops: stops).estimatedTotalMinutes)
    }
}
