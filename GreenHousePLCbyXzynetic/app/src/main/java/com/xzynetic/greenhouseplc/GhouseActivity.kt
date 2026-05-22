
@file:Suppress(
    "DEPRECATION",
    "SpellCheckingInspection",
    "SameParameterValue",
    "RemoveRedundantQualifierName",
    "RedundantConversionMethodCall",
    "CanBeVal",
    "MayBeConstant",
    "unused"
)
@file:android.annotation.SuppressLint(
    "SetTextI18n",
    "ClickableViewAccessibility",
    "DrawAllocation",
    "UseKtx",
    "ObsoleteSdkInt"
)

package com.xzynetic.greenhouseplc

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.view.*
import android.view.animation.*
import android.widget.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
class GhouseActivity : Activity() {

    private lateinit var headerPanel: LinearLayout
    private lateinit var deviceNameText: TextView
    private lateinit var statusDot: TextView
    private lateinit var connectionText: TextView
    private lateinit var modeText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var emergencyText: TextView
    private lateinit var alertBanner: TextView

    private lateinit var pageContainer: FrameLayout
    private lateinit var navTemp: TextView
    private lateinit var navWater: TextView
    private lateinit var navLight: TextView
    private lateinit var bottomNav: LinearLayout

    private lateinit var tempRefs: TemperatureRefs
    private lateinit var waterRefs: WaterRefs
    private lateinit var lightRefs: LightRefs

