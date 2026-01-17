package io.homeassistant.companion.android.glasses

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import androidx.xr.glimmer.list.VerticalList
import androidx.xr.glimmer.list.rememberListState
import io.homeassistant.companion.android.common.assist.AssistMessage
import io.homeassistant.companion.android.glasses.R as GlassesR
import kotlin.math.min
import timber.log.Timber

private val DefaultListItemHeight = 64.dp
private val ListItemSpacing = 12.dp
// Lists should only show three items or less within a view.
// https://developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/lists
private const val MaxItemsInList = 3
private val IconSize = 30.dp

@Composable
fun VoiceAssistScreen(
    conversation: List<AssistMessage>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val onExit = { activity.finish() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ChatListView(
            conversation = conversation,
            onExit = onExit,
        )
    }
}

// Represents a list of ChatItems, and an exit button.
@Composable
private fun ChatListView(
    conversation: List<AssistMessage>,
    onExit: () -> Unit,
) {
    // Used to scroll list. This is "remembered" so it persists across recompositions.
    val listState = rememberListState()

    // Number of chat strings plus the exit button.
    val totalItems = conversation.size + 1
    val listHeight = (min(totalItems, MaxItemsInList) * DefaultListItemHeight.value +
        min(totalItems - 1, MaxItemsInList) * ListItemSpacing.value)

    if (conversation.isNotEmpty()) {
        // Scroll to the last conversation item when it changes.
        LaunchedEffect(conversation.last().hashCode()) {
            // Scroll to the last item in the conversation.
            listState.animateScrollToItem(conversation.size - 1)
        }
    }

    VerticalList(
        modifier = Modifier.height(listHeight.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
        state = listState,
    ) {
        for (msg in conversation) {
            // TODO: We should use stable key so the list can animate individual messages correctly.
            item {
                ChatItem(msg)
            }
        }

        // TODO: Disabled exit button. For unknown reason, when adding this button the animateScrollToItem() starts to
        // behave unpredictably.
        // item {
        //     ListItem(
        //         onClick = onExit,
        //         leadingIcon = {
        //             Image(
        //                 painter = painterResource(id = GlassesR.drawable.ic_close),
        //                 contentDescription = "Exit the app",
        //                 modifier = Modifier.size(IconSize),
        //             )
        //         }
        //     ) {
        //         Text(text = "Exit")
        //     }
        // }
    }
}

// Used to preview ChatList.
@Preview
@Composable
private fun ChatListViewPreview() {
    ChatListView(
        conversation = listOf(
            AssistMessage("What time is it?", true),
            AssistMessage("It's 12:30pm", false),
            AssistMessage("Say something", true),
            AssistMessage("You've correctly identified the fontSize parameter, but it requires a specific unit type, " +
                "not just a raw number. In Jetpack Compose, font sizes should be specified using the .sp " +
                "(scale-independent pixels) unit.\nTo fix this, you need to import sp and use it to define the font " +
                "size.", false),
        ),
        onExit = {},
    )
}

// Represents a single chat conversation entry.
@Composable
private fun ChatItem(msg: AssistMessage, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier,
    ) {
        Text(
            text = msg.message,
            fontSize = 17.sp,
        )
    }
}
