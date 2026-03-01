package io.element.android.libraries.matrix.api.room.custominfo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 封装多个自定义状态.
 */
data class RoomCustomInfo(val roomId: String) {
    val states: List<State> = buildList {
        add(AutoDeleteState())
        //todo add other states
    }

    val eventTypes: List<String> by lazy { states.map { it.eventType } }
    inline fun <reified T : State> getState(): T =
        states.firstOrNull { it is T } as? T
            ?: error("State ${T::class.simpleName} is required but not registered")

    suspend fun updateOrUpload(jsonArray: JsonArray, upload: suspend (state: State) -> Unit): RoomCustomInfo {
        states.forEach { state ->
            val map = jsonArray.associateBy { it.jsonObject[State.TYPE]?.jsonPrimitive?.content }
            val jsonElement = map[state.eventType]
            if (jsonElement != null) {
                state.update(jsonElement)
            } else {
                upload(state)
            }
        }
        return this
    }
}

/**
 * 从type映射到content，content内容自定义。已知状态事件的json格式如下：
 * {
 *   "type": "m.room.member",
 *   "content": {},
 *   "event_id": "$26RqwJMLw-yds1GAH_QxjHRC1Da9oasK0e5VLnck_45",
 *   "origin_server_ts": 1632489532305,
 *   "room_id": "!jEsUZKDJdhlrceRyVU:example.org",
 *   "sender": "@example:example.org",
 *   "state_key": "@user:example.org",
 *   "unsigned": {}
 * }
 */
interface State {
    companion object {
        const val TYPE_PREFIX = "info.wanchat"
        const val TYPE = "type"
        const val CONTENT = "content"
    }

    val eventType: String
    val stateKey: String

    /** 用于构造json的content字段，把状态保存到服务器 */
    fun getContent(): String

    /**只有在用户首次启用该功能时，stopTime才应该取系统时间，否则就保持原样。否则每当修改定时参数，就只对今后的聊天记录生效，前面的没机会被删除。
     */
    fun ensureActivated()

    /**把服务器状态更新到本机内存 */
    fun update(jsonElement: JsonElement)
}

