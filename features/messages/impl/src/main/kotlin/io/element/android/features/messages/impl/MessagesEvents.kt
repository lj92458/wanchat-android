/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl

import io.element.android.features.messages.impl.actionlist.model.TimelineItemAction
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.matrix.api.room.custominfo.AutoDeleteState.AutoDeleteEnum
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.user.MatrixUser

sealed interface MessagesEvents {
    data class HandleAction(val action: TimelineItemAction, val event: TimelineItem.Event) : MessagesEvents
    data class ToggleReaction(val emoji: String, val eventOrTransactionId: EventOrTransactionId) : MessagesEvents
    data class InviteDialogDismissed(val action: InviteDialogAction) : MessagesEvents
    data class OnUserClicked(val user: MatrixUser) : MessagesEvents
    data object Dismiss : MessagesEvents
    data object MarkAsFullyReadAndExit : MessagesEvents

    // MultiSelect  ====================================================================================
    data object ToggleMultiSelectMode : MessagesEvents // 开启或关闭多选模式
    data class ToggleEventSelection(val event: TimelineItem.Event) : MessagesEvents // 选择该条消息，或不选该条消息
    data object MultiDelete : MessagesEvents
    data class AutoDeleteStateChange(var autoDeleteEnum: AutoDeleteEnum) : MessagesEvents
}

enum class InviteDialogAction {
    Cancel,
    Invite,
}
