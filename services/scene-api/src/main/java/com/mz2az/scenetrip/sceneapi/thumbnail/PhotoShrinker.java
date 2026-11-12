package com.mz2az.scenetrip.sceneapi.thumbnail;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * 사진을 작은 사진으로 줄인다 — JDK ImageIO 만으로(계획 {@code photo-thumbnails.md} §2-1·§2-2, ADR 0030, MZ2AZ-561).
 *
 * <p>JPEG·PNG 를 읽어 긴 변 {@link ThumbnailRules#LONG_SIDE} 의 JPEG(q{@link
 * ThumbnailRules#JPEG_QUALITY})로 쓴다. 비율은 그대로, 키우지 않는다. 메타데이터(EXIF 등)는 남기지 않는다. 투명한 곳은 흰 바탕이 된다.
 *
 * <p><b>줄여 읽기</b> — 원본을 통째로 풀지 않고 {@link ImageReadParam#setSourceSubsampling} 으로 긴 변이 720 이상인 가장 작은
 * 크기로 읽는다(2048 폰 사진은 1024 로, 약 4MB). 1Gi 파드에서 한 장씩만 다룬다.
 *
 * <p>EXIF 방향은 보지 않는다 — 세 앱 모두 올리기 전에 방향을 그림에 굽고 다시 JPEG 로 쓴다(웹 {@code photoUpload.ts}, iOS
 * PhotoShrink). 촬영지 사진도 이미 바로 선 그림이다.
 */
public final class PhotoShrinker {

  /** 그림으로 읽을 수 없다 — 모르는 형식(WebP·HEIC), 잘린 파일, CMYK JPEG 등. 다시 해도 같다. */
  public static final class UnreadableImageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnreadableImageException(String message) {
      super(message);
    }

    public UnreadableImageException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private PhotoShrinker() {}

  /**
   * 줄인다.
   *
   * @return 긴 변 720 이하의 JPEG 바이트
   * @throws UnreadableImageException 그림으로 읽을 수 없다
   */
  public static byte[] shrink(byte[] original) {
    if (original == null || original.length == 0) {
      throw new UnreadableImageException("빈 파일입니다");
    }
    BufferedImage decoded;
    int width;
    int height;
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
      Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
      if (readers == null || !readers.hasNext()) {
        throw new UnreadableImageException("모르는 그림 형식입니다");
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, true, true);
        width = reader.getWidth(0);
        height = reader.getHeight(0);
        ImageReadParam param = reader.getDefaultReadParam();
        int step = subsampling(width, height);
        if (step > 1) {
          param.setSourceSubsampling(step, step, 0, 0);
        }
        decoded = reader.read(0, param);
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof UnreadableImageException u) {
        throw u;
      }
      // 잘린 파일은 IIOException, CMYK·이상한 색 공간은 런타임 예외(CMMException 등)로 온다 — 모두 「읽을 수 없다」.
      throw new UnreadableImageException("그림을 읽지 못했습니다: " + e.getMessage(), e);
    }
    if (decoded == null) {
      throw new UnreadableImageException("그림을 읽지 못했습니다");
    }

    ThumbnailRules.Size size = ThumbnailRules.targetSize(width, height);
    BufferedImage out = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    try {
      // 투명한 곳은 흰 바탕 — JPEG 에는 알파가 없어 그냥 쓰면 검게 된다.
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, size.width(), size.height());
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.drawImage(decoded, 0, 0, size.width(), size.height(), null);
    } finally {
      g.dispose();
    }
    return jpeg(out);
  }

  /** 줄여 읽기의 간격 — 읽은 그림의 긴 변이 {@link ThumbnailRules#LONG_SIDE} 아래로 내려가지 않는 가장 큰 정수. */
  static int subsampling(int width, int height) {
    return Math.max(1, Math.max(width, height) / ThumbnailRules.LONG_SIDE);
  }

  private static byte[] jpeg(BufferedImage image) {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      throw new IllegalStateException("JDK 에 JPEG 쓰개가 없습니다");
    }
    ImageWriter writer = writers.next();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
      writer.setOutput(out);
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(ThumbnailRules.JPEG_QUALITY);
      // 메타데이터 없이 — EXIF(위치·기기)를 남기지 않는다.
      writer.write(null, new IIOImage(image, null, null), param);
    } catch (IOException e) {
      throw new IllegalStateException("JPEG 를 쓰지 못했습니다", e);
    } finally {
      writer.dispose();
    }
    return bytes.toByteArray();
  }
}
