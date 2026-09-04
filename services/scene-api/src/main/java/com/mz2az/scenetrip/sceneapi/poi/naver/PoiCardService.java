package com.mz2az.scenetrip.sceneapi.poi.naver;

import com.mz2az.scenetrip.sceneapi.api.model.PoiCard;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCardBatch;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Candidate;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Match;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Detail;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Outcome;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 편의시설 카드 — 표·매처·클라이언트를 「한 POI 를 처리하는 순서」로 잇는다.
 *
 * <p>세 갈래 —
 *
 * <ul>
 *   <li>{@link #card} 단건. 표에 없으면 <b>지금</b> 출처에 묻고 기다렸다 준다(≈0.35 초). 핀을 누른 사용자는 그 한 곳을 지금 보려는 것이다.
 *   <li>{@link #cards} 여럿. 표에 있는 것만 주고 없는 것은 {@code pending} 으로 표시해 줄에 넣는다. 출처를 부르지 않는다.
 *   <li>{@link #fetch} 출처에 한 번 묻는 순서 — 검색 → 후보 고르기 → (헛돌면 시군구 붙여 재검색) → 상세. 단건과 일꾼이 같이 쓴다.
 * </ul>
 *
 * <p>「못 받음」(타임아웃·차단)은 표에 쓰지 않는다 — 다음에 다시 묻는다. 「없음」은 쓴다 — 다시 물어도 없다.
 */
@Service
public class PoiCardService {

  private static final Logger log = LoggerFactory.getLogger(PoiCardService.class);

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

  private final PoiStore pois;
  private final PoiNaverStore store;
  private final NaverPlaceClient client;
  private final Optional<CardFiller> filler;

  /** {@code filler} 가 없으면(구현이 아직 없거나 꺼짐) 여럿 조회는 줄에 넣지 못하고 {@code pending} 만 답한다. */
  PoiCardService(
      PoiStore pois, PoiNaverStore store, NaverPlaceClient client, Optional<CardFiller> filler) {
    this.pois = pois;
    this.store = store;
    this.client = client;
    this.filler = filler;
  }

  /** 단건. POI 가 없으면 비어 있다 — 404 는 컨트롤러의 몫. */
  public Optional<PoiCard> card(long poiId) {
    Optional<PoiDetail> poi = pois.findDetail(poiId, null, null);
    if (poi.isEmpty()) {
      return Optional.empty();
    }
    Optional<NaverCard> cached = store.find(poiId, NaverMatcher.RULE_VERSION);
    if (cached.isPresent()) {
      return Optional.of(toCard(cached.get()));
    }
    Fetched fetched = fetch(poi.get());
    if (fetched.received()) {
      store.save(fetched.card());
      return Optional.of(toCard(fetched.card()));
    }
    if (fetched.blocked()) {
      filler.ifPresent(CardFiller::noteBlocked);
    }
    return Optional.of(
        new PoiCard(poiId)
            .found(false)
            .why(fetched.transientWhy())
            .checkedAt(OffsetDateTime.now()));
  }

  /** 여럿. 요청한 순서대로, 요청한 개수만큼. 출처를 부르지 않는다. */
  public PoiCardBatch cards(List<Long> poiIds) {
    Set<Long> existing = pois.existingIds(poiIds);
    Map<Long, NaverCard> cached = store.findAll(existing, NaverMatcher.RULE_VERSION);

    List<PoiCard> items = new ArrayList<>(poiIds.size());
    List<Long> missing = new ArrayList<>();
    for (long id : poiIds) {
      if (!existing.contains(id)) {
        items.add(
            new PoiCard(id).found(false).why("그 id 의 장소가 없다").checkedAt(OffsetDateTime.now()));
      } else if (cached.containsKey(id)) {
        items.add(toCard(cached.get(id)));
      } else {
        items.add(new PoiCard(id).pending(true));
        missing.add(id);
      }
    }

    PoiCardBatch batch = new PoiCardBatch(items);
    if (!missing.isEmpty()) {
      List<Long> distinct = missing.stream().distinct().collect(Collectors.toList());
      if (filler.isPresent()) {
        filler.get().enqueue(distinct);
        batch.retryAfterSeconds(filler.get().retryAfterSeconds());
      } else {
        // 일꾼이 없으면 채워질 일이 없다. 상한을 주어 앱이 세 번 묻고 그만두게 한다.
        batch.retryAfterSeconds(30);
      }
    }
    return batch;
  }

  /**
   * 출처에 한 번 묻는다. 검색 → 후보 고르기 → 상세. 첫 검색이 헛돌면(후보 0 또는 가장 가까운 것도 far 밖) 이름에 「지역 시군구」를 붙여 한 번 더 — 실측에서
   * 매칭률 +6~11p. 네이버는 같은 이름의 다른 동네 지점을 1 등으로 주는 일이 잦다(「보성식당」 140 km).
   */
  public Fetched fetch(PoiDetail poi) {
    String name = poi.getName();
    PoiCategoryGroup group = poi.getCategoryGroup();
    double lat = poi.getLatitude();
    double lng = poi.getLongitude();

    Outcome<List<Candidate>> first = client.search(name);
    if (!first.ok()) {
      return new Fetched(null, first.why(), first.blocked());
    }
    Match match = NaverMatcher.pick(name, lat, lng, group, first.value());

    if (!match.found() && wanderedOff(match, group)) {
      String district = district(poi);
      if (district != null) {
        Outcome<List<Candidate>> second = client.search(name + " " + district);
        if (!second.ok()) {
          return new Fetched(null, second.why(), second.blocked());
        }
        Match retry = NaverMatcher.pick(name, lat, lng, group, second.value());
        if (retry.found()) {
          match = retry;
        }
      }
    }

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

  /** 표의 행 → 계약. 판·출처 id 는 뺀다. 없는 값은 실리지 않는다. */
  static PoiCard toCard(NaverCard c) {
    PoiCard card = new PoiCard(c.poiId()).found(c.found()).checkedAt(c.checkedAt());
    if (!c.found()) {
      return card.why(c.why());
    }
    return card.name(c.name())
        .category(c.category())
        .address(c.address())
        .phone(c.phone())
        .hours(c.hours())
        .score(c.score())
        .reviewCount(c.reviewCount())
        .blogReviews(c.blogReviews())
        .images(c.images().stream().map(PoiCardService::uri).filter(u -> u != null).toList())
        .naverUrl(uri(c.url()));
  }

  private static URI uri(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return URI.create(value);
    } catch (IllegalArgumentException e) {
      log.debug("URI 가 아니다: {}", value);
      return null;
    }
  }
}
