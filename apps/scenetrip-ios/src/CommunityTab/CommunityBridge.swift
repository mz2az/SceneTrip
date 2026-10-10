import SceneApiClient

extension CommunityPost {
    init(summary: TripPostSummary) {
        self.init(
            id: CommunityRules.identity(summary.id), board: .review, title: summary.title,
            body: summary.excerpt, createdAt: summary.createdAt, courseTitle: summary.course?.title,
            author: summary.author?.nickname, serverId: summary.id, serverIsMine: summary.isMine,
            remotePhotos: summary.coverPhotoUrl.map { [$0] }, detailLoaded: false
        )
    }

    init(detail: TripPostDetail) {
        self.init(
            id: CommunityRules.identity(detail.id), board: .review, title: detail.title,
            body: detail.body, createdAt: detail.createdAt, course: detail.course.map(PostCourse.init),
            author: detail.author?.nickname, serverId: detail.id, serverIsMine: detail.isMine,
            remotePhotos: detail.photoUrls, detailLoaded: true
        )
    }
}

extension PostCourse {
    init(_ course: TripPostCourse) {
        title = course.title
        days = course.days.sorted { $0.dayNumber < $1.dayNumber }.map { day in
            day.stops.map { stop in
                Stop(placeId: stop.placeId, name: stop.name, address: stop.address, type: stop.category,
                     latitude: stop.latitude, longitude: stop.longitude, imageUrl: stop.imageUrl, poiId: stop.poiId,
                     displayName: stop.displayName, nameRoman: stop.nameRoman,
                     displayAddress: stop.displayAddress, categoryLabel: stop.categoryLabel)
            }
        }
    }
}
