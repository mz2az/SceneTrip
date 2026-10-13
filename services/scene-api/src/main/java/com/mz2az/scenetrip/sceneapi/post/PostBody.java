package com.mz2az.scenetrip.sceneapi.post;

import com.mz2az.scenetrip.sceneapi.api.model.TripPostBlock;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostBlockKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** 블로그 본문의 문단과 첨부 사진 참조를 검증한다(MZ2AZ-428). */
public final class PostBody {
  private static final int MAX_TEXT_BLOCKS = 64;
  private static final int MAX_PHOTOS = 8;
  private static final int MAX_BODY_LENGTH = 5000;
  private static final int MAX_CAPTION_LENGTH = 500;
  private static final int MAX_CREDIT_LENGTH = 1000;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PostBody() {}

  public static final class RejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RejectedException(String message) {
      super(message);
    }
  }

  public static List<TripPostBlock> normalize(
      List<TripPostBlock> blocks, String body, int photoCount) {
    if (blocks == null) {
      return null;
    }
    if (blocks.isEmpty()
        || blocks.size() > MAX_TEXT_BLOCKS + MAX_PHOTOS
        || photoCount < 0
        || photoCount > MAX_PHOTOS
        || body.length() > MAX_BODY_LENGTH) {
      throw invalid();
    }
    List<TripPostBlock> normalized = new ArrayList<>();
    List<String> paragraphs = new ArrayList<>();
    Set<Integer> photos = new HashSet<>();
    for (TripPostBlock block : blocks) {
      if (block == null || block.getKind() == null) {
        throw invalid();
      }
      if (block.getKind() == TripPostBlockKind.TEXT) {
        normalized.add(text(block, paragraphs));
      } else {
        normalized.add(photo(block, photoCount, photos));
      }
    }
    if (paragraphs.isEmpty()
        || paragraphs.size() > MAX_TEXT_BLOCKS
        || !String.join("\n\n", paragraphs).equals(body)
        || photos.size() != photoCount) {
      throw invalid();
    }
    return List.copyOf(normalized);
  }

  private static TripPostBlock text(TripPostBlock block, List<String> paragraphs) {
    if (block.getText() == null
        || block.getText().isBlank()
        || block.getPhotoIndex() != null
        || block.getCaption() != null
        || block.getCredit() != null
        || block.getAiGenerated() != null) {
      throw invalid();
    }
    String text = block.getText().strip();
    paragraphs.add(text);
    return new TripPostBlock(TripPostBlockKind.TEXT).text(text);
  }

  private static TripPostBlock photo(TripPostBlock block, int photoCount, Set<Integer> used) {
    Integer index = block.getPhotoIndex();
    if (block.getText() != null
        || index == null
        || index < 0
        || index >= photoCount
        || !used.add(index)
        || tooLong(block.getCaption(), MAX_CAPTION_LENGTH)
        || tooLong(block.getCredit(), MAX_CREDIT_LENGTH)) {
      throw invalid();
    }
    return new TripPostBlock(TripPostBlockKind.PHOTO)
        .photoIndex(index)
        .caption(block.getCaption())
        .credit(block.getCredit())
        .aiGenerated(block.getAiGenerated());
  }

  public static String encode(List<TripPostBlock> blocks) {
    return blocks == null ? null : JSON.writeValueAsString(blocks);
  }

  public static List<TripPostBlock> decode(String json) {
    return json == null ? null : List.of(JSON.readValue(json, TripPostBlock[].class));
  }

  private static boolean tooLong(String text, int maximum) {
    return text != null && text.length() > maximum;
  }

  private static RejectedException invalid() {
    return new RejectedException("본문의 문단과 사진 배치를 확인해 주세요");
  }
}