    private val pages = mutableListOf<View>()
    private var currentPageIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45) }

    private var espIp: String = "192.168.4.1"
    private var requestRunning = false
    private var lastAlertMs = 0L
    private var currentThresholds = Thresholds()

    /*
       Actual connection mode:
       - Normal Connect button opens this activity with GHOUSE_SIMULATION = false.
       - Secret leaf badge opens this activity with GHOUSE_SIMULATION = true.
       In simulation mode, this screen never tries ESP32 /status and uses live changing demo data.
    */
    private var forceSimulationMode = false
    private var demoTick = 0L

    private var touchStartX = 0f
    private var touchStartY = 0f

    private val humidityHistory = mutableListOf<Float>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            fetchStatus()
            handler.postDelayed(this, if (forceSimulationMode) 700L else 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ghouse)

        espIp = intent.getStringExtra("esp_ip")
            ?: intent.getStringExtra("ESP_IP")
                    ?: "192.168.4.1"

        forceSimulationMode =
            intent.getBooleanExtra("GHOUSE_SIMULATION", false) ||
                    intent.getBooleanExtra("SIMULATION_MODE", false)

        window.statusBarColor = Palette.darkGreen
        window.navigationBarColor = Palette.offWhite

        bindRootViews()
        styleRootViews()
        buildPages()
        setupNavigation()
        setupSwipeNavigation()
        setupHeaderAnimations()

        updateUi(Telemetry.mock(connected = false, tick = demoTick++))
    }

    override fun onResume() {
        super.onResume()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        tone.release()
    }

    private fun bindRootViews() {
        headerPanel = findViewById(R.id.headerPanel)
        deviceNameText = findViewById(R.id.deviceNameText)
        statusDot = findViewById(R.id.statusDot)
        connectionText = findViewById(R.id.connectionText)
        modeText = findViewById(R.id.modeText)
        uptimeText = findViewById(R.id.uptimeText)
        emergencyText = findViewById(R.id.emergencyText)
        alertBanner = findViewById(R.id.alertBanner)
        pageContainer = findViewById(R.id.pageContainer)
        navTemp = findViewById(R.id.navTemp)
        navWater = findViewById(R.id.navWater)
        navLight = findViewById(R.id.navLight)
        bottomNav = findViewById(R.id.bottomNav)
    }

    private fun styleRootViews() {
        headerPanel.elevation = dp(8).toFloat()
        bottomNav.elevation = dp(10).toFloat()
        alertBanner.elevation = dp(12).toFloat()

        listOf(navTemp, navWater, navLight).forEach {
            it.gravity = Gravity.CENTER
            it.textSize = 12f
            it.setPadding(dp(10), dp(8), dp(10), dp(8))
        }
    }

    private fun buildPages() {
        tempRefs = buildTemperaturePage()
        waterRefs = buildWaterPage()
        lightRefs = buildLightPage()

        pages.clear()
        pages.add(tempRefs.root)
        pages.add(waterRefs.root)
        pages.add(lightRefs.root)

        showPage(0, animate = false)
    }

    private fun setupNavigation() {
        navTemp.setOnClickListener { showPage(0, animate = true) }
        navWater.setOnClickListener { showPage(1, animate = true) }
        navLight.setOnClickListener { showPage(2, animate = true) }
        selectBottomNav(0)
    }

    private fun setupSwipeNavigation() {
        pageContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY

                    if (abs(dx) > dp(70) && abs(dx) > abs(dy)) {
                        if (dx < 0 && currentPageIndex < pages.lastIndex) {
                            showPage(currentPageIndex + 1, animate = true)
                        } else if (dx > 0 && currentPageIndex > 0) {
                            showPage(currentPageIndex - 1, animate = true)
                        }
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    private fun setupHeaderAnimations() {
        statusDot.startAnimation(
            AlphaAnimation(0.35f, 1f).apply {
                duration = 750
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
        )
    }

    private fun showPage(index: Int, animate: Boolean) {
        if (index !in pages.indices) return

        val oldIndex = currentPageIndex
        currentPageIndex = index

        val page = pages[index]
        val oldParent = page.parent
        if (oldParent is ViewGroup) oldParent.removeView(page)

        pageContainer.removeAllViews()
        pageContainer.addView(
            page,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        if (animate) {
            val fromRight = index >= oldIndex
            page.translationX = if (fromRight) pageContainer.width.toFloat() else -pageContainer.width.toFloat()
            page.alpha = 0.2f
            page.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        selectBottomNav(index)
    }

    private fun selectBottomNav(index: Int) {
        val navs = listOf(navTemp, navWater, navLight)

        navs.forEachIndexed { i, textView ->
            val selected = i == index
            textView.background = rounded(if (selected) Palette.mint else Color.TRANSPARENT, 22)
            textView.setTextColor(if (selected) Palette.white else Palette.mutedText)

            textView.animate()
                .scaleX(if (selected) 1.05f else 1f)
                .scaleY(if (selected) 1.05f else 1f)
                .setDuration(180)
                .start()
        }
    }

    private fun buildTemperaturePage(): TemperatureRefs {
        val page = pageColumn()

        val summaryCard = card()
        summaryCard.addView(titleText("Temperature & Humidity", "🌡️"))
        summaryCard.addView(smallText("Temperature is a climate scene, not a graph. It shifts from cold to warm to hot. Humidity has accurate X and Y reference lines."))

        val temperatureCard = card()
        val temperatureValue = bigMetric("0.0°C")
        val temperatureStatus = statusText("Waiting for DHT11 temperature...")
        val climateView = TemperatureClimateView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210))
        }

        temperatureCard.addView(rowTitle("Temperature", "Cold → Safe → Hot"))
        temperatureCard.addView(temperatureValue)
        temperatureCard.addView(temperatureStatus)
        temperatureCard.addView(climateView)

        val humidityCard = card()
        val humidityValue = bigMetric("0%")
        val humidityStatus = statusText("Waiting for DHT11 humidity...")
        val humidityChart = HumidityScaleChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230))
        }

        humidityCard.addView(rowTitle("Humidity", "X = latest readings • Y = percent"))
        humidityCard.addView(humidityValue)
        humidityCard.addView(humidityStatus)
        humidityCard.addView(humidityChart)

        val fanCard = card()
        val fanBreezeView = FanBreezeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170))
        }
        val fanState = bigMetric("FAN OFF")
        val fanReason = smallText("Reason: waiting for JSON")

        fanCard.addView(rowTitle("Fan Breeze", "Trees + wind when ON"))
        fanCard.addView(fanState)
        fanCard.addView(fanReason)
        fanCard.addView(fanBreezeView)

        val controls = card()
        val auto = pillButton("AUTO Mode")
        val manual = pillButton("MANUAL Mode")
        val fanOn = pillButton("Fan ON")
        val fanOff = pillButton("Fan OFF")

        auto.setOnClickListener { sendGetCommand("/setMode?mode=AUTO") }
        manual.setOnClickListener { sendGetCommand("/setMode?mode=MANUAL") }
        fanOn.setOnClickListener { sendGetCommand("/fan?state=ON") }
        fanOff.setOnClickListener { sendGetCommand("/fan?state=OFF") }

        controls.addView(rowTitle("Temperature Controls", "Fan + mode commands"))
        controls.addView(buttonGrid(auto, manual))
        controls.addView(buttonGrid(fanOn, fanOff))

        val settings = card()
        val tempHighEdit = input("40")
        val tempLowEdit = input("37")
        val humHighEdit = input("40")
        val humLowEdit = input("35")
        val save = pillButton("Save Temperature Settings")

        save.setOnClickListener { saveThresholdsFromUi() }

        settings.addView(rowTitle("Temp/Humidity Thresholds", "Adjust then save"))
        settings.addView(inputRow("Temp High °C", tempHighEdit))
        settings.addView(inputRow("Temp Low °C", tempLowEdit))
        settings.addView(inputRow("Humidity High %", humHighEdit))
        settings.addView(inputRow("Humidity Low %", humLowEdit))
        settings.addView(save)

        page.addView(summaryCard)
        page.addView(temperatureCard)
        page.addView(humidityCard)
        page.addView(fanCard)
        page.addView(controls)
        page.addView(settings)
        page.addView(bottomSpace())

        return TemperatureRefs(
            root = scrollWrap(page),
            temperatureCard = temperatureCard,
            humidityCard = humidityCard,
            fanCard = fanCard,
            temperatureValue = temperatureValue,
            humidityValue = humidityValue,
            temperatureStatus = temperatureStatus,
            humidityStatus = humidityStatus,
            climateView = climateView,
            humidityChart = humidityChart,
            fanBreezeView = fanBreezeView,
            fanState = fanState,
            fanReason = fanReason,
            fanOnButton = fanOn,
            fanOffButton = fanOff,
            tempHighEdit = tempHighEdit,
            tempLowEdit = tempLowEdit,
            humHighEdit = humHighEdit,
            humLowEdit = humLowEdit
        )
    }

    private fun buildWaterPage(): WaterRefs {
        val page = pageColumn()

        val summaryCard = card()
        summaryCard.addView(titleText("Water & Soil System", "💧"))
        summaryCard.addView(smallText("Water tank shows actual fill level. Soil changes dry/OK/wet. Pump shows water flowing to plants."))

        val tankCard = card()
        val waterValue = bigMetric("0%")
        val waterRaw = smallText("Raw: --")
        val waterStatus = statusText("Waiting for water level...")
        val waterTankView = WaterTankView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240))
        }

        tankCard.addView(rowTitle("Water Tank", "Estimated level from sensor"))
        tankCard.addView(waterValue)
        tankCard.addView(waterRaw)
        tankCard.addView(waterStatus)
        tankCard.addView(waterTankView)

        val soilCard = card()
        val soilValue = bigMetric("0%")
        val soilRaw = smallText("Raw: --")
        val soilStatus = statusText("Waiting for soil moisture...")
        val soilScene = SoilConditionView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(205))
        }

        soilCard.addView(rowTitle("Soil Moisture", "Dry / OK / Wet scene"))
        soilCard.addView(soilValue)
        soilCard.addView(soilRaw)
        soilCard.addView(soilStatus)
        soilCard.addView(soilScene)

        val pumpCard = card()
        val pumpState = bigMetric("PUMP OFF")
        val pumpReason = smallText("Reason: waiting for JSON")
        val pumpTimer = statusText("Cooldown: --")
        val pumpView = PumpWateringView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(195))
        }

        pumpCard.addView(rowTitle("Pump Watering", "Actual watering animation"))
        pumpCard.addView(pumpState)
        pumpCard.addView(pumpReason)
        pumpCard.addView(pumpTimer)
        pumpCard.addView(pumpView)

        val controls = card()
        val auto = pillButton("AUTO Mode")
        val manual = pillButton("MANUAL Mode")
        val pumpOn = pillButton("Pump ON")
        val pumpOff = pillButton("Pump OFF")

        auto.setOnClickListener { sendGetCommand("/setMode?mode=AUTO") }
        manual.setOnClickListener { sendGetCommand("/setMode?mode=MANUAL") }
        pumpOn.setOnClickListener { sendGetCommand("/pump?state=ON") }
        pumpOff.setOnClickListener { sendGetCommand("/pump?state=OFF") }

        controls.addView(rowTitle("Water Controls", "Pump + mode commands"))
        controls.addView(buttonGrid(auto, manual))
        controls.addView(buttonGrid(pumpOn, pumpOff))

        val settings = card()
        val waterLowEdit = input("30")
        val soilDryEdit = input("3000")
        val soilWetEdit = input("1500")
        val pumpRunEdit = input("3")
        val pumpCooldownEdit = input("5")
        val save = pillButton("Save Water Settings")
        val calibrate = pillButton("Calibrate Sensors")

        save.setOnClickListener { saveThresholdsFromUi() }
        calibrate.setOnClickListener {
            Toast.makeText(this, "Connect this later to your ESP32 calibration endpoints.", Toast.LENGTH_LONG).show()
        }

        settings.addView(rowTitle("Water/Soil Thresholds", "Pump safety settings"))
        settings.addView(inputRow("Water Low %", waterLowEdit))
        settings.addView(inputRow("Soil Dry Raw", soilDryEdit))
        settings.addView(inputRow("Soil Wet Raw", soilWetEdit))
        settings.addView(inputRow("Pump Run Sec", pumpRunEdit))
        settings.addView(inputRow("Pump Cooldown Sec", pumpCooldownEdit))
        settings.addView(save)
        settings.addView(calibrate)

        page.addView(summaryCard)
        page.addView(tankCard)
        page.addView(soilCard)
        page.addView(pumpCard)
        page.addView(controls)
        page.addView(settings)
        page.addView(bottomSpace())

        return WaterRefs(
            root = scrollWrap(page),
            tankCard = tankCard,
            soilCard = soilCard,
            pumpCard = pumpCard,
            waterValue = waterValue,
            waterRaw = waterRaw,
            waterStatus = waterStatus,
            waterTankView = waterTankView,
            soilValue = soilValue,
            soilRaw = soilRaw,
            soilStatus = soilStatus,
            soilScene = soilScene,
            pumpState = pumpState,
            pumpReason = pumpReason,
            pumpTimer = pumpTimer,
            pumpView = pumpView,
            pumpOnButton = pumpOn,
            pumpOffButton = pumpOff,
            waterLowEdit = waterLowEdit,
            soilDryEdit = soilDryEdit,
            soilWetEdit = soilWetEdit,
            pumpRunEdit = pumpRunEdit,
            pumpCooldownEdit = pumpCooldownEdit
        )
    }

    private fun buildLightPage(): LightRefs {
        val page = pageColumn()

        val summaryCard = card()
        summaryCard.addView(titleText("Light Management", "☀️"))
        summaryCard.addView(smallText("The sunflower becomes happy or sad based on light. Tap the sunflower and it says hi. LED array shines as artificial sunlight."))

        val plantCard = card()
        val lightValue = bigMetric("0%")
        val ldrRaw = smallText("Raw: --")
        val lightStatus = statusText("Tap the sunflower. It is interactive.")
        val lightPlant = LightSunflowerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        }

        plantCard.addView(rowTitle("Sunflower Light Mood", "Interactive expression"))
        plantCard.addView(lightValue)
        plantCard.addView(ldrRaw)
        plantCard.addView(lightStatus)
        plantCard.addView(lightPlant)

        val ledCard = card()
        val ledState = bigMetric("LED ARRAY OFF")
        val ledReason = smallText("Reason: waiting for JSON")
        val ledView = LedArrayShineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(205))
        }

        ledCard.addView(rowTitle("LED Grow Light", "Artificial sunlight shine"))
        ledCard.addView(ledState)
        ledCard.addView(ledReason)
        ledCard.addView(ledView)

        val controls = card()
        val auto = pillButton("AUTO Mode")
        val manual = pillButton("MANUAL Mode")
        val ledOn = pillButton("LED ON")
        val ledOff = pillButton("LED OFF")

        auto.setOnClickListener { sendGetCommand("/setMode?mode=AUTO") }
        manual.setOnClickListener { sendGetCommand("/setMode?mode=MANUAL") }
        ledOn.setOnClickListener { sendGetCommand("/led?state=ON") }
        ledOff.setOnClickListener { sendGetCommand("/led?state=OFF") }

        controls.addView(rowTitle("Light Controls", "LED + mode commands"))
        controls.addView(buttonGrid(auto, manual))
        controls.addView(buttonGrid(ledOn, ledOff))

        val settings = card()
        val ldrDarkEdit = input("2500")
        val save = pillButton("Save Light Settings")

        save.setOnClickListener { saveThresholdsFromUi() }

        settings.addView(rowTitle("LDR Threshold", "Darkness raw trigger"))
        settings.addView(inputRow("LDR Dark Raw", ldrDarkEdit))
        settings.addView(save)

        page.addView(summaryCard)
        page.addView(plantCard)
        page.addView(ledCard)
        page.addView(controls)
        page.addView(settings)
        page.addView(bottomSpace())

        return LightRefs(
            root = scrollWrap(page),
            plantCard = plantCard,
            ledCard = ledCard,
            lightValue = lightValue,
            ldrRaw = ldrRaw,
            lightStatus = lightStatus,
            lightPlant = lightPlant,
            ledState = ledState,
            ledReason = ledReason,
            ledView = ledView,
            ledOnButton = ledOn,
            ledOffButton = ledOff,
            ldrDarkEdit = ldrDarkEdit
        )
    }

    private fun fetchStatus() {
        if (forceSimulationMode) {
            updateUi(Telemetry.mock(connected = false, tick = demoTick++))
            return
        }

        if (requestRunning) return
        requestRunning = true

        Thread {
            try {
                val jsonText = httpGet("http://$espIp/status")
                val telemetry = parseTelemetry(JSONObject(jsonText), connected = true)

                runOnUiThread {
                    requestRunning = false
                    updateUi(telemetry)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    requestRunning = false
                    updateDisconnected(e.message ?: "Unable to reach ESP32.")
                }
            }
        }.start()
    }

    private fun sendGetCommand(path: String) {
        Thread {
            try {
                httpGet("http://$espIp$path")

                runOnUiThread {
                    Toast.makeText(this, "Command sent: $path", Toast.LENGTH_SHORT).show()
                    fetchStatus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Command failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveThresholdsFromUi() {
        val thresholds = Thresholds(
            temperatureHighC = tempRefs.tempHighEdit.text.toString().toFloatOrNull() ?: currentThresholds.temperatureHighC,
            temperatureLowC = tempRefs.tempLowEdit.text.toString().toFloatOrNull() ?: currentThresholds.temperatureLowC,
            humidityHighPercent = tempRefs.humHighEdit.text.toString().toFloatOrNull() ?: currentThresholds.humidityHighPercent,
            humidityLowPercent = tempRefs.humLowEdit.text.toString().toFloatOrNull() ?: currentThresholds.humidityLowPercent,
            waterLevelLowPercent = waterRefs.waterLowEdit.text.toString().toIntOrNull() ?: currentThresholds.waterLevelLowPercent,
            soilDryThresholdRaw = waterRefs.soilDryEdit.text.toString().toIntOrNull() ?: currentThresholds.soilDryThresholdRaw,
            soilWetThresholdRaw = waterRefs.soilWetEdit.text.toString().toIntOrNull() ?: currentThresholds.soilWetThresholdRaw,
            ldrDarkThresholdRaw = lightRefs.ldrDarkEdit.text.toString().toIntOrNull() ?: currentThresholds.ldrDarkThresholdRaw,
            pumpRunSeconds = waterRefs.pumpRunEdit.text.toString().toIntOrNull() ?: currentThresholds.pumpRunSeconds,
            pumpCooldownSeconds = waterRefs.pumpCooldownEdit.text.toString().toIntOrNull() ?: currentThresholds.pumpCooldownSeconds
        )

        val body = JSONObject().apply {
            put("cmd", "SET_THRESHOLDS")
            put("temperatureHighC", thresholds.temperatureHighC)
            put("temperatureLowC", thresholds.temperatureLowC)
            put("humidityHighPercent", thresholds.humidityHighPercent)
            put("humidityLowPercent", thresholds.humidityLowPercent)
            put("waterLevelLowPercent", thresholds.waterLevelLowPercent)
            put("soilDryThresholdRaw", thresholds.soilDryThresholdRaw)
            put("soilWetThresholdRaw", thresholds.soilWetThresholdRaw)
            put("ldrDarkThresholdRaw", thresholds.ldrDarkThresholdRaw)
            put("pumpRunSeconds", thresholds.pumpRunSeconds)
            put("pumpCooldownSeconds", thresholds.pumpCooldownSeconds)
        }

        Thread {
            try {
                httpPostJson("http://$espIp/setThresholds", body.toString())

                runOnUiThread {
                    currentThresholds = thresholds
                    Toast.makeText(this, "Thresholds saved.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to save thresholds: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun httpGet(urlText: String): String {
        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1400
            readTimeout = 1400
        }

        return conn.inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        }
    }

    private fun httpPostJson(urlText: String, json: String): String {
        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 1800
            readTimeout = 1800
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        OutputStreamWriter(conn.outputStream).use { it.write(json) }

        return conn.inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        }
    }

    private fun updateUi(t: Telemetry) {
        currentThresholds = t.thresholds
        append(humidityHistory, t.humidityPercent)

        updateHeader(t)
        updateTemperatureSection(t)
        updateWaterSection(t)
        updateLightSection(t)
        updateAlerts(t)
    }

    private fun updateDisconnected(reason: String) {
        connectionText.text = "Disconnected"
        statusDot.setTextColor(Palette.critical)
        alertBanner.visibility = View.VISIBLE
        alertBanner.text = "ESP32 disconnected: $reason"
    }

    private fun updateHeader(t: Telemetry) {
        deviceNameText.text = t.deviceName
        connectionText.text = if (t.connected) "Connected" else "Disconnected / Demo"
        modeText.text = t.mode
        uptimeText.text = "Uptime: ${formatUptime(t.uptimeMs)}"
        emergencyText.text = if (t.emergencyStop || !t.enabled) "EMERGENCY OFF" else "SYSTEM ON"

        statusDot.setTextColor(
            when {
                !t.connected -> Palette.warning
                t.emergencyStop || !t.enabled -> Palette.critical
                hasAnyWarning(t) -> Palette.warning
                else -> Palette.mint
            }
        )

        modeText.background = rounded(
            when {
                t.emergencyStop || !t.enabled -> Palette.critical
                t.mode.equals("MANUAL", true) -> Palette.warning
                else -> Palette.mint
            },
            18
        )
    }

    private fun updateTemperatureSection(t: Telemetry) {
        animateNumber(tempRefs.temperatureValue, t.temperatureC, "°C", decimals = 1)
        animateNumber(tempRefs.humidityValue, t.humidityPercent, "%", decimals = 0)

        tempRefs.temperatureStatus.text = when {
            !t.dhtValid -> "DHT11 sensor error"
            t.temperatureC >= t.thresholds.temperatureHighC -> "Hot greenhouse • Fan required"
            t.temperatureC <= 20f -> "Cool greenhouse • Monitor temperature"
            t.temperatureC >= 30f -> "Warm greenhouse • Watch closely"
            else -> "Comfort temperature"
        }

        tempRefs.humidityStatus.text = when {
            !t.dhtValid -> "DHT11 humidity invalid"
            t.humidityPercent >= t.thresholds.humidityHighPercent -> "High humidity • Fan active"
            else -> "Humidity safe"
        }

        tempRefs.climateView.setTemperature(t.temperatureC, t.thresholds.temperatureHighC)
        tempRefs.humidityChart.setValues(humidityHistory, t.humidityPercent, t.thresholds.humidityHighPercent)

        val fanOn = t.fanState.equals("ON", true)
        tempRefs.fanState.text = if (fanOn) "FAN ON" else "FAN OFF"
        tempRefs.fanReason.text = "Reason: ${t.fanReason}"
        tempRefs.fanBreezeView.setFanState(fanOn)

        tempRefs.temperatureCard.background = roundedCard(colorForTemperatureSoft(t.temperatureC))
        tempRefs.humidityCard.background = roundedCard(colorForHumiditySoft(t.humidityPercent))
        tempRefs.fanCard.background = roundedCard(if (fanOn) Palette.softMint else Palette.white)

        setManualControlsEnabled(t.enabled && !t.emergencyStop && t.mode.equals("MANUAL", true), tempRefs.fanOnButton, tempRefs.fanOffButton)

        setThresholdTextIfNotFocused(tempRefs.tempHighEdit, t.thresholds.temperatureHighC.toStringClean())
        setThresholdTextIfNotFocused(tempRefs.tempLowEdit, t.thresholds.temperatureLowC.toStringClean())
        setThresholdTextIfNotFocused(tempRefs.humHighEdit, t.thresholds.humidityHighPercent.toStringClean())
        setThresholdTextIfNotFocused(tempRefs.humLowEdit, t.thresholds.humidityLowPercent.toStringClean())
    }

    private fun updateWaterSection(t: Telemetry) {
        animateNumber(waterRefs.waterValue, t.waterPercent.toFloat(), "%", decimals = 0)
        waterRefs.waterRaw.text = "Raw: ${t.waterRaw}"

        waterRefs.waterStatus.text = if (t.waterPercent <= t.thresholds.waterLevelLowPercent) {
            "LOW WATER • Refill external tank"
        } else {
            "Tank Level Normal"
        }

        waterRefs.waterTankView.setTankLevel(t.waterPercent, t.waterRaw, t.thresholds.waterLevelLowPercent)

        animateNumber(waterRefs.soilValue, t.soilPercent.toFloat(), "%", decimals = 0)
        waterRefs.soilRaw.text = "Raw: ${t.soilRaw}"
        waterRefs.soilStatus.text = "Soil: ${t.soilStatus}"
        waterRefs.soilScene.setSoil(t.soilPercent, t.soilStatus)

        val pumpOn = t.pumpState.equals("ON", true) || t.pumpState.equals("WATERING", true)

        waterRefs.pumpState.text = when {
            pumpOn -> "PUMP ON"
            t.pumpReason.equals("COOLDOWN", true) -> "COOLDOWN"
            else -> "PUMP OFF"
        }

        waterRefs.pumpReason.text = "Reason: ${t.pumpReason}"

        val coolSec = max(0, t.cooldownRemainingMs / 1000)

        waterRefs.pumpTimer.text = when {
            pumpOn -> "Watering plants now"
            coolSec > 0 -> "Cooldown remaining: ${coolSec}s"
            else -> "Cooldown: Ready"
        }

        waterRefs.pumpView.setPumpState(pumpOn)

        waterRefs.tankCard.background = roundedCard(if (t.waterPercent <= t.thresholds.waterLevelLowPercent) Palette.softCritical else Palette.white)
        waterRefs.soilCard.background = roundedCard(if (t.soilStatus.equals("DRY", true)) Palette.softWarning else Palette.white)
        waterRefs.pumpCard.background = roundedCard(if (pumpOn) Palette.softMint else Palette.white)

        setManualControlsEnabled(t.enabled && !t.emergencyStop && t.mode.equals("MANUAL", true), waterRefs.pumpOnButton, waterRefs.pumpOffButton)

        setThresholdTextIfNotFocused(waterRefs.waterLowEdit, t.thresholds.waterLevelLowPercent.toString())
        setThresholdTextIfNotFocused(waterRefs.soilDryEdit, t.thresholds.soilDryThresholdRaw.toString())
        setThresholdTextIfNotFocused(waterRefs.soilWetEdit, t.thresholds.soilWetThresholdRaw.toString())
        setThresholdTextIfNotFocused(waterRefs.pumpRunEdit, t.thresholds.pumpRunSeconds.toString())
        setThresholdTextIfNotFocused(waterRefs.pumpCooldownEdit, t.thresholds.pumpCooldownSeconds.toString())
    }

    private fun updateLightSection(t: Telemetry) {
        animateNumber(lightRefs.lightValue, t.ldrPercent.toFloat(), "%", decimals = 0)
        lightRefs.ldrRaw.text = "Raw: ${t.ldrRaw}"

        val ledOn = t.ledState.equals("ON", true)

        lightRefs.lightStatus.text = when {
            t.ldrStatus.equals("DARK", true) && ledOn -> "Sunflower is recovering because LED sunlight helps"
            t.ldrStatus.equals("DARK", true) -> "Sunflower is sad • Low light"
            t.ldrStatus.equals("BRIGHT", true) -> "Sunflower is happy • Bright natural light"
            else -> "Sunflower mood is normal"
        }

        lightRefs.lightPlant.setLight(t.ldrPercent, t.ldrStatus, ledOn)

        lightRefs.ledState.text = if (ledOn) "LED ARRAY ON" else "LED ARRAY OFF"
        lightRefs.ledReason.text = "Reason: ${t.ledReason}"
        lightRefs.ledView.setLedState(ledOn)

        lightRefs.plantCard.background = roundedCard(if (t.ldrStatus.equals("DARK", true)) Palette.softWarning else Palette.white)
        lightRefs.ledCard.background = roundedCard(if (ledOn) Palette.softMint else Palette.white)

        setManualControlsEnabled(t.enabled && !t.emergencyStop && t.mode.equals("MANUAL", true), lightRefs.ledOnButton, lightRefs.ledOffButton)

        setThresholdTextIfNotFocused(lightRefs.ldrDarkEdit, t.thresholds.ldrDarkThresholdRaw.toString())
    }

    private fun updateAlerts(t: Telemetry) {
        val alerts = mutableListOf<String>()

        if (!t.connected) alerts += "Using demo values until ESP32 /status responds"
        if (t.emergencyStop || !t.enabled) alerts += "Emergency switch is OFF"
        if (!t.dhtValid) alerts += "DHT11 sensor error"
        if (t.temperatureC >= t.thresholds.temperatureHighC) alerts += "High temperature"
        if (t.humidityPercent >= t.thresholds.humidityHighPercent) alerts += "High humidity"
        if (t.waterPercent <= t.thresholds.waterLevelLowPercent) alerts += "Low water"
        if (t.soilStatus.equals("DRY", true)) alerts += "Dry soil"
        if (t.ldrStatus.equals("DARK", true)) alerts += "Low light"

        if (alerts.isEmpty()) {
            alertBanner.visibility = View.GONE
        } else {
            alertBanner.visibility = View.VISIBLE
            alertBanner.text = alerts.joinToString(" • ")

            // Prevent repeated AppOps / AudioTrack log spam in simulation or disconnected demo mode.
            // Real ESP32-connected mode can still beep/vibrate for real alerts.
            if (t.connected && !forceSimulationMode) {
                playAlertWithCooldown()
            }
        }
    }

    private fun playAlertWithCooldown() {
        val now = System.currentTimeMillis()
        if (now - lastAlertMs < 9000L) return

        lastAlertMs = now

        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 140)

            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        80,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(80)
            }
        } catch (_: Exception) {
        }
    }

    private fun parseTelemetry(root: JSONObject, connected: Boolean): Telemetry {
        val system = root.optJSONObject("system") ?: JSONObject()
        val sensors = root.optJSONObject("sensors") ?: JSONObject()
        val actuators = root.optJSONObject("actuators") ?: JSONObject()
        val th = root.optJSONObject("thresholds") ?: JSONObject()

        val dht = sensors.optJSONObject("dht11")
            ?: sensors.optJSONObject("dht22")
            ?: JSONObject()

        val water = sensors.optJSONObject("waterLevel") ?: JSONObject()
        val soil = sensors.optJSONObject("soilMoisture") ?: JSONObject()
        val ldr = sensors.optJSONObject("ldr") ?: JSONObject()
        val master = sensors.optJSONObject("masterButton") ?: JSONObject()

        val fan = actuators.optJSONObject("fan") ?: JSONObject()
        val pump = actuators.optJSONObject("pump") ?: JSONObject()
        val led = actuators.optJSONObject("ledArray") ?: JSONObject()

        val thresholds = Thresholds(
            temperatureHighC = th.optDouble("temperatureHighC", 40.0).toFloat(),
            temperatureLowC = th.optDouble("temperatureLowC", 37.0).toFloat(),
            humidityHighPercent = th.optDouble("humidityHighPercent", 40.0).toFloat(),
            humidityLowPercent = th.optDouble("humidityLowPercent", 35.0).toFloat(),
            waterLevelLowPercent = th.optInt("waterLevelLowPercent", 30),
            soilDryThresholdRaw = th.optInt("soilDryThresholdRaw", 3000),
            soilWetThresholdRaw = th.optInt("soilWetThresholdRaw", 1500),
            ldrDarkThresholdRaw = th.optInt("ldrDarkThresholdRaw", 2500),
            pumpRunSeconds = th.optInt("pumpRunSeconds", 3),
            pumpCooldownSeconds = th.optInt("pumpCooldownSeconds", 5)
        )

        return Telemetry(
            connected = connected,
            deviceName = system.optString("deviceName", "GreenHouseByXzynetic"),
            enabled = system.optBoolean("enabled", true),
            mode = system.optString("mode", "AUTO"),
            emergencyStop = system.optBoolean("emergencyStop", false),
            uptimeMs = system.optLong("uptimeMs", 0L),
            temperatureC = dht.optDouble("temperatureC", 0.0).toFloat(),
            humidityPercent = dht.optDouble("humidityPercent", 0.0).toFloat(),
            dhtValid = dht.optBoolean("valid", true),
            waterRaw = water.optInt("raw", 0),
            waterPercent = water.optInt("percent", 0),
            waterStatus = water.optString("status", "UNKNOWN"),
            soilRaw = soil.optInt("raw", 0),
            soilPercent = soil.optInt("percent", 0),
            soilStatus = soil.optString("status", "UNKNOWN"),
            ldrRaw = ldr.optInt("raw", 0),
            ldrPercent = ldr.optInt("percent", 0),
            ldrStatus = ldr.optString("status", "UNKNOWN"),
            masterRaw = master.optInt("raw", 0),
            masterState = master.optString("state", "UNKNOWN"),
            fanState = fan.optString("state", "OFF"),
            fanReason = fan.optString("reason", "UNKNOWN"),
            pumpState = pump.optString("state", "OFF"),
            pumpReason = pump.optString("reason", "UNKNOWN"),
            pumpLastRunMsAgo = pump.optLong("lastRunMsAgo", 0L),
            cooldownRemainingMs = pump.optLong("cooldownRemainingMs", 0L),
            ledState = led.optString("state", "OFF"),
            ledReason = led.optString("reason", "UNKNOWN"),
            thresholds = thresholds
        )
    }

    private fun pageColumn(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun scrollWrap(child: View): ScrollView {
        return ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            addView(child)
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedCard(Palette.white)
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(18), dp(8), dp(18), dp(8))
            }
        }
    }

    private fun roundedCard(color: Int): GradientDrawable {
        return rounded(color, 24).apply {
            setStroke(dp(1), Color.argb(18, 6, 78, 59))
        }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun titleText(text: String, icon: String): TextView {
        return TextView(this).apply {
            this.text = "$icon  $text"
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Palette.darkText)
        }
    }

    private fun rowTitle(left: String, right: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(
            TextView(this).apply {
                text = left
                textSize = 16f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Palette.darkText)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )

        row.addView(
            TextView(this).apply {
                text = right
                textSize = 11f
                setTextColor(Palette.mutedText)
            }
        )

        return row
    }

    private fun bigMetric(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 28f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Palette.darkGreen)
            includeFontPadding = false
            setPadding(0, dp(10), 0, dp(4))
        }
    }

    private fun statusText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Palette.mutedText)
            setPadding(0, dp(4), 0, dp(8))
        }
    }

    private fun smallText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Palette.mutedText)
            setPadding(0, dp(4), 0, dp(6))
        }
    }

    private fun pillButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            setTextColor(Palette.white)
            background = rounded(Palette.mint, 24)
            minHeight = dp(44)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
        }
    }

    private fun input(defaultValue: String): EditText {
        return EditText(this).apply {
            setText(defaultValue)
            textSize = 14f
            setSingleLine(true)
            setTextColor(Palette.darkText)
            background = rounded(Palette.offWhite, 16).apply {
                setStroke(dp(1), Color.argb(38, 6, 78, 59))
            }
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(44))
        }
    }

    private fun inputRow(label: String, input: EditText): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))

            addView(
                TextView(this@GhouseActivity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(Palette.darkText)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            )

            addView(input)
        }
    }

    private fun buttonGrid(a: Button, b: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            a.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(4), dp(5), dp(4), dp(5))
            }

            b.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(4), dp(5), dp(4), dp(5))
            }

            addView(a)
            addView(b)
        }
    }

    private fun bottomSpace(): Space {
        return Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(24))
        }
    }

    private fun setManualControlsEnabled(enabled: Boolean, vararg buttons: Button) {
        buttons.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.45f
            it.background = rounded(if (enabled) Palette.mint else Palette.disabled, 24)
        }
    }

    private fun setThresholdTextIfNotFocused(editText: EditText, value: String) {
        if (!editText.hasFocus() && editText.text.toString() != value) {
            editText.setText(value)
        }
    }

    private fun animateNumber(tv: TextView, value: Float, suffix: String, decimals: Int) {
        val old = (tv.tag as? Float) ?: value
        tv.tag = value

        ValueAnimator.ofFloat(old, value).apply {
            duration = 350L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                tv.text = if (decimals == 0) "${v.toInt()}$suffix" else String.format(Locale.US, "%.${decimals}f%s", v, suffix)
            }
            start()
        }
    }

    private fun append(list: MutableList<Float>, value: Float) {
        list.add(value)
        while (list.size > 60) list.removeAt(0)
    }

    private fun formatUptime(ms: Long): String {
        val sec = ms / 1000
        val m = (sec / 60) % 60
        val h = sec / 3600
        val s = sec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun colorForTemperatureSoft(v: Float): Int = when {
        v >= currentThresholds.temperatureHighC -> Palette.softCritical
        v >= 30f -> Palette.softWarning
        else -> Palette.white
    }

    private fun colorForHumiditySoft(v: Float): Int = when {
        v >= currentThresholds.humidityHighPercent -> Palette.softCritical
        v >= currentThresholds.humidityHighPercent - 5f -> Palette.softWarning
        else -> Palette.white
    }

    private fun hasAnyWarning(t: Telemetry): Boolean {
        return !t.dhtValid ||
                t.temperatureC >= t.thresholds.temperatureHighC ||
                t.humidityPercent >= t.thresholds.humidityHighPercent ||
                t.waterPercent <= t.thresholds.waterLevelLowPercent ||
                t.soilStatus.equals("DRY", true) ||
                t.ldrStatus.equals("DARK", true)
    }

    private fun Float.toStringClean(): String {
        return if (this % 1f == 0f) this.toInt().toString() else String.format(Locale.US, "%.1f", this)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    data class TemperatureRefs(
        val root: View,
        val temperatureCard: LinearLayout,
        val humidityCard: LinearLayout,
        val fanCard: LinearLayout,
        val temperatureValue: TextView,
        val humidityValue: TextView,
        val temperatureStatus: TextView,
        val humidityStatus: TextView,
        val climateView: TemperatureClimateView,
        val humidityChart: HumidityScaleChartView,
        val fanBreezeView: FanBreezeView,
        val fanState: TextView,
        val fanReason: TextView,
        val fanOnButton: Button,
        val fanOffButton: Button,
        val tempHighEdit: EditText,
        val tempLowEdit: EditText,
        val humHighEdit: EditText,
        val humLowEdit: EditText
    )

    data class WaterRefs(
        val root: View,
        val tankCard: LinearLayout,
        val soilCard: LinearLayout,
        val pumpCard: LinearLayout,
        val waterValue: TextView,
        val waterRaw: TextView,
        val waterStatus: TextView,
        val waterTankView: WaterTankView,
        val soilValue: TextView,
        val soilRaw: TextView,
        val soilStatus: TextView,
        val soilScene: SoilConditionView,
        val pumpState: TextView,
        val pumpReason: TextView,
        val pumpTimer: TextView,
        val pumpView: PumpWateringView,
        val pumpOnButton: Button,
        val pumpOffButton: Button,
        val waterLowEdit: EditText,
        val soilDryEdit: EditText,
        val soilWetEdit: EditText,
        val pumpRunEdit: EditText,
        val pumpCooldownEdit: EditText
    )

    data class LightRefs(
        val root: View,
        val plantCard: LinearLayout,
        val ledCard: LinearLayout,
        val lightValue: TextView,
        val ldrRaw: TextView,
        val lightStatus: TextView,
        val lightPlant: LightSunflowerView,
        val ledState: TextView,
        val ledReason: TextView,
        val ledView: LedArrayShineView,
        val ledOnButton: Button,
        val ledOffButton: Button,
        val ldrDarkEdit: EditText
    )
}

/* ==========================================================
   CUSTOM UI ANIMATED VIEWS
   ========================================================== */

class TemperatureClimateView(context: Context) : View(context) {
    private var temperatureC = 25f
    private var highThreshold = 40f
    private var anim = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.darkGreen
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    fun setTemperature(temp: Float, high: Float) {
        temperatureC = temp
        highThreshold = high
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        anim += 0.08f

        val w = width.toFloat()
        val h = height.toFloat()
        val temp01 = ((temperatureC - 15f) / max(1f, highThreshold - 15f)).coerceIn(0f, 1f)

        val skyCold = Color.rgb(202, 232, 255)
        val skyWarm = Color.rgb(216, 250, 221)
        val skyHot = Color.rgb(255, 213, 174)
        val sky = blend(if (temp01 < 0.5f) skyCold else skyWarm, if (temp01 < 0.5f) skyWarm else skyHot, if (temp01 < 0.5f) temp01 * 2f else (temp01 - 0.5f) * 2f)

        paint.shader = LinearGradient(0f, 0f, 0f, h, sky, Color.WHITE, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 28f, 28f, paint)
        paint.shader = null

        val sunR = 26f + temp01 * 18f
        paint.color = if (temp01 > 0.65f) Color.rgb(255, 146, 65) else Color.rgb(255, 205, 74)
        canvas.drawCircle(w - 55f, 45f, sunR, paint)

        if (temperatureC <= 20f) {
            paint.color = Color.argb(120, 255, 255, 255)
            repeat(8) {
                val x = (it * w / 7f) + sin(anim + it).toFloat() * 8f
                canvas.drawCircle(x, 38f + (it % 2) * 24f, 9f, paint)
                canvas.drawLine(x - 8f, 38f, x + 8f, 38f, paint)
                canvas.drawLine(x, 30f, x, 46f, paint)
            }
        }

        paint.color = Color.rgb(114, 74, 42)
        canvas.drawRoundRect(RectF(0f, h - 44f, w, h), 0f, 0f, paint)

        repeat(4) { i ->
            val baseX = 48f + i * (w - 90f) / 3f
            val bend = sin(anim + i).toFloat() * (4f + temp01 * 8f)
            drawTree(canvas, baseX, h - 42f, 50f + i * 5f, bend, temp01)
        }

        val label = when {
            temperatureC >= highThreshold -> "HOT"
            temperatureC >= 30f -> "WARM"
            temperatureC <= 20f -> "COLD"
            else -> "SAFE"
        }
        textPaint.color = if (temperatureC >= highThreshold) Palette.critical else Palette.darkGreen
        canvas.drawText(label, w / 2f, 42f, textPaint)

        postInvalidateDelayed(32L)
    }

    private fun drawTree(canvas: Canvas, x: Float, baseY: Float, size: Float, bend: Float, heat: Float) {
        paint.shader = null
        paint.strokeWidth = 8f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(100, 63, 38)
        canvas.drawLine(x, baseY, x + bend, baseY - size, paint)

        paint.color = blend(Color.rgb(13, 148, 85), Color.rgb(224, 132, 52), heat)
        canvas.drawCircle(x + bend, baseY - size, size * 0.46f, paint)
        paint.color = Color.argb(110, 255, 255, 255)
        canvas.drawCircle(x + bend - 10f, baseY - size - 10f, size * 0.15f, paint)
    }
}

class HumidityScaleChartView(context: Context) : View(context) {
    private var history = listOf<Float>()
    private var latest = 0f
    private var highThreshold = 40f

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.darkGreen
        strokeWidth = 3f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 6, 78, 59)
        strokeWidth = 1.2f
    }

    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.warning
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.mint
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.mutedText
        textSize = 22f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setValues(history: List<Float>, latest: Float, high: Float) {
        this.history = history.takeLast(60)
        this.latest = latest.coerceIn(0f, 100f)
        this.highThreshold = high.coerceIn(0f, 100f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = 58f
        val top = 20f
        val right = width - 18f
        val bottom = height - 42f
        val chartW = right - left
        val chartH = bottom - top

        // Fixed X/Y axes
        canvas.drawLine(left, top, left, bottom, axisPaint)
        canvas.drawLine(left, bottom, right, bottom, axisPaint)

        // Fixed Y-axis labels
        val yMarks = listOf(0, 25, 50, 75, 100)
        labelPaint.textAlign = Paint.Align.RIGHT
        yMarks.forEach { value ->
            val y = bottom - chartH * (value / 100f)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText("${value}%", left - 8f, y + 7f, labelPaint)
        }

        // Fixed X-axis reference labels
        val xMarks = listOf("60s", "45s", "30s", "15s", "now")
        labelPaint.textAlign = Paint.Align.CENTER
        xMarks.indices.forEach { i ->
            val x = left + chartW * i / (xMarks.size - 1)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawText(xMarks[i], x, height - 12f, labelPaint)
        }

        // High humidity threshold line
        val thresholdY = bottom - chartH * (highThreshold / 100f)
        canvas.drawLine(left, thresholdY, right, thresholdY, thresholdPaint)

        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = Palette.warning
        canvas.drawText("Threshold ${highThreshold.toInt()}%", left + 10f, thresholdY - 8f, labelPaint)
        labelPaint.color = Palette.mutedText

        // Persistent humidity trend line. No old "up/down scale" animation.
        if (history.size >= 2) {
            val path = Path()
            val fillPath = Path()

            history.forEachIndexed { index, value ->
                val x = left + chartW * index / max(1f, (history.size - 1).toFloat())
                val y = bottom - chartH * (value.coerceIn(0f, 100f) / 100f)

                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, bottom)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            val lastX = left + chartW
            fillPath.lineTo(lastX, bottom)
            fillPath.close()

            val lineColor = if (latest >= highThreshold) Palette.critical else Palette.mint

            fillPaint.shader = LinearGradient(
                0f,
                top,
                0f,
                bottom,
                Color.argb(85, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)
            fillPaint.shader = null

            linePaint.color = lineColor
            canvas.drawPath(path, linePaint)

            val currentX = left + chartW
            val currentY = bottom - chartH * (latest / 100f)
            dotPaint.color = lineColor
            canvas.drawCircle(currentX, currentY, 9f, dotPaint)

            labelPaint.textAlign = Paint.Align.RIGHT
            labelPaint.color = lineColor
            labelPaint.textSize = 24f
            canvas.drawText("${latest.toInt()}%", currentX - 14f, currentY - 12f, labelPaint)
            labelPaint.color = Palette.mutedText
            labelPaint.textSize = 22f
        }
    }
}

class FanBreezeView(context: Context) : View(context) {
    private var fanOn = false
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setFanState(on: Boolean) {
        fanOn = on
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        phase += if (fanOn) 0.18f else 0.04f

        val w = width.toFloat()
        val h = height.toFloat()

        paint.shader = LinearGradient(0f, 0f, w, h, Color.rgb(229, 255, 241), Color.WHITE, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 28f, 28f, paint)
        paint.shader = null

        paint.color = Color.rgb(220, 242, 230)
        canvas.drawRoundRect(RectF(0f, h - 34f, w, h), 0f, 0f, paint)

        val windStrength = if (fanOn) 24f else 2f

        drawWindTree(canvas, w * 0.26f, h - 34f, windStrength)
        drawWindTree(canvas, w * 0.56f, h - 34f, windStrength * 0.8f)
        drawWindTree(canvas, w * 0.80f, h - 34f, windStrength * 1.15f)

        if (fanOn) {
            paint.color = Color.argb(125, 16, 185, 129)
            paint.strokeWidth = 4f
            paint.style = Paint.Style.STROKE
            repeat(5) {
                val y = 34f + it * 22f
                val path = Path()
                path.moveTo(10f, y + sin(phase + it).toFloat() * 8f)
                path.cubicTo(w * 0.32f, y - 20f, w * 0.60f, y + 22f, w - 16f, y)
                canvas.drawPath(path, paint)
            }
            paint.style = Paint.Style.FILL

            paint.color = Color.rgb(34, 197, 94)
            repeat(10) {
                val x = ((phase * 55 + it * 43) % (w + 50f)) - 30f
                val y = 26f + (it % 5) * 25f + sin(phase + it).toFloat() * 8f
                canvas.save()
                canvas.rotate(sin(phase + it).toFloat() * 25f, x, y)
                canvas.drawOval(RectF(x, y, x + 14f, y + 7f), paint)
                canvas.restore()
            }
        } else {
            paint.color = Palette.mutedText
            paint.textSize = 32f
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("Breeze idle", w / 2f, 38f, paint)
        }

        postInvalidateDelayed(32L)
    }

    private fun drawWindTree(canvas: Canvas, x: Float, ground: Float, strength: Float) {
        val lean = sin(phase).toFloat() * strength
        paint.color = Color.rgb(102, 66, 38)
        paint.strokeWidth = 8f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x, ground, x + lean * 0.5f, ground - 70f, paint)

        paint.color = Color.rgb(16, 132, 86)
        canvas.drawOval(RectF(x - 32f + lean, ground - 118f, x + 40f + lean, ground - 54f), paint)
        paint.color = Color.rgb(34, 197, 94)
        canvas.drawOval(RectF(x - 15f + lean, ground - 130f, x + 55f + lean, ground - 68f), paint)
    }
}

