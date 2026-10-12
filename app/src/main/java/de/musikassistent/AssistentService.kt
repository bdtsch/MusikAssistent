package de.musikassistent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

/**
 * Läuft im Hintergrund und lauscht offline (Vosk, nur ein paar Wörter = wenig CPU)
 * auf das Aktivierungswort. Danach wird ein Befehl erkannt und ausgeführt.
 */
class AssistentService : Service() {

    companion object {
        const val TAG = "MusikAssistent"
        const val AKTION_STOP = "de.musikassistent.STOP"
        private const val KANAL = "assistent"
        private const val NOTIF_ID = 1
        private const val ABTASTRATE = 16000f

        @Volatile var laeuft = false
            private set

        private var modell: Model? = null   // bleibt geladen, solange der Prozess lebt

        fun starten(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, AssistentService::class.java))
        }

        fun stoppen(ctx: Context) {
            ctx.startService(Intent(ctx, AssistentService::class.java).setAction(AKTION_STOP))
        }
    }

    private enum class Zustand { LADEN, LAUSCHEN, BEFEHL, AUSFUEHREN }

    private val haupt = Handler(Looper.getMainLooper())
    private lateinit var einst: Einstellungen
    private lateinit var musik: MusikSteuerung
    private lateinit var audio: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsBereit = false

    private var voskDienst: SpeechService? = null
    private var voskRec: Recognizer? = null
    private var google: SpeechRecognizer? = null
    private var fokus: AudioFocusRequest? = null
    private var zustand = Zustand.LADEN
    private var befehlsTimeout: Runnable? = null

    // ---------------- Lebenszyklus ----------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        einst = Einstellungen(this)
        musik = MusikSteuerung(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        kanalAnlegen()
        startForeground(NOTIF_ID, benachrichtigung("Lade Spracherkennung …"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        laeuft = true

        // Hält die CPU wach, damit das Lauschen bei ausgeschaltetem Display weiterläuft
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusikAssistent:lauschen")
            .apply { setReferenceCounted(false); acquire() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.GERMAN
                ttsBereit = true
            }
        }

        modellLaden()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AKTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        laeuft = false
        haupt.removeCallbacksAndMessages(null)
        voskStoppen()
        googleStoppen()
        fokusAbgeben()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------------- Modell & Aktivierungswort ----------------

    private fun modellLaden() {
        modell?.let { lauschen(); return }
        StorageService.unpack(this, "model-de", "model",
            { m -> modell = m; lauschen() },
            { e ->
                Log.e(TAG, "Modell konnte nicht geladen werden", e)
                status("Fehler: Sprachmodell fehlt")
            })
    }

    /** Phase 1: nur auf das Aktivierungswort hören (Grammatik mit wenigen Wörtern = sparsam). */
    private fun lauschen() {
        if (!laeuft) return
        val m = modell ?: return
        googleStoppen()
        voskStoppen()
        zustand = Zustand.LAUSCHEN
        val wort = einst.aktivierungswort
        status("Hört auf „$wort“")

        val grammatik = JSONArray().put(wort).put("[unk]").toString()
        try {
            val rec = Recognizer(m, ABTASTRATE, grammatik).also { voskRec = it }
            voskDienst = SpeechService(rec, ABTASTRATE).also { it.startListening(wortHoerer) }
        } catch (e: Exception) {
            Log.e(TAG, "Mikrofon nicht verfügbar", e)
            status("Mikrofon belegt – neuer Versuch …")
            haupt.postDelayed({ lauschen() }, 3000)
        }
    }

    private val wortHoerer = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = pruefen(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = pruefen(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) = pruefen(hypothesis, "text")
        override fun onError(exception: Exception?) {
            Log.e(TAG, "Vosk-Fehler", exception)
            haupt.postDelayed({ lauschen() }, 1000)
        }
        override fun onTimeout() {}

        private fun pruefen(json: String?, feld: String) {
            if (zustand != Zustand.LAUSCHEN || json == null) return
            val text = JSONObject(json).optString(feld)
            if (text.contains(einst.aktivierungswort)) aktiviert()
        }
    }

    // ---------------- Phase 2: Befehl erkennen ----------------

    private fun aktiviert() {
        zustand = Zustand.BEFEHL
        voskStoppen()
        fokusHolen()       // Musik leiser machen, damit man verstanden wird
        status("Ich höre …")
        piep()
        // kurz warten, damit der Piepton nicht mit aufgenommen wird
        haupt.postDelayed({
            if (einst.googleErkennung && SpeechRecognizer.isRecognitionAvailable(this)) googleBefehl()
            else offlineBefehl()
        }, 300)
    }

    /** Google-Erkennung: deutlich besser bei englischen Songtiteln und Bandnamen. */
    private fun googleBefehl() {
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        google = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val liste = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                befehlErhalten(liste ?: emptyList<String>())
            }
            override fun onError(error: Int) {
                Log.w(TAG, "Google-Erkennung Fehler $error")
                if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_CLIENT) {
                    googleStoppen()
                    offlineBefehl()      // kein Netz → offline weiter
                } else befehlErhalten(null)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            // Englisch zusätzlich erlauben (Songtitel, englische Befehle)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en-GB"))
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("de-DE", "en-US"))
            }
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        sr.startListening(intent)
    }

    /** Offline-Erkennung als Fallback (ohne Internet, bei Songnamen ungenauer). */
    private fun offlineBefehl() {
        val m = modell ?: return befehlErhalten(null)
        try {
            val rec = Recognizer(m, ABTASTRATE).also { voskRec = it }
            voskDienst = SpeechService(rec, ABTASTRATE).also { dienst ->
                dienst.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {}
                    override fun onResult(hypothesis: String?) {
                        val text = hypothesis?.let { JSONObject(it).optString("text") }
                        if (!text.isNullOrBlank()) befehlErhalten(listOf(text))
                    }
                    override fun onFinalResult(hypothesis: String?) {}
                    override fun onError(exception: Exception?) = befehlErhalten(null)
                    override fun onTimeout() = befehlErhalten(null)
                }, 7000)
            }
        } catch (e: Exception) {
            befehlErhalten(null)
        }
    }

    private fun befehlErhalten(vorschlaege: List<String>?) {
        if (zustand != Zustand.BEFEHL) return
        zustand = Zustand.AUSFUEHREN
        voskStoppen()
        googleStoppen()
        fokusAbgeben()
        Log.i(TAG, "Befehl: $vorschlaege")

        val liste = vorschlaege?.filter { it.isNotBlank() }.orEmpty()
        if (liste.isEmpty()) {
            sagenDannLauschen("Nicht verstanden")
            return
        }
        status("Verstanden: ${liste.first()}")
        ausfuehren(BefehlsParser.parseAlle(liste))
    }

    // ---------------- Phase 3: ausführen ----------------

    private fun ausfuehren(b: Befehl) {
        when (b) {
            Befehl.Weiter -> { musik.weiter(); lauschen() }
            Befehl.Zurueck -> { musik.zurueck(); lauschen() }
            Befehl.Pause -> { musik.pause(); lauschen() }
            Befehl.Abspielen -> { musik.abspielen(); lauschen() }
            Befehl.Lauter -> { musik.lautstaerke(true); lauschen() }
            Befehl.Leiser -> { musik.lautstaerke(false); lauschen() }
            Befehl.Stopp -> sagen("Assistent aus") { stopSelf() }
            is Befehl.Suchen -> {
                var app = b.app ?: einst.standardApp
                if (!musik.istInstalliert(app)) {
                    app = MusikApp.values().firstOrNull { musik.istInstalliert(it) }
                        ?: return sagenDannLauschen("Weder Tidal noch Spotify installiert")
                }
                val ansage = if (b.suchbegriff.isBlank()) "Starte ${app.anzeige}"
                    else "Spiele ${b.suchbegriff} auf ${app.anzeige}"
                // erst ansagen, dann starten, damit die Ansage nicht in die Musik quatscht
                sagen(ansage) {
                    musik.suchenUndSpielen(b.suchbegriff, app) { spielt ->
                        if (spielt) lauschen()
                        else if (!musik.hatSitzungszugriff())
                            sagenDannLauschen("Nur Suche geöffnet. Bitte Benachrichtigungszugriff erlauben")
                        else sagenDannLauschen("Konnte nicht direkt abspielen")
                    }
                }
            }
            is Befehl.Unbekannt -> sagenDannLauschen("Unbekannter Befehl")
        }
    }

    // ---------------- Hilfen ----------------

    private fun sagenDannLauschen(text: String) = sagen(text) { lauschen() }

    /** Spricht den Text (wenn aktiviert) und ruft danach [weiter] auf dem Hauptthread auf. */
    private fun sagen(text: String, weiter: () -> Unit) {
        status(text)
        val t = tts
        if (!einst.sprachausgabe || !ttsBereit || t == null) {
            haupt.post { weiter() }; return
        }
        val id = System.nanoTime().toString()
        var erledigt = false
        val fertig = { if (!erledigt) { erledigt = true; haupt.post { weiter() } } }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) fertig() }
            @Deprecated("alt") override fun onError(utteranceId: String?) { fertig() }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        haupt.postDelayed({ fertig() }, 6000) // Sicherheitsnetz
    }

    private fun piep() {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                haupt.postDelayed({ release() }, 400)
            }
        } catch (_: Exception) {}
    }

    private fun fokusHolen() {
        val anfrage = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        fokus = anfrage
        audio.requestAudioFocus(anfrage)
    }

    private fun fokusAbgeben() {
        fokus?.let { audio.abandonAudioFocusRequest(it) }
        fokus = null
    }

    private fun voskStoppen() {
        voskDienst?.let { it.stop(); it.shutdown() }
        voskDienst = null
        voskRec?.close()
        voskRec = null
    }

    private fun googleStoppen() {
        google?.let { it.cancel(); it.destroy() }
        google = null
    }

    private fun kanalAnlegen() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(KANAL, "Assistent aktiv", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun benachrichtigung(text: String): Notification {
        val oeffnen = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopp = PendingIntent.getService(this, 1,
            Intent(this, AssistentService::class.java).setAction(AKTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, KANAL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Musik-Assistent")
            .setContentText(text)
            .setContentIntent(oeffnen)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Beenden", stopp).build())
            .build()
    }

    private fun status(text: String) {
        if (!laeuft) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, benachrichtigung(text))
    }
}
