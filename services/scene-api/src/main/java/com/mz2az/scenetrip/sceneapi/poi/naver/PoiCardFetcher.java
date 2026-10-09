package com.mz2az.scenetrip.sceneapi.poi.naver;

import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Candidate;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Match;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Detail;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Outcome;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 출처에 한 번 묻는 순서 — 검색 → 후보 고르기 → (헛돌면 시군구 붙여 재검색) → 상세. 상태가 없다. 링크만 필요하면 상세 앞에서 멈추는 {@link #locate}
 * 를 쓴다.
 *
 * <p>{@link PoiCardService}(단건, 지금)와 {@link PoiCardFiller}(여럿, 뒤에서)가 같이 쓴다. 둘이 서로를 모르게 하려고 여기로 뺐다 —
 * 서비스가 일꾼을 알고 일꾼이 서비스를 알면 순환이라 스프링이 만들지 못한다.
 */
@Component
public class PoiCardFetcher {

  /**
   * 출처에 한 번 물은 결과. 받았으면 {@code card}(찾았든 못 찾았든), 못 받았으면 {@code transientWhy}.
   *
   * @param card 표에 쓸 결과. 못 받았으면 null
   * @param transientWhy 못 받은 이유
   * @param blocked 출처가 막았다
   */
  public record Fetched(NaverCard card, String transientWhy, boolean blocked) {
    public boolean received() {
      return card != null;
    }
  }

  private final NaverPlaceClient client;

  PoiCardFetcher(NaverPlaceClient client) {
    this.client = client;
  }

  /**
   * 검색만으로 고른 결과. 받았으면 {@code match}(찾았든 못 찾았든), 못 받았으면 {@code transientWhy}.
   *
   * @param match 고른 결과. 못 받았으면 null
   * @param transientWhy 못 받은 이유
   * @param blocked 출처가 막았다
   */
  public record Located(Match match, String transientWhy, boolean blocked) {
    public boolean received() {
      return match != null;
    }
  }

  /**
   * 검색 → 후보 고르기 → (헛돌면 시군구 붙여 재검색). <b>상세는 부르지 않는다</b> — 링크(장소 번호)만 필요한 쪽({@link NaverLinks})이 이것만
   * 쓴다. 첫 검색이 헛돌면(후보 0 또는 가장 가까운 것도 far 밖) 이름에 「지역 시군구」를 붙여 한 번 더 — 실측에서 매칭률 +6~11p. 네이버는 같은 이름의 다른
   * 동네 지점을 1 등으로 주는 일이 잦다(「보성식당」 140 km).
   */
  public Located locate(PoiDetail poi) {
    String name = poi.getName();
    PoiCategoryGroup group = poi.getCategoryGroup();
    double lat = poi.getLatitude();
    double lng = poi.getLongitude();

    Outcome<List<Candidate>> first = client.search(name);
    if (!first.ok()) {
      return new Located(null, first.why(), first.blocked());
    }
    Match match = NaverMatcher.pick(name, lat, lng, group, first.value());

    if (!match.found() && wanderedOff(match, group)) {
      String district = district(poi);
      if (district != null) {
        Outcome<List<Candidate>> second = client.search(name + " " + district);
        if (!second.ok()) {
          return new Located(null, second.why(), second.blocked());
        }
        Match retry = NaverMatcher.pick(name, lat, lng, group, second.value());
        if (retry.found()) {
          match = retry;
        }
      }
    }
    return new Located(match, null, false);
  }

  /** 출처에 한 번 묻는다. {@link #locate} 로 고르고, 찾았으면 상세까지. */
  public Fetched fetch(PoiDetail poi) {
    Located located = locate(poi);
    if (!located.received()) {
      return new Fetched(null, located.transientWhy(), located.blocked());
    }
    Match match = located.match();
    if (!match.found()) {
      return new Fetched(
          NaverCard.notFound(poi.getId(), match.why(), NaverMatcher.RULE_VERSION), null, false);
    }
    Outcome<Detail> detail = client.detail(match.candidate().id());
    if (!detail.ok()) {
      return new Fetched(null, detail.why(), detail.blocked());
    }
    Detail d = detail.value();
    return new Fetched(
        new NaverCard(
            poi.getId(),
            true,
            null,
            NaverMatcher.RULE_VERSION,
            null,
            match.candidate().id(),
            d.name(),
            d.category(),
            d.address(),
            d.phone(),
            d.hours(),
            d.score(),
            d.reviewCount(),
            d.blogReviews(),
            d.images(),
            d.url()),
        null,
        false);
  }

  /** 첫 검색이 헛돌았나 — 후보가 없거나, 가장 가까운 후보도 계단 밖. */
  private static boolean wanderedOff(Match match, PoiCategoryGroup group) {
    if (match.nearestMeters() == null) {
      return true;
    }
    int far = group == PoiCategoryGroup.SIGHT || group == PoiCategoryGroup.TRANSIT ? 800 : 150;
    return match.nearestMeters() > far;
  }

  /** 「경기 시흥시」. 둘 중 하나라도 없으면 재검색을 안 한다. */
  private static String district(PoiDetail poi) {
    if (poi.getRegion() == null || poi.getCity() == null) {
      return null;
    }
    return poi.getRegion() + " " + poi.getCity();
  }
}
