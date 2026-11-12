package com.sakura.webapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug only: `adb shell am broadcast -a com.sakura.webapp.debug.WAKE -n com.sakura.webapp/.WakeActivationTestReceiver`. */
class WakeActivationTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val voice = Intent(context, MainActivity::class.java)
            .setAction(IncomingIntents.ACTION_VOICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Like a real detection: the listener lets go of the microphone first.
        WakeWordService.send(context, WakeWordService.ACTION_PAUSE)
        WakeActivation.run(context.applicationContext) {
            val grow = android.app.ActivityOptions.makeCustomAnimation(context, R.anim.voice_grow, 0)
            context.startActivity(voice, grow.toBundle())
        }
    }
}
