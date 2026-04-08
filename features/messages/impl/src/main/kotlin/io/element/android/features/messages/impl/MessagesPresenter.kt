/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import im.vector.app.features.analytics.plan.PinUnpinAction
import io.element.android.appconfig.MessageComposerConfig
import io.element.android.features.messages.api.timeline.HtmlConverterProvider
import io.element.android.features.messages.impl.actionlist.ActionListEvents
import io.element.android.features.messages.impl.actionlist.ActionListState
import io.element.android.features.messages.impl.actionlist.model.TimelineItemAction
import io.element.android.features.messages.impl.crypto.identity.IdentityChangeState
import io.element.android.features.messages.impl.link.LinkState
import io.element.android.features.messages.impl.messagecomposer.MessageComposerEvents
import io.element.android.features.messages.impl.messagecomposer.MessageComposerState
import io.element.android.features.messages.impl.pinned.banner.PinnedMessagesBannerState
import io.element.android.features.messages.impl.timeline.MarkAsFullyRead
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.impl.timeline.TimelineEvents
import io.element.android.features.messages.impl.timeline.TimelineState
import io.element.android.features.messages.impl.timeline.components.customreaction.CustomReactionState
import io.element.android.features.messages.impl.timeline.components.reactionsummary.ReactionSummaryState
import io.element.android.features.messages.impl.timeline.components.receipt.bottomsheet.ReadReceiptBottomSheetState
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemThreadInfo
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContentWithAttachment
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemPollContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemStateContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.isRedacted
import io.element.android.features.messages.impl.timeline.protection.TimelineProtectionState
import io.element.android.features.messages.impl.voicemessages.composer.DefaultVoiceMessageComposerPresenter
import io.element.android.features.roomcall.api.RoomCallState
import io.element.android.features.roommembermoderation.api.RoomMemberModerationEvents
import io.element.android.features.roommembermoderation.api.RoomMemberModerationState
import io.element.android.libraries.androidutils.clipboard.ClipboardHelper
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.flatMap
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.core.meta.BuildType
import io.element.android.libraries.core.tasks.LongTaskManager
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.designsystem.utils.snackbar.collectSnackbarMessageAsState
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.toThreadId
import io.element.android.libraries.matrix.api.encryption.EncryptionService
import io.element.android.libraries.matrix.api.encryption.identity.IdentityState
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.DelayInfo
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.MAX_DELAY_TIME
import io.element.android.libraries.matrix.api.room.MessageEventType
import io.element.android.libraries.matrix.api.room.RoomInfo
import io.element.android.libraries.matrix.api.room.RoomMembersState
import io.element.android.libraries.matrix.api.room.RoomRuntimeRegistry
import io.element.android.libraries.matrix.api.room.custominfo.AutoDeleteState
import io.element.android.libraries.matrix.api.room.custominfo.AutoDeleteState.AutoDeleteEnum
import io.element.android.libraries.matrix.api.room.deleteSmartly
import io.element.android.libraries.matrix.api.room.isDm
import io.element.android.libraries.matrix.api.room.powerlevels.canPinUnpin
import io.element.android.libraries.matrix.api.room.powerlevels.canRedactOther
import io.element.android.libraries.matrix.api.room.powerlevels.canRedactOwn
import io.element.android.libraries.matrix.api.room.powerlevels.canSendMessage
import io.element.android.libraries.matrix.api.room.roomMembers
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.ui.messages.reply.map
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.libraries.matrix.ui.room.getDirectRoomMember
import io.element.android.libraries.recentemojis.api.AddRecentEmoji
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

