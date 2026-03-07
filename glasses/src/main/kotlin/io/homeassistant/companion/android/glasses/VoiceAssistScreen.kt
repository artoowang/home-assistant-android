package io.homeassistant.companion.android.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import androidx.xr.glimmer.list.VerticalList
import androidx.xr.glimmer.list.rememberListState
import com.mikepenz.iconics.compose.Image
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import io.homeassistant.companion.android.common.assist.AssistMessage
import kotlin.math.min

private val DefaultListItemHeight = 64.dp
private val ListItemSpacing = 12.dp

// Lists should only show three items or less within a view.
// https://developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/lists
private const val MaxItemsInList = 3
private val IconSize = 30.dp

// These are the size obtained from running the app on glasses emulator.
internal const val EmulatorScreenWidthDp = 450
internal const val EmulatorScreenHeightDp = 394

// This contains the microphone states for UI.
data class MicState(val recording: Boolean, val lastRecordedLevel: Float)

// `micState` is null if the microphone is not enabled. Otherwise, it indicates the current state of the microphone.
// See MicState.
@Composable
fun VoiceAssistScreen(micState: MicState?, conversation: List<AssistMessage>, toggleMicrophone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ChatListView(
            conversation,
            toggleMicrophone,
        )
        if (micState != null) {
            // TODO: This allows the scale go all the way up to 2 (when level is 1.0). Might need to revisit.
            val scale = 1f + micState.lastRecordedLevel
            Image(
                asset = CommunityMaterial.Icon3.cmd_microphone,
                contentDescription = "Microphone",
                colorFilter = ColorFilter.tint(
                    if (micState.recording) GlimmerTheme.colors.positive else GlimmerTheme.colors.outline,
                ),
                modifier = Modifier
                    .size(28.dp)
                    .scale(scale),
            )
        }
    }
}

// Represents a list of ChatItems, and an exit button.
@Composable
private fun ChatListView(conversation: List<AssistMessage>, toggleMicrophone: () -> Unit) {
    // Used to scroll list. This is "remembered" so it persists across recompositions.
    val listState = rememberListState()

    // Number of chat strings plus the exit button.
    val totalItems = conversation.size + 1
    val listHeight = (
        min(totalItems, MaxItemsInList) * DefaultListItemHeight.value +
            min(totalItems - 1, MaxItemsInList) * ListItemSpacing.value
        )

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
                ChatItem(msg, toggleMicrophone)
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

// Represents a single chat conversation entry. `modifier` is used for ListItem.
@Composable
private fun ChatItem(msg: AssistMessage, toggleMicrophone: () -> Unit) {
    val textColor = when {
        msg.isError -> GlimmerTheme.colors.negative
        msg.isInput -> GlimmerTheme.colors.outline
        else -> Color.Unspecified
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .align(if (msg.isInput) Alignment.CenterEnd else Alignment.CenterStart),
            onClick = toggleMicrophone,
        ) {
            Text(
                text = msg.message,
                color = textColor,
                style = GlimmerTheme.typography.bodySmall,
                fontSize = 17.sp,
            )
        }
    }
}

@Preview(
    widthDp = EmulatorScreenWidthDp,
    heightDp = EmulatorScreenHeightDp,
)
@Composable
private fun VoiceAssistScreenPreview() {
    GlimmerTheme {
        VoiceAssistScreen(
            micState = MicState(
                recording = true,
                lastRecordedLevel = 0.0f,
            ),
            conversation = listOf(
                AssistMessage("What time is it?", isInput = true),
                AssistMessage("It's 12:30pm", isInput = false),
                AssistMessage("Say something", isInput = true),
                AssistMessage(
                    "You've correctly identified the fontSize parameter, but it requires a specific unit type, " +
                        "not just a raw number. In Jetpack Compose, font sizes should be specified using the .sp " +
                        "(scale-independent pixels) unit.\nTo fix this, you need to import sp and use it to define the font " +
                        "size.",
                    isInput = false,
                ),
                AssistMessage("...", isInput = true),
            ),
            toggleMicrophone = {},
        )
    }
}
