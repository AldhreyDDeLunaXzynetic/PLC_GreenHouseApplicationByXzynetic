



@file:Suppress("DEPRECATION", "SpellCheckingInspection")
@file:android.annotation.SuppressLint("SetTextI18n", "MissingPermission", "UseKtx")

package com.xzynetic.greenhouseplc

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var btnConnect: Button
    private lateinit var txtStatus: TextView
    private lateinit var progressConnect: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private val uiAnimators = mutableListOf<Animator>()

    private var mediaPlayer: MediaPlayer? = null
    private var connectedNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private var targetSsid = "GreenHouseByXzynetic"
    private var targetPassword = "Xzynetic"
    private var targetIp = "192.168.4.1"

    private var isConnecting = false
    private var isNavigating = false

    private val timeoutRunnable = Runnable {
        if (isConnecting && progressConnect.visibility == View.VISIBLE) {
            isConnecting = false
            setLoading(false, "Connection timeout. Please enter Wi-Fi details manually.")
            showManualConnectionDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.btnConnect)
        txtStatus = findViewById(R.id.txtStatus)
        progressConnect = findViewById(R.id.progressConnect)

        startUiAnimations()
        startBackgroundMusic()

        /*
            REAL CONNECTION MODE:
            Connect button now does the actual Wi-Fi/ESP32 verification flow.
            It will only open GhouseActivity after /status responds correctly.
        */
        btnConnect.setOnClickListener {
            startConnectionFlow(targetSsid, targetPassword, targetIp)
        }

        /*
            SECRET SIMULATION MODE:
            Press the floating leaf badge to open GhouseActivity in simulation mode.
            This does NOT connect to ESP32.
            This is useful for UI/demo/debugging.
        */
        findViewById<View>(R.id.leafBadge).setOnClickListener {
            openGhouseSimulation()
        }

        findViewById<View>(R.id.leafBadge).setOnLongClickListener {
            Toast.makeText(this, "Opening greenhouse simulation...", Toast.LENGTH_SHORT).show()
            openGhouseSimulation()
            true
        }
    }

    private fun openGhouseSimulation() {
        if (isNavigating) return

        isNavigating = true
        isConnecting = false
        handler.removeCallbacks(timeoutRunnable)
        cleanupNetworkCallback()

        val intent = Intent(this, GhouseActivity::class.java)
        intent.putExtra("esp_ip", targetIp)
        intent.putExtra("ESP_IP", targetIp)

        // GhouseActivity patch below uses these extras.
        intent.putExtra("GHOUSE_SIMULATION", true)
        intent.putExtra("SIMULATION_MODE", true)

        startActivity(intent)
    }

    private fun startUiAnimations() {
        runCatching {
            findViewById<View>(R.id.leafBadge)
                .startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_leaf))
        }

        runCatching {
            findViewById<View>(R.id.btnGlow)
                .startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_glow))
        }

        runCatching {
            findViewById<View>(R.id.cardMain)
                .startAnimation(AnimationUtils.loadAnimation(this, R.anim.card_enter))
        }

        runCatching {
            startFeatureIconAnimations()
            startExtraWindAnimations()
        }
    }

    private fun startFeatureIconAnimations() {
        val tempIcon = findViewById<View>(R.id.icTempFeature)
        val waterIcon = findViewById<View>(R.id.icWaterFeature)
        val lightIcon = findViewById<View>(R.id.icLightFeature)

        animateTempIcon(tempIcon)
        animateWaterIcon(waterIcon)
        animateLightIcon(lightIcon)
    }

    private fun startExtraWindAnimations() {
        val decorLeaf = findViewById<View>(R.id.icDecorLeaf)
        val leafBadgeImage = findViewById<View>(R.id.icLeafBadgeImage)
        val signalIcon = findViewById<View>(R.id.icSignalSmall)
        val wifiIcon = findViewById<View>(R.id.icWifiButton)
        val arrowIcon = findViewById<View>(R.id.icArrowButton)
        val connectButton = findViewById<View>(R.id.btnConnect)
        val greenhouseTitle = findViewById<View>(R.id.titleGreenhouseRow)

        animateSmallLeafInWind(decorLeaf)
        animateBadgeLeaf(leafBadgeImage)
        animateSignal(signalIcon)
        animateWifiIcon(wifiIcon)
        animateArrow(arrowIcon)
        animateConnectButton(connectButton)
        animateTitleBreath(greenhouseTitle)
    }

    private fun animateTempIcon(view: View) {
        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.TRANSLATION_Y,
                0f,
                (-6f).dp(),
                0f,
                3f.dp(),
                0f
            ).apply {
                duration = 1800
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_X,
                1f,
                1.06f,
                1f
            ).apply {
                duration = 1800
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_Y,
                1f,
                1.06f,
                1f
            ).apply {
                duration = 1800
                interpolator = AccelerateDecelerateInterpolator()
            }
        )
    }

    private fun animateWaterIcon(view: View) {
        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.TRANSLATION_Y,
                0f,
                (-8f).dp(),
                0f,
                (-4f).dp(),
                0f
            ).apply {
                duration = 1600
                startDelay = 180
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.ROTATION,
                0f,
                -4f,
                4f,
                0f
            ).apply {
                duration = 1600
                startDelay = 180
                interpolator = AccelerateDecelerateInterpolator()
            }
        )
    }

    private fun animateLightIcon(view: View) {
        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_X,
                1f,
                1.16f,
                1f
            ).apply {
                duration = 1200
                startDelay = 320
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_Y,
                1f,
                1.16f,
                1f
            ).apply {
                duration = 1200
                startDelay = 320
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.ALPHA,
                0.70f,
                1f,
                0.70f
            ).apply {
                duration = 1200
                startDelay = 320
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.ROTATION,
                -5f,
                5f,
                -5f
            ).apply {
                duration = 2200
                startDelay = 320
                interpolator = AccelerateDecelerateInterpolator()
            }
        )
    }

    private fun animateSmallLeafInWind(view: View) {
        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.TRANSLATION_X,
                0f,
                5f.dp(),
                0f,
                (-5f).dp(),
                0f
            ).apply {
                duration = 1900
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.ROTATION,
                -12f,
                12f,
                -12f
            ).apply {
                duration = 1900
                interpolator = AccelerateDecelerateInterpolator()
            }
        )
    }

    private fun animateBadgeLeaf(view: View) {
        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.ROTATION,
                -8f,
                8f,
                -8f
            ).apply {
                duration = 2100
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_X,
                1f,
                1.08f,
                1f
            ).apply {
                duration = 2100
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_Y,
                1f,
                1.08f,
                1f
            ).apply {
                duration = 2100
                interpolator = AccelerateDecelerateInterpolator()
            }
        )
    }

    private fun animateSignal(view: View) {
        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.ALPHA,
                0.45f,
                1f,
                0.45f
            ).apply {
                duration = 1300
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_X,
                0.92f,
                1.12f,
                0.92f
            ).apply {
                duration = 1300
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_Y,
                0.92f,
                1.12f,
                0.92f
            ).apply {
                duration = 1300
                interpolator = AccelerateDecelerateInterpolator()
            }
        )
    }

    private fun animateWifiIcon(view: View) {
        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.TRANSLATION_Y,
                0f,
                (-3f).dp(),
                0f
            ).apply {
                duration = 1200
                startDelay = 160
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.ALPHA,
                0.75f,
                1f,
                0.75f
            ).apply {
                duration = 1200
                startDelay = 160
                interpolator = AccelerateDecelerateInterpolator()
            }
        )
    }

    private fun animateArrow(view: View) {
        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.TRANSLATION_X,
                0f,
                6f.dp(),
                0f
            ).apply {
                duration = 1000
                startDelay = 260
                interpolator = AccelerateDecelerateInterpolator()
            }
        )
    }

    private fun animateConnectButton(view: View) {
        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_X,
                1f,
                1.018f,
                1f
            ).apply {
                duration = 1800
                startDelay = 400
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_Y,
                1f,
                1.018f,
                1f
            ).apply {
                duration = 1800
                startDelay = 400
                interpolator = AccelerateDecelerateInterpolator()
            }
        )
    }

    private fun animateTitleBreath(view: View) {
        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_X,
                1f,
                1.015f,
                1f
            ).apply {
                duration = 2400
                interpolator = AccelerateDecelerateInterpolator()
            }
        )

        startLoop(
            ObjectAnimator.ofFloat(
                view,
                View.SCALE_Y,
                1f,
                1.015f,
                1f
            ).apply {
                duration = 2400
                interpolator = AccelerateDecelerateInterpolator()
            }
        )
    }

    private fun startLoop(animator: ObjectAnimator) {
        animator.repeatCount = ValueAnimator.INFINITE
        animator.repeatMode = ValueAnimator.RESTART
        uiAnimators.add(animator)
        animator.start()
    }

    private fun Float.dp(): Float {
        return this * resources.displayMetrics.density
    }

    private fun startBackgroundMusic() {
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.greenhouse_bg_loop)
            mediaPlayer?.isLooping = true
            mediaPlayer?.setVolume(0.18f, 0.18f)
            mediaPlayer?.start()
        } catch (_: Exception) {
        }
    }

    private fun goToGhouseActivity(ip: String) {
        if (isNavigating) return

        isNavigating = true
        isConnecting = false
        handler.removeCallbacks(timeoutRunnable)
        cleanupNetworkCallback()

        val intent = Intent(this, GhouseActivity::class.java)
        intent.putExtra("esp_ip", ip)
        intent.putExtra("ESP_IP", ip)
        intent.putExtra("GHOUSE_SIMULATION", false)
        intent.putExtra("SIMULATION_MODE", false)

        startActivity(intent)
    }

    private fun startConnectionFlow(ssid: String, password: String, ip: String) {
        if (isNavigating) return

        isConnecting = true
        cleanupNetworkCallback()
        handler.removeCallbacks(timeoutRunnable)

        setLoading(true, "Connecting to $ssid...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithWifiSpecifier(ssid, password, ip)
        } else {
            txtStatus.text = "Connect your phone manually to $ssid, then checking ESP32..."
            verifyEspConnection(ip)
        }

        handler.postDelayed(timeoutRunnable, 10000)
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun connectWithWifiSpecifier(ssid: String, password: String, ip: String) {
        try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            connectedNetworkCallback = object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {
                    super.onAvailable(network)

                    connectivityManager.bindProcessToNetwork(network)

                    runOnUiThread {
                        if (!isNavigating) {
                            txtStatus.text = "Wi-Fi connected. Checking greenhouse PLC..."
                        }
                    }

                    verifyEspConnection(ip)
                }

                override fun onUnavailable() {
                    super.onUnavailable()

                    runOnUiThread {
                        if (!isNavigating) {
                            isConnecting = false
                            handler.removeCallbacks(timeoutRunnable)
                            setLoading(false, "Wi-Fi connection failed.")
                            showManualConnectionDialog()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)

                    runOnUiThread {
                        if (!isNavigating) {
                            isConnecting = false
                            setLoading(false, "Connection lost.")
                        }
                    }
                }
            }

            connectivityManager.requestNetwork(request, connectedNetworkCallback!!)

        } catch (_: SecurityException) {
            isConnecting = false
            handler.removeCallbacks(timeoutRunnable)
            setLoading(false, "Missing Wi-Fi permission. Please allow permissions.")
            showManualConnectionDialog()

        } catch (_: Exception) {
            isConnecting = false
            handler.removeCallbacks(timeoutRunnable)
            setLoading(false, "Unable to start Wi-Fi connection.")
            showManualConnectionDialog()
        }
    }

    private fun verifyEspConnection(ip: String) {
        thread {
            try {
                val url = URL("http://$ip/status")
                val connection = url.openConnection() as HttpURLConnection

                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                connection.disconnect()

                if (responseCode in 200..299) {
                    runOnUiThread {
                        isConnecting = false
                        handler.removeCallbacks(timeoutRunnable)
                        setLoading(false, "Connected successfully.")
                        goToGhouseActivity(ip)
                    }
                } else {
                    runOnUiThread {
                        if (!isNavigating) {
                            isConnecting = false
                            handler.removeCallbacks(timeoutRunnable)
                            setLoading(false, "ESP32 did not respond correctly.")
                            showManualConnectionDialog()
                        }
                    }
                }

            } catch (_: Exception) {
                runOnUiThread {
                    if (!isNavigating) {
                        isConnecting = false
                        handler.removeCallbacks(timeoutRunnable)
                        setLoading(false, "Unable to reach greenhouse PLC.")
                        showManualConnectionDialog()
                    }
                }
            }
        }
    }

    private fun showManualConnectionDialog() {
        if (isNavigating) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_connection, null)

        val inputSsid = dialogView.findViewById<EditText>(R.id.inputSsid)
        val inputPassword = dialogView.findViewById<EditText>(R.id.inputPassword)
        val inputIp = dialogView.findViewById<EditText>(R.id.inputIp)

        inputSsid.setText(targetSsid)
        inputPassword.setText(targetPassword)
        inputIp.setText(targetIp)

        AlertDialog.Builder(this)
            .setTitle("Manual Connection")
            .setMessage("Enter the ESP32 greenhouse PLC Wi-Fi details.")
            .setView(dialogView)
            .setPositiveButton("Retry") { _, _ ->
                targetSsid = inputSsid.text.toString().trim()
                targetPassword = inputPassword.text.toString().trim()
                targetIp = inputIp.text.toString().trim()

                startConnectionFlow(targetSsid, targetPassword, targetIp)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                isConnecting = false
                handler.removeCallbacks(timeoutRunnable)
                setLoading(false, "Target Wi-Fi: GreenHouseByXzynetic")
                dialog.dismiss()
            }
            .show()
    }

    private fun setLoading(isLoading: Boolean, message: String) {
        progressConnect.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnConnect.isEnabled = !isLoading
        btnConnect.text = if (isLoading) "Connecting..." else "Connect"
        txtStatus.text = message
    }

    private fun cleanupNetworkCallback() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        connectedNetworkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }

        connectedNetworkCallback = null
    }

    override fun onPause() {
        super.onPause()

        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()

        isNavigating = false

        try {
            mediaPlayer?.start()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacks(timeoutRunnable)

        uiAnimators.forEach {
            try {
                it.cancel()
            } catch (_: Exception) {
            }
        }

        uiAnimators.clear()

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }

        mediaPlayer = null

        cleanupNetworkCallback()
    }
}
