package com.mz2az.scenetrip.sceneapi.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.api.model.TripPostBlock;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostBlockKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("블로그 본문 — 평문과 문단·사진 순서가 같은 글이어야 한다")
class PostBodyTest {
  @Test
  void omittedBlocksRemainCompatible() {
    assertThat(PostBody.normalize(null, "옛 글", 2)).isNull();
  }

  @Test
  void orderedBlocksKeepCaptionCreditAndAiLabel() {
    List<TripPostBlock> blocks =
        List.of(
            text(" 첫 문단 "),
            photo(1).caption("경복궁").credit("출처").aiGenerated(true),
            text("둘째 문단"),
            photo(0));
    List<TripPostBlock> normalized = PostBody.normalize(blocks, "첫 문단\n\n둘째 문단", 2);
    assertThat(normalized.get(0).getText()).isEqualTo("첫 문단");
    assertThat(normalized.get(1).getPhotoIndex()).isEqualTo(1);
    assertThat(normalized.get(1).getCredit()).isEqualTo("출처");
    assertThat(normalized.get(1).getAiGenerated()).isTrue();
  }

  @Test
  void emptyLayoutAndMissingTextAreRejected() {
    rejected(List.of(), "본문", 0);
    rejected(List.of(photo(0)), "본문", 1);
    rejected(List.of(text("   ")), "본문", 0);
    rejected(List.of(new TripPostBlock()), "본문", 0);
  }

  @Test
  void inconsistentBodyAndMetadataOnTextAreRejected() {
    rejected(List.of(text("다른 본문")), "본문", 0);
    rejected(List.of(text("본문").photoIndex(0)), "본문", 0);
    rejected(List.of(text("본문").caption("설명")), "본문", 0);
    rejected(List.of(text("본문").credit("출처")), "본문", 0);
    rejected(List.of(text("본문").aiGenerated(false)), "본문", 0);
  }

  @Test
  void everyPhotoMustBeReferencedOnceWithinRange() {
    rejected(List.of(text("본문"), photo(-1)), "본문", 1);
    rejected(List.of(text("본문"), photo(1)), "본문", 1);
    rejected(List.of(text("본문"), photo(0), photo(0)), "본문", 2);
    rejected(List.of(text("본문")), "본문", 1);
    rejected(List.of(text("본문"), new TripPostBlock(TripPostBlockKind.PHOTO)), "본문", 1);
    rejected(List.of(text("본문"), photo(0).text("사진 대신 글")), "본문", 1);
  }

  @Test
  void jsonRoundTripKeepsSpecialTextAndAllPhotoMetadata() {
    List<TripPostBlock> blocks =
        List.of(
            text("따옴표 \"와 '\n줄바꿈"),
            photo(0).caption("바다의 '빛'").credit("원본: 한국관광공사\n공공누리 제1유형").aiGenerated(true));
    assertThat(PostBody.decode(PostBody.encode(blocks))).isEqualTo(blocks);
    assertThat(PostBody.decode(null)).isNull();
  }

  @Test
  void limitsApplyBeforeStorageOrPhotoMove() {
    rejected(List.of(text("본문"), photo(0).caption("가".repeat(501))), "본문", 1);
    rejected(List.of(text("본문"), photo(0).credit("가".repeat(1001))), "본문", 1);
    List<TripPostBlock> blocks = new ArrayList<>();
    for (int i = 0; i < 65; i++) {
      blocks.add(text("문단"));
    }
    rejected(blocks, "문단\n\n".repeat(64) + "문단", 0);
  }

  private static void rejected(List<TripPostBlock> blocks, String body, int photos) {
    assertThatThrownBy(() -> PostBody.normalize(blocks, body, photos))
        .isInstanceOf(PostBody.RejectedException.class);
  }

  private static TripPostBlock text(String value) {
    return new TripPostBlock(TripPostBlockKind.TEXT).text(value);
  }

  private static TripPostBlock photo(int index) {
    return new TripPostBlock(TripPostBlockKind.PHOTO).photoIndex(index);
  }
}
