package com.mz2az.scenetrip.data

/**
 * 받은 위치 한 건의 원본 — 정확도와 시각만 있으면 고르는 규칙을 돌릴 수 있다.
 *
 * iOS `CLLocation` 에 해당한다. 규칙을 `android.location.Location` 에서 떼어 두면 JVM 단위 시험이
 * 프레임워크 없이 돈다(`LocationFixTests`). [accuracyMeters] 가 음수면 정확도를 모르는(무효) 값이다.
 */
data class LocationReading(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timeMillis: Long,
) {
    fun metersTo(other: LocationReading): Double = haversineKm(latitude, longitude, other.latitude, other.longitude) * 1000
}

/**
 * 받은 위치 중 **무엇을 내 자리로 쓸지** 정하는 규칙 (MZ2AZ-509). iOS `Models/LocationFix.swift`
 * (PR #327) 의 값과 판단을 그대로 옮겼다 — 화면 쪽은 이 규칙만 부르고 판단을 따로 들지 않는다.
 *
 * 도심에서 처음 오는 값은 와이파이·기지국으로 잡은 대략값이라 몇백 m 씩 어긋난다. 그 값에 멈추지
 * 않고 최고 정확도로 계속 받으며 더 나은 값이 오면 갈아탄다.
 */
object LocationFix {
    /** 이보다 오래된 값은 버린다 — 앱을 켜자마자 오는 캐시 값이 지난 자리일 수 있다. */
    const val MAX_AGE_MS = 10_000L

    /** 이 정도면 더 기다릴 것 없이 쓴다(한 번 받기의 조기 종료), 단위 m. */
    const val GOOD_ENOUGH_METERS = 20f

    /** 「내 위치」를 누른 뒤 더 나은 값이 오면 한 번 다시 맞추는 시간. */
    const val SETTLE_WINDOW_MS = 5_000L

    /** 한 번 받기가 기다리는 최대 시간. 그 안에 [GOOD_ENOUGH_METERS] 가 안 오면 가장 나은 값을 쓴다. */
    const val ONE_SHOT_WAIT_MS = 3_000L

    /** 여행 중 이보다 흐린 값은 버린다 — 이미 자리를 알면 튀는 점만 만든다, 단위 m. */
    const val TRIP_MAX_ACCURACY_METERS = 100f

    /** 다시 맞출 만큼 옮겨졌다고 보는 거리, 단위 m. */
    const val RECENTER_METERS = 20.0

    /** 쓸 수 있는 값인가 — 정확도가 음수면 무효, 너무 오래됐으면 지난 자리다. */
    fun isUsable(
        reading: LocationReading,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = reading.accuracyMeters >= 0 && nowMillis - reading.timeMillis <= MAX_AGE_MS

    /**
     * 지금 보여 주는 값을 새 값으로 바꿀까.
     *
     * 더 정확하면 바꾼다. 조금 덜 정확해도 **더 새것**이면 바꾼다 — 사람은 움직이고, 정확도가
     * 조금 흔들릴 때마다 옛 자리에 붙어 있으면 점이 안 따라온다. 다만 확 흐려진 값(실내로 들어가
     * 기지국 값으로 떨어진 것)은 버틴다.
     */
    fun shouldReplace(
        current: LocationReading?,
        new: LocationReading,
    ): Boolean {
        if (current == null) return true
        if (new.accuracyMeters <= current.accuracyMeters) return true
        if (new.timeMillis <= current.timeMillis) return false
        return new.accuracyMeters <= maxOf(current.accuracyMeters * 1.5f, GOOD_ENOUGH_METERS)
    }

    /** 「내 위치」로 옮긴 카메라를 다시 맞출 만한가 — 더 정확해졌고 화면에서 티가 날 만큼(20 m) 옮겨졌다. */
    fun worthRecentering(
        shown: LocationReading,
        new: LocationReading,
    ): Boolean = new.accuracyMeters < shown.accuracyMeters && new.metersTo(shown) > RECENTER_METERS

    /** 여행 중 받은 값을 쓸까 — 처음 값은 무엇이든 쓰고, 그다음부터는 너무 흐린 값을 버린다. */
    fun acceptsTripFix(
        new: LocationReading,
        hasFix: Boolean,
    ): Boolean = !hasFix || new.accuracyMeters <= TRIP_MAX_ACCURACY_METERS
}
