package com.mz2az.scenetrip.sceneapi.thumbnail;

import java.util.List;

/**
 * 작은 사진 규칙 — 크기 하나, 키 이름, 시도 한도(계획 {@code docs/project/plans/photo-thumbnails.md} §2-2·§9-3, ADR 0030,
 * MZ2AZ-561).
 *
 * <p>사용자 사진 작업({@code PhotoThumbnailJob})과 촬영지 사진 도구({@link PlaceThumbnails})가 같은 규칙을 쓴다.
 */
public final class ThumbnailRules {

  /** 긴 변(px). 가장 큰 목록 칸(350px)을 화면 2 배에서 덮는다. */
  public static final int LONG_SIDE = 720;

  /** JPEG 품질. */
  public static final float JPEG_QUALITY = 0.80f;

  /** 만들기에 이만큼 실패하면 더 하지 않는다 — 응답은 원본으로 간다. */
  public static final int MAX_ATTEMPTS = 3;

  /** 크기를 이름에 넣은 하위 폴더 — 나중에 다른 크기를 같은 자리에 더할 수 있다. */
  static final String FOLDER = "thumb-" + LONG_SIDE + "/";

  /** 작은 사진을 둘 수 있는 자리 — 후기·리뷰 사진(사용자 버킷), 촬영지 사진(공용 버킷). */
  static final List<String> PREFIXES = List.of("posts/", "reviews/", "place/");

  /** 그림의 가로·세로(px). */
  public record Size(int width, int height) {}

  private ThumbnailRules() {}

  /**
   * 작은 사진의 크기 — 긴 변을 {@link #LONG_SIDE} 로, 비율 그대로(짧은 변은 반올림, 최소 1). 긴 변이 이미 그 이하면 그대로다(키우지 않는다).
   *
   * @throws IllegalArgumentException 가로나 세로가 0 이하
   */
  public static Size targetSize(int width, int height) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("그림 크기가 올바르지 않습니다: " + width + "×" + height);
    }
    int longSide = Math.max(width, height);
    if (longSide <= LONG_SIDE) {
      return new Size(width, height);
    }
    int shortSide = Math.min(width, height);
    int scaled = (int) Math.max(1, Math.round((double) shortSide * LONG_SIDE / longSide));
    return width >= height ? new Size(LONG_SIDE, scaled) : new Size(scaled, LONG_SIDE);
  }

  /**
   * 원본 키의 작은 사진 키 — 원래 자리 아래 {@code thumb-720/}, 하위 경로는 그대로, 확장자는 언제나 {@code .jpg}(작은 사진은 늘
   * JPEG). 예: {@code posts/a.jpg} → {@code posts/thumb-720/a.jpg}, {@code place/x/b.png} → {@code
   * place/thumb-720/x/b.jpg}.
   *
   * @throws IllegalArgumentException 작은 사진을 두지 않는 자리(임시 자리·장면 사진 등), 이미 작은 사진인 키, 이름이 없는 키
   */
  public static String thumbnailKey(String key) {
    String prefix = prefixOf(key);
    if (prefix == null) {
      throw new IllegalArgumentException("작은 사진을 만들지 않는 자리입니다: " + key);
    }
    String rest = key.substring(prefix.length());
    if (rest.startsWith(FOLDER)) {
      throw new IllegalArgumentException("이미 작은 사진입니다: " + key);
    }
    int slash = rest.lastIndexOf('/');
    String name = rest.substring(slash + 1);
    if (name.isEmpty()) {
      throw new IllegalArgumentException("이름이 없는 키입니다: " + key);
    }
    int dot = name.lastIndexOf('.');
    String stem = dot > 0 ? name.substring(0, dot) : name;
    return prefix + FOLDER + rest.substring(0, slash + 1) + stem + ".jpg";
  }

  /** 세 자리({@code posts/}·{@code reviews/}·{@code place/}) 바로 아래 {@code thumb-720/} 의 키인가. */
  public static boolean isThumbnailKey(String key) {
    String prefix = prefixOf(key);
    return prefix != null && key.startsWith(prefix + FOLDER);
  }

  private static String prefixOf(String key) {
    if (key == null) {
      return null;
    }
    for (String prefix : PREFIXES) {
      if (key.startsWith(prefix)) {
        return prefix;
      }
    }
    return null;
  }
}
