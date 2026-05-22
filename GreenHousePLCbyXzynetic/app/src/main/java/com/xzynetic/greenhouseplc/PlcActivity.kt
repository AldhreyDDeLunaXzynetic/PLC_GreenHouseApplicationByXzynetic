package com.xzynetic.greenhouseplc

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class PlcActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val espIp = intent.getStringExtra("ESP_IP") ?: "192.168.4.1"

        val textView = TextView(this)
        textView.text = "Connected to Greenhouse PLC\nESP32 IP: $espIp"
        textView.textSize = 22f
        textView.setPadding(40, 80, 40, 40)

        setContentView(textView)
    }
}