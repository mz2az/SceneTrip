package com.mz2az.scenetrip.sceneapi.guide;

import com.mz2az.scenetrip.sceneapi.api.model.GuideEffect;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.cart.CartStore;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 에이전트가 돌려준 {@code effects} 를 DB 에 적용한다 — 이 작업에서 유일하게 판단이 들어가는 곳.
 *
 * <p>에이전트는 DB 를 만지지 않는다(ADR 0013). 「담아 줘」에 답변 문장과 함께 {@code cart.add} 쪽지를 실어 보내고, 신원을 아는 이쪽이 대신
 * 저장한다. 쪽지 다섯 종 중 실행하는 것은 {@code cart.add}·{@code cart.remove} 둘뿐이다. {@code plan.*} 은 앱의 편집 사본을 갈아
 * 끼우라는 뜻이라 저장하지 않는다 — 저장은 사용자의 「완료」가 부르는 {@code PUT /courses/{id}} 뿐이고, 챗봇만 예외로 두면 「취소」가 동작하지 않는다.
 * 모르는 {@code op} 는 앱도 무시하니 여기서도 무시한다.
 *
 * <p><b>무시와 실패의 기준 — 실행한 뒤의 장바구니 상태가 답변 문장과 맞는가.</b>
 *
 * <ul>
 *   <li>이미 담긴 곳을 또 담으라, 안 담긴 곳을 빼라 → <b>무시.</b> 하려던 상태가 이미 돼 있어 사용자가 보는 결과가 옳다. 로그만 남긴다.
 *   <li>{@code placeId} 가 없다, 그 장소가 DB 에 없다 → <b>실패(500).</b> 「담았어요」가 화면에 뜨는데 실제로는 안 담긴 것이다. 정상
 *       경로에서는 안 온다 — 에이전트는 우리 DB 가 준 id 를 되돌려 보낼 뿐이다. 오면 에이전트가 계약을 어겼거나 데이터가 어긋난 것이라 조용히 넘기면 버그를 못
 *       찾는다. 다시 보내도 같으니 503(잠시 뒤 다시)이 아니라 500(결함)이다.
 *   <li>DB 가 예외를 던진다 → <b>실패(500).</b> 잡지 않는다. 「담았어요」라고 답하고 저장이 안 된 채 200 을 주면 사용자는 됐다고 믿는다.
 * </ul>
 *
 * <p>{@code CartController} 와 규칙이 다르다 — 거기서는 없는 장소가 404, 중복이 409 다. 그쪽은 「담기」가 요청의 전부라 실패가 곧 응답이고,
 * 여기서는 「담기」가 답변에 딸린 부수효과라서다.
 *
 * <p>{@code sourceContentId} 는 null 로 넣는다. 챗봇은 「어느 작품 때문에 담았는지」를 아직 안 준다 — 필요해지면 계약의 {@code
 * GuideEffect} 에 필드를 더한다(계약 먼저).
 */
@Component
public class GuideEffectApplier {

  private static final Logger log = LoggerFactory.getLogger(GuideEffectApplier.class);

  static final String OP_CART_ADD = "cart.add";
  static final String OP_CART_REMOVE = "cart.remove";

  private final CartStore cart;

  GuideEffectApplier(CartStore cart) {
    this.cart = cart;
  }

  /**
   * 순서대로 전부 적용한다. 하나가 무시돼도 다음 것은 처리한다.
   *
   * @param user {@code X-Device-Id} 로 찾은 계정. 에이전트는 이 값을 모르고, 여기서 처음 붙는다.
   * @param effects 에이전트의 {@code effects}. 비어 있을 수 있다(도구가 거절됐거나 조회만 한 턴).
   * @throws IllegalStateException {@code cart.*} 에 {@code placeId} 가 없거나 그 장소가 DB 에 없을 때 — 500 으로
   *     나간다
   */
  public void apply(UUID user, List<GuideEffect> effects) {
    if (effects == null) {
      return;
    }
    for (GuideEffect e : effects) {
      String op = e.getOp();
      if (OP_CART_ADD.equals(op)) {
        add(user, e);
      } else if (OP_CART_REMOVE.equals(op)) {
        remove(user, e);
      }
      // plan.* 과 모르는 op 는 아무것도 하지 않는다 — 앱으로 그대로 간다.
    }
  }

  private void add(UUID user, GuideEffect e) {
    long placeId = requirePlaceId(e);
    if (!cart.placeExists(placeId)) {
      throw new IllegalStateException(
          "cart.add 의 장소 " + placeId + "(" + e.getName() + ") 이(가) DB 에 없다 — 에이전트가 준 id 가 어긋났다");
    }
    if (cart.add(user, placeId, null, Lang.KO).isEmpty()) {
      log.info("cart.add 무시 — 이미 담겨 있다: {} ({})", placeId, e.getName());
    }
  }

  private void remove(UUID user, GuideEffect e) {
    long placeId = requirePlaceId(e);
    if (!cart.remove(user, placeId)) {
      log.info("cart.remove 무시 — 담겨 있지 않다: {} ({})", placeId, e.getName());
    }
  }

  private static long requirePlaceId(GuideEffect e) {
    Long placeId = e.getPlaceId();
    if (placeId == null) {
      throw new IllegalStateException(
          e.getOp() + " 에 placeId 가 없다 (" + e.getName() + ") — 계약은 정수를 요구한다. 이름으로 대체하지 않는다");
    }
    return placeId;
  }
}
