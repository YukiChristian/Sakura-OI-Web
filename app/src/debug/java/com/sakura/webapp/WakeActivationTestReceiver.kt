package com.sakura.webapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug only: `adb shell am broadcast -a com.sakura.webapp.debug.WAKE -n com.sakura.webapp/.WakeActivationTestReceiver`
 * (add `--ez heard true` to make the listening service act as if it heard the phrase).
 */
class WakeActivationTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // `--ez heard true`: the listening service acts as if it heard the phrase (tests its recovery).
        if (intent.getBooleanExtra("heard", false)) {
            context.startService(Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_TEST_HEARD))
            return
        }
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
