package com.mz2az.scenetrip.sceneapi.poi.naver;

import com.mz2az.scenetrip.sceneapi.api.model.PoiCard;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCardBatch;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
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
 *   <li>출처에 묻는 순서 자체는 {@link PoiCardFetcher} 에 있다 — 단건과 일꾼({@code PoiCardFiller})이 같이 쓴다.
 * </ul>
 *
 * <p>「못 받음」(타임아웃·차단)은 표에 쓰지 않는다 — 다음에 다시 묻는다. 「없음」은 쓴다 — 다시 물어도 없다.
 */
@Service
public class PoiCardService {

  private static final Logger log = LoggerFactory.getLogger(PoiCardService.class);

  private final PoiStore pois;
  private final PoiNaverStore store;
  private final PoiCardFetcher fetcher;
  private final Optional<CardFiller> filler;

  /** {@code filler} 가 없으면(구현이 아직 없거나 꺼짐) 여럿 조회는 줄에 넣지 못하고 {@code pending} 만 답한다. */
  PoiCardService(
      PoiStore pois, PoiNaverStore store, PoiCardFetcher fetcher, Optional<CardFiller> filler) {
    this.pois = pois;
    this.store = store;
    this.fetcher = fetcher;
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
    PoiCardFetcher.Fetched fetched = fetcher.fetch(poi.get());
    if (fetched.received()) {
      // checked_at 은 DB 가 찍는다 — save 가 저장된 행을 돌려주므로 그것을 내보낸다.
      return Optional.of(toCard(store.save(fetched.card())));
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
