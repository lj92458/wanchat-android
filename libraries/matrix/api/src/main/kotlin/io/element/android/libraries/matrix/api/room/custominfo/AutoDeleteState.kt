package io.element.android.libraries.matrix.api.room.custominfo

import io.element.android.libraries.matrix.api.R as matrixR
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import timber.log.Timber

/**
 * 自定义状态： 自动删除。"content": {"waiting_time": "10s", "stop_time": 0},
 */
data class AutoDeleteState(
    var autoDeleteEnum: AutoDeleteEnum = AutoDeleteEnum.NONE,
    var stopTime: Long = 0  // 时间戳，表示截止到什么时间，就不再处理。
) : State {
    companion object {
        val defaultState: AutoDeleteState = AutoDeleteState()
        const val WAITING_TIME = "waiting_time"
        const val STOP_TIME = "stop_time"
    }

    override val eventType: String = "${State.TYPE_PREFIX}.auto_delete"
    override val stateKey = ""

    /**只有在用户首次启用该功能时，stopTime才应该取系统时间，否则就保持原样。否则每当修改定时参数，就只对今后的聊天记录生效，前面的没机会被删除。
     */
    override fun ensureActivated() {
        if (autoDeleteEnum != AutoDeleteEnum.NONE && stopTime == defaultState.stopTime) {
            stopTime = System.currentTimeMillis()
        }
    }

    override fun getContent(): String {
        ensureActivated()
        return """{
            |"$WAITING_TIME": "${autoDeleteEnum.value}",
            |"$STOP_TIME": $stopTime
            |}""".trimMargin()
    }

    override fun update(jsonElement: JsonElement) {
        try {
            val obj = jsonElement.jsonObject
            val content = obj[State.CONTENT]?.jsonObject
            val waitingTimeValue = content?.get(WAITING_TIME)?.jsonPrimitive?.content
            autoDeleteEnum = AutoDeleteEnum.entries.find { it.value == waitingTimeValue } ?: AutoDeleteEnum.NONE
            stopTime = content?.get(STOP_TIME)?.jsonPrimitive?.long ?: defaultState.stopTime
        } catch (e: Exception) {
            Timber.Forest.e(e)
        }
    }

    /** 自动删除、阅后即焚。
     * 枚举类型："0","10s","30s","1m","10m","30m","1h","1d","1w","1M", "6M"
     * */
    enum class AutoDeleteEnum(val value: String, val stringRes: Int) {
        NONE("0", matrixR.string.screen_room_auto_delete_none),
//        THIRTY_SECONDS("30s", matrixR.string.screen_room_auto_delete_30s),
//        ONE_MINUTE("1m", matrixR.string.screen_room_auto_delete_1m),
//        TEN_MINUTES("10m", matrixR.string.screen_room_auto_delete_10m),
//        THIRTY_MINUTES("30m", matrixR.string.screen_room_auto_delete_30m),
        ONE_HOUR("1h", matrixR.string.screen_room_auto_delete_1h),
        ONE_DAY("1d", matrixR.string.screen_room_auto_delete_1d),
        ONE_WEEK("1w", matrixR.string.screen_room_auto_delete_1w),
        ONE_MONTH("1M", matrixR.string.screen_room_auto_delete_1M),
        THREE_MONTHS("3M", matrixR.string.screen_room_auto_delete_3M),
        SIX_MONTHS("6M", matrixR.string.screen_room_auto_delete_6M),
        ONE_YEAR("1y", matrixR.string.screen_room_auto_delete_1y);
    }

}