@AssistedInject
class MessagesPresenter(
    @Assisted private val navigator: MessagesNavigator,
    private val room: JoinedRoom,
    @Assisted private val composerPresenter: Presenter<MessageComposerState>,
    voiceMessageComposerPresenterFactory: DefaultVoiceMessageComposerPresenter.Factory,
    @Assisted private val timelinePresenter: Presenter<TimelineState>,
    private val timelineProtectionPresenter: Presenter<TimelineProtectionState>,
    private val identityChangeStatePresenter: Presenter<IdentityChangeState>,
    private val linkPresenter: Presenter<LinkState>,
    @Assisted private val actionListPresenter: Presenter<ActionListState>,
    private val customReactionPresenter: Presenter<CustomReactionState>,
    private val reactionSummaryPresenter: Presenter<ReactionSummaryState>,
    private val readReceiptBottomSheetPresenter: Presenter<ReadReceiptBottomSheetState>,
    private val pinnedMessagesBannerPresenter: Presenter<PinnedMessagesBannerState>,
    private val roomCallStatePresenter: Presenter<RoomCallState>,
    private val roomMemberModerationPresenter: Presenter<RoomMemberModerationState>,
    private val snackbarDispatcher: SnackbarDispatcher,
    private val dispatchers: CoroutineDispatchers,
    private val clipboardHelper: ClipboardHelper,
    private val htmlConverterProvider: HtmlConverterProvider,
    private val buildMeta: BuildMeta,
    @Assisted private val timelineController: TimelineController,
    private val permalinkParser: PermalinkParser,
    private val analyticsService: AnalyticsService,
    private val encryptionService: EncryptionService,
    private val featureFlagService: FeatureFlagService,
    private val addRecentEmoji: AddRecentEmoji,
    private val markAsFullyRead: MarkAsFullyRead,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
) : Presenter<MessagesState> {
    @AssistedFactory
    interface Factory {
        fun create(
            navigator: MessagesNavigator,
            composerPresenter: Presenter<MessageComposerState>,
            timelinePresenter: Presenter<TimelineState>,
            actionListPresenter: Presenter<ActionListState>,
            timelineController: TimelineController,
        ): MessagesPresenter
    }

    private val voiceMessageComposerPresenter = voiceMessageComposerPresenterFactory.create(
        timelineMode = timelineController.mainTimelineMode()
    )

    private val markingAsReadAndExiting = AtomicBoolean(false)

    @Composable
    override fun present(): MessagesState {
        htmlConverterProvider.Update()

        val coroutineScope = rememberCoroutineScope()
        val roomInfo by room.roomInfoFlow.collectAsState()
        val roomCustomInfo by room.roomCustomInfoFlow.collectAsState()
        val localCoroutineScope = rememberCoroutineScope()
        val composerState = composerPresenter.present()
        val voiceMessageComposerState = voiceMessageComposerPresenter.present()
        val timelineState = timelinePresenter.present()
        val timelineProtectionState = timelineProtectionPresenter.present()
        val identityChangeState = identityChangeStatePresenter.present()
        val actionListState = actionListPresenter.present()
        val linkState = linkPresenter.present()
        val customReactionState = customReactionPresenter.present()
        val reactionSummaryState = reactionSummaryPresenter.present()
        val readReceiptBottomSheetState = readReceiptBottomSheetPresenter.present()
        val pinnedMessagesBannerState = pinnedMessagesBannerPresenter.present()
        val roomCallState = roomCallStatePresenter.present()
        val roomMemberModerationState = roomMemberModerationPresenter.present()

        val userEventPermissions by userEventPermissions(roomInfo)

        val roomAvatar by remember {
            derivedStateOf { roomInfo.avatarData() }
        }
        val heroes by remember {
            derivedStateOf { roomInfo.heroes().toImmutableList() }
        }

        var hasDismissedInviteDialog by rememberSaveable {
            mutableStateOf(false)
        }
        LaunchedEffect(Unit) {
            // Remove the unread flag on entering but don't send read receipts
            // as those will be handled by the timeline.
            withContext(dispatchers.io) {
                room.setUnreadFlag(isUnread = false)

                // If for some reason the encryption state is unknown, fetch it
                if (roomInfo.isEncrypted == null) {
                    room.getUpdatedIsEncrypted()
                }
            }
        }

        val inviteProgress = remember { mutableStateOf<AsyncData<Unit>>(AsyncData.Uninitialized) }
        var showReinvitePrompt by remember { mutableStateOf(false) }
        val composerHasFocus by remember { derivedStateOf { composerState.textEditorState.hasFocus() } }
        LaunchedEffect(hasDismissedInviteDialog, composerHasFocus, roomInfo) {
            withContext(dispatchers.io) {
                showReinvitePrompt = !hasDismissedInviteDialog && composerHasFocus && roomInfo.isDm && roomInfo.activeMembersCount == 1L
            }
        }

        val snackbarMessage by snackbarDispatcher.collectSnackbarMessageAsState()

        var dmUserVerificationState by remember { mutableStateOf<IdentityState?>(null) }

        val membersState by room.membersStateFlow.collectAsState()
        val dmRoomMember by room.getDirectRoomMember(membersState)
        val roomMemberIdentityStateChanges = identityChangeState.roomMemberIdentityStateChanges

        // Debug: 检查所有成员的状态
        val allMembers = membersState.roomMembers()
        if (allMembers != null) {
            Timber.d("Room members count: ${allMembers.size}, activeMembersCount: ${roomInfo.activeMembersCount}")
            allMembers.forEach { member ->
                Timber.d("Member: ${member.userId.value}, membership: ${member.membership}, isActive: ${member.membership.isActive()}")
            }
        }
        // 多选，不一定是为了删除，而是能干很多事情。
        val selectedEvents = remember { mutableStateMapOf<TimelineItem.Event, Boolean>() }
        var isMultiSelect by remember { mutableStateOf(false) } //用户开启多选模式时，点击的是哪条消息
        //自定义状态。这里最好把room作为key，防止它变化
        val autoDeleteState by remember(roomCustomInfo) { derivedStateOf { roomCustomInfo.getState<AutoDeleteState>() } }
        val clearProgress by LongTaskManager.progress.map { it[room.roomId.value] }.collectAsState(initial = null)
        //阅后即焚，或清空房间。两种任务共用状态
        val clearProgressIsRunning by remember(clearProgress) { derivedStateOf { clearProgress?.isRunning ?: false } }

        /**
         * 定时删除、阅后即焚。本函数具有幂等性。可反复调用。
         * do while循环，可以看成永不关闭的，那么room就永不销毁。用户每次回到home页再进入房间，就会创建新的room，但没关系：这种新建的room调用autoDelete时，
         * 发现clearProgressIsRunning==true，于是不会执行incrementTasks函数，也不会启动新的do-while循环，那么这种room在退出时就会销毁。等于每个房间只有一个room在长期运行。
         * 这问题不大，内存不会爆。但是我可以改进：给while循环增加条件，当delayList协程都执行完毕，就退出while。具体来说，就是把launch记录到RoomRuntimeState对象中。
         * 问题是：销毁room，虽然能减轻内存消耗，但是让“定时删除”打折扣：如果用户连续几天不进入某房间，该房间的定时删除就失效了，直到用户再次进入房间。不过这是大多数聊天工具的策略。
         */
        fun autoDelete() {
            if (!clearProgressIsRunning && autoDeleteState.autoDeleteEnum != AutoDeleteEnum.NONE) {
                LongTaskManager.runTask(
                    before = { room.incrementTasks() },
                    task = {
                        val beginTime = System.currentTimeMillis()
                        val roomRuntimeState = RoomRuntimeRegistry.get(room.roomId.value)
                        if (roomRuntimeState.members.isEmpty()) {
                            roomRuntimeState.members = room.getMembers().getOrElse { emptyList() }
                        }
                        Timber.d("autoDelete: Redacting beginTime: $beginTime")
                        val autoDeleteStateValue = room.customInfo().getState<AutoDeleteState>()
                        val waitingTime: Double = DelayInfo.fromString(autoDeleteStateValue.autoDeleteEnum.value)?.inMilliseconds ?: 0.0
                        val willDeleteNum = room.deleteSmartly(autoDeleteStateValue.stopTime, waitingTime.toLong(), roomRuntimeState)
                        Timber.d("autoDelete: Redacting willDeleteNum: $willDeleteNum")
                        if (willDeleteNum > 0) {
                            // 等待删除任务完成，确保整个过程不超过用户设置的 waitingTime
                            // 至少等待 1 分钟，避免超时时间过短
                            val remainingTime = maxOf(60_000L, waitingTime.toLong() - (System.currentTimeMillis() - beginTime))
                            roomRuntimeState.redactMsgJob?.let { job ->
                                withTimeoutOrNull(remainingTime) {
                                    job.join()
                                }
                            }
                        }
                        //休息10分钟，再执行(测试时不需要休息)
                        if (buildMeta.buildType != BuildType.DEBUG) delay(MAX_DELAY_TIME - (System.currentTimeMillis() - beginTime))
                    },
                    after = { room.completeLongTask() },
                    roomId = room.roomId.value,
                    clearType = LongTaskManager.ClearType.DELETE
                )
            }
        }
        LaunchedEffect(autoDeleteState) {
            if (autoDeleteState.autoDeleteEnum == AutoDeleteEnum.NONE
                && LongTaskManager.progress.value[room.roomId.value]?.clearType == LongTaskManager.ClearType.DELETE
            ) {
                LongTaskManager.update(room.roomId.value) { it.copy(isRunning = false) }
            } else {
                autoDelete()
            }
        }

        LaunchedEffect(Unit) {
            room.timelineForDelete.timelineItems.collect { list ->
                autoDelete()
            }
        }

        LifecycleResumeEffect(dmRoomMember, roomInfo.isEncrypted) {
            if (roomInfo.isEncrypted == true) {
                val dmRoomMemberId = dmRoomMember?.userId
                localCoroutineScope.launch {
                    dmRoomMemberId?.let { userId ->
                        dmUserVerificationState = roomMemberIdentityStateChanges.find { it.identityRoomMember.userId == userId }?.identityState
                            ?: encryptionService.getUserIdentity(userId).getOrNull()
                    }
                }
            }
            onPauseOrDispose {}
        }
        // multi select  多选，不一定是为了删除，而是能干很多事情。 ==============================
        fun toggleMultiSelectMode() {
            isMultiSelect = !isMultiSelect
            if (!isMultiSelect) { //退出多选模式，立刻清空集合
                selectedEvents.clear()
            }
        }

        fun toggleItemSelection(event: TimelineItem.Event) {
            if (selectedEvents.contains(event)) selectedEvents.remove(event)
            else selectedEvents[event] = true
        }

        //响应点击事件，自动把当前消息加入列表
        fun handleMultiSelectAction(event: TimelineItem.Event) {
            if (event.eventId != null) {
                toggleMultiSelectMode()
                toggleItemSelection(event)
            }
        }

        fun CoroutineScope.multiDelete() = launch {
            selectedEvents.forEach { (event, isSelected) ->
                if (isSelected && !event.content.isRedacted()) {
                    if (event.isMine && userEventPermissions.canRedactOwn
                        || !event.isMine && userEventPermissions.canRedactOther) {
                        handleActionRedact(event)
                    }
                }
            }
            toggleMultiSelectMode()
        }

        // end multi select ===========================================================================
        /**
         * 把自动删除相关的配置，保存到服务器数据库中
         */
        fun CoroutineScope.autoDeleteStateChange(autoDeleteEnum: AutoDeleteEnum) = launch {
            val state = autoDeleteState
            state.autoDeleteEnum = autoDeleteEnum
            val result = room.sendStateEventRaw(
                eventType = state.eventType,
                stateKey = state.stateKey,
                content = state.getContent()
            )
            if (result.isSuccess) {
                Timber.d("autoDeleteStateChange success: eventId = ${result.getOrNull()}")
            } else {
                Timber.w("autoDeleteStateChange error: ${result.exceptionOrNull()}")
            }

        }

        fun CoroutineScope.handleTimelineAction(
            action: TimelineItemAction,
            targetEvent: TimelineItem.Event,
            composerState: MessageComposerState,
            timelineProtectionState: TimelineProtectionState,
            enableTextFormatting: Boolean,
            timelineState: TimelineState,
        ) = launch {
            when (action) {
                TimelineItemAction.CopyText -> handleCopyContents(targetEvent)
                TimelineItemAction.CopyCaption -> handleCopyCaption(targetEvent)
                TimelineItemAction.CopyLink -> handleCopyLink(targetEvent)
                TimelineItemAction.Redact -> handleActionRedact(targetEvent)
                TimelineItemAction.Edit,
                TimelineItemAction.EditPoll -> handleActionEdit(targetEvent, composerState, enableTextFormatting)

                TimelineItemAction.AddCaption -> handleActionAddCaption(targetEvent, composerState)
                TimelineItemAction.EditCaption -> handleActionEditCaption(targetEvent, composerState)
                TimelineItemAction.RemoveCaption -> handleRemoveCaption(targetEvent)
                TimelineItemAction.Reply -> handleActionReply(targetEvent, composerState, timelineProtectionState)
                TimelineItemAction.ReplyInThread -> {
                    val displayThreads = featureFlagService.isFeatureEnabled(FeatureFlags.Threads)
                    if (displayThreads) {
                        // Get either the thread id this event is in, or the event id if it's not in a thread so we can start one
                        val threadId = when (targetEvent.threadInfo) {
                            is TimelineItemThreadInfo.ThreadResponse -> targetEvent.threadInfo.threadRootId
                            is TimelineItemThreadInfo.ThreadRoot, null -> targetEvent.eventId?.toThreadId()
                        } ?: return@launch
                        navigator.navigateToThread(threadId, null)
                    } else {
                        handleActionReply(targetEvent, composerState, timelineProtectionState)
                    }
                }

                TimelineItemAction.ViewSource -> handleShowDebugInfoAction(targetEvent)
                TimelineItemAction.Forward -> handleForwardAction(targetEvent)
                TimelineItemAction.ReportContent -> handleReportAction(targetEvent)
                TimelineItemAction.EndPoll -> handleEndPollAction(targetEvent, timelineState)
                TimelineItemAction.Pin -> handlePinAction(targetEvent)
                TimelineItemAction.Unpin -> handleUnpinAction(targetEvent)
                TimelineItemAction.ViewInTimeline -> Unit
                TimelineItemAction.MultiSelect -> handleMultiSelectAction(targetEvent)
            }
        }

        fun handleEvents(event: MessagesEvents) {
            when (event) {
                is MessagesEvents.HandleAction -> {
                    localCoroutineScope.handleTimelineAction(
                        action = event.action,
                        targetEvent = event.event,
                        composerState = composerState,
                        enableTextFormatting = composerState.showTextFormatting,
                        timelineState = timelineState,
                        timelineProtectionState = timelineProtectionState,
                    )
                }

                is MessagesEvents.ToggleReaction -> {
                    localCoroutineScope.toggleReaction(event.emoji, event.eventOrTransactionId)
                }

                is MessagesEvents.InviteDialogDismissed -> {
                    hasDismissedInviteDialog = true

                    if (event.action == InviteDialogAction.Invite) {
                        localCoroutineScope.reinviteOtherUser(inviteProgress)
                    }
                }

                is MessagesEvents.Dismiss -> actionListState.eventSink(ActionListEvents.Clear)
                is MessagesEvents.OnUserClicked -> {
                    roomMemberModerationState.eventSink(RoomMemberModerationEvents.ShowActionsForUser(event.user))
                }

                is MessagesEvents.MarkAsFullyReadAndExit -> coroutineScope.launch {
                    if (!markingAsReadAndExiting.getAndSet(true)) {
                        val latestEventId = room.liveTimeline.getLatestEventId().getOrElse {
                            Timber.w(it, "Failed to get latest event id to mark as fully read")
                            navigator.close()
                            return@launch
                        }
                        latestEventId?.let { eventId ->
                            sessionCoroutineScope.launch {
                                markAsFullyRead(room.roomId, eventId)
                            }
                        }
                        navigator.close()
                        markingAsReadAndExiting.set(false)
                    }
                }

                is MessagesEvents.ToggleMultiSelectMode -> toggleMultiSelectMode()
                is MessagesEvents.ToggleEventSelection -> toggleItemSelection(event.event)
                MessagesEvents.MultiDelete -> localCoroutineScope.multiDelete()
                is MessagesEvents.AutoDeleteStateChange -> localCoroutineScope.autoDeleteStateChange(event.autoDeleteEnum)
            }
        }

        return MessagesState(
            roomId = room.roomId,
            roomName = roomInfo.name,
            roomAvatar = roomAvatar,
            heroes = heroes,
            userEventPermissions = userEventPermissions,
            composerState = composerState,
            voiceMessageComposerState = voiceMessageComposerState,
            timelineState = timelineState,
            timelineProtectionState = timelineProtectionState,
            identityChangeState = identityChangeState,
            linkState = linkState,
            actionListState = actionListState,
            customReactionState = customReactionState,
            reactionSummaryState = reactionSummaryState,
            readReceiptBottomSheetState = readReceiptBottomSheetState,
            snackbarMessage = snackbarMessage,
            inviteProgress = inviteProgress.value,
            showReinvitePrompt = showReinvitePrompt,
            enableTextFormatting = MessageComposerConfig.ENABLE_RICH_TEXT_EDITING,
            roomCallState = roomCallState,
            appName = buildMeta.applicationName,
            pinnedMessagesBannerState = pinnedMessagesBannerState,
            dmUserVerificationState = dmUserVerificationState,
            roomMemberModerationState = roomMemberModerationState,
            successorRoom = roomInfo.successorRoom,
            selectedEvents = selectedEvents,
            isMultiSelect = isMultiSelect,
            autoDeleteState = autoDeleteState,
            clearProgressIsRunning = clearProgressIsRunning,
            clearProgress = clearProgress
        ) { handleEvents(it) }
    }

    @Composable
    private fun userEventPermissions(roomInfo: RoomInfo): State<UserEventPermissions> {
        val key = if (roomInfo.privilegedCreatorRole && roomInfo.creators.contains(room.sessionId)) {
            Long.MAX_VALUE
        } else {
            roomInfo.roomPowerLevels?.hashCode() ?: 0L
        }
        return produceState(UserEventPermissions.DEFAULT, key1 = key) {
            value = UserEventPermissions(
                canSendMessage = room.canSendMessage(type = MessageEventType.RoomMessage).getOrElse { true },
                canSendReaction = room.canSendMessage(type = MessageEventType.Reaction).getOrElse { true },
                canRedactOwn = room.canRedactOwn().getOrElse { false },
                canRedactOther = room.canRedactOther().getOrElse { false },
                canPinUnpin = room.canPinUnpin().getOrElse { false },
            )
        }
    }

    private fun RoomInfo.avatarData(): AvatarData {
        return AvatarData(
            id = id.value,
            name = name,
            url = avatarUrl,
            size = AvatarSize.TimelineRoom
        )
    }

    private fun RoomInfo.heroes(): List<AvatarData> {
        return heroes.map { user ->
            user.getAvatarData(size = AvatarSize.TimelineRoom)
        }
    }

    private suspend fun handleRemoveCaption(targetEvent: TimelineItem.Event) {
        timelineController.invokeOnCurrentTimeline {
            editCaption(
                eventOrTransactionId = targetEvent.eventOrTransactionId,
                caption = null,
                formattedCaption = null,
            )
        }
    }

    private suspend fun handlePinAction(targetEvent: TimelineItem.Event) {
        if (targetEvent.eventId == null) return
        analyticsService.capture(
            PinUnpinAction(
                from = PinUnpinAction.From.Timeline,
                kind = PinUnpinAction.Kind.Pin,
            )
        )
        timelineController.invokeOnCurrentTimeline {
            pinEvent(targetEvent.eventId)
                .onFailure {
                    Timber.e(it, "Failed to pin event ${targetEvent.eventId}")
                    snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
                }
        }
    }

    private suspend fun handleUnpinAction(targetEvent: TimelineItem.Event) {
        if (targetEvent.eventId == null) return
        analyticsService.capture(
            PinUnpinAction(
                from = PinUnpinAction.From.Timeline,
                kind = PinUnpinAction.Kind.Unpin,
            )
        )
        timelineController.invokeOnCurrentTimeline {
            unpinEvent(targetEvent.eventId)
                .onFailure {
                    Timber.e(it, "Failed to unpin event ${targetEvent.eventId}")
                    snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
                }
        }
    }

    private fun CoroutineScope.toggleReaction(
        emoji: String,
        eventOrTransactionId: EventOrTransactionId,
    ) = launch(dispatchers.io) {
        timelineController.invokeOnCurrentTimeline {
            toggleReaction(emoji, eventOrTransactionId)
                .flatMap { added -> if (added) addRecentEmoji(emoji) else Result.success(Unit) }
                .onFailure { Timber.e(it) }
        }
    }

    private fun CoroutineScope.reinviteOtherUser(inviteProgress: MutableState<AsyncData<Unit>>) = launch(dispatchers.io) {
        inviteProgress.value = AsyncData.Loading()
        runCatchingExceptions {
            val memberList = when (val memberState = room.membersStateFlow.value) {
                is RoomMembersState.Ready -> memberState.roomMembers
                is RoomMembersState.Error -> memberState.prevRoomMembers.orEmpty()
                else -> emptyList()
            }

            val member = memberList.first { it.userId != room.sessionId }
            room.inviteUserById(member.userId).onFailure { t ->
                Timber.e(t, "Failed to reinvite DM partner")
            }.getOrThrow()
        }.fold(
            onSuccess = {
                inviteProgress.value = AsyncData.Success(Unit)
            },
            onFailure = {
                inviteProgress.value = AsyncData.Failure(it)
            }
        )
    }

    private suspend fun handleActionRedact(event: TimelineItem.Event) {
        timelineController.invokeOnCurrentTimeline {
            redactEvent(eventOrTransactionId = event.eventOrTransactionId, reason = null)
                .onFailure { Timber.e(it) }
        }
    }

    private fun handleActionEdit(
        targetEvent: TimelineItem.Event,
        composerState: MessageComposerState,
        enableTextFormatting: Boolean,
    ) {
        when (targetEvent.content) {
            is TimelineItemPollContent -> {
                if (targetEvent.eventId == null) return
                navigator.navigateToEditPoll(targetEvent.eventId)
            }

            else -> {
                val composerMode = MessageComposerMode.Edit(
                    targetEvent.eventOrTransactionId,
                    (targetEvent.content as? TimelineItemTextBasedContent)?.let {
                        if (enableTextFormatting) {
                            it.htmlBody ?: it.body
                        } else {
                            it.body
                        }
                    }.orEmpty(),
                )
                composerState.eventSink(
                    MessageComposerEvents.SetMode(composerMode)
                )
            }
        }
    }

    private fun handleActionAddCaption(
        targetEvent: TimelineItem.Event,
        composerState: MessageComposerState,
    ) {
        val composerMode = MessageComposerMode.EditCaption(
            eventOrTransactionId = targetEvent.eventOrTransactionId,
            content = "",
        )
        composerState.eventSink(
            MessageComposerEvents.SetMode(composerMode)
        )
    }

    private fun handleActionEditCaption(
        targetEvent: TimelineItem.Event,
        composerState: MessageComposerState,
    ) {
        val composerMode = MessageComposerMode.EditCaption(
            eventOrTransactionId = targetEvent.eventOrTransactionId,
            content = (targetEvent.content as? TimelineItemEventContentWithAttachment)?.caption.orEmpty(),
        )
        composerState.eventSink(
            MessageComposerEvents.SetMode(composerMode)
        )
    }

    private suspend fun handleActionReply(
        targetEvent: TimelineItem.Event,
        composerState: MessageComposerState,
        timelineProtectionState: TimelineProtectionState,
    ) {
        if (targetEvent.eventId == null) return
        timelineController.invokeOnCurrentTimeline {
            val replyToDetails = loadReplyDetails(targetEvent.eventId).map(permalinkParser)
            val composerMode = MessageComposerMode.Reply(
                replyToDetails = replyToDetails,
                hideImage = timelineProtectionState.hideMediaContent(targetEvent.eventId),
            )
            composerState.eventSink(
                MessageComposerEvents.SetMode(composerMode)
            )
        }
    }

    private fun handleShowDebugInfoAction(event: TimelineItem.Event) {
        navigator.navigateToEventDebugInfo(event.eventId, event.debugInfo)
    }

    private fun handleForwardAction(event: TimelineItem.Event) {
        if (event.eventId == null) return
        navigator.forwardEvent(event.eventId)
    }

    private fun handleReportAction(event: TimelineItem.Event) {
        if (event.eventId == null) return
        navigator.navigateToReportMessage(event.eventId, event.senderId)
    }

    private fun handleEndPollAction(
        event: TimelineItem.Event,
        timelineState: TimelineState,
    ) {
        event.eventId?.let { timelineState.eventSink(TimelineEvents.EndPoll(it)) }
    }

    private suspend fun handleCopyLink(event: TimelineItem.Event) {
        event.eventId ?: return
        room.getPermalinkFor(event.eventId).fold(
            onSuccess = { permalink ->
                clipboardHelper.copyPlainText(permalink)
                snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_link_copied_to_clipboard))
            },
            onFailure = {
                Timber.e(it, "Failed to get permalink for event ${event.eventId}")
                snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_error))
            }
        )
    }

    private fun handleCopyContents(event: TimelineItem.Event) {
        val content = when (event.content) {
            is TimelineItemTextBasedContent -> event.content.body
            is TimelineItemStateContent -> event.content.body
            else -> return
        }
        clipboardHelper.copyPlainText(content)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            snackbarDispatcher.post(SnackbarMessage(R.string.screen_room_timeline_message_copied))
        }
    }

    private fun handleCopyCaption(event: TimelineItem.Event) {
        val content = (event.content as? TimelineItemEventContentWithAttachment)?.caption ?: return
        clipboardHelper.copyPlainText(content)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            snackbarDispatcher.post(SnackbarMessage(CommonStrings.common_copied_to_clipboard))
        }
    }
}
