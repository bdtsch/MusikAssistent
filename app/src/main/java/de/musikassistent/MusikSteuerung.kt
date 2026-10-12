package de.musikassistent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent

/** Steuert den gerade laufenden Player (egal ob Tidal, Spotify, ...) und startet Suchen. */
class MusikSteuerung(private val context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun taste(keyCode: Int) {
        val zeit = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(zeit, zeit, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(zeit, zeit, KeyEvent.ACTION_UP, keyCode, 0))
    }

    fun weiter() = taste(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun zurueck() = taste(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun pause() = taste(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun abspielen() = taste(KeyEvent.KEYCODE_MEDIA_PLAY)

    fun lautstaerke(hoch: Boolean) {
        val richtung = if (hoch) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, richtung, AudioManager.FLAG_SHOW_UI) }
    }

    fun istInstalliert(app: MusikApp): Boolean =
        context.packageManager.getLaunchIntentForPackage(app.paket) != null

    /**
     * Sucht und spielt ab. Zuerst der Android-Standard "Abspielen aus Suche"
     * (wie bei Google Assistant / Android Auto). Klappt das nicht, wird
     * die Suche in der App geöffnet.
     * @return true, wenn irgendetwas gestartet wurde
     */
    fun suchenUndSpielen(begriff: String, app: MusikApp): Boolean {
        val spielen = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(app.paket)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, begriff)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (starte(spielen)) return true

        // Plan B: Suche in der App öffnen
        val q = Uri.encode(begriff)
        val suche = when (app) {
            MusikApp.SPOTIFY -> Uri.parse("spotify:search:$q")
            MusikApp.TIDAL -> Uri.parse("https://tidal.com/search?q=$q")
        }
        val ansehen = Intent(Intent.ACTION_VIEW, suche).apply {
            setPackage(app.paket)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return starte(ansehen)
    }

    private fun starte(intent: Intent): Boolean = try {
        if (intent.resolveActivity(context.packageManager) == null) false
        else { context.startActivity(intent); true }
    } catch (e: Exception) {
        false
    }
}
