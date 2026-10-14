package com.mz2az.scenetrip.sceneapi.place;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Array;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link PlaceStore#sceneImages} — SQL {@code ARRAY(...)} 로 묶은 장면 스틸을 URI 목록으로(MZ2AZ-429). */
@DisplayName("PlaceStore.sceneImages — 장면 스틸 배열")
class PlaceStoreSceneImagesTest {

  @Test
  @DisplayName("순서를 지키고, 주소가 아닌 값·빈 값·null 은 버린다")
  void keepsOrderAndDropsInvalid() throws SQLException {
    Array array =
        array(
            "https://example.invalid/b.jpg",
            "https://example.invalid/has space.jpg",
            "",
            null,
            "https://example.invalid/a.jpg");

    assertThat(PlaceStore.sceneImages(array))
        .containsExactly(
            URI.create("https://example.invalid/b.jpg"),
            URI.create("https://example.invalid/a.jpg"));
  }

  @Test
  @DisplayName("빈 배열과 null 은 빈 목록이다")
  void emptyOrNullIsEmptyList() throws SQLException {
    assertThat(PlaceStore.sceneImages(array())).isNotNull().isEmpty();
    assertThat(PlaceStore.sceneImages(null)).isNotNull().isEmpty();
  }

  /** 드라이버가 돌려주는 text[] 처럼 {@code getArray()} 가 {@code String[]} 인 배열. */
  private static Array array(String... values) {
    return (Array)
        Proxy.newProxyInstance(
            Array.class.getClassLoader(),
            new Class<?>[] {Array.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getArray") && method.getParameterCount() == 0) {
                return values;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
