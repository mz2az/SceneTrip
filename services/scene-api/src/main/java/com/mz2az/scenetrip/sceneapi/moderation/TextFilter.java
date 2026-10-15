package com.mz2az.scenetrip.sceneapi.moderation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 쓸 수 없는 말 거르기 — App Store 심사 지침 1.2 의 「부적절한 글이 올라가지 못하게」(MZ2AZ-438, 계획 {@code
 * community-moderation.md} §2).
 *
 * <p>목록은 리소스 {@code moderation/banned-words.txt}(쓸 수 없는 말)와 {@code moderation/allowed-words.txt}(그
 * 말이 안에 든 멀쩡한 낱말)다. 코드를 고치지 않고 파일만 바꿔 늘린다.
 *
 * <p>비교 전에 정규화한다 — 소문자, 글자·숫자·띄어쓰기 말고는 빼기(「s.h.i.t」), 그리고 <b>한 글자씩 띄워 쓴 덩어리를 붙이기</b> (「씨 발」·「s h i
 * t」). 낱말 사이 띄어쓰기는 남긴다 — 다 빼면 「도착 시 발권」 이 「시발」 에, 「아가씨 바다」 가 「씨바」 에 걸린다. 그다음 낱말마다 멀쩡한 낱말을 먼저
 * 지우고(「시발점」) 쓸 수 없는 말이 남았는지 본다.
 *
 * <p>먼저 유니코드 NFKC 로 맞춘다 — 전각 「ｆｕｃｋ」 과 자모로 쪼갠 한글이 같은 글자가 된다. 숫자 바로 뒤의 「시발」(「7시발 KTX」 — N시 출발)은 지운다.
 * 목록 줄 끝의 {@code !} 는 「한 낱말 안에서만」 — 한 글자씩 띄운 덩어리(「도착 시 발 아파요」)에서는 찾지 않는다.
 *
 * <p>목록을 한 번 읽어 두는 정적 도구다 — 컨트롤러 생성자에 의존성을 더하지 않는다. 완벽한 거르기는 없다 — 빠져나간 것은 신고로 잡는다.
 */
public final class TextFilter {

  /** 목록 한 줄 — 쓸 수 없는 말과, 한 낱말 안에서만 찾는가({@code !}). */
  private record Banned(String word, boolean wordOnly) {}

  private static final List<Banned> BANNED =
      load("moderation/banned-words.txt").stream()
          .map(
              line ->
                  line.endsWith("!")
                      ? new Banned(normalize(line.substring(0, line.length() - 1)), true)
                      : new Banned(normalize(line), false))
          .filter(b -> !b.word().isEmpty())
          .toList();

  /** 「7시발」·「23 시발」 — N시 출발. */
  private static final Pattern HOUR_DEPARTURE = Pattern.compile("[0-9]+시발");

  /** 긴 것부터 지운다 — 짧은 낱말이 긴 낱말의 일부를 먼저 지워 남은 조각이 걸리지 않게. */
  private static final List<String> ALLOWED =
      load("moderation/allowed-words.txt").stream()
          .map(TextFilter::normalize)
          .filter(w -> !w.isEmpty())
          .sorted(Comparator.comparingInt(String::length).reversed())
          .toList();

  private TextFilter() {}

  /** 쓸 수 있는 글인가. 비었거나 {@code null} 이면 {@code true}. */
  public static boolean allows(String text) {
    if (text == null || text.isBlank()) {
      return true;
    }
    for (Chunk chunk : words(text)) {
      String word = HOUR_DEPARTURE.matcher(chunk.text()).replaceAll("");
      for (String ok : ALLOWED) {
        word = word.replace(ok, "");
      }
      for (Banned bad : BANNED) {
        if (bad.wordOnly() && chunk.joined()) {
          continue;
        }
        if (word.contains(bad.word())) {
          return false;
        }
      }
    }
    return true;
  }

  /** 낱말 덩어리 — {@code joined} 면 한 글자씩 띄운 것을 붙인 덩어리다. */
  record Chunk(String text, boolean joined) {}

  /**
   * 낱말 덩어리로 나눈다 — 소문자로, 글자·숫자·띄어쓰기만 남긴 뒤 띄어쓰기로 나누고, 한 글자짜리가 이어지면 붙인다. 「씨 발 놈」 → 「씨발놈」, 「도착 시 발권」 →
   * 「도착」·「시」·「발권」.
   */
  static List<Chunk> words(String text) {
    String lower = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    StringBuilder kept = new StringBuilder(lower.length());
    lower
        .codePoints()
        .filter(c -> Character.isLetterOrDigit(c) || Character.isWhitespace(c))
        .forEach(kept::appendCodePoint);
    List<Chunk> out = new ArrayList<>();
    StringBuilder run = new StringBuilder();
    int runLength = 0;
    for (String token : kept.toString().trim().split("\\s+")) {
      if (token.isEmpty()) {
        continue;
      }
      if (token.codePointCount(0, token.length()) == 1) {
        run.append(token);
        runLength++;
        continue;
      }
      flush(out, run, runLength);
      runLength = 0;
      out.add(new Chunk(token, false));
    }
    flush(out, run, runLength);
    return out;
  }

  /** 모아 둔 한 글자들을 덩어리로 — 한 글자뿐이면 띄운 것이 아니라 그냥 한 글자 낱말이다. */
  private static void flush(List<Chunk> out, StringBuilder run, int runLength) {
    if (run.length() > 0) {
      out.add(new Chunk(run.toString(), runLength > 1));
      run.setLength(0);
    }
  }

  /** 목록 한 줄을 비교할 모양으로 — NFKC, 소문자, 글자·숫자만. */
  static String normalize(String text) {
    String lower = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder(lower.length());
    lower.codePoints().filter(Character::isLetterOrDigit).forEach(out::appendCodePoint);
    return out.toString();
  }

  private static List<String> load(String resource) {
    InputStream in = TextFilter.class.getClassLoader().getResourceAsStream(resource);
    if (in == null) {
      throw new IllegalStateException("금칙어 목록이 없습니다: " + resource);
    }
    List<String> words = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        int hash = line.indexOf('#');
        String word = (hash >= 0 ? line.substring(0, hash) : line).strip();
        if (!word.isEmpty()) {
          words.add(word);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return List.copyOf(words);
  }
}
