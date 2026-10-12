package de.musikassistent

import android.service.notification.NotificationListenerService

/**
 * Macht nichts mit Benachrichtigungen. Android verlangt diesen Dienst nur,
 * damit die App auf die Mediensitzungen von Tidal/Spotify zugreifen und
 * dort direkt "Suchen und Abspielen" auslösen darf.
 */
class MedienListener : NotificationListenerService()
