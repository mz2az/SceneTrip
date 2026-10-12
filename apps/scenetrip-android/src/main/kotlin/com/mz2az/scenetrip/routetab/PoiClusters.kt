package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** 현재 받은 POI를 지도 축척의 64dp 반경으로 묶고 원본 구성원을 보존한다. */
object PoiClusters {
    private const val RADIUS = 64.0
    private const val TILE_SIZE = 256.0
    private const val MAXIMUM_ZOOM = 21.0
    private const val MAXIMUM_LATITUDE = 85.05112878

    data class Cluster(
        val places: List<GuidePlace>,
    ) {
        val latitude: Double get() = places.map { it.latitude }.average()
        val longitude: Double get() = places.map { it.longitude }.average()

        fun opensList(
            zoom: Double,
            maximumZoom: Double,
        ): Boolean {
            val first = places.firstOrNull() ?: return false
            return zoom >= maximumZoom ||
                places.all {
                    it.latitude == first.latitude && it.longitude == first.longitude
                }
        }
    }

    private data class Cell(
        val column: Int,
        val row: Int,
    )

    private data class Point(
        val x: Double,
        val y: Double,
    ) {
        val cell: Cell get() = Cell(floor(x / RADIUS).toInt(), floor(y / RADIUS).toInt())

        fun distanceSquared(other: Point) = (x - other.x).pow(2) + (y - other.y).pow(2)
    }

    fun make(
        places: List<GuidePlace>,
        zoom: Double,
        pickedId: Long? = null,
    ): List<Cluster> {
        if (!zoom.isFinite()) return emptyList()
        val width = TILE_SIZE * 2.0.pow(zoom.coerceIn(0.0, MAXIMUM_ZOOM))
        val groups = mutableListOf<MutableList<GuidePlace>>()
        val anchors = mutableListOf<Point>()
        val buckets = mutableMapOf<Cell, MutableList<Int>>()
        val seen = mutableSetOf<Long>()
        for (place in places.sortedBy { it.id.toString() }) {
            val point = project(place, width) ?: continue
            if (!seen.add(place.id)) continue
            val match = nearest(point, anchors, buckets)
            if (place.id != pickedId && match != null) {
                groups[match].add(place)
            } else {
                val index = groups.size
                groups.add(mutableListOf(place))
                anchors.add(point)
                if (place.id != pickedId) buckets.getOrPut(point.cell) { mutableListOf() }.add(index)
            }
        }
        return groups.map { Cluster(it.toList()) }
    }

    private fun project(
        place: GuidePlace,
        width: Double,
    ): Point? {
        if (!place.latitude.isFinite() || !place.longitude.isFinite() ||
            abs(place.latitude) > MAXIMUM_LATITUDE || abs(place.longitude) > 180
        ) {
            return null
        }
        val sine = sin(place.latitude * Math.PI / 180)
        return Point(
            (place.longitude + 180) / 360 * width,
            (0.5 - ln((1 + sine) / (1 - sine)) / (4 * Math.PI)) * width,
        )
    }

    private fun nearest(
        point: Point,
        anchors: List<Point>,
        buckets: Map<Cell, List<Int>>,
    ): Int? {
        var best: Int? = null
        var distance = RADIUS * RADIUS
        for (column in -1..1) {
            for (row in -1..1) {
                val cell = Cell(point.cell.column + column, point.cell.row + row)
                for (index in buckets[cell].orEmpty()) {
                    val gap = point.distanceSquared(anchors[index])
                    if (gap < distance || (gap == distance && index < (best ?: Int.MAX_VALUE))) {
                        best = index
                        distance = gap
                    }
                }
            }
        }
        return best
    }
}
