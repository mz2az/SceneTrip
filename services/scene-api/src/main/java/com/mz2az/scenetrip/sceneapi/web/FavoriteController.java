package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.FavoritesApi;
import com.mz2az.scenetrip.sceneapi.api.model.ContentList;
import com.mz2az.scenetrip.sceneapi.api.model.FavoriteContentCreate;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.favorite.FavoriteStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 찜 — 하트.
 *
 * <p><b>작품에는 찜, 장소에는 장바구니다. 장소에 찜은 없다.</b> 둘은 다른 개념이라 엔드포인트를 합치지 않았다 — 장소를 담는 것은 {@link
 * CartController} 다.
 *
 * <p><b>담기와 빼기가 모두 멱등이다.</b> 하트는 토글이라 같은 상태를 두 번 요청하는 일이 흔하다. 이미 찜한 것을 또 찜해도, 찜하지 않은 것을 해제해도 {@code
 * 204} 다 — 그때마다 오류를 내면 프론트가 사용자에게 보여 줄 것이 없다. 장바구니가 중복에 {@code 409} 를 내는 것과 갈리는 지점이고, 계약에도 그렇게 적혀
 * 있다.
 *
 * <p>비회원도 찜할 수 있다. 찜은 남에게 보이지 않으므로 로그인 벽을 세울 이유가 없다.
 */
@RestController
class FavoriteController implements FavoritesApi {

  private final FavoriteStore store;
  private final UserStore users;

  FavoriteController(FavoriteStore store, UserStore users) {
    this.store = store;
    this.users = users;
  }

  @Override
  public ResponseEntity<ContentList> listFavoriteContents(
      UUID xDeviceId, Lang acceptLanguage, Integer limit, Integer offset) {

    FavoriteStore.Page page = store.list(users.resolve(xDeviceId), acceptLanguage, limit, offset);

    ContentList body = new ContentList(page.items(), page.total(), limit, offset);
    return Responses.ok(body, Responses.used(acceptLanguage, page.anyInRequestedLang()));
  }

  @Override
  public ResponseEntity<Void> addFavoriteContent(
      UUID xDeviceId, FavoriteContentCreate favoriteContentCreate) {

    long contentId = favoriteContentCreate.getContentId();

    // 없는 작품을 찜하는 것은 404 다. 그냥 넣으면 외래키 위반이 500 으로 나가는데,
    // 그것은 서버 결함이 아니라 클라이언트가 잘못된 id 를 보낸 것이다.
    if (!store.contentExists(contentId)) {
      throw ApiException.notFound("CONTENT_NOT_FOUND", "작품 " + contentId + " 이(가) 없습니다");
    }

    store.add(users.resolve(xDeviceId), contentId);
    return ResponseEntity.noContent().build();
  }

  /**
   * 찜 해제.
   *
   * <p>찜하지 않은 작품을 해제해도, 없는 작품 id 를 보내도 {@code 204} 다. 둘 다 "그 작품은 내 찜 목록에 없다" 라는 같은 결과이고, 사용자가 할 일도
   * 같다 — 하트를 비워 두는 것. 담기와 달리 존재 확인을 하지 않는 이유가 이것이다.
   */
  @Override
  public ResponseEntity<Void> removeFavoriteContent(UUID xDeviceId, Long contentId) {
    store.remove(users.resolve(xDeviceId), contentId);
    return ResponseEntity.noContent().build();
  }
}
