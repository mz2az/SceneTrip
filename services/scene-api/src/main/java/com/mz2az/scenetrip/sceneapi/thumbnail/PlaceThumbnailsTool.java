package com.mz2az.scenetrip.sceneapi.thumbnail;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * {@code just place-thumbnails} — 촬영지 사진의 작은 사진을 공용 버킷에 올리고 {@code seed/place_image_thumbnails.tsv}
 * 를 쓴다 (계획 {@code photo-thumbnails.md} §2-1·§4 단계 2, MZ2AZ-561).
 *
 * <p><b>기본은 미리 보기다</b> — 망에도 AWS 에도 손대지 않고 올릴 목록과 경로만 찍는다. {@code --sample-dir DIR} 을 주면 앞 {@code
 * --sample N}(기본 20) 장을 공개 주소로 받아 줄인 결과를 그 폴더에만 쓰고 크기를 찍는다(실측용, 올리지 않는다).
 *
 * <p>{@code --upload} 일 때만 올린다 — 사람의 AWS 자격(SDK 기본 사슬, SSO)으로, 사용자 승인 뒤에(계획 §8-3). 이미 있는 작은 사진은
 * 건너뛰고, 올릴 때도 {@code If-None-Match: *} 로 덮어쓰지 않는다. 다 올린 뒤 버킷에 작은 사진이 있는 주소만 TSV 에 적는다.
 *
 * <pre>
 * just place-thumbnails                                  # 미리 보기
 * just place-thumbnails --sample-dir /tmp/thumbs         # 20 장 실측(로컬에만)
 * just place-thumbnails --upload                         # 올리고 TSV 쓰기(승인 뒤)
 * </pre>
 */
public final class PlaceThumbnailsTool {

  private static final String CSV = "services/scene-api/seed/candidates.csv";
  private static final String TSV = "services/scene-api/seed/place_image_thumbnails.tsv";
  private static final String COLUMN = "place_image_url";

  private PlaceThumbnailsTool() {}

