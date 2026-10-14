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

        /** Häufige Wörter (auch aus Liedern), die als "Gegenkandidaten" dienen. */
        private val ABLENKWOERTER = listOf(
            "ja", "nein", "ich", "du", "wir", "und", "oder", "aber", "so", "mal", "da", "hier",
            "nicht", "noch", "nur", "mit", "für", "von", "auf", "ein", "eine", "der", "die", "das",
            "ist", "bin", "hab", "habe", "mein", "dein", "dich", "mich", "liebe", "herz", "nacht",
            "tag", "leben", "immer", "nie", "alles", "hallo", "halt", "allo", "also", "hallen", "musi",
            "mus", "mu", "ok", "okay", "hey", "oh", "la", "na", "ah", "radio", "computer", "auto"
        )

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

        // Grammatik: Aktivierungswort + seine Einzelwörter + Ablenkwörter + [unk].
        // Die Ablenkwörter geben der Erkennung "andere Möglichkeiten", sodass Gesang
        // oder Gerede nicht mehr so leicht auf das Aktivierungswort gezwungen wird.
        val grammatik = JSONArray().put(wort)
        wort.split(" ").filter { it.isNotBlank() }.forEach { grammatik.put(it) }
        ABLENKWOERTER.forEach { grammatik.put(it) }
        grammatik.put("[unk]")
        try {
            val rec = Recognizer(m, ABTASTRATE, grammatik.toString()).also { voskRec = it }
            rec.setWords(true)   // liefert Sicherheit (conf) pro Wort
            voskDienst = SpeechService(rec, ABTASTRATE).also { it.startListening(wortHoerer) }
        } catch (e: Exception) {
            Log.e(TAG, "Mikrofon nicht verfügbar", e)
            status("Mikrofon belegt – neuer Versuch …")
            haupt.postDelayed({ lauschen() }, 3000)
        }
    }

    private val wortHoerer = object : org.vosk.android.RecognitionListener {
        // Teilergebnisse werden ignoriert – die lösen beim Singen am häufigsten falsch aus
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) = pruefen(hypothesis)
        override fun onFinalResult(hypothesis: String?) = pruefen(hypothesis)
        override fun onError(exception: Exception?) {
            Log.e(TAG, "Vosk-Fehler", exception)
            haupt.postDelayed({ lauschen() }, 1000)
        }
        override fun onTimeout() {}

        private fun pruefen(json: String?) {
            if (zustand != Zustand.LAUSCHEN || json == null) return
            if (aktivierungswortSicher(json)) aktiviert()
        }
    }

    /**
     * Prüft, ob das Aktivierungswort wirklich und deutlich gesagt wurde:
     * alle Wörter direkt hintereinander, jedes mit hoher Erkennungssicherheit.
     * Läuft gerade Musik (man singt evtl. mit), gilt eine strengere Schwelle.
     */
    private fun aktivierungswortSicher(json: String): Boolean {
        val ziel = einst.aktivierungswort.split(" ").filter { it.isNotBlank() }
        if (ziel.isEmpty()) return false
        val woerter = JSONObject(json).optJSONArray("result") ?: return false
        var schwelle = when (einst.empfindlichkeit) { 0 -> 0.95; 2 -> 0.75; else -> 0.88 }
        if (audio.isMusicActive) schwelle = minOf(0.97, schwelle + 0.05)

        for (start in 0..woerter.length() - ziel.size) {
            var ok = true
            var vorherEnde = -1.0
            for (i in ziel.indices) {
                val w = woerter.getJSONObject(start + i)
                val conf = w.optDouble("conf", 0.0)
                val anfang = w.optDouble("start", 0.0)
                if (w.optString("word") != ziel[i] || conf < schwelle ||
                    (vorherEnde >= 0 && anfang - vorherEnde > 0.6)) { ok = false; break }
                vorherEnde = w.optDouble("end", 0.0)
            }
            if (ok) {
                Log.i(TAG, "Aktivierungswort erkannt: $json")
                return true
            }
        }
        if (woerter.length() > 0) Log.d(TAG, "Verworfen (Schwelle $schwelle): $json")
        return false
    }

    // ---------------- Phase 2: Befehl erkennen ----------------

    private fun aktiviert() {
        voskStoppen()
        zuhoeren { befehlVerarbeiten(it) }
    }

    private var aufAntwort: (List<String>?) -> Unit = {}

    /** Piept, macht die Musik leiser und hört einen Satz lang zu. */
    private fun zuhoeren(callback: (List<String>?) -> Unit) {
        zustand = Zustand.BEFEHL
        aufAntwort = callback
        voskStoppen()
        fokusHolen()       // Musik leiser machen, damit man verstanden wird
        status("Ich höre …")
        piep()
        // kurz warten, damit der Piepton nicht mit aufgenommen wird
        haupt.postDelayed({
            if (zustand != Zustand.BEFEHL) return@postDelayed
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
                antwortErhalten(liste ?: emptyList<String>())
            }
            override fun onError(error: Int) {
                Log.w(TAG, "Google-Erkennung Fehler $error")
                if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_CLIENT) {
                    googleStoppen()
                    offlineBefehl()      // kein Netz → offline weiter
                } else antwortErhalten(null)
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
        val m = modell ?: return antwortErhalten(null)
        try {
            val rec = Recognizer(m, ABTASTRATE).also { voskRec = it }
            voskDienst = SpeechService(rec, ABTASTRATE).also { dienst ->
                dienst.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {}
                    override fun onResult(hypothesis: String?) {
                        val text = hypothesis?.let { JSONObject(it).optString("text") }
                        if (!text.isNullOrBlank()) antwortErhalten(listOf(text))
                    }
                    override fun onFinalResult(hypothesis: String?) {}
                    override fun onError(exception: Exception?) = antwortErhalten(null)
                    override fun onTimeout() = antwortErhalten(null)
                }, 7000)
            }
        } catch (e: Exception) {
            antwortErhalten(null)
        }
    }

    private fun antwortErhalten(vorschlaege: List<String>?) {
        if (zustand != Zustand.BEFEHL) return
        zustand = Zustand.AUSFUEHREN
        voskStoppen()
        googleStoppen()
        fokusAbgeben()
        Log.i(TAG, "Gehört: $vorschlaege")
        aufAntwort(vorschlaege)
    }

    private fun befehlVerarbeiten(vorschlaege: List<String>?) {
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
                if (b.suchbegriff.isBlank()) {
                    abspielen("", b.art, null, app)
                    return
                }
                status("Suche „${b.suchbegriff}“ …")
                val zielApp = app
                Thread {
                    val e = SongSuche.entscheiden(b.suchbegriff, b.art)
                    haupt.post {
                        if (!laeuft) return@post
                        when {
                            e is SuchEntscheidung.Nachfragen && einst.rueckfragen ->
                                nachfragen(e.kandidaten, zielApp)
                            e is SuchEntscheidung.Nachfragen ->
                                abspielen(b.suchbegriff, SuchArt.SONG, e.kandidaten.first(), zielApp)
                            e is SuchEntscheidung.Spielen ->
                                abspielen(e.anfrage, e.art, e.kandidat, zielApp)
                        }
                    }
                }.start()
            }
            is Befehl.Unbekannt -> sagenDannLauschen("Unbekannter Befehl")
        }
    }

    /** "Welchen meinst du? Eins: … Zwei: …" und auf die Antwort hören. */
    private fun nachfragen(kandidaten: List<Kandidat>, app: MusikApp) {
        val namen = listOf("Eins", "Zwei", "Drei")
        val frage = "Welchen meinst du? " + kandidaten.mapIndexed { i, k ->
            "${namen[i]}: ${k.titel} von ${k.kuenstler}."
        }.joinToString(" ")
        sagen(frage, immerSprechen = true) {
            zuhoeren { antwort ->
                val wahl = BefehlsParser.parseAuswahl(antwort.orEmpty(), kandidaten.map { it.titel to it.kuenstler })
                when {
                    wahl == -1 -> sagenDannLauschen("Okay, abgebrochen")
                    wahl != null -> abspielen("", SuchArt.SONG, kandidaten[wahl], app)
                    else -> sagen("Ich nehme den ersten") { abspielen("", SuchArt.SONG, kandidaten[0], app) }
                }
            }
        }
    }

    /** Spielt ab und sagt danach ehrlich an, was wirklich läuft. */
    private fun abspielen(anfrage: String, art: SuchArt, kandidat: Kandidat?, app: MusikApp) {
        zustand = Zustand.AUSFUEHREN
        status("Starte ${app.anzeige} …")
        musik.suchenUndSpielen(anfrage, art, kandidat, app) { erg ->
            when (erg) {
                is AbspielErgebnis.Spielt -> {
                    val ansage = when {
                        erg.titel.isBlank() -> "${app.anzeige} läuft"
                        erg.kuenstler.isBlank() -> "Läuft: ${erg.titel}"
                        else -> "Läuft: ${erg.titel} von ${erg.kuenstler}"
                    }
                    sagenDannLauschen(ansage)
                }
                AbspielErgebnis.NurSuche ->
                    if (!musik.hatSitzungszugriff())
                        sagenDannLauschen("Konnte nicht direkt abspielen, nur die Suche ist offen. Bitte Benachrichtigungszugriff erlauben", immerSprechen = true)
                    else
                        sagenDannLauschen("Konnte nicht direkt abspielen, die Suche ist offen", immerSprechen = true)
                AbspielErgebnis.Fehlgeschlagen ->
                    sagenDannLauschen("${app.anzeige} konnte nicht geöffnet werden", immerSprechen = true)
            }
        }
    }

    // ---------------- Hilfen ----------------

    private fun sagenDannLauschen(text: String, immerSprechen: Boolean = false) =
        sagen(text, immerSprechen) { lauschen() }

    /** Spricht den Text (wenn aktiviert) und ruft danach [weiter] auf dem Hauptthread auf. */
    private fun sagen(text: String, immerSprechen: Boolean = false, weiter: () -> Unit) {
        status(text)
        val t = tts
        if ((!einst.sprachausgabe && !immerSprechen) || !ttsBereit || t == null) {
            haupt.post { weiter() }; return
        }
        val id = System.nanoTime().toString()
        var erledigt = false
        fokusHolen()   // Musik während der Ansage leiser
        val fertig = { if (!erledigt) { erledigt = true; haupt.post { fokusAbgeben(); weiter() } } }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) fertig() }
            @Deprecated("alt") override fun onError(utteranceId: String?) { fertig() }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        haupt.postDelayed({ fertig() }, 4000L + text.length * 90L) // Sicherheitsnetz, je nach Textlänge
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