class WaterTankView(context: Context) : View(context) {
    private var level = 0f
    private var raw = 0
    private var lowThreshold = 30
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setTankLevel(percent: Int, rawValue: Int, threshold: Int) {
        ValueAnimator.ofFloat(level, percent.coerceIn(0, 100).toFloat()).apply {
            duration = 450L
            addUpdateListener {
                level = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        raw = rawValue
        lowThreshold = threshold
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        phase += 0.10f

        val w = width.toFloat()
        val h = height.toFloat()

        paint.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(238, 255, 249), Color.WHITE, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 28f, 28f, paint)
        paint.shader = null

        val tank = RectF(w * 0.20f, 28f, w * 0.80f, h - 30f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.color = Palette.darkGreen
        canvas.drawRoundRect(tank, 28f, 28f, paint)

        val fillH = tank.height() * (level / 100f)
        val top = tank.bottom - fillH
        val wavePath = Path()
        wavePath.moveTo(tank.left, top)

        var x = tank.left
        while (x <= tank.right) {
            val y = top + sin((x / 24f) + phase).toFloat() * 7f
            if (x == tank.left) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            x += 8f
        }

        wavePath.lineTo(tank.right, tank.bottom)
        wavePath.lineTo(tank.left, tank.bottom)
        wavePath.close()

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, top, 0f, tank.bottom, Color.rgb(96, 199, 255), Color.rgb(20, 122, 203), Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipRect(tank)
        canvas.drawPath(wavePath, paint)
        canvas.restore()
        paint.shader = null

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 42f
        paint.color = if (level <= lowThreshold) Palette.critical else Palette.darkGreen
        canvas.drawText("${level.toInt()}% LEFT", w / 2f, h / 2f + 8f, paint)

        paint.textSize = 24f
        paint.typeface = Typeface.DEFAULT
        paint.color = Palette.mutedText
        canvas.drawText("Raw sensor: $raw", w / 2f, h / 2f + 42f, paint)

        paint.textSize = 25f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = if (level <= lowThreshold) Palette.critical else Palette.mint
        canvas.drawText(if (level <= lowThreshold) "LOW WATER" else "TANK NORMAL", w / 2f, 25f, paint)

        postInvalidateDelayed(32L)
    }
}

class SoilConditionView(context: Context) : View(context) {
    private var percent = 0f
    private var status = "UNKNOWN"
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setSoil(p: Int, s: String) {
        percent = p.coerceIn(0, 100).toFloat()
        status = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        phase += 0.08f

        val w = width.toFloat()
        val h = height.toFloat()

        val dry = status.equals("DRY", true)
        val wet = status.equals("WET", true)

        val bg = when {
            dry -> Color.rgb(255, 246, 222)
            wet -> Color.rgb(224, 246, 255)
            else -> Color.rgb(233, 255, 241)
        }

        paint.color = bg
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 28f, 28f, paint)

        paint.color = when {
            dry -> Color.rgb(175, 118, 66)
            wet -> Color.rgb(98, 77, 60)
            else -> Color.rgb(117, 84, 52)
        }

        canvas.drawRoundRect(RectF(0f, h * 0.66f, w, h), 0f, 0f, paint)

        if (dry) {
            paint.color = Color.argb(170, 95, 66, 43)
            paint.strokeWidth = 3f
            repeat(7) {
                val sx = 18f + it * w / 7f
                val sy = h * 0.74f + (it % 3) * 14f
                canvas.drawLine(sx, sy, sx + 34f, sy + 10f, paint)
            }
        }

        if (wet) {
            paint.color = Color.argb(130, 75, 180, 255)
            repeat(10) {
                val x = 18f + it * w / 10f
                val y = h * 0.70f + sin(phase + it).toFloat() * 4f
                canvas.drawCircle(x, y, 5f, paint)
            }
        }

        drawPlant(canvas, w / 2f, h * 0.66f, dry, wet)

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 30f
        paint.color = when {
            dry -> Palette.warning
            wet -> Color.rgb(37, 99, 235)
            else -> Palette.darkGreen
        }
        val label = when {
            dry -> "DRY SOIL"
            wet -> "WET SOIL"
            else -> "SOIL OK"
        }
        canvas.drawText("$label • ${percent.toInt()}%", w / 2f, 34f, paint)

        postInvalidateDelayed(32L)
    }

    private fun drawPlant(canvas: Canvas, x: Float, ground: Float, dry: Boolean, wet: Boolean) {
        paint.color = if (dry) Color.rgb(120, 93, 45) else Palette.darkGreen
        paint.strokeWidth = 8f
        paint.strokeCap = Paint.Cap.ROUND
        val sway = if (dry) 0f else sin(phase).toFloat() * 5f
        canvas.drawLine(x, ground, x + sway, ground - 78f, paint)

        val leafColor = when {
            dry -> Color.rgb(179, 139, 56)
            wet -> Color.rgb(34, 160, 120)
            else -> Color.rgb(34, 197, 94)
        }

        paint.color = leafColor
        canvas.drawOval(RectF(x - 58f + sway, ground - 88f, x + 8f + sway, ground - 48f), paint)
        canvas.drawOval(RectF(x - 8f + sway, ground - 92f, x + 58f + sway, ground - 52f), paint)
        canvas.drawOval(RectF(x - 34f + sway, ground - 130f, x + 34f + sway, ground - 74f), paint)
    }
}

class PumpWateringView(context: Context) : View(context) {
    private var pumpOn = false
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setPumpState(on: Boolean) {
        pumpOn = on
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        phase += if (pumpOn) 0.18f else 0.05f

        val w = width.toFloat()
        val h = height.toFloat()

        paint.color = Color.rgb(239, 255, 250)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 28f, 28f, paint)

