package com.mz2az.scenetrip.sceneapi.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 줄이기 — 계획 {@code photo-thumbnails.md} §2-1·§2-2·§5(MZ2AZ-561). 서버 배경 작업과 촬영지 도구가 같은 클래스를 쓴다.
 *
 * <p>명세에서 나온 기대: JDK ImageIO 만으로 JPEG·PNG 를 읽어 긴 변 720px JPEG(q0.80)로 쓴다. 비율은 그대로, 키우지 않는다. EXIF 는
 * 남기지 않는다. PNG 의 투명한 곳은 흰 바탕이 된다. 못 읽는 바이트는 {@link PhotoShrinker.UnreadableImageException}.
 *
 * <p>그림은 시험 안에서 {@code ImageIO} 로 만든다 — 바이너리 고정 파일을 두지 않는다.
 */
@DisplayName("PhotoShrinker — 긴 변 720 JPEG 로 줄인다")
class PhotoShrinkerTest {

  @Test
  @DisplayName("폰 사진 2048×1536 JPEG → 720×540 JPEG, 원본보다 작다")
  void shrinksLandscapeJpeg() {
    byte[] original = jpeg(noisy(2048, 1536, BufferedImage.TYPE_INT_RGB));

    byte[] out = PhotoShrinker.shrink(original);

    assertThat(formatOf(out)).isEqualToIgnoringCase("jpeg");
    BufferedImage img = read(out);
    assertThat(img.getWidth()).isEqualTo(720);
    assertThat(img.getHeight()).isEqualTo(540);
    assertThat(out.length).isLessThan(original.length);
  }

  @Test
  @DisplayName("세로 사진 1536×2048 → 540×720 — 긴 변이 높이")
  void shrinksPortrait() {
    BufferedImage img =
        read(PhotoShrinker.shrink(jpeg(noisy(1536, 2048, BufferedImage.TYPE_INT_RGB))));

    assertThat(img.getWidth()).isEqualTo(540);
    assertThat(img.getHeight()).isEqualTo(720);
  }

  @Test
  @DisplayName("비율을 지킨다 — 2048×1365 는 720×480(±1)")
  void keepsAspect() {
    BufferedImage img =
        read(PhotoShrinker.shrink(jpeg(noisy(2048, 1365, BufferedImage.TYPE_INT_RGB))));

    assertThat(img.getWidth()).isEqualTo(720);
    assertThat(img.getHeight()).isBetween(479, 481);
  }

  @Test
  @DisplayName("촬영지 사진 1280×720 → 720×405")
  void shrinksPlacePhoto() {
    BufferedImage img =
        read(PhotoShrinker.shrink(jpeg(noisy(1280, 720, BufferedImage.TYPE_INT_RGB))));

    assertThat(img.getWidth()).isEqualTo(720);
    assertThat(img.getHeight()).isEqualTo(405);
  }

  @Test
  @DisplayName("작은 그림은 키우지 않는다 — 400×300 은 400×300 JPEG")
  void doesNotUpscale() {
    byte[] out = PhotoShrinker.shrink(jpeg(noisy(400, 300, BufferedImage.TYPE_INT_RGB)));

    assertThat(formatOf(out)).isEqualToIgnoringCase("jpeg");
    BufferedImage img = read(out);
    assertThat(img.getWidth()).isEqualTo(400);
    assertThat(img.getHeight()).isEqualTo(300);
  }

  @Test
  @DisplayName("PNG 도 읽는다 — 결과는 JPEG, 긴 변 720")
  void readsPng() {
    byte[] out = PhotoShrinker.shrink(png(noisy(1600, 900, BufferedImage.TYPE_INT_RGB)));

    assertThat(formatOf(out)).isEqualToIgnoringCase("jpeg");
    BufferedImage img = read(out);
    assertThat(img.getWidth()).isEqualTo(720);
    assertThat(img.getHeight()).isEqualTo(405);
  }

  @Test
  @DisplayName("PNG 의 투명한 곳은 흰 바탕 — 검게 되지 않는다")
  void transparentPngBecomesWhite() {
    BufferedImage clear = new BufferedImage(1000, 500, BufferedImage.TYPE_INT_ARGB); // 전부 투명

    BufferedImage img = read(PhotoShrinker.shrink(png(clear)));

    Color center = new Color(img.getRGB(img.getWidth() / 2, img.getHeight() / 2));
    assertThat(center.getRed()).isGreaterThanOrEqualTo(245);
    assertThat(center.getGreen()).isGreaterThanOrEqualTo(245);
    assertThat(center.getBlue()).isGreaterThanOrEqualTo(245);
  }

