package com.mz2az.scenetrip.routetab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.sceneapi.client.api.GuideApi
import com.mz2az.scenetrip.sceneapi.client.model.GuideChatRequest
import com.mz2az.scenetrip.sceneapi.client.model.GuideMessage
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** 한 번 주고받은 말. iOS `RouteGuide.Turn`. */
data class GuideTurn(
    val id: UUID = UUID.randomUUID(),
    val role: Role,
    val text: String,
    val tools: List<String> = emptyList(),
) {
    enum class Role { USER, ASSISTANT }
}

/**
 * 여행 가이드 대화 — 여기서만 서버(`POST /guide/chat`)를 부른다. iOS
 * `RouteTab/RouteGuide.swift`(`ask`)·`RouteGuideSession.swift`를 옮긴 것이다.
 *
 * **화면(챗봇 창)보다 오래 산다** — iOS는 앱 공용 싱글턴(`RouteGuideSession.shared`)이라
 * 창을 닫아도 대화가 남는다. 여기서는 [RouteEditorView] 화면 하나가 붙잡고 있다가
 * 화면을 나가면 함께 버린다 — 코스 편집 화면 안에서만 쓰는 기능이라 화면보다 오래
 * 살려 둘 이유가 아직 없다.
 *
 * **효과(`effects`)·화면 명령(`ui`)은 적용하지 않는다** — 지도 포커스·경로 그리기·
 * 코스 자동 전환 같은 것들은 iOS의 `RouteEditorView` 상태와 깊이 얽혀 있고, 여기
 * 편집기는 아직 그 상태(주변 편의시설 지도 표시, AI 장소 지도 레이어)를 들고 있지
 * 않다. 답은 사람이 읽고, 찾아 준 장소는 "담기"로 사람이 직접 넣는다.
 */
class RouteGuideSession(
    context: Context,
) {
    var turns by mutableStateOf<List<GuideTurn>>(emptyList())
        private set
    var places by mutableStateOf<List<GuidePlace>>(emptyList())
        private set
    var asking by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    val isEmpty: Boolean get() = turns.isEmpty()

    private val sessionId: UUID = UUID.randomUUID()
    private val guideApi = GuideApi(API_BASE)
    private val deviceId: UUID = InstallIdentity.of(context)

    /** 계약 상한 40 — 넘치면 오래된 것부터 잊는다. iOS `RouteGuide.ask`. */
    suspend fun ask(
        text: String,
        latitude: Double,
        longitude: Double,
    ) {
        turns = turns + GuideTurn(role = GuideTurn.Role.USER, text = text)
        asking = true
        runCatching {
            withContext(Dispatchers.IO) {
                guideApi.chatWithGuide(
                    deviceId,
                    GuideChatRequest(
                        sessionId = sessionId,
                        latitude = latitude,
                        longitude = longitude,
                        messages =
                            turns.takeLast(40).map {
                                GuideMessage(
                                    role = if (it.role == GuideTurn.Role.USER) GuideMessage.Role.user else GuideMessage.Role.assistant,
                                    content = it.text,
                                )
                            },
                    ),
                )
            }
        }.onSuccess { reply ->
            turns = turns + GuideTurn(role = GuideTurn.Role.ASSISTANT, text = reply.reply, tools = reply.toolsUsed.map { it.tool })
            if (reply.places.isNotEmpty()) places = reply.places
            failure = null
        }.onFailure {
            failure = ApiFailure.of(it).message
        }
        asking = false
    }
}