        // Pump body
        paint.color = Palette.darkGreen
        canvas.drawRoundRect(RectF(22f, h - 90f, 105f, h - 38f), 18f, 18f, paint)
        paint.color = Palette.mint
        canvas.drawCircle(78f, h - 64f, 18f, paint)

        // Hose
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 9f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(42, 117, 90)
        val hose = Path()
        hose.moveTo(105f, h - 64f)
        hose.cubicTo(w * 0.28f, h - 120f, w * 0.52f, h - 115f, w * 0.72f, h - 78f)
        canvas.drawPath(hose, paint)
        paint.style = Paint.Style.FILL

        // Plant
        val plantX = w * 0.76f
        val ground = h - 36f
        paint.color = Color.rgb(110, 73, 43)
        canvas.drawRoundRect(RectF(0f, ground, w, h), 0f, 0f, paint)
        paint.color = Palette.darkGreen
        paint.strokeWidth = 8f
        canvas.drawLine(plantX, ground, plantX, ground - 70f, paint)
        paint.color = Color.rgb(34, 197, 94)
        canvas.drawOval(RectF(plantX - 55f, ground - 90f, plantX + 5f, ground - 48f), paint)
        canvas.drawOval(RectF(plantX - 5f, ground - 95f, plantX + 55f, ground - 53f), paint)

