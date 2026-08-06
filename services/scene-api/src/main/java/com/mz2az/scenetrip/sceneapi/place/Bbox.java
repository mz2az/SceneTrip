package com.mz2az.scenetrip.sceneapi.place;

import java.util.Optional;

/**
 * 지도 뷰포트.
 *
 * <p>순서는 {@code minLng,minLat,maxLng,maxLat} 다 — GeoJSON 과 같은 <b>경도-위도</b> 순서다. 위도를 먼저 쓰는 관습(구글 지도의
 * URL 등)과 반대라 뒤집어 보내기 쉬운데, 뒤집으면 오류 없이 엉뚱한 영역이 조회된다.
 */
public record Bbox(double minLng, double minLat, double maxLng, double maxLat) {

  /**
   * 쉼표로 구분된 숫자 넷을 읽는다. 규칙에 맞지 않으면 비어 있는 값을 돌려준다.
   *
   * <p>생성된 인터페이스의 {@code @Pattern} 이 "숫자 4개" 까지는 이미 걸러 준다. 여기서 더 보는 것은 <b>뜻이 통하는가</b>다 — 좌표 범위를
   * 벗어났는지, 최소가 최대보다 큰지. 문법은 맞지만 뜻이 없는 값은 조회하면 언제나 0 건이라, 사용자에게는 "검색 결과가 없다" 로 보인다. 오류로 알려 주는 편이 낫다.
   */
  public static Optional<Bbox> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String[] parts = raw.split(",");
    if (parts.length != 4) {
      return Optional.empty();
    }
    double[] values = new double[4];
    for (int i = 0; i < 4; i++) {
      try {
        values[i] = Double.parseDouble(parts[i].trim());
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    }
    Bbox bbox = new Bbox(values[0], values[1], values[2], values[3]);
    return bbox.isValid() ? Optional.of(bbox) : Optional.empty();
  }

  private boolean isValid() {
    return minLng >= -180
        && maxLng <= 180
        && minLat >= -90
        && maxLat <= 90
        && minLng < maxLng
        // 위도는 뒤집힌 값을 허용하지 않는다. 경도와 달리 날짜변경선을 넘는 상황이 없다.
        && minLat < maxLat;
  }
}
