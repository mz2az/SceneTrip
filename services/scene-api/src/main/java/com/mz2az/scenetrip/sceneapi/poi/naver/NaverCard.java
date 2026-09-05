package com.mz2az.scenetrip.sceneapi.poi.naver;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code poi_naver} 의 한 행 — 이 POI 를 바깥 출처에서 찾아본 결과 전부.
 *
 * <p>{@code found} 가 거짓이면 {@code why} 만 있고 상세는 전부 null 이다. 참이면 {@code why} 가 null 이고 상세가 있되, 출처에 없는
 * 값은 null 이다. 「아직 안 물어봄」은 이 타입으로 표현하지 않는다 — 행이 없는 것이다.
 *
 * @param poiId 우리 POI
 * @param found 출처에서 같은 곳을 찾았는가
 * @param why 못 찾았을 때 이유 한 줄
 * @param ruleVersion 판정 규칙의 판
 * @param checkedAt 언제 확인했나
 * @param naverId 출처의 장소 id
 * @param name 출처가 부르는 이름
 * @param category 출처의 분류 문구
 * @param address 도로명 주소
 * @param phone 전화
 * @param hours 영업시간 문구
 * @param score 별점. 없을 수 있다
 * @param reviewCount 방문자 리뷰 수
 * @param blogReviews 블로그 리뷰 수
 * @param images 사진 URL, 최대 3
 * @param url 출처의 장소 페이지
 */
public record NaverCard(
    long poiId,
    boolean found,
    String why,
    String ruleVersion,
    OffsetDateTime checkedAt,
    String naverId,
    String name,
    String category,
    String address,
    String phone,
    String hours,
    Double score,
    Integer reviewCount,
    Integer blogReviews,
    List<String> images,
    String url) {

  /** 못 찾은 결과. 상세는 전부 비어 있다. */
  public static NaverCard notFound(long poiId, String why, String ruleVersion) {
    return new NaverCard(
        poiId,
        false,
        why,
        ruleVersion,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        null);
  }
}
