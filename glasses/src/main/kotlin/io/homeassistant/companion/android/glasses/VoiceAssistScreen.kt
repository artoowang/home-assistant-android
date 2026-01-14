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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import androidx.xr.glimmer.list.VerticalList
import io.homeassistant.companion.android.glasses.R as GlassesR
import kotlin.math.min
import timber.log.Timber

private val DefaultListItemHeight = 64.dp
private const val MaxItemsInList = 4
private val IconSize = 30.dp

@Composable
fun VoiceAssistScreen(
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
        GlimmerScreenContent(
            onExit = onExit,
        )
    }
}

@Composable
private fun GlimmerScreenContent(
    onExit: () -> Unit
) {
    ChatListView(
        onExit = onExit,
    )
}

@Composable
private fun ChatListView(
    onExit: () -> Unit,
) {
    // TODO
    val totalItems = 1

    val listHeight = (min(totalItems, MaxItemsInList) * DefaultListItemHeight.value +
        min(totalItems - 1, MaxItemsInList) * 12f)

    VerticalList(
        modifier = Modifier.height(listHeight.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ListItem(
                onClick = onExit,
                leadingIcon = {
                    Image(
                        painter = painterResource(id = GlassesR.drawable.ic_close),
                        contentDescription = "Exit the app",
                        modifier = Modifier.size(IconSize)
                    )
                }
            ) {
                Text(text = "Exit")
            }
        }
    }
}

@Preview
@Composable
private fun ChatListViewPreview() {
    ChatListView(onExit = {})
}

//@Composable
//private fun GlimmerMicControlItem(
//    isMicOn: Boolean,
//    onToggle: () -> Unit
//) {
//    val icon = if (isMicOn) UiComponentR.drawable.ic_mic_off else UiComponentR.drawable.ic_ai_mic
//
//
//    val displayTask = if (isMicOn) {
//        stringResource(R.string.mic_on_label)
//    } else {
//        stringResource(R.string.mic_off_label)
//    }
//
//    val contentDesc = if (isMicOn) {
//        stringResource(R.string.mic_status_on)
//    } else {
//        stringResource(R.string.mic_status_off)
//    }
//
//    ListItem(
//        onClick = onToggle,
//        leadingIcon = {
//            Image(
//                painter = painterResource(id = icon),
//                contentDescription = contentDesc,
//                modifier = Modifier.size(IconSize)
//            )
//        }
//    ) {
//        Text(text = displayTask)
//    }
//}
//
//@Composable
//private fun GlimmerTodoItem(
//    task: Todo,
//    onToggle: (Int) -> Unit
//) {
//    val icon = if (task.isCompleted) UiComponentR.drawable.ic_check else UiComponentR.drawable.ic_circle
//
//    ListItem(
//        onClick = { onToggle(task.id) },
//        leadingIcon = {
//            Image(
//                painter = painterResource(id = icon),
//                contentDescription = if (task.isCompleted)
//                    stringResource(R.string.status_completed)
//                else
//                    stringResource(R.string.status_pending),
//                modifier = Modifier.size(IconSize)
//            )
//        }
//    ) {
//        Text(
//            text = task.task,
//            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
//        )
//    }
//}
