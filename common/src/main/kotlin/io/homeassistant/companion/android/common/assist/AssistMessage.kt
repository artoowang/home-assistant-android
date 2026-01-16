package io.homeassistant.companion.android.common.assist

data class AssistMessage(val message: String, val isInput: Boolean, val isError: Boolean = false)