        if (pumpOn) {
            paint.color = Color.rgb(58, 165, 255)
            repeat(12) {
                val t = ((phase * 0.15f + it / 12f) % 1f)
                val x = 105f + (plantX - 120f) * t
                val y = h - 70f - sin(t * Math.PI).toFloat() * 80f + sin(phase + it).toFloat() * 4f
                canvas.drawCircle(x, y, 5f, paint)
            }

            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 28f
            paint.color = Palette.mint
            canvas.drawText("WATERING", w / 2f, 32f, paint)
        } else {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 28f
            paint.color = Palette.mutedText
            canvas.drawText("Pump idle", w / 2f, 32f, paint)
        }

        postInvalidateDelayed(32L)
    }
}

class LightSunflowerView(context: Context) : View(context) {
    private var lightPercent = 0f
    private var status = "NORMAL"
    private var ledOn = false
    private var phase = 0f
    private var hiFrames = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener {
            hiFrames = 90
            invalidate()
        }
    }

    fun setLight(percent: Int, s: String, led: Boolean) {
        lightPercent = percent.coerceIn(0, 100).toFloat()
        status = s
        ledOn = led
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        phase += 0.08f
        if (hiFrames > 0) hiFrames--

        val w = width.toFloat()
        val h = height.toFloat()

        val dark = status.equals("DARK", true)
        val bright = status.equals("BRIGHT", true)
        val helpedByLed = dark && ledOn

        val bgTop = when {
            dark && !ledOn -> Color.rgb(224, 231, 239)
            helpedByLed -> Color.rgb(255, 248, 212)
            bright -> Color.rgb(255, 240, 182)
            else -> Color.rgb(237, 255, 243)
        }

        paint.shader = LinearGradient(0f, 0f, 0f, h, bgTop, Color.WHITE, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 28f, 28f, paint)
        paint.shader = null

        // Sun or LED glow source
        if (bright || helpedByLed) {
            paint.color = if (helpedByLed) Color.rgb(255, 225, 87) else Color.rgb(255, 198, 53)
            canvas.drawCircle(w - 68f, 60f, 34f + sin(phase).toFloat() * 3f, paint)
            paint.color = Color.argb(90, 255, 220, 84)
            canvas.drawCircle(w - 68f, 60f, 65f + sin(phase).toFloat() * 4f, paint)
        }

        // Ground
        val groundY = h - 46f
        paint.color = Color.rgb(121, 85, 48)
        canvas.drawRoundRect(RectF(0f, groundY, w, h), 0f, 0f, paint)

        val centerX = w / 2f
        val faceY = h * 0.40f
        val sway = if (dark && !ledOn) sin(phase).toFloat() * 1.5f else sin(phase).toFloat() * 5f
        val lean = if (dark && !ledOn) 16f else 0f

        // Thick sunflower stem
        paint.color = Palette.darkGreen
        paint.strokeWidth = 14f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(centerX, groundY, centerX + sway - lean, faceY + 82f, paint)

        // Large leaves
        paint.color = if (dark && !ledOn) Color.rgb(121, 149, 101) else Color.rgb(34, 197, 94)
        canvas.drawOval(RectF(centerX - 115f + sway, faceY + 110f, centerX - 20f + sway, faceY + 170f), paint)
        canvas.drawOval(RectF(centerX + 20f + sway, faceY + 115f, centerX + 115f + sway, faceY + 175f), paint)

        // Sunflower head position
        val flowerCx = centerX + sway - lean
        val flowerCy = faceY
        val petalColor = if (dark && !ledOn) Color.rgb(217, 178, 75) else Color.rgb(255, 204, 52)

        // Large petals
        paint.color = petalColor
        repeat(16) { i ->
            val angle = Math.toRadians((i * 22.5) - 90.0)
            val px = flowerCx + cos(angle).toFloat() * 78f
            val py = flowerCy + sin(angle).toFloat() * 78f
            canvas.save()
            canvas.rotate(Math.toDegrees(angle).toFloat() + 90f, px, py)
            canvas.drawOval(RectF(px - 18f, py - 44f, px + 18f, py + 18f), paint)
            canvas.restore()
        }

        // Face center
        paint.color = Color.rgb(110, 74, 44)
        canvas.drawCircle(flowerCx, flowerCy, 62f, paint)

        // Seeds
        paint.color = Color.rgb(86, 56, 31)
        repeat(22) { i ->
            val angle = Math.toRadians((i * 360.0 / 22.0))
            val rx = flowerCx + cos(angle).toFloat() * 26f
            val ry = flowerCy + sin(angle).toFloat() * 26f
            canvas.drawCircle(rx, ry, 3f, paint)
        }

        // Eyes
        paint.color = Color.BLACK
        val eyeOffsetY = if (dark && !ledOn) 6f else 0f
        canvas.drawOval(RectF(flowerCx - 26f, flowerCy - 14f + eyeOffsetY, flowerCx - 10f, flowerCy + 6f + eyeOffsetY), paint)
        canvas.drawOval(RectF(flowerCx + 10f, flowerCy - 14f + eyeOffsetY, flowerCx + 26f, flowerCy + 6f + eyeOffsetY), paint)

        paint.color = Color.WHITE
        canvas.drawCircle(flowerCx - 20f, flowerCy - 9f + eyeOffsetY, 3f, paint)
        canvas.drawCircle(flowerCx + 16f, flowerCy - 9f + eyeOffsetY, 3f, paint)

        // Expressive mouth
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f

        val mouthRect = when {
            dark && !ledOn -> RectF(flowerCx - 25f, flowerCy + 15f, flowerCx + 25f, flowerCy + 40f)
            bright || helpedByLed -> RectF(flowerCx - 25f, flowerCy + 8f, flowerCx + 25f, flowerCy + 35f)
            else -> RectF(flowerCx - 20f, flowerCy + 18f, flowerCx + 20f, flowerCy + 28f)
        }

        when {
            dark && !ledOn -> canvas.drawArc(mouthRect, 200f, 140f, false, paint) // sad
            bright || helpedByLed -> canvas.drawArc(mouthRect, 20f, 140f, false, paint) // happy
            else -> canvas.drawLine(flowerCx - 18f, flowerCy + 23f, flowerCx + 18f, flowerCy + 23f, paint) // neutral
        }

        paint.style = Paint.Style.FILL

        // Blush when happy/recovering
        if (bright || helpedByLed) {
            paint.color = Color.argb(100, 255, 120, 120)
            canvas.drawCircle(flowerCx - 36f, flowerCy + 12f, 9f, paint)
            canvas.drawCircle(flowerCx + 36f, flowerCy + 12f, 9f, paint)
        }

        // Label
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 31f
        paint.color = when {
            dark && !ledOn -> Palette.warning
            helpedByLed -> Color.rgb(202, 138, 4)
            bright -> Palette.darkGreen
            else -> Palette.darkGreen
        }

        val label = when {
            hiFrames > 0 -> "Hi! I am your sunflower 🌻"
            dark && !ledOn -> "I am sad... I need light"
            helpedByLed -> "LED light is helping me!"
            bright -> "I am very happy!"
            else -> "I feel okay"
        }

        canvas.drawText(label, w / 2f, 36f, paint)

        paint.textSize = 24f
        paint.typeface = Typeface.DEFAULT
        paint.color = Palette.mutedText
        canvas.drawText("Light level: ${lightPercent.toInt()}%", w / 2f, 66f, paint)

        postInvalidateDelayed(32L)
    }
}

