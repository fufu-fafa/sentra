package com.sentra.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Short voice prompts in Bahasa Indonesia (§9.7: voice is only a complement and kept brief, so it
 * doesn't cover the sounds around the child). A new prompt replaces one still being spoken.
 */
class Speaker(context: Context) {
    @Volatile private var ready = false
    private var pendingText: String? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) onReady() else Log.w(TAG, "TextToSpeech unavailable ($status)")
    }

    private fun onReady() {
        val result = tts.setLanguage(Locale("id", "ID"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "No Indonesian voice installed; using the phone's default voice")
        }
        tts.setSpeechRate(1.0f)
        ready = true
        synchronized(this) { pendingText.also { pendingText = null } }?.let(::speak)
    }

    fun speak(text: String) {
        if (!ready) {
            synchronized(this) { pendingText = text } // said as soon as the engine is up
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sentra-${System.nanoTime()}")
    }

    private companion object {
        const val TAG = "Speaker"
    }
}
