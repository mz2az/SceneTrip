package com.mz2az.scenetrip.sceneapi.navigation;

/**
 * 위도·경도 한 쌍.
 *
 * <p>길찾기는 좌표가 넷(출발·도착·승차·하차) 오간다. {@code latitude, longitude} 를 따로 넘기면 순서가 한 번은 뒤집힌다 — 카카오는 {@code
 * x} 가 경도, {@code y} 가 위도이고 GeoJSON 은 {@code [경도, 위도]} 인데 우리 계약은 {@code latitude, longitude} 다. 한
 * 타입에 묶어 두면 뒤집힐 자리가 이 파일 안으로 좁혀진다.
 */
public record Coordinate(double lat, double lng) {

  private static final double EARTH_RADIUS_METERS = 6_371_000.0;

  /**
   * 두 점 사이 직선거리(m). 하버사인.
   *
   * <p>실제 걷는 거리가 아니다 — 강·철길이 사이에 끼면 몇 배 차이 난다. 「대중교통을 물어볼지 말지」 같은 굵은 판단에만 쓴다.
   */
  public double distanceMetersTo(Coordinate other) {
    double deltaLat = Math.toRadians(other.lat - lat);
    double deltaLng = Math.toRadians(other.lng - lng);
    double a =
        Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
            + Math.cos(Math.toRadians(lat))
                * Math.cos(Math.toRadians(other.lat))
                * Math.sin(deltaLng / 2)
                * Math.sin(deltaLng / 2);
    return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
  }
}