class LedArrayShineView(context: Context) : View(context) {
    private var ledOn = false
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setLedState(on: Boolean) {
        ledOn = on
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        phase += if (ledOn) 0.12f else 0.04f

        val w = width.toFloat()
        val h = height.toFloat()

        paint.color = Color.rgb(246, 255, 250)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 28f, 28f, paint)

        // LED array bar
        paint.color = if (ledOn) Color.rgb(255, 218, 80) else Color.rgb(166, 173, 181)
        canvas.drawRoundRect(RectF(w * 0.18f, 22f, w * 0.82f, 56f), 16f, 16f, paint)

        repeat(6) {
            val cx = w * 0.23f + it * (w * 0.54f / 5f)
            paint.color = if (ledOn) Color.rgb(255, 245, 135) else Color.rgb(209, 213, 219)
            canvas.drawCircle(cx, 39f, 8f, paint)
        }

        if (ledOn) {
            paint.color = Color.argb(80, 255, 214, 74)
            repeat(6) {
                val cx = w * 0.23f + it * (w * 0.54f / 5f)
                val beam = Path()
                beam.moveTo(cx - 12f, 56f)
                beam.lineTo(cx + sin(phase + it).toFloat() * 18f, h - 35f)
                beam.lineTo(cx + 12f, 56f)
                beam.close()
                canvas.drawPath(beam, paint)
            }
        }

