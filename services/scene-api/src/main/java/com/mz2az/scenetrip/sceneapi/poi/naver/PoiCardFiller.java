package com.mz2az.scenetrip.sceneapi.poi.naver;

import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 뒤에서 채우는 줄과 일꾼. {@link CardFiller} 의 구현.
 *
 * <p>요청 스레드는 {@link #enqueue} 로 넣고 바로 응답한다. 일꾼 스레드 <b>하나</b>가 줄에서 하나씩 꺼내 출처에 묻고 표에 쓴다 — 일부러 하나다.
 * 둘이면 초당 호출이 배로 늘어 막힐 확률만 오른다. 최소 간격(기본 300 ms, 초당 3 건)을 지킨다.
 *
 * <p>{@link #retryAfterSeconds} 는 어림이다 — 줄 길이 × 최근 20 건의 평균 처리 시간(+ 쉬는 시간). 서버가 바빠지면 평균이 올라가 힌트도
 * 늘어난다. 틀리면 앱의 다음 요청이 다시 계산해 받는다.
 *
 * <p>출처가 막으면(403·429) 한동안 쉬고, 연속 세 번이면 내린다 — 데모 중에 계속 두드려서 더 오래 막히는 것보다 낫다. 재시작하면 다시 켜진다.
 *
 * <p>스레드는 {@link SmartLifecycle} 로 띄운다 — 생성자에서 띄우면 초기화가 끝나기 전의 객체를 다른 스레드가 보고, 종료 때 아무도 멈추지 않는다.
 */
@Component
public class PoiCardFiller implements CardFiller, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(PoiCardFiller.class);

  private static final int CAPACITY = 500;
  private static final int RECENT = 20;
  private static final long DEFAULT_MILLIS = 350;
  private static final int TRIP_AFTER = 3;
  private static final int MAX_HINT_SECONDS = 30;

  private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>(CAPACITY);
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
  private final long[] recentMillis = new long[RECENT];
  private final Object recentLock = new Object();
  private int recentCount;
  private int recentNext;
  private volatile Instant pausedUntil = Instant.EPOCH;
  private final AtomicInteger consecutiveBlocks = new AtomicInteger();
  private final AtomicBoolean tripped = new AtomicBoolean();

  private final com.mz2az.scenetrip.sceneapi.poi.PoiStore pois;
  private final PoiNaverStore store;
  private final PoiCardFetcher fetcher;
  private final Duration minInterval;
  private final Duration pause;
  private volatile Thread worker;

  PoiCardFiller(
      com.mz2az.scenetrip.sceneapi.poi.PoiStore pois,
      PoiNaverStore store,
      PoiCardFetcher fetcher,
      @Value("${scenetrip.naver.min-interval-ms:300}") long minIntervalMs,
      @Value("${scenetrip.naver.pause-seconds:60}") long pauseSeconds) {
    this.pois = pois;
    this.store = store;
    this.fetcher = fetcher;
    this.minInterval = Duration.ofMillis(minIntervalMs);
    this.pause = Duration.ofSeconds(pauseSeconds);
  }

  // ── CardFiller ────────────────────────────────────────────────────────────

  @Override
  public void enqueue(Collection<Long> poiIds) {
    if (tripped.get()) {
      return;
    }
    for (Long id : poiIds) {
      // Set.add 는 이미 있으면 false — 「있나 보고 넣기」가 한 동작이라 두 스레드가 겹쳐도 하나만 들어간다.
      if (!inFlight.add(id)) {
        continue;
      }
      if (!queue.offer(id)) {
        inFlight.remove(id);
        log.warn("카드 줄이 가득 찼다({}) — poi {} 를 버린다. 앱이 다시 묻는다", CAPACITY, id);
      }
    }
  }

  @Override
  public int retryAfterSeconds() {
    long wait = queue.size() * averageMillis();
    long paused = Duration.between(Instant.now(), pausedUntil).toMillis();
    if (paused > 0) {
      wait += paused;
    }
    long seconds = (wait + 999) / 1000;
    return (int) Math.max(1, Math.min(MAX_HINT_SECONDS, seconds));
  }

  @Override
  public void noteBlocked() {
    pausedUntil = Instant.now().plus(pause);
    if (consecutiveBlocks.incrementAndGet() >= TRIP_AFTER && tripped.compareAndSet(false, true)) {
      queue.clear();
      inFlight.clear();
      log.error("출처가 연속 {}회 막았다 — 카드 채우기를 내린다. 재시작하면 다시 켜진다", TRIP_AFTER);
    }
  }

  /** 테스트·진단용. 줄에 서 있는 개수. */
  int queued() {
    return queue.size();
  }

  /** 테스트·진단용. 연속 막힘으로 내려갔는가. */
  boolean tripped() {
    return tripped.get();
  }

  // ── SmartLifecycle ────────────────────────────────────────────────────────

  @Override
  public void start() {
    Thread t = new Thread(this::run, "poi-card-filler");
    t.setDaemon(true);
    worker = t;
    t.start();
  }

  @Override
  public void stop() {
    Thread t = worker;
    if (t != null) {
      t.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    Thread t = worker;
    return t != null && t.isAlive();
  }

  // ── 일꾼 ──────────────────────────────────────────────────────────────────

  private void run() {
    while (!Thread.currentThread().isInterrupted()) {
      Long id;
      try {
        id = queue.take(); // 비어 있으면 여기서 잠든다
      } catch (InterruptedException e) {
        return;
      }
      try {
        sleepUntil(pausedUntil); // 막힌 동안은 꺼낸 것도 보내지 않는다
        long t0 = System.nanoTime();
        handle(id);
        long took = (System.nanoTime() - t0) / 1_000_000;
        record(took);
        long gap = minInterval.toMillis() - took;
        if (gap > 0) {
          Thread.sleep(gap);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        log.warn("카드 채우기 실패 — poi {}", id, e); // 한 건이 일꾼을 죽이지 못한다
      } finally {
        inFlight.remove(id);
      }
    }
  }

  private void handle(long id) {
    Optional<PoiDetail> poi = pois.findDetail(id, null, null);
    if (poi.isEmpty()) {
      return; // 줄에 서 있는 동안 지워졌다
    }
    if (store.find(id, NaverMatcher.RULE_VERSION).isPresent()) {
      return; // 그 사이 단건 경로가 먼저 채웠다
    }
    PoiCardFetcher.Fetched fetched = fetcher.fetch(poi.get());
    if (fetched.received()) {
      store.save(fetched.card());
      consecutiveBlocks.set(0);
    } else if (fetched.blocked()) {
      noteBlocked();
    }
    // 못 받음: 아무것도 하지 않는다. 앱이 다시 물으면 다시 줄에 선다.
  }

  private static void sleepUntil(Instant until) throws InterruptedException {
    long millis = Duration.between(Instant.now(), until).toMillis();
    if (millis > 0) {
      Thread.sleep(millis);
    }
  }

  private void record(long millis) {
    synchronized (recentLock) {
      recentMillis[recentNext] = millis;
      recentNext = (recentNext + 1) % RECENT;
      if (recentCount < RECENT) {
        recentCount++;
      }
    }
  }

  private long averageMillis() {
    synchronized (recentLock) {
      if (recentCount == 0) {
        return DEFAULT_MILLIS;
      }
      long sum = 0;
      for (int i = 0; i < recentCount; i++) {
        sum += recentMillis[i];
      }
      return sum / recentCount;
    }
  }
}
