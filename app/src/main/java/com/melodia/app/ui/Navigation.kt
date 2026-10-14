package com.melodia.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Tab { CREATE, LIBRARY, SETTINGS }

/** Full-screen pages shown on top of the tabs. */
sealed interface Overlay {
    data class VoiceDesigner(val voiceId: String?) : Overlay
    data object Player : Overlay
    data object PhoneAi : Overlay
    data object PcSetup : Overlay
    data object TermuxGuide : Overlay
    data object EngineLog : Overlay
    data object About : Overlay
}

class Navigator {
    var tab by mutableStateOf(Tab.CREATE)
    val stack = mutableStateListOf<Overlay>()

    fun push(overlay: Overlay) {
        stack.add(overlay)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun goTo(tab: Tab) {
        stack.clear()
        this.tab = tab
    }
}