        // Plants under LED
        drawSmallPlant(canvas, w * 0.35f, h - 32f, ledOn)
        drawSmallPlant(canvas, w * 0.55f, h - 32f, ledOn)
        drawSmallPlant(canvas, w * 0.73f, h - 32f, ledOn)

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 28f
        paint.color = if (ledOn) Color.rgb(202, 138, 4) else Palette.mutedText
        canvas.drawText(if (ledOn) "Artificial sunlight ON" else "LED array idle", w / 2f, h - 8f, paint)

        postInvalidateDelayed(32L)
    }

    private fun drawSmallPlant(canvas: Canvas, x: Float, ground: Float, happy: Boolean) {
        val sway = if (happy) sin(phase + x).toFloat() * 4f else 0f
        paint.color = Palette.darkGreen
        paint.strokeWidth = 6f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x, ground, x + sway, ground - 58f, paint)
        paint.color = if (happy) Color.rgb(34, 197, 94) else Color.rgb(111, 142, 100)
        canvas.drawOval(RectF(x - 38f + sway, ground - 60f, x + sway, ground - 24f), paint)
        canvas.drawOval(RectF(x + sway, ground - 64f, x + 38f + sway, ground - 28f), paint)
    }
}

/* ==========================================================
   DATA MODELS
   ========================================================== */

