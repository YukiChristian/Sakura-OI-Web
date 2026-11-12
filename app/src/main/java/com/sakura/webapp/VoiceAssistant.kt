package com.sakura.webapp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Lets the user pick Sakura as the phone's digital assistant (Settings → Apps → Default
 * apps → Digital assistant). The assistant gesture or button then opens Open WebUI in voice
 * mode. Android only lists apps that provide these services.
 */
class SakuraVoiceInteractionService : VoiceInteractionService()

class SakuraVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SakuraVoiceSession(this)
}

private class SakuraVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(IncomingIntents.ACTION_VOICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startAssistantActivity(intent)
        else context.startActivity(intent)
        hide()
    }
}

/**
 * Android requires an assistant to name a speech recognizer. Open WebUI does its own speech
 * recognition, so this one only reports that it cannot be used directly.
 */
class SakuraRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
