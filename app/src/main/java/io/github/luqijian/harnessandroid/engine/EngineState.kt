package io.github.luqijian.harnessandroid.engine

sealed interface EngineState {
    data object Idle : EngineState

    data class Installing(val progress: Float, val detail: String) : EngineState

    data class Starting(val detail: String = "") : EngineState

    data class Ready(val url: String, val port: Int) : EngineState

    data class Error(val message: String, val detail: String? = null) : EngineState

    data object Stopped : EngineState
}