data class Thresholds(
    val temperatureHighC: Float = 40f,
    val temperatureLowC: Float = 37f,
    val humidityHighPercent: Float = 40f,
    val humidityLowPercent: Float = 35f,
    val waterLevelLowPercent: Int = 30,
    val soilDryThresholdRaw: Int = 3000,
    val soilWetThresholdRaw: Int = 1500,
    val ldrDarkThresholdRaw: Int = 2500,
    val pumpRunSeconds: Int = 3,
    val pumpCooldownSeconds: Int = 5
)

data class Telemetry(
    val connected: Boolean,
    val deviceName: String,
    val enabled: Boolean,
    val mode: String,
    val emergencyStop: Boolean,
    val uptimeMs: Long,
    val temperatureC: Float,
    val humidityPercent: Float,
    val dhtValid: Boolean,
    val waterRaw: Int,
    val waterPercent: Int,
    val waterStatus: String,
    val soilRaw: Int,
    val soilPercent: Int,
    val soilStatus: String,
    val ldrRaw: Int,
    val ldrPercent: Int,
    val ldrStatus: String,
    val masterRaw: Int,
    val masterState: String,
    val fanState: String,
    val fanReason: String,
    val pumpState: String,
    val pumpReason: String,
    val pumpLastRunMsAgo: Long,
    val cooldownRemainingMs: Long,
    val ledState: String,
    val ledReason: String,
    val thresholds: Thresholds
) {
    companion object {
        private var demoTemp = 28f
        private var demoHumidity = 45f
        private var demoWater = 70f
        private var demoSoil = 55f
        private var demoLight = 58f

        private fun jitter(amount: Float): Float {
            return ((Math.random().toFloat() - 0.5f) * amount)
        }

        /*
           Live UI simulation:
           This is intentionally NOT a perfect sine wave anymore.
           It uses smooth random drift so the humidity chart keeps moving naturally
           and does not look frozen or repetitive after a while.
        */
        fun mock(connected: Boolean, tick: Long = 0L): Telemetry {
            val now = System.currentTimeMillis()
            val phase = now / 1000.0

            demoTemp = (demoTemp + sin(phase / 5.0).toFloat() * 0.18f + jitter(0.55f))
                .coerceIn(18f, 43f)

            demoHumidity = (demoHumidity + sin(phase / 3.2).toFloat() * 0.22f + jitter(1.15f))
                .coerceIn(25f, 88f)

            demoWater = (demoWater + sin(phase / 8.0).toFloat() * 0.08f + jitter(0.45f))
                .coerceIn(12f, 100f)

            demoSoil = (demoSoil + sin(phase / 4.3).toFloat() * 0.20f + jitter(1.0f))
                .coerceIn(10f, 95f)

            demoLight = (demoLight + sin(phase / 3.7).toFloat() * 0.35f + jitter(1.45f))
                .coerceIn(5f, 100f)

            val thresholds = Thresholds()

            val temp = demoTemp
            val hum = demoHumidity
            val water = demoWater.toInt()
            val soil = demoSoil.toInt()
            val light = demoLight.toInt()

            val fanOn = temp >= thresholds.temperatureHighC || hum >= thresholds.humidityHighPercent
            val soilDry = soil < 35
            val soilWet = soil > 75
            val dark = light < 40
            val bright = light > 75

            return Telemetry(
                connected = connected,
                deviceName = "GreenHouseByXzynetic",
                enabled = true,
                mode = if ((tick / 20) % 2 == 0L) "AUTO" else "MANUAL",
                emergencyStop = false,
                uptimeMs = tick * 700L,
                temperatureC = temp,
                humidityPercent = hum,
                dhtValid = true,
                waterRaw = (water * 40).coerceIn(0, 4095),
                waterPercent = water,
                waterStatus = if (water <= thresholds.waterLevelLowPercent) "LOW" else "NORMAL",
                soilRaw = (4095 - soil * 35).coerceIn(0, 4095),
                soilPercent = soil,
                soilStatus = when {
                    soilDry -> "DRY"
                    soilWet -> "WET"
                    else -> "NORMAL"
                },
                ldrRaw = (light * 35).coerceIn(0, 4095),
                ldrPercent = light,
                ldrStatus = when {
                    dark -> "DARK"
                    bright -> "BRIGHT"
                    else -> "NORMAL"
                },
                masterRaw = 1,
                masterState = "ON",
                fanState = if (fanOn) "ON" else "OFF",
                fanReason = if (fanOn) "HIGH_TEMP_OR_HUMIDITY" else "SAFE_RANGE",
                pumpState = if (soilDry) "ON" else "OFF",
                pumpReason = if (soilDry) "DRY_SOIL" else "MOIST_SOIL",
                pumpLastRunMsAgo = 4200L,
                cooldownRemainingMs = if (soilDry) 0L else 1800L,
                ledState = if (dark) "ON" else "OFF",
                ledReason = if (dark) "LOW_LIGHT" else "ENOUGH_LIGHT",
                thresholds = thresholds
            )
        }
    }
}

object Palette {
    val darkGreen: Int = Color.parseColor("#064E3B")
    val mint: Int = Color.parseColor("#10B981")
    val offWhite: Int = Color.parseColor("#F4F7F5")
    val white: Int = Color.WHITE
    val warning: Int = Color.parseColor("#F59E0B")
    val critical: Int = Color.parseColor("#EF4444")
    val darkText: Int = Color.parseColor("#1F2937")
    val mutedText: Int = Color.parseColor("#6B7280")
    val disabled: Int = Color.parseColor("#9CA3AF")
    val softMint: Int = Color.parseColor("#DFF8EC")
    val softWarning: Int = Color.parseColor("#FFF4D9")
    val softCritical: Int = Color.parseColor("#FFE2E2")
}

fun blend(a: Int, b: Int, tRaw: Float): Int {
    val t = tRaw.coerceIn(0f, 1f)
    return Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )
}

