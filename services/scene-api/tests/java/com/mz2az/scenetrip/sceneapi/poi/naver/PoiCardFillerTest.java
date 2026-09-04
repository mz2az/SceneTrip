package com.mz2az.scenetrip.sceneapi.poi.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardFetcher.Fetched;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 일꾼 스레드를 띄우되 출처는 가짜다. 간격·휴식은 짧게 줘서 테스트가 느려지지 않게 한다. 기다림은 폴링 — 조건이 되면 바로 끝난다. */
@DisplayName("PoiCardFiller — 줄과 일꾼")
class PoiCardFillerTest {

  private PoiStore pois;
  private PoiNaverStore store;
  private PoiCardFetcher fetcher;
  private PoiCardFiller filler;

  private static PoiDetail poi(long id) {
    return new PoiDetail(id, "가게 " + id, "한식", PoiCategoryGroup.FOOD, 37.5, 127.0);
  }

  @BeforeEach
  void setUp() {
    pois = mock(PoiStore.class);
    store = mock(PoiNaverStore.class);
    fetcher = mock(PoiCardFetcher.class);
    when(pois.findDetail(anyLong(), any(), any()))
        .thenAnswer(inv -> Optional.of(poi(inv.getArgument(0))));
    when(store.find(anyLong(), anyString())).thenReturn(Optional.empty());
    filler = new PoiCardFiller(pois, store, fetcher, 10, 1); // 간격 10 ms · 휴식 1 초
    filler.start();
  }

  @AfterEach
  void tearDown() {
    filler.stop();
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 3_000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("3 초 안에 조건이 되지 않았다");
      }
      Thread.sleep(20);
    }
  }

  @Test
  @DisplayName("넣으면 일꾼이 꺼내 출처에 묻고 표에 쓴다")
  void processesQueue() throws Exception {
    when(fetcher.fetch(any())).thenAnswer(inv -> found(((PoiDetail) inv.getArgument(0)).getId()));

    filler.enqueue(List.of(1L, 2L, 3L));

    await(() -> filler.queued() == 0);
    await(() -> mockedSaves() == 3);
    verify(store, times(3)).save(any());
  }

  @Test
  @DisplayName("같은 id 는 한 번만 — 줄에 있거나 처리 중이면 안 넣는다")
  void dedupes() throws Exception {
    when(fetcher.fetch(any())).thenAnswer(inv -> found(((PoiDetail) inv.getArgument(0)).getId()));

    filler.enqueue(List.of(7L, 7L, 7L));
    filler.enqueue(List.of(7L));

    await(() -> mockedSaves() >= 1);
    Thread.sleep(100);
    verify(store, times(1)).save(any());
  }

  @Test
  @DisplayName("그 사이 단건 경로가 채웠으면 출처를 부르지 않는다")
  void skipsAlreadyFilled() throws Exception {
    when(store.find(anyLong(), anyString()))
        .thenReturn(Optional.of(NaverCard.notFound(5L, "이미", NaverMatcher.RULE_VERSION)));

    filler.enqueue(List.of(5L));

    await(() -> filler.queued() == 0);
    Thread.sleep(50);
    verify(fetcher, never()).fetch(any());
  }

  @Test
  @DisplayName("못 받음이면 아무것도 쓰지 않는다 — 다음에 다시 묻는다")
  void transientFailureWritesNothing() throws Exception {
    when(fetcher.fetch(any())).thenReturn(new Fetched(null, "타임아웃", false));

    filler.enqueue(List.of(9L));

    await(() -> filler.queued() == 0);
    Thread.sleep(50);
    verify(store, never()).save(any());
    assertThat(filler.tripped()).isFalse();
  }

  @Test
  @DisplayName("힌트 — 줄 길이 × 평균 처리 시간, 1~30 초")
  void hintFollowsQueueAndSpeed() {
    // 아직 처리한 게 없다 → 기본 350 ms. 줄이 비었으면 최소 1 초.
    assertThat(filler.retryAfterSeconds()).isEqualTo(1);
    // 막혔으면 쉬는 시간이 더해진다 (휴식 1 초)
    filler.noteBlocked();
    assertThat(filler.retryAfterSeconds()).isBetween(1, 2);
  }

  @Test
  @DisplayName("막히면 쉬고, 연속 세 번이면 내린다 — 그 뒤 enqueue 는 무시")
  void tripsAfterThreeBlocks() throws Exception {
    when(fetcher.fetch(any())).thenReturn(new Fetched(null, "HTTP 429", true));

    filler.enqueue(List.of(1L));
    await(() -> filler.queued() == 0);
    Thread.sleep(50);
    assertThat(filler.tripped()).as("한 번은 쉬기만").isFalse();

    filler.noteBlocked();
    filler.noteBlocked();
    assertThat(filler.tripped()).isTrue();

    filler.enqueue(List.of(2L, 3L));
    assertThat(filler.queued()).isZero();
  }

  private int mockedSaves() {
    return org.mockito.Mockito.mockingDetails(store).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("save"))
        .mapToInt(i -> 1)
        .sum();
  }

  private static Fetched found(long id) {
    return new Fetched(
        new NaverCard(
            id,
            true,
            null,
            NaverMatcher.RULE_VERSION,
            null,
            "n" + id,
            "이름",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            "https://map.naver.com/p/entry/place/n" + id),
        null,
        false);
  }
}
