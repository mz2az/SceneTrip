package com.mz2az.scenetrip.sceneapi.limit;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메모리 위의 {@link UsageStore} — 단위 레인이 DB 없이 {@link PaidQuota} 를 진짜로 돌린다.
 *
 * <p>원자성은 {@link ConcurrentHashMap#merge} 로 지킨다(DB 의 UPSERT 와 같은 약속: 늘린 뒤의 수를 돌려준다, 0 아래로 내려가지
 * 않는다). 창의 열쇠는 시작 <b>순간</b>이다 — 같은 시각을 다른 오프셋으로 적어도 같은 창이어야 한다(DB 의 timestamptz 와 같다).
 */
public class InMemoryUsageStore extends UsageStore {

  private final Map<String, Integer> counts = new ConcurrentHashMap<>();
  private volatile int purges;

  public InMemoryUsageStore() {
    super(null);
  }

  private static String key(String subject, String feature, OffsetDateTime windowStart) {
    return subject + "|" + feature + "|" + windowStart.toInstant();
  }

  @Override
  public int increment(String subject, String feature, OffsetDateTime windowStart) {
    return counts.merge(key(subject, feature, windowStart), 1, Integer::sum);
  }

  @Override
  public void decrement(String subject, String feature, OffsetDateTime windowStart) {
    counts.computeIfPresent(key(subject, feature, windowStart), (k, n) -> n > 0 ? n - 1 : 0);
  }

  @Override
  public void purgeOld() {
    purges++;
  }

  /** 그 창의 지금 수. 없으면 0. */
  public int count(String subject, String feature, OffsetDateTime windowStart) {
    return counts.getOrDefault(key(subject, feature, windowStart), 0);
  }

  /**
   * 기능 하나의 모든 창 합계 — 「세지 않았다」 를 창을 몰라도 확인한다. 표의 {@code feature} 값은 {@code <기능>:<창 종류>} ({@code
   * guide-chat:hour} …)이므로 {@code <기능>:} 으로 시작하는 줄을 모두 더한다.
   */
  public int total(String subject, String feature) {
    String prefix = subject + "|" + feature + ":";
    return counts.entrySet().stream()
        .filter(e -> e.getKey().startsWith(prefix))
        .mapToInt(Map.Entry::getValue)
        .sum();
  }

  public int purges() {
    return purges;
  }

  public void clear() {
    counts.clear();
    purges = 0;
  }
}
