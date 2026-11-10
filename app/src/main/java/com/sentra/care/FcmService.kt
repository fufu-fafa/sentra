package com.sentra.care

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sentra.care

/**
 * Firebase Cloud Messaging from the relay. Messages are data-only, so this runs even when the app
 * is closed: alarms open the full-screen alarm, the rest become notifications. Only in use when
 * the app was built with app/google-services.json.
 */
class FcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        care.handlePush(message.data)
    }

    override fun onNewToken(token: String) {
        care.onNewPushToken(token)
    }
}
