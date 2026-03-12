package com.niwantha.hutchturbo

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var turboManager: TurboManager? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val output = intent?.getStringExtra("output") ?: ""
            runOnUiThread {
                sendToJs("script-output", output)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Use a simple layout container to handle padding/insets
        val rootLayout = android.widget.FrameLayout(this)
        webView = WebView(this)
        rootLayout.addView(webView)
        setContentView(rootLayout)

        // Prevent app from going under status bar (Fix "automatic full screen")
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        // Enable remote debugging
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Set a default background color
        webView.setBackgroundColor(Color.parseColor("#080a0f"))

        turboManager = TurboManager(this) { } 

        val bridge = TurboBridge(
            onStartTurbo = { interval ->
                startTurboService(interval)
            },
            onStopTurbo = {
                stopTurboService()
            },
            onAdapterRequest = {
                val adapterName = turboManager?.getActiveAdapterName() ?: "Unknown"
                sendToJs("adapter-found", adapterName)
            },
            onCloseRequest = {
                finish()
            }
        )

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // Allow cross-origin requests from file URLs (important for some JS frameworks)
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            databaseEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d("HutchTurbo", "Page Started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("HutchTurbo", "Page Finished: $url")
                
                val adapterName = turboManager?.getActiveAdapterName() ?: "Unknown"
                sendToJs("adapter-found", adapterName)
                
                if (TurboService.isRunning) {
                    sendToJs("script-output", TurboService.lastOutput)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.e("HutchTurbo", "WebView Error: ${error?.description} code: ${error?.errorCode} for ${request?.url}")
                }
            }
            
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed() // Proceed with SSL errors for the booster/CDNs if needed (common in debugging)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("HutchTurboConsole", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                return true
            }
        }

        webView.addJavascriptInterface(bridge, "TurboBridge")
        
        // Use a slight delay to ensure everything is ready? (Sometimes helps with white screen on start)
        webView.postDelayed({
            webView.loadUrl("file:///android_asset/src/index.html")
        }, 100)

        checkPermissions()
        
        val filter = IntentFilter("TurboUpdate")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun startTurboService(interval: Long) {
        val intent = Intent(this, TurboService::class.java)
        intent.putExtra("interval", interval)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopTurboService() {
        val intent = Intent(this, TurboService::class.java)
        stopService(intent)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun sendToJs(channel: String, data: String) {
        runOnUiThread {
            // Escape special chars better
            val escapedData = data.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
            webView.evaluateJavascript("if(window.sendToJS) { window.sendToJS('$channel', `$escapedData`); }", null)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {}
        super.onDestroy()
    }
}