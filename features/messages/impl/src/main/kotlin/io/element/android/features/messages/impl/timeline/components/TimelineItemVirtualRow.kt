/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.element.android.features.messages.impl.timeline.TimelineEvents
import io.element.android.features.messages.impl.timeline.TimelineEvents.*
import io.element.android.features.messages.impl.timeline.TimelineRoomInfo
import io.element.android.features.messages.impl.timeline.components.virtual.TimelineItemDaySeparatorView
import io.element.android.features.messages.impl.timeline.components.virtual.TimelineItemReadMarkerView
import io.element.android.features.messages.impl.timeline.components.virtual.TimelineItemRoomBeginningView
import io.element.android.features.messages.impl.timeline.components.virtual.TimelineLoadingMoreIndicator
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemDaySeparatorModel
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemLastForwardIndicatorModel
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemLoadingIndicatorModel
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemReadMarkerModel
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemRoomBeginningModel
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemTypingNotificationModel
import io.element.android.features.messages.impl.typing.TypingNotificationView
import timber.log.Timber

@Composable
fun TimelineItemVirtualRow(
    virtual: TimelineItem.Virtual,
    timelineRoomInfo: TimelineRoomInfo,
    eventSink: (TimelineEvents.EventFromTimelineItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when (virtual.model) {
            is TimelineItemDaySeparatorModel -> TimelineItemDaySeparatorView(virtual.model)
            TimelineItemReadMarkerModel -> TimelineItemReadMarkerView()
            TimelineItemRoomBeginningModel -> {
                TimelineItemRoomBeginningView(
                    predecessorRoom = timelineRoomInfo.predecessorRoom,
                    roomName = timelineRoomInfo.name,
                    isDm = timelineRoomInfo.isDm,
                    onPredecessorRoomClick = { roomId ->
                        eventSink(NavigateToPredecessorOrSuccessorRoom(roomId))
                    },
                )
            }
            is TimelineItemLoadingIndicatorModel -> {
                TimelineLoadingMoreIndicator(virtual.model.direction)
                val latestEventSink by rememberUpdatedState(eventSink)
                LaunchedEffect(virtual.model.timestamp) {
                    Timber.d("Pagination triggered by load more indicator")
                    //把这里注释掉，不影响效果。滑动手势依然会触发分页，只不过只触发一次。作者加了这一行，会连续触发多次。
                    // 副作用就是：当用户快速大幅度滑动时，消息还是一页一页地加载。显得有些卡顿。
                    latestEventSink(LoadMoreByLoadingIndicator(virtual.model.direction))
                }
            }
            // Empty model trick to avoid timeline jumping during forward pagination.
            is TimelineItemLastForwardIndicatorModel -> {
                Spacer(modifier = Modifier)
            }
            is TimelineItemTypingNotificationModel -> {
                TypingNotificationView(
                    state = timelineRoomInfo.typingNotificationState,
                )
            }
        }
    }
}
