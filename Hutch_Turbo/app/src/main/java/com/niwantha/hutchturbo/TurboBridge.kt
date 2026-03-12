package com.niwantha.hutchturbo

import android.webkit.JavascriptInterface
import org.json.JSONObject

class TurboBridge(
    private val onStartTurbo: (Long) -> Unit,
    private val onStopTurbo: () -> Unit,
    private val onAdapterRequest: () -> Unit,
    private val onCloseRequest: () -> Unit
) {
    @JavascriptInterface
    fun startTurbo(optionsJson: String) {
        try {
            val options = JSONObject(optionsJson)
            val interval = options.optLong("interval", 1000L)
            onStartTurbo(interval)
        } catch (e: Exception) {
            onStartTurbo(1000L)
        }
    }

    @JavascriptInterface
    fun stopTurbo() {
        onStopTurbo()
    }

    @JavascriptInterface
    fun getAdapter() {
        onAdapterRequest()
    }

    @JavascriptInterface
    fun closeApp() {
        onCloseRequest()
    }

    @JavascriptInterface
    fun isServiceRunning(): Boolean {
        return TurboService.isRunning
    }
}