  public static void main(String[] args) throws Exception {
    Path root =
        Path.of(Optional.ofNullable(System.getenv("BUILD_WORKSPACE_DIRECTORY")).orElse("."));
    Path csv = root.resolve(CSV);
    Path tsv = root.resolve(TSV);
    boolean upload = false;
    Path sampleDir = null;
    int sample = 20;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--upload" -> upload = true;
        case "--csv" -> csv = Path.of(args[++i]);
        case "--tsv" -> tsv = Path.of(args[++i]);
        case "--sample-dir" -> sampleDir = Path.of(args[++i]);
        case "--sample" -> sample = Integer.parseInt(args[++i]);
        default -> {
          System.err.println("모르는 인자: " + args[i]);
          System.exit(2);
        }
      }
    }

    List<String> urls = imageUrls(csv);
    List<String> targets = PlaceThumbnails.pending(urls, Set.of());
    System.out.printf(
        "%s: 사진 주소 %d 개, 그중 우리 촬영지 사진(%s place/) %d 장%n",
        csv, urls.size(), PlaceThumbnails.BUCKET, targets.size());

    if (!upload) {
      Set<String> recorded = recordedKeys(tsv);
      List<String> todo = PlaceThumbnails.pending(targets, recorded);
      System.out.printf(
          "TSV 에 이미 적힌 것 %d 장, 새로 올릴 것 %d 장%n", targets.size() - todo.size(), todo.size());
      for (String url : todo) {
        System.out.printf(
            "  %s%n    → s3://%s/%s%n",
            url, PlaceThumbnails.BUCKET, PlaceThumbnails.thumbnailKey(url).orElseThrow());
      }
      if (sampleDir != null) {
        sample(todo.isEmpty() ? targets : todo, sampleDir, sample);
      }
      System.out.println("미리 보기만 했습니다 — 올리려면 --upload (AWS 자격, 사용자 승인 뒤)");
      return;
    }

    try (S3Client s3 =
        S3Client.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClient(UrlConnectionHttpClient.create())
            .build()) {
      Set<String> existing = existingKeys(s3);
      List<String> todo = PlaceThumbnails.pending(targets, existing);
      System.out.printf("버킷에 이미 있는 것 %d 장, 올릴 것 %d 장%n", targets.size() - todo.size(), todo.size());
      HttpClient http = http();
      int done = 0;
      long bytes = 0;
      for (String url : todo) {
        String key = PlaceThumbnails.thumbnailKey(url).orElseThrow();
        byte[] small = PhotoShrinker.shrink(download(http, url));
        try {
          s3.putObject(
              PutObjectRequest.builder()
                  .bucket(PlaceThumbnails.BUCKET)
                  .key(key)
                  .contentType("image/jpeg")
                  // 키가 바뀌지 않는 한 같은 그림이다(덮어쓰지 않는다).
                  .cacheControl("public, max-age=31536000, immutable")
                  .ifNoneMatch("*")
                  .build(),
              RequestBody.fromBytes(small));
        } catch (S3Exception e) {
          if (e.statusCode() != 412) {
            throw e;
          }
          System.out.printf("  이미 있음(건너뜀): %s%n", key);
        }
        existing.add(key);
        done++;
        bytes += small.length;
        System.out.printf("  [%d/%d] %s (%d KB)%n", done, todo.size(), key, small.length / 1024);
      }
      if (done > 0) {
        System.out.printf("올림 %d 장, 평균 %d KB%n", done, bytes / done / 1024);
      }
      writeTsv(tsv, targets, existing);
    }
  }

  /** CSV 의 {@code place_image_url} 칸({@code ;} 로 나뉜 목록)을 모두, 나온 순서로. */
  static List<String> imageUrls(Path csv) throws IOException {
    try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
      List<List<String>> rows = CsvReader.read(reader);
      if (rows.isEmpty()) {
        return List.of();
      }
      List<String> header = rows.get(0);
      int column = -1;
      for (int i = 0; i < header.size(); i++) {
        if (header.get(i).replace("﻿", "").equals(COLUMN)) {
          column = i;
        }
      }
      if (column < 0) {
        throw new IllegalArgumentException(csv + " 에 " + COLUMN + " 칸이 없습니다");
      }
      List<String> urls = new ArrayList<>();
      for (List<String> row : rows.subList(1, rows.size())) {
        if (column >= row.size()) {
          continue;
        }
        for (String url : row.get(column).split(";")) {
          if (!url.isBlank()) {
            urls.add(url.strip());
          }
        }
      }
      return urls;
    }
  }

  /** 지금 TSV 에 적힌 작은 사진 키 — 미리 보기가 「이미 한 것」 을 가른다. */
  private static Set<String> recordedKeys(Path tsv) throws IOException {
    Set<String> keys = new HashSet<>();
    if (!Files.exists(tsv)) {
      return keys;
    }
    for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
      String[] cols = line.split("\t", -1);
      if (cols.length >= 2 && cols[1].startsWith(PlaceThumbnails.ORIGIN)) {
        keys.add(cols[1].substring(PlaceThumbnails.ORIGIN.length()));
      }
    }
    return keys;
  }

  private static Set<String> existingKeys(S3Client s3) {
    Set<String> keys = new HashSet<>();
    s3
        .listObjectsV2Paginator(
            ListObjectsV2Request.builder()
                .bucket(PlaceThumbnails.BUCKET)
                .prefix("place/" + ThumbnailRules.FOLDER)
                .build())
        .contents()
        .stream()
        .map(S3Object::key)
        .forEach(keys::add);
    return keys;
  }

  /** 버킷에 작은 사진이 있는 주소만 — CSV 에 나온 순서로. 같은 폴더에 임시 파일을 쓰고 바꿔 넣는다. */
  private static void writeTsv(Path tsv, List<String> targets, Set<String> existing)
      throws IOException {
    Set<String> lines = new LinkedHashSet<>();
    lines.add(PlaceThumbnails.TSV_HEADER);
    for (String url : targets) {
      String key = PlaceThumbnails.thumbnailKey(url).orElseThrow();
      if (existing.contains(key)) {
        lines.add(PlaceThumbnails.tsvLine(url, PlaceThumbnails.ORIGIN + key));
      }
    }
    Path tmp = tsv.resolveSibling(tsv.getFileName() + ".tmp");
    Files.write(tmp, lines, StandardCharsets.UTF_8);
    Files.move(tmp, tsv, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    System.out.printf("%s — %d 줄%n", tsv, lines.size() - 1);
  }

  /** 앞 {@code n} 장을 받아 줄여 로컬 폴더에만 쓴다 — 크기 실측용. */
  private static void sample(List<String> urls, Path dir, int n) throws IOException {
    Files.createDirectories(dir);
    HttpClient http = http();
    long before = 0;
    long after = 0;
    int count = 0;
    for (String url : urls.subList(0, Math.min(n, urls.size()))) {
      byte[] original = download(http, url);
      byte[] small = PhotoShrinker.shrink(original);
      String name = PlaceThumbnails.thumbnailKey(url).orElseThrow().replace('/', '_');
      Files.write(dir.resolve(name), small);
      before += original.length;
      after += small.length;
      count++;
      System.out.printf("  %s %d KB → %d KB%n", name, original.length / 1024, small.length / 1024);
    }
    if (count > 0) {
      System.out.printf(
          "표본 %d 장: 평균 %d KB → %d KB (%s)%n",
          count, before / count / 1024, after / count / 1024, dir);
    }
  }

  private static HttpClient http() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  private static byte[] download(HttpClient http, String url) {
    try {
      HttpResponse<byte[]> response =
          http.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
              HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(url + " — HTTP " + response.statusCode());
      }
      return response.body();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** RFC 4180 CSV — 따옴표 안의 쉼표·줄바꿈·두 겹 따옴표. 적재 CSV 는 줄바꿈이 든 칸이 있다. */
  static final class CsvReader {
    private CsvReader() {}

    static List<List<String>> read(Reader in) throws IOException {
      List<List<String>> rows = new ArrayList<>();
      List<String> row = new ArrayList<>();
      StringBuilder field = new StringBuilder();
      boolean quoted = false;
      boolean any = false;
      int c;
      while ((c = in.read()) != -1) {
        char ch = (char) c;
        any = true;
        if (quoted) {
          if (ch == '"') {
            in.mark(1);
            int next = in.read();
            if (next == '"') {
              field.append('"');
            } else {
              quoted = false;
              if (next != -1) {
                in.reset();
              }
            }
          } else {
            field.append(ch);
          }
        } else if (ch == '"') {
          quoted = true;
        } else if (ch == ',') {
          row.add(field.toString());
          field.setLength(0);
        } else if (ch == '\n') {
          row.add(field.toString());
          field.setLength(0);
          rows.add(row);
          row = new ArrayList<>();
          any = false;
        } else if (ch != '\r') {
          field.append(ch);
        }
      }
      if (any) {
        row.add(field.toString());
        rows.add(row);
      }
      return rows;
    }
  }
}
