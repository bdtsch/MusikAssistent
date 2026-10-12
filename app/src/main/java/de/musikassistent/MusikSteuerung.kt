package de.musikassistent

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

/**
 * Steuert Tidal/Spotify.
 * Direktes Abspielen klappt über die Mediensitzung der App (playFromSearch) –
 * dafür braucht die App "Benachrichtigungszugriff". Ohne den gibt es nur
 * den alten Weg per Intent (bei Spotify öffnet sich dann oft nur die Suche).
 */
class MusikSteuerung(private val context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sessions = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val haupt = Handler(Looper.getMainLooper())
    private val listener = ComponentName(context, MedienListener::class.java)

    // ---------------- einfache Steuerung ----------------

    fun weiter() = aktiv()?.transportControls?.skipToNext() ?: taste(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun zurueck() = aktiv()?.transportControls?.skipToPrevious() ?: taste(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun pause() = aktiv()?.transportControls?.pause() ?: taste(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun abspielen() = aktiv()?.transportControls?.play() ?: taste(KeyEvent.KEYCODE_MEDIA_PLAY)

    fun lautstaerke(hoch: Boolean) {
        val richtung = if (hoch) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, richtung, AudioManager.FLAG_SHOW_UI) }
    }

    private fun taste(keyCode: Int) {
        val zeit = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(zeit, zeit, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(zeit, zeit, KeyEvent.ACTION_UP, keyCode, 0))
    }

    fun istInstalliert(app: MusikApp): Boolean =
        context.packageManager.getLaunchIntentForPackage(app.paket) != null

    // ---------------- Mediensitzungen ----------------

    fun hatSitzungszugriff(): Boolean {
        val erlaubt = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        return erlaubt.contains(listener.flattenToString()) || erlaubt.contains(context.packageName)
    }

    private fun alleSitzungen(): List<MediaController> = try {
        if (hatSitzungszugriff()) sessions.getActiveSessions(listener) else emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    /** Die gerade spielende (oder zuletzt aktive) Sitzung. */
    private fun aktiv(): MediaController? {
        val alle = alleSitzungen()
        return alle.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: alle.firstOrNull()
    }

    private fun sitzungVon(app: MusikApp): MediaController? =
        alleSitzungen().firstOrNull { it.packageName == app.paket }

    // ---------------- Suchen & Abspielen ----------------

    /**
     * Sucht und spielt ab. [fertig] bekommt true, wenn die Musik läuft,
     * false, wenn höchstens die Suche geöffnet werden konnte.
     */
    fun suchenUndSpielen(begriff: String, app: MusikApp, fertig: (Boolean) -> Unit) {
        if (!hatSitzungszugriff()) {
            fertig(perIntent(begriff, app) && false)
            return
        }
        val sitzung = sitzungVon(app)
        if (sitzung != null) {
            perSitzung(sitzung, begriff, app, fertig)
            return
        }
        // App läuft noch nicht: im Hintergrund starten und auf ihre Mediensitzung warten
        appStarten(app)
        warteAufSitzung(app, versuche = 20) { s ->
            if (s != null) perSitzung(s, begriff, app, fertig)
            else fertig(perIntent(begriff, app) && false)
        }
    }

    private fun perSitzung(s: MediaController, begriff: String, app: MusikApp, fertig: (Boolean) -> Unit) {
        Log.i(AssistentService.TAG, "playFromSearch an ${s.packageName}: $begriff")
        if (begriff.isBlank()) s.transportControls.play()
        else s.transportControls.playFromSearch(begriff, Bundle().apply {
            putString(SearchManager.QUERY, begriff)
        })
        // Prüfen, ob es wirklich losgeht. Wenn nicht: Intent-Weg als Notlösung
        pruefeObSpielt(app, versuche = 10, vorherTitel = titel(s)) { spielt ->
            if (spielt) fertig(true)
            else fertig(perIntent(begriff, app) && false)
        }
    }

    private fun titel(s: MediaController?) =
        s?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)

    private fun pruefeObSpielt(app: MusikApp, versuche: Int, vorherTitel: String?, ergebnis: (Boolean) -> Unit) {
        haupt.postDelayed({
            val s = sitzungVon(app)
            val state = s?.playbackState?.state
            val spielt = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_CONNECTING
            if (spielt && (titel(s) != vorherTitel || versuche <= 6)) ergebnis(true)
            else if (versuche <= 0) ergebnis(spielt)
            else pruefeObSpielt(app, versuche - 1, vorherTitel, ergebnis)
        }, 500)
    }

    private fun warteAufSitzung(app: MusikApp, versuche: Int, ergebnis: (MediaController?) -> Unit) {
        haupt.postDelayed({
            val s = sitzungVon(app)
            when {
                s != null -> haupt.postDelayed({ ergebnis(sitzungVon(app) ?: s) }, 1200) // App kurz fertig laden lassen
                versuche <= 0 -> ergebnis(null)
                else -> warteAufSitzung(app, versuche - 1, ergebnis)
            }
        }, 500)
    }

    private fun appStarten(app: MusikApp) {
        // Musikdienst der App direkt per Medientaste wecken – startet ohne Bildschirmwechsel
        val wecken = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(app.paket)
        val zeit = SystemClock.uptimeMillis()
        try {
            context.sendBroadcast(Intent(wecken).putExtra(Intent.EXTRA_KEY_EVENT,
                KeyEvent(zeit, zeit, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)))
            context.sendBroadcast(Intent(wecken).putExtra(Intent.EXTRA_KEY_EVENT,
                KeyEvent(zeit, zeit, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)))
        } catch (_: Exception) {}
        // Zusätzlich die App öffnen (darf der Dienst dank "Über anderen Apps einblenden")
        context.packageManager.getLaunchIntentForPackage(app.paket)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(it) } catch (_: Exception) {}
        }
    }

    /** Alter Weg: Android-Standard-Intent, danach notfalls Suche in der App öffnen. */
    private fun perIntent(begriff: String, app: MusikApp): Boolean {
        val spielen = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(app.paket)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, begriff)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (starte(spielen)) return true
        val q = Uri.encode(begriff)
        val suche = when (app) {
            MusikApp.SPOTIFY -> Uri.parse("spotify:search:$q")
            MusikApp.TIDAL -> Uri.parse("https://tidal.com/search?q=$q")
        }
        return starte(Intent(Intent.ACTION_VIEW, suche).setPackage(app.paket).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun starte(intent: Intent): Boolean = try {
        if (intent.resolveActivity(context.packageManager) == null) false
        else { context.startActivity(intent); true }
    } catch (e: Exception) {
        false
    }
}
