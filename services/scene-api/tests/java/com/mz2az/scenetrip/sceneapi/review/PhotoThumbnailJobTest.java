package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mz2az.scenetrip.sceneapi.thumbnail.ThumbnailRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 사진 작은 판 작업의 고정값 — 계획 {@code photo-thumbnails.md} §2-1(MZ2AZ-561).
 *
 * <p>명세: 1 분마다, 한 차례에 최대 20 장, 실패는 3 번까지. 저장소가 없는 환경에서는 아무것도 하지 않는다({@link StorageDeletionJob} 과 같은
 * 모양). 어떤 줄을 고르는지·키를 적는지는 통합 레인({@code PhotoThumbnailJobIntegrationTest})이 실제 DB 로 본다.
 */
@DisplayName("PhotoThumbnailJob — 한 차례 20 장, 시도 3 번, 저장소 없으면 0")
class PhotoThumbnailJobTest {

  @Test
  @DisplayName("한 차례 최대 20 장")
  void batchIsTwenty() {
    assertThat(PhotoThumbnailJob.BATCH).isEqualTo(20);
  }

  @Test
  @DisplayName("시도 한도는 규칙의 3")
  void attemptLimit() {
    assertThat(ThumbnailRules.MAX_ATTEMPTS).isEqualTo(3);
  }

  @Test
  @DisplayName("저장소가 없으면(available=false) 0 이고 DB 도 저장소도 부르지 않는다")
  void unavailableStorageIsNoop() {
    JdbcClient jdbc = mock(JdbcClient.class);
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    PhotoStorage storage = mock(PhotoStorage.class);
    when(storage.available()).thenReturn(false);

    assertThat(new PhotoThumbnailJob(jdbc, transactions, storage).run()).isZero();

    verifyNoInteractions(jdbc, transactions);
  }
}
