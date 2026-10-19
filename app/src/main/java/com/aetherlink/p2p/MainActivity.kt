package com.aetherlink.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQ_CODE = 1001
        private const val TCP_PORT = 8988
    }

    private lateinit var webView: WebView
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var broadcastReceiver: BroadcastReceiver? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var socketWriter: PrintWriter? = null
    private val isSocketRunning = AtomicBoolean(false)
    private var connectedPeerName: String = "Tactical Node"

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(NativeBridge(), "AndroidBridge")
        }
        setContentView(webView)

        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = wifiP2pManager?.initialize(this, mainLooper, null)

        setupBroadcastReceiver()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun hasRequiredPermissions(): Boolean {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQ_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                emitToJs("onSystemLog", JSONObject().put("msg", "Permissions granted. Ready for P2P scan."))
                startPeerDiscoveryInternal()
            } else {
                emitToJs("onError", JSONObject().put("msg", "Location / Nearby Wi-Fi permissions are required for hardware peer discovery."))
            }
        }
    }

    private fun setupBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        emitToJs("onP2pState", JSONObject().put("enabled", enabled))
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (hasRequiredPermissions()) {
                            wifiP2pManager?.requestPeers(channel) { peerList ->
                                val arr = JSONArray()
                                for (device in peerList.deviceList) {
                                    val obj = JSONObject().apply {
                                        put("name", device.deviceName.ifEmpty { "Unknown Node" })
                                        put("address", device.deviceAddress)
                                        put("status", mapDeviceStatus(device.status))
                                    }
                                    arr.put(obj)
                                }
                                emitToJs("onPeersUpdated", JSONObject().put("peers", arr))
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }

                        if (networkInfo?.isConnected == true) {
                            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                                handleP2pConnectionEstablished(info)
                            }
                        } else {
                            closeActiveSockets()
                            emitToJs("onConnectionChanged", JSONObject().apply {
                                put("connected", false)
                                put("role", "NONE")
                                put("peerName", "")
                            })
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        device?.let {
                            emitToJs("onSelfDevice", JSONObject().apply {
                                put("name", it.deviceName.ifEmpty { Build.MODEL })
                                put("address", it.deviceAddress)
                            })
                        }
                    }
                }
            }
        }
    }

    private fun mapDeviceStatus(status: Int): String = when (status) {
        WifiP2pDevice.AVAILABLE -> "AVAILABLE"
        WifiP2pDevice.INVITED -> "INVITED"
        WifiP2pDevice.CONNECTED -> "CONNECTED"
        WifiP2pDevice.FAILED -> "FAILED"
        WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN"
    }

    private fun handleP2pConnectionEstablished(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        if (isSocketRunning.get()) return

        val isGroupOwner = info.isGroupOwner
        val ownerAddress = info.groupOwnerAddress?.hostAddress ?: return

        emitToJs("onSystemLog", JSONObject().put(
            "msg",
            if (isGroupOwner) "Group formed: Operating as Group Owner (TCP Server)"
            else "Group formed: Operating as Client -> $ownerAddress"
        ))

        if (isGroupOwner) {
            startTcpServer()
        } else {
            startTcpClient(ownerAddress)
        }
    }

    private fun startTcpServer() {
        isSocketRunning.set(true)
        ioExecutor.execute {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(TCP_PORT).apply { reuseAddress = true }
                emitToJs("onSystemLog", JSONObject().put("msg", "Awaiting peer TCP handshake on port $TCP_PORT..."))
                val client = serverSocket!!.accept()
                manageConnectedSocket(client, "GROUP_OWNER")
            } catch (e: Exception) {
                if (isSocketRunning.get()) {
                    emitToJs("onError", JSONObject().put("msg", "Server socket error: ${e.localizedMessage}"))
                }
                closeActiveSockets()
            }
        }
    }

    private fun startTcpClient(hostIp: String) {
        isSocketRunning.set(true)
        ioExecutor.execute {
            var attempts = 0
            var connected = false
            while (attempts < 8 && isSocketRunning.get() && !connected) {
                try {
                    attempts++
                    val socket = Socket()
                    socket.connect(InetSocketAddress(hostIp, TCP_PORT), 2500)
                    connected = true
                    manageConnectedSocket(socket, "CLIENT")
                } catch (e: Exception) {
                    Thread.sleep(700)
                }
            }
            if (!connected && isSocketRunning.get()) {
                emitToJs("onError", JSONObject().put("msg", "Failed to establish TCP link with Group Owner."))
                closeActiveSockets()
            }
        }
    }

    private fun manageConnectedSocket(socket: Socket, role: String) {
        try {
            activeSocket = socket
            socketWriter = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

            // Send immediate identity handshake
            val helloPacket = JSONObject().apply {
                put("type", "HANDSHAKE")
                put("sender", Build.MODEL)
                put("timestamp", System.currentTimeMillis())
            }
            socketWriter?.println(helloPacket.toString())

            emitToJs("onConnectionChanged", JSONObject().apply {
                put("connected", true)
                put("role", role)
                put("peerName", connectedPeerName)
                put("ip", socket.inetAddress?.hostAddress ?: "P2P-Link")
            })

            var line: String?
            while (isSocketRunning.get() && reader.readLine().also { line = it } != null) {
                val raw = line ?: continue
                val packet = JSONObject(raw)
                when (packet.optString("type")) {
                    "HANDSHAKE" -> {
                        connectedPeerName = packet.optString("sender", "Remote Node")
                        emitToJs("onPeerIdentified", JSONObject().put("peerName", connectedPeerName))
                    }
                    "CHAT" -> {
                        emitToJs("onMessageReceived", packet)
                    }
                }
            }
        } catch (e: Exception) {
            if (isSocketRunning.get()) {
                emitToJs("onSystemLog", JSONObject().put("msg", "Peer socket closed."))
            }
        } finally {
            closeActiveSockets()
            emitToJs("onConnectionChanged", JSONObject().apply {
                put("connected", false)
                put("role", "NONE")
                put("peerName", "")
            })
        }
    }

    private fun closeActiveSockets() {
        isSocketRunning.set(false)
        try { socketWriter?.close() } catch (_: Exception) {}
        try { activeSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        socketWriter = null
        activeSocket = null
        serverSocket = null
    }

    @SuppressLint("MissingPermission")
    private fun startPeerDiscoveryInternal() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            return
        }
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                emitToJs("onSystemLog", JSONObject().put("msg", "Wi-Fi Direct radar sweep active."))
            }

            override fun onFailure(reason: Int) {
                val reasonText = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported on this device."
                    WifiP2pManager.BUSY -> "Wi-Fi Direct hardware busy. Ensure Wi-Fi and Location are toggled ON."
                    else -> "Discovery failed (code $reason)."
                }
                emitToJs("onError", JSONObject().put("msg", reasonText))
            }
        })
    }

    private fun emitToJs(eventName: String, payload: JSONObject) {
        val escapedJson = payload.toString()
        mainHandler.post {
            webView.evaluateJavascript(
                "window.onNativeEvent && window.onNativeEvent('$eventName', $escapedJson);",
                null
            )
        }
    }

    inner class NativeBridge {

        @JavascriptInterface
        fun startDiscovery() {
            mainHandler.post { startPeerDiscoveryInternal() }
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun connectToPeer(deviceAddress: String, deviceName: String) {
            mainHandler.post {
                if (!hasRequiredPermissions()) {
                    requestRequiredPermissions()
                    return@post
                }
                connectedPeerName = deviceName.ifEmpty { "Remote Node" }
                val config = WifiP2pConfig().apply {
                    this.deviceAddress = deviceAddress
                    wps.setup = WpsInfo.PBC
                }
                wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        emitToJs("onSystemLog", JSONObject().put("msg", "Link invitation transmitted to $connectedPeerName..."))
                    }

                    override fun onFailure(reason: Int) {
                        emitToJs("onError", JSONObject().put("msg", "Connection handshake failed (code $reason)."))
                    }
                })
            }
        }

        @JavascriptInterface
        fun sendMessage(text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            ioExecutor.execute {
                try {
                    val writer = socketWriter
                    if (writer == null || !isSocketRunning.get()) {
                        emitToJs("onError", JSONObject().put("msg", "No active TCP link. Connect to a peer first."))
                        return@execute
                    }
                    val packet = JSONObject().apply {
                        put("type", "CHAT")
                        put("sender", Build.MODEL)
                        put("text", clean)
                        put("timestamp", System.currentTimeMillis())
                    }
                    writer.println(packet.toString())
                    emitToJs("onMessageSent", packet)
                } catch (e: Exception) {
                    emitToJs("onError", JSONObject().put("msg", "Failed to transmit packet: ${e.localizedMessage}"))
                }
            }
        }

        @JavascriptInterface
        fun disconnectPeer() {
            mainHandler.post {
                closeActiveSockets()
                wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        emitToJs("onSystemLog", JSONObject().put("msg", "P2P group terminated."))
                    }
                    override fun onFailure(reason: Int) {
                        emitToJs("onSystemLog", JSONObject().put("msg", "Socket closed."))
                    }
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        broadcastReceiver?.let { registerReceiver(it, intentFilter) }
    }

    override fun onPause() {
        super.onPause()
        broadcastReceiver?.let { unregisterReceiver(it) }
    }

    override fun onDestroy() {
        closeActiveSockets()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
