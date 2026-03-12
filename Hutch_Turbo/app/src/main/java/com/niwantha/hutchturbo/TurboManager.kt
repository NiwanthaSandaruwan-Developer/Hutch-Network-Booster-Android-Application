package com.niwantha.hutchturbo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class TurboManager(private val context: Context, private val updateCallback: (String) -> Unit) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isRunning = false
    private var interval: Long = 1000
    
    private var peakDl: Double = 0.0
    private var peakUl: Double = 0.0
    private var totalDlBytes: Long = 0
    private var totalUlBytes: Long = 0
    private var updates: Int = 0
    
    private var lastRxBytes: Long = TrafficStats.getTotalRxBytes()
    private var lastTxBytes: Long = TrafficStats.getTotalTxBytes()

    fun start(interval: Long) {
        if (isRunning) return
        this.interval = interval
        isRunning = true
        
        job = scope.launch {
            while (isRunning) {
                // Generate Traffic
                generateTraffic()
                
                // Monitor Stats
                val currentRx = TrafficStats.getTotalRxBytes()
                val currentTx = TrafficStats.getTotalTxBytes()
                
                val rxDiff = currentRx - lastRxBytes
                val txDiff = currentTx - lastTxBytes
                
                lastRxBytes = currentRx
                lastTxBytes = currentTx
                
                val dlKB = rxDiff / 1024.0
                val ulKB = txDiff / 1024.0
                
                totalDlBytes += rxDiff
                totalUlBytes += txDiff
                
                if (dlKB > peakDl) peakDl = dlKB
                if (ulKB > peakUl) peakUl = ulKB
                
                updates++
                
                // Format output string to match PowerShell parser in renderer.js
                val output = StringBuilder()
                output.append("Adapter : ${getActiveAdapterName()}\n")
                output.append("Updates : $updates\n")
                output.append("Download Speed : ${String.format("%.2f", dlKB)} KB/s  (${String.format("%.2f", dlKB / 1024.0)} MB/s)   Peak: ${String.format("%.2f", peakDl)} KB/s\n")
                output.append("Upload Speed   : ${String.format("%.2f", ulKB)} KB/s  (${String.format("%.2f", ulKB / 1024.0)} MB/s)   Peak: ${String.format("%.2f", peakUl)} KB/s\n")
                output.append("Total Download : $totalDlBytes KB\n") 
                output.append("Total Upload   : $totalUlBytes KB\n")
                
                withContext(Dispatchers.Main) {
                    updateCallback(output.toString())
                }
                
                delay(interval)
            }
        }
    }

    private fun generateTraffic() {
        val urls = listOf(
            "https://hutch.lk/",
            "https://www.cloudflare.com",
            "https://www.google.com"
        )
        
        for (urlString in urls) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.useCaches = false
                connection.inputStream.use { it.read(ByteArray(1024)) } // Read small chunk
                connection.disconnect()
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
    }
    
    fun getActiveAdapterName(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "No connection"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "No connection"
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Network"
            else -> "Other"
        }
    }
}
