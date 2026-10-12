package de.musikassistent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Einfache Einstellungsseite – komplett ohne XML-Layouts und Zusatzbibliotheken. */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_UMSCHALTEN = "umschalten"
    }

    private lateinit var einst: Einstellungen
    private lateinit var startKnopf: Button
    private lateinit var hinweis: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        einst = Einstellungen(this)

        // Aufruf über die Schnelleinstellungs-Kachel: nur umschalten und schließen
        if (intent?.getBooleanExtra(EXTRA_UMSCHALTEN, false) == true) {
            if (AssistentService.laeuft) AssistentService.stoppen(this)
            else if (allesErlaubt()) AssistentService.starten(this)
            else { aufbauen(); return }
            finish(); return
        }
        aufbauen()
    }

    override fun onResume() {
        super.onResume()
        if (::startKnopf.isInitialized) aktualisieren()
    }

    private fun aufbauen() {
        val dp = resources.displayMetrics.density
        fun abstand(v: View, oben: Int = 12) = v.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (oben * dp).toInt() }
        }
        fun titel(t: String) = abstand(TextView(this).apply { text = t; textSize = 18f }, 24)

        val spalte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
        }

        spalte.addView(TextView(this).apply {
            text = "Musik-Assistent"; textSize = 26f
        })

        startKnopf = abstand(Button(this).apply {
            textSize = 18f
            setOnClickListener { umschalten() }
        }, 16) as Button
        spalte.addView(startKnopf)

        hinweis = abstand(TextView(this)) as TextView
        spalte.addView(hinweis)

        // --- Standard-App ---
        spalte.addView(titel("Standard-App für „spiel …“"))
        val gruppe = RadioGroup(this)
        MusikApp.values().forEach { app ->
            gruppe.addView(RadioButton(this).apply {
                id = app.ordinal + 1
                text = app.anzeige
                isChecked = einst.standardApp == app
            })
        }
        gruppe.setOnCheckedChangeListener { _, id -> einst.standardApp = MusikApp.values()[id - 1] }
        spalte.addView(gruppe)

        // --- Aktivierungswort ---
        spalte.addView(titel("Aktivierungswort"))
        val wortFeld = EditText(this).apply {
            setText(einst.aktivierungswort)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        spalte.addView(wortFeld)
        spalte.addView(TextView(this).apply {
            text = "Nur einfache deutsche Wörter verwenden (z. B. „hallo musik“, „okay radio“, „computer“). " +
                "Zwei Wörter lösen seltener versehentlich aus."
            textSize = 13f
        })
        spalte.addView(abstand(Button(this).apply {
            text = "Wort speichern"
            setOnClickListener {
                einst.aktivierungswort = wortFeld.text.toString()
                neustartWennAktiv()
                Toast.makeText(this@MainActivity, "Gespeichert", Toast.LENGTH_SHORT).show()
            }
        }, 8))

        // --- Optionen ---
        spalte.addView(titel("Optionen"))
        spalte.addView(CheckBox(this).apply {
            text = "Gesprochene Rückmeldung („Spiele …“)"
            isChecked = einst.sprachausgabe
            setOnCheckedChangeListener { _, an -> einst.sprachausgabe = an }
        })
        spalte.addView(CheckBox(this).apply {
            text = "Google-Spracherkennung für Befehle (besser bei Songtiteln, braucht Internet; ohne Netz automatisch offline)"
            isChecked = einst.googleErkennung
            setOnCheckedChangeListener { _, an -> einst.googleErkennung = an }
        })

        // --- Berechtigungen ---
        spalte.addView(titel("Einrichtung"))
        spalte.addView(abstand(Button(this).apply {
            text = "1. Mikrofon & Benachrichtigungen erlauben"
            setOnClickListener { laufzeitRechte() }
        }, 8))
        spalte.addView(abstand(Button(this).apply {
            text = "2. „Über anderen Apps einblenden“ erlauben"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }, 8))
        spalte.addView(abstand(Button(this).apply {
            text = "3. Akku-Optimierung ausschalten"
            setOnClickListener { akkuAusnahme() }
        }, 8))
        spalte.addView(abstand(Button(this).apply {
            text = "4. Benachrichtigungszugriff erlauben (für direktes Abspielen)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }, 8))
        spalte.addView(TextView(this).apply {
            textSize = 13f
            text = "Ist der Schalter bei Schritt 4 ausgegraut („Eingeschränkte Einstellung“): " +
                "Einstellungen → Apps → Musik-Assistent → oben rechts ⋮ → „Eingeschränkte Einstellungen zulassen“, " +
                "danach Schritt 4 nochmal."
        })
        spalte.addView(abstand(Button(this).apply {
            text = "App-Info öffnen"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        }, 4))

        spalte.addView(abstand(TextView(this).apply {
            textSize = 14f
            text = """
                Befehle (nach dem Aktivierungswort und dem Piepton), Deutsch oder Englisch:
                • „weiter“ / „next“ / „skip“ – nächster Song
                • „zurück“ / „previous“ – vorheriger Song
                • „pause“ / „stopp“ – anhalten
                • „play“ / „fortsetzen“ / „resume“ – weiterspielen
                • „lauter“ / „leiser“ / „volume up“ / „volume down“
                • „spiel Bohemian Rhapsody“ – Standard-App
                • „spiel Linkin Park auf Spotify“ / „play Numb on Spotify“
                • „spiel das Album Random Access Memories auf Tidal“
                • „spiel Tidal“ – Tidal starten und weiterspielen
                • „Assistent aus“ / „stop listening“ – beendet das Lauschen

                Tipp: In den Schnelleinstellungen (Statusleiste runterziehen → Stift) gibt es eine
                Kachel „Musik-Assistent“ zum schnellen An- und Ausschalten, z. B. beim Einsteigen ins Auto.
            """.trimIndent()
        }, 24))

        setContentView(ScrollView(this).apply { addView(spalte) })
        aktualisieren()
    }

    private fun aktualisieren() {
        startKnopf.text = if (AssistentService.laeuft) "■  Assistent stoppen" else "▶  Assistent starten"
        val fehlend = mutableListOf<String>()
        if (!hatRecht(Manifest.permission.RECORD_AUDIO)) fehlend += "Mikrofon"
        if (!Settings.canDrawOverlays(this)) fehlend += "Über anderen Apps einblenden"
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) fehlend += "Akku-Optimierung aus (empfohlen)"
        if (!MusikSteuerung(this).hatSitzungszugriff()) fehlend += "Benachrichtigungszugriff (sonst nur Suche statt Abspielen)"
        hinweis.text = if (fehlend.isEmpty()) "Alles eingerichtet ✓"
            else "Noch nötig: " + fehlend.joinToString(", ")
        hinweis.gravity = Gravity.START
    }

    private fun umschalten() {
        if (AssistentService.laeuft) {
            AssistentService.stoppen(this)
        } else {
            if (!hatRecht(Manifest.permission.RECORD_AUDIO)) { laufzeitRechte(); return }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Bitte zuerst Schritt 2 erlauben, sonst kann die App Tidal/Spotify nicht öffnen",
                    Toast.LENGTH_LONG).show()
            }
            AssistentService.starten(this)
        }
        startKnopf.postDelayed({ aktualisieren() }, 300)
    }

    private fun neustartWennAktiv() {
        if (!AssistentService.laeuft) return
        AssistentService.stoppen(this)
        startKnopf.postDelayed({ AssistentService.starten(this) }, 800)
    }

    private fun allesErlaubt() = hatRecht(Manifest.permission.RECORD_AUDIO)

    private fun hatRecht(r: String) = checkSelfPermission(r) == PackageManager.PERMISSION_GRANTED

    private fun laufzeitRechte() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun akkuAusnahme() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        aktualisieren()
    }
}
