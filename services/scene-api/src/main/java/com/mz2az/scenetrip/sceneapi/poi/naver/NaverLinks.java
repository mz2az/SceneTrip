package com.mz2az.scenetrip.sceneapi.poi.naver;

import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 편의시설의 네이버 장소 링크(ADR 0021, MZ2AZ-373) — 상세의 {@code naverPlaceUrl}.
 *
 * <p>네이버에서 <b>장소 번호만</b> 찾는다. 검색 결과로 같은 곳을 고르고({@link PoiCardFetcher#locate}), 상세(사진·평점·영업시간)는 부르지
 * 않는다. 찾은 번호는 {@code poi_naver} 에 둔다 — 찾았든 「없음」이든 저장해 다시 묻지 않는다.
 *
 * <p><b>절대 실패로 끝내지 않는다.</b> 꺼져 있거나, 막혔거나, 느리면 비어 있다 — 앱은 이름 검색 링크로 넘긴다. 상세 화면이 바깥 사정으로 죽으면 안 된다.
 * 네이버가 막으면(403·429) 한동안 묻지 않는다 — 막힌 동안 상세를 열 때마다 두드리지 않으려고.
 */
@Component
public class NaverLinks {

  private static final Logger log = LoggerFactory.getLogger(NaverLinks.class);

  private static final String PLACE_PAGE = "https://map.naver.com/p/entry/place/";

  /** 네이버 장소 번호는 숫자뿐이다. 바깥에서 온 값이라 주소에 붙이기 전에 꼴을 본다. */
  private static final Pattern NAVER_ID = Pattern.compile("\\d{1,20}");

  private final boolean enabled;
  private final PoiNaverStore store;
  private final PoiCardFetcher fetcher;
  private final Duration pause;
  private volatile Instant pausedUntil = Instant.EPOCH;

  NaverLinks(
      @Value("${scenetrip.naver.link-lookup.enabled:false}") boolean enabled,
      PoiNaverStore store,
      PoiCardFetcher fetcher,
      @Value("${scenetrip.naver.pause-seconds:60}") long pauseSeconds) {
    this.enabled = enabled;
    this.store = store;
    this.fetcher = fetcher;
    this.pause = Duration.ofSeconds(pauseSeconds);
  }

  /** 이 편의시설의 네이버 장소 화면. 모르면 비어 있다. */
  public Optional<URI> placeUrl(PoiDetail poi) {
    if (!enabled) {
      return Optional.empty();
    }
    Optional<NaverCard> cached = store.find(poi.getId(), NaverMatcher.RULE_VERSION);
    if (cached.isPresent()) {
      return link(cached.get());
    }
    if (Instant.now().isBefore(pausedUntil)) {
      return Optional.empty();
    }

    PoiCardFetcher.Located located = fetcher.locate(poi);
    if (!located.received()) {
      // 「못 받음」은 저장하지 않는다 — 다음에 다시 묻는다.
      if (located.blocked()) {
        pausedUntil = Instant.now().plus(pause);
      }
      return Optional.empty();
    }
    NaverMatcher.Match match = located.match();
    NaverCard row =
        match.found() && NAVER_ID.matcher(match.candidate().id()).matches()
            ? NaverCard.linkOnly(
                poi.getId(),
                match.candidate().id(),
                NaverMatcher.RULE_VERSION,
                PLACE_PAGE + match.candidate().id())
            : NaverCard.notFound(
                poi.getId(),
                match.found() ? "장소 번호의 꼴이 이상하다" : match.why(),
                NaverMatcher.RULE_VERSION);
    try {
      store.save(row);
    } catch (RuntimeException e) {
      // 저장을 못 해도 이번 응답에는 싣는다 — 다음에 다시 찾을 뿐이다.
      log.warn("네이버 링크를 저장하지 못했다 — poi {}", poi.getId(), e);
    }
    return link(row);
  }

  /** 찾은 행이면 장소 화면 주소. 번호에서 다시 만든다 — 옛 카드 행의 {@code url} 칸을 믿지 않는다. */
  private static Optional<URI> link(NaverCard card) {
    if (!card.found() || card.naverId() == null || !NAVER_ID.matcher(card.naverId()).matches()) {
      return Optional.empty();
    }
    return Optional.of(URI.create(PLACE_PAGE + card.naverId()));
  }
}
