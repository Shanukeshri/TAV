package com.example.teachablevoice.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Service that creates the TAVVoiceInteractionSession when the system requests it.
 * This is bound by the system when TAVVoiceInteractionService.showSession() is called.
 */
class TAVVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return TAVVoiceInteractionSession(this)
    }
}
