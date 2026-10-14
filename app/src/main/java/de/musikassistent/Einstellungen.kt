package de.musikassistent

import android.content.Context

/** Kleine Einstellungs-Hilfe auf Basis von SharedPreferences. */
class Einstellungen(context: Context) {
    private val prefs = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)

    var standardApp: MusikApp
        get() = MusikApp.valueOf(prefs.getString("app", MusikApp.TIDAL.name)!!)
        set(v) = prefs.edit().putString("app", v.name).apply()

    /** Aktivierungswort, muss aus Wörtern bestehen, die das deutsche Offline-Modell kennt. */
    var aktivierungswort: String
        get() = prefs.getString("wort", "hallo musik")!!
        set(v) = prefs.edit().putString("wort", v.lowercase().trim()).apply()

    /** Gesprochene Rückmeldung ("Spiele ...") */
    var sprachausgabe: Boolean
        get() = prefs.getBoolean("tts", true)
        set(v) = prefs.edit().putBoolean("tts", v).apply()

    /** Google-Spracherkennung für Befehle (besser bei Songtiteln) statt nur offline */
    var googleErkennung: Boolean
        get() = prefs.getBoolean("google", true)
        set(v) = prefs.edit().putBoolean("google", v).apply()

    /** Bei mehrdeutigen Songs nachfragen ("Welchen meinst du?") */
    var rueckfragen: Boolean
        get() = prefs.getBoolean("rueckfragen", true)
        set(v) = prefs.edit().putBoolean("rueckfragen", v).apply()

    /** Empfindlichkeit des Aktivierungsworts: 0 = niedrig (kaum Fehlauslösungen), 1 = mittel, 2 = hoch */
    var empfindlichkeit: Int
        get() = prefs.getInt("empfindlichkeit", 1)
        set(v) = prefs.edit().putInt("empfindlichkeit", v).apply()
}