  @Test
  @DisplayName("EXIF 를 남기지 않는다 — 원본에 있던 APP1 Exif 조각이 결과에 없다")
  void dropsExif() {
    byte[] original = withExif(jpeg(noisy(1200, 900, BufferedImage.TYPE_INT_RGB)));
    assertThat(contains(original, "Exif".getBytes(StandardCharsets.US_ASCII)))
        .as("시험 준비 — 원본에 Exif 가 들었다")
        .isTrue();

    byte[] out = PhotoShrinker.shrink(original);

    assertThat(contains(out, "Exif".getBytes(StandardCharsets.US_ASCII))).isFalse();
  }

  @Test
  @DisplayName("그림이 아닌 바이트는 UnreadableImageException")
  void rejectsGarbage() {
    assertThatThrownBy(() -> PhotoShrinker.shrink(new byte[] {1, 2, 3, 4, 5}))
        .isInstanceOf(PhotoShrinker.UnreadableImageException.class);
    assertThatThrownBy(() -> PhotoShrinker.shrink(new byte[0]))
        .isInstanceOf(PhotoShrinker.UnreadableImageException.class);
  }

  @Test
  @DisplayName("JPEG 머리만 있고 몸이 잘린 바이트도 UnreadableImageException")
  void rejectsTruncatedJpeg() {
    byte[] whole = jpeg(noisy(800, 600, BufferedImage.TYPE_INT_RGB));
    byte[] head = java.util.Arrays.copyOf(whole, 20);

    assertThatThrownBy(() -> PhotoShrinker.shrink(head))
        .isInstanceOf(PhotoShrinker.UnreadableImageException.class);
  }

  // ───────────── 도우미 ─────────────

  /** 무늬 + 잡음 — JPEG 가 지나치게 작아지지 않게. 같은 씨앗이라 늘 같다. */
  private static BufferedImage noisy(int w, int h, int type) {
    BufferedImage img = new BufferedImage(w, h, type);
    Graphics2D g = img.createGraphics();
    g.setPaint(new java.awt.GradientPaint(0, 0, Color.BLUE, w, h, Color.ORANGE));
    g.fillRect(0, 0, w, h);
    g.dispose();
    Random random = new Random(561);
    for (int i = 0; i < w * h / 4; i++) {
      img.setRGB(random.nextInt(w), random.nextInt(h), random.nextInt(0xFFFFFF));
    }
    return img;
  }

  private static byte[] jpeg(BufferedImage img) {
    return encode(img, "jpg");
  }

  private static byte[] png(BufferedImage img) {
    return encode(img, "png");
  }

  private static byte[] encode(BufferedImage img, String format) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      assertThat(ImageIO.write(img, format, out)).as("시험 준비 — " + format + " 쓰기").isTrue();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static BufferedImage read(byte[] bytes) {
    try {
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
      assertThat(img).as("결과를 그림으로 읽을 수 있어야 한다").isNotNull();
      return img;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String formatOf(byte[] bytes) {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      assertThat(readers.hasNext()).as("알아보는 형식이어야 한다").isTrue();
      return readers.next().getFormatName();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** SOI(FF D8) 바로 뒤에 작은 APP1 Exif 조각(빈 TIFF IFD)을 끼운다. */
  private static byte[] withExif(byte[] jpeg) {
    byte[] payload = {
      'E',
      'x',
      'i',
      'f',
      0,
      0, // Exif 머리
      'M',
      'M',
      0,
      42,
      0,
      0,
      0,
      8, // TIFF 머리(빅엔디언), 첫 IFD 는 8
      0,
      0, // 항목 0 개
      0,
      0,
      0,
      0 // 다음 IFD 없음
    };
    int length = payload.length + 2;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(jpeg, 0, 2);
    out.write(0xFF);
    out.write(0xE1);
    out.write((length >> 8) & 0xFF);
    out.write(length & 0xFF);
    out.write(payload, 0, payload.length);
    out.write(jpeg, 2, jpeg.length - 2);
    return out.toByteArray();
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }
}
