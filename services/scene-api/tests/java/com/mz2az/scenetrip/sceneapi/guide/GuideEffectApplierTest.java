package com.mz2az.scenetrip.sceneapi.guide;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.GuideEffect;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.cart.CartStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code effects} 갈래 처리. DB 없이 {@code CartStore} 를 가짜로 세우고 「이 쪽지를 주면 가짜가 몇 번 불리나」를 센다.
 *
 * <p>기준은 머리말의 것 — 실행 뒤 장바구니 상태가 답변 문장과 맞으면 무시, 안 맞으면 실패.
 */
class GuideEffectApplierTest {

  private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private CartStore cart;
  private GuideEffectApplier applier;

  @BeforeEach
  void setUp() {
    cart = mock(CartStore.class);
    applier = new GuideEffectApplier(cart);
    when(cart.placeExists(anyLong())).thenReturn(true);
    when(cart.add(any(), anyLong(), isNull(), any())).thenReturn(Optional.of(new CartItem()));
    when(cart.remove(any(), anyLong())).thenReturn(true);
  }

  private static GuideEffect effect(String op, Long placeId, String name) {
    GuideEffect e = new GuideEffect(op);
    e.setPlaceId(placeId);
    e.setName(name);
    return e;
  }

  @Test
  @DisplayName("cart.add → CartStore.add 한 번, sourceContentId 는 null")
  void addsToCart() {
    applier.apply(USER, List.of(effect("cart.add", 1187L, "서울중앙고")));

    verify(cart).add(eq(USER), eq(1187L), isNull(), eq(Lang.KO));
  }

  @Test
  @DisplayName("cart.remove → CartStore.remove 한 번")
  void removesFromCart() {
    applier.apply(USER, List.of(effect("cart.remove", 1187L, "서울중앙고")));

    verify(cart).remove(USER, 1187L);
  }

  @Test
  @DisplayName("이미 담긴 곳을 또 담으라 → 무시. 하려던 상태가 이미 돼 있다")
  void duplicateAddIsIgnored() {
    when(cart.add(any(), anyLong(), isNull(), any())).thenReturn(Optional.empty());

    assertThatCode(() -> applier.apply(USER, List.of(effect("cart.add", 1187L, "서울중앙고"))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("안 담긴 곳을 빼라 → 무시")
  void removingAbsentIsIgnored() {
    when(cart.remove(any(), anyLong())).thenReturn(false);

    assertThatCode(() -> applier.apply(USER, List.of(effect("cart.remove", 1187L, "서울중앙고"))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("placeId 가 없는 cart.add → 실패(500). 「담았어요」가 뜨는데 안 담긴 것이다")
  void missingPlaceIdFails() {
    assertThatThrownBy(() -> applier.apply(USER, List.of(effect("cart.add", null, "서울중앙고"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("placeId");
    verify(cart, never()).add(any(), anyLong(), any(), any());
  }

  @Test
  @DisplayName("DB 에 없는 장소의 cart.add → 실패(500). 에이전트가 준 id 가 어긋난 것")
  void unknownPlaceFails() {
    when(cart.placeExists(9999L)).thenReturn(false);

    assertThatThrownBy(() -> applier.apply(USER, List.of(effect("cart.add", 9999L, "없는곳"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("9999");
    verify(cart, never()).add(any(), anyLong(), any(), any());
  }

  @Test
  @DisplayName("DB 예외는 잡지 않고 그대로 올라간다 — 「담았어요」 뒤에 200 을 주면 사용자는 됐다고 믿는다")
  void dbFailurePropagates() {
    when(cart.add(any(), anyLong(), isNull(), any()))
        .thenThrow(new RuntimeException("connection refused"));

    assertThatThrownBy(() -> applier.apply(USER, List.of(effect("cart.add", 1187L, "서울중앙고"))))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("connection refused");
  }

  @Test
  @DisplayName("plan.draft · plan.revise · plan.move → CartStore 를 전혀 안 건드린다")
  void planEffectsTouchNothing() {
    applier.apply(
        USER,
        List.of(
            effect("plan.draft", null, null),
            effect("plan.revise", null, null),
            effect("plan.move", null, null)));

    verifyNoInteractions(cart);
  }

  @Test
  @DisplayName("모르는 op → 무시. 에이전트가 명령을 늘려도 서버가 안 깨진다")
  void unknownOpIsIgnored() {
    applier.apply(USER, List.of(effect("cart.rename", 1187L, "서울중앙고")));

    verifyNoInteractions(cart);
  }

  @Test
  @DisplayName("여러 쪽지는 순서대로 전부 — 하나가 무시돼도 다음 것은 처리된다")
  void appliesAllInOrder() {
    when(cart.add(eq(USER), eq(1L), isNull(), any())).thenReturn(Optional.empty()); // 이미 담김

    applier.apply(
        USER,
        List.of(
            effect("cart.add", 1L, "이미담긴곳"),
            effect("plan.revise", null, null),
            effect("cart.add", 2L, "새로담는곳"),
            effect("cart.remove", 3L, "빼는곳")));

    verify(cart).add(eq(USER), eq(1L), isNull(), any());
    verify(cart).add(eq(USER), eq(2L), isNull(), any());
    verify(cart).remove(USER, 3L);
  }

  @Test
  @DisplayName("effects 가 null 이거나 비어 있으면 아무것도 안 한다")
  void emptyIsNoop() {
    applier.apply(USER, null);
    applier.apply(USER, List.of());

    verifyNoInteractions(cart);
  }
}
