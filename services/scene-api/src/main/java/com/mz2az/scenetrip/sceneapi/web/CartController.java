package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.CartApi;
import com.mz2az.scenetrip.sceneapi.api.model.Cart;
import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.CartItemCreate;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.cart.CartStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장바구니 — 담기까지만.
 *
 * <p><b>헤더로 오는 {@code X-Install-Id} 는 설치 UUID 이고, 저장의 주체는 계정({@code app_user.id})이다.</b> 그 사이를
 * {@link CurrentAccount#resolve} 가 잇는다 — 로그인했으면 토큰의 계정이다.
 *
 * <p>둘을 나눈 이유는 설치 UUID 가 사람이 아니라 설치본을 가리키기 때문이다. 로그인해도 이 컨트롤러는 그대로다 — 어느 계정인지 정하는 일은 {@link
 * CurrentAccount} 에 갇혀 있다.
 *
 * <p>목업에는 담는 경로가 셋이다 — 장소 카드의 {@code +}, 상세의 저장 버튼, 장면 팝업의 북마크. 셋 다 같은 하나를 부른다.
 */
@RestController
class CartController implements CartApi {

  private final CartStore store;
  private final CurrentAccount accounts;

  CartController(CartStore store, CurrentAccount accounts) {
    this.store = store;
    this.accounts = accounts;
  }

  @Override
  public ResponseEntity<Cart> getCart(UUID xInstallId, Lang acceptLanguage) {
    CartStore.Contents contents = store.list(accounts.resolve(xInstallId), acceptLanguage);
    Cart body = new Cart(contents.items(), contents.items().size());
    return Responses.ok(body, Responses.used(acceptLanguage, contents.shownLangs()));
  }

  @Override
  public ResponseEntity<CartItem> addCartItem(
      UUID xInstallId, CartItemCreate cartItemCreate, Lang acceptLanguage) {

    long placeId = cartItemCreate.getPlaceId();

    // 없는 장소를 담는 것은 404 다. 그냥 넣으면 외래키 위반이 500 으로 나가는데,
    // 그것은 서버 결함이 아니라 클라이언트가 잘못된 id 를 보낸 것이다.
    if (!store.placeExists(placeId)) {
      throw ApiException.notFound("PLACE_NOT_FOUND", "장소 " + placeId + " 이(가) 없습니다");
    }

    CartItem item =
        store
            .add(
                accounts.resolve(xInstallId),
                placeId,
                cartItemCreate.getSourceContentId(),
                acceptLanguage)
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "DUPLICATE_CART_ITEM", "장소 " + placeId + " 은(는) 이미 담겨 있습니다"));

    return ResponseEntity.status(HttpStatus.CREATED).body(item);
  }

  @Override
  public ResponseEntity<Void> removeCartItem(UUID xInstallId, Long placeId) {
    // 담겨 있지 않은 것을 빼려 하면 404 다. 조용히 204 로 돌려주면 "저장됨" 토글이
    // 어긋나 있는 상태를 클라이언트가 알아차리지 못한다.
    if (!store.remove(accounts.resolve(xInstallId), placeId)) {
      throw ApiException.notFound("CART_ITEM_NOT_FOUND", "장소 " + placeId + " 은(는) 장바구니에 없습니다");
    }
    return ResponseEntity.noContent().build();
  }
}
