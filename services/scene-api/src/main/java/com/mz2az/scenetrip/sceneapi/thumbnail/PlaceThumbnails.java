package com.mz2az.scenetrip.sceneapi.thumbnail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 촬영지 사진의 작은 사진 — {@code just place-thumbnails} 도구의 순수한 부분(계획 {@code photo-thumbnails.md} §2-1·§3,
 * MZ2AZ-561). 내려받기·올리기는 {@link PlaceThumbnailsTool} 이 한다.
 *
 * <p>대상은 공용 버킷 {@code scenetrip-media-prod} 의 {@code place/…} 사진뿐이다 — 장면 스틸({@code scene/…})·관광공사 등
 * 바깥 주소는 아니다. 작은 사진은 같은 버킷 {@code place/thumb-720/<place/ 뒤 경로, 확장자 .jpg>} 에 덮어쓰기 없이 올리고, 결과는
 * {@code seed/place_image_thumbnails.tsv}({@link #TSV_HEADER})에 적는다.
 */
public final class PlaceThumbnails {

  /** 공용 버킷 이름. */
  public static final String BUCKET = "scenetrip-media-prod";

  /** 공용 버킷의 공개 주소(끝에 /). 사진 주소가 이것으로 시작해야 우리 사진이다. */
  public static final String ORIGIN = "https://" + BUCKET + ".s3.ap-northeast-2.amazonaws.com/";

  /** 결과 TSV 의 머리줄. */
  public static final String TSV_HEADER = "url\tthumbnail_url";

  private static final String PLACE = "place/";

  private PlaceThumbnails() {}

  /** 원본 사진 주소의 작은 사진 키. 대상이 아니면(바깥 주소·장면 사진·이미 작은 사진·빈 값) 비어 있다. */
  public static Optional<String> thumbnailKey(String url) {
    if (url == null || !url.startsWith(ORIGIN)) {
      return Optional.empty();
    }
    String key = url.substring(ORIGIN.length());
    // 주소 꼴 그대로 키로 쓴다 — 인코딩·질의가 섞인 주소는 키와 어긋날 수 있어 건드리지 않는다(지금 자료에는 없다).
    if (!key.startsWith(PLACE) || ThumbnailRules.isThumbnailKey(key) || hasUrlSyntax(key)) {
      return Optional.empty();
    }
    try {
      return Optional.of(ThumbnailRules.thumbnailKey(key));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** 원본 사진 주소의 작은 사진 공개 주소. 대상이 아니면 비어 있다. */
  public static Optional<String> thumbnailUrl(String url) {
    return thumbnailKey(url).map(k -> ORIGIN + k);
  }

  /** 원본 주소의 저장소 키 — {@link #thumbnailKey} 가 대상으로 본 주소에만 부른다. */
  static String sourceKey(String url) {
    return url.substring(ORIGIN.length());
  }

  /**
   * TSV 한 줄 — {@code 원본\t작은 사진}.
   *
   * @throws IllegalArgumentException 칸 안에 탭·줄바꿈이 있다(줄이 깨진다)
   */
  public static String tsvLine(String url, String thumbnailUrl) {
    for (String value : new String[] {url, thumbnailUrl}) {
      if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
        throw new IllegalArgumentException("TSV 칸에 탭·줄바꿈이 있습니다: " + value);
      }
    }
    return url + "\t" + thumbnailUrl;
  }

  /**
   * 대상 주소 — 우리 촬영지 사진만, 중복 없이, 처음 나온 순서로. 작은 사진 키가 이미 {@code existingKeys} 에 있으면 건너뛴다(덮어쓰지 않는다).
   */
  public static List<String> pending(Collection<String> urls, Set<String> existingKeys) {
    Set<String> seen = new LinkedHashSet<>();
    List<String> out = new ArrayList<>();
    for (String url : urls) {
      Optional<String> key = thumbnailKey(url);
      if (key.isEmpty() || !seen.add(url) || existingKeys.contains(key.get())) {
        continue;
      }
      out.add(url);
    }
    return out;
  }

  private static boolean hasUrlSyntax(String key) {
    return key.indexOf('?') >= 0 || key.indexOf('#') >= 0 || key.indexOf('%') >= 0;
  }
}
