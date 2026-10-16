package com.dubdu.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Temporary starter screen. Replace with the Gemini-generated implementation
 * when the original app source is provided.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = TextView(this).apply {
            text = "Dubdu Android\n\nProject scaffold is ready. Add the app source to continue."
            textSize = 18f
            setPadding(32, 48, 32, 32)
        }
        setContentView(message)
    }
}
