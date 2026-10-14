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
/** Was nach einem Abspielversuch wirklich passiert ist. */
sealed class AbspielErgebnis {
    /** Es läuft etwas Neues. [passt] = entspricht dem gesuchten Song (soweit prüfbar). */
    data class Spielt(val titel: String, val kuenstler: String, val passt: Boolean) : AbspielErgebnis()
    /** Konnte nicht direkt abspielen, aber die Suche in der App wurde geöffnet. */
    object NurSuche : AbspielErgebnis()
    object Fehlgeschlagen : AbspielErgebnis()
}

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
     * Sucht und spielt ab und meldet ehrlich zurück, was danach wirklich läuft.
     * @param ziel konkreter Song aus der Katalogsuche (falls bekannt) – macht die Suche in der App genauer
     */
    fun suchenUndSpielen(
        anfrage: String, art: SuchArt, ziel: Kandidat?, app: MusikApp,
        fertig: (AbspielErgebnis) -> Unit
    ) {
        val text = if (ziel != null) "${ziel.titel} ${ziel.kuenstler}" else anfrage
        if (!hatSitzungszugriff()) {
            fertig(perIntent(text, app))
            return
        }
        val sitzung = sitzungVon(app)
        if (sitzung != null) {
            perSitzung(sitzung, text, art, ziel, app, fertig)
            return
        }
        // App läuft noch nicht: starten und auf ihre Mediensitzung warten
        appStarten(app)
        warteAufSitzung(app, versuche = 20) { s ->
            if (s != null) perSitzung(s, text, art, ziel, app, fertig)
            else fertig(perIntent(text, app))
        }
    }

    private fun perSitzung(
        s: MediaController, text: String, art: SuchArt, ziel: Kandidat?, app: MusikApp,
        fertig: (AbspielErgebnis) -> Unit
    ) {
        val vorherTitel = titel(s)
        val vorherSpielte = s.playbackState?.state == PlaybackState.STATE_PLAYING
        Log.i(AssistentService.TAG, "playFromSearch an ${s.packageName}: $text ($art), vorher: $vorherTitel")

        if (text.isBlank()) {
            s.transportControls.play()
        } else {
            s.transportControls.playFromSearch(text, suchExtras(text, art, ziel))
        }

        pruefe(app, versuche = 18, vorherTitel, vorherSpielte, nurFortsetzen = text.isBlank(), ziel) { ergebnis ->
            if (ergebnis != null) fertig(ergebnis)
            else fertig(perIntent(text, app))   // hat nicht geklappt → wenigstens Suche öffnen
        }
    }

    /** Zusatzinfos nach Android-Standard, damit die App weiß, ob Song, Album oder Künstler gemeint ist. */
    private fun suchExtras(text: String, art: SuchArt, ziel: Kandidat?) = Bundle().apply {
        putString(SearchManager.QUERY, text)
        when {
            ziel != null -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_TITLE, ziel.titel)
                putString(MediaStore.EXTRA_MEDIA_ARTIST, ziel.kuenstler)
                if (ziel.album.isNotEmpty()) putString(MediaStore.EXTRA_MEDIA_ALBUM, ziel.album)
            }
            art == SuchArt.KUENSTLER -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_ARTIST, text)
            }
            art == SuchArt.ALBUM -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_ALBUM, text)
            }
            art == SuchArt.PLAYLIST -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_PLAYLIST, text)
            }
            else -> putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
    }

    private fun titel(s: MediaController?) =
        s?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)

    private fun kuenstler(s: MediaController?) =
        s?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)

    /**
     * Wartet bis zu ~9 s. Erfolg nur, wenn wirklich etwas Neues läuft:
     * - der Titel hat sich geändert, oder
     * - der laufende Titel passt zum gesuchten Song, oder
     * - es sollte nur fortgesetzt werden und es läuft.
     * Läuft einfach der alte Song weiter, zählt das NICHT als Erfolg.
     */
    private fun pruefe(
        app: MusikApp, versuche: Int, vorherTitel: String?, vorherSpielte: Boolean,
        nurFortsetzen: Boolean, ziel: Kandidat?, ergebnis: (AbspielErgebnis?) -> Unit
    ) {
        haupt.postDelayed({
            val s = sitzungVon(app)
            val spielt = s?.playbackState?.state == PlaybackState.STATE_PLAYING
            val t = titel(s)
            val passtZumZiel = ziel != null && t != null &&
                SongSuche.aehnlich(SongSuche.norm(t), SongSuche.norm(ziel.titel)) >= 0.7
            val neu = t != null && t != vorherTitel
            when {
                spielt && (neu || passtZumZiel || (nurFortsetzen && !vorherSpielte) || (nurFortsetzen && t != null)) ->
                    // kurz warten, falls die Titelinfo noch nachgeladen wird
                    haupt.postDelayed({
                        val s2 = sitzungVon(app)
                        ergebnis(AbspielErgebnis.Spielt(titel(s2) ?: t ?: "", kuenstler(s2) ?: kuenstler(s) ?: "",
                            passtZumZiel || ziel == null))
                    }, 700)
                versuche <= 0 -> ergebnis(null)
                else -> pruefe(app, versuche - 1, vorherTitel, vorherSpielte, nurFortsetzen, ziel, ergebnis)
            }
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

    /** Notlösung ohne Sitzung: Android-Standard-Intent, sonst Suche in der App öffnen. */
    private fun perIntent(begriff: String, app: MusikApp): AbspielErgebnis =
        if (perIntentStarten(begriff, app)) AbspielErgebnis.NurSuche else AbspielErgebnis.Fehlgeschlagen

    private fun perIntentStarten(begriff: String, app: MusikApp): Boolean {
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
