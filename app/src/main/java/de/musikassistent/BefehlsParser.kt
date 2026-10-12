package de.musikassistent

/** Was der Nutzer gesagt hat, übersetzt in eine Aktion. */
sealed class Befehl {
    object Weiter : Befehl()
    object Zurueck : Befehl()
    object Pause : Befehl()
    object Abspielen : Befehl()
    object Lauter : Befehl()
    object Leiser : Befehl()
    object Stopp : Befehl()            // Assistent beenden
    data class Suchen(val suchbegriff: String, val app: MusikApp?) : Befehl()
    data class Unbekannt(val text: String) : Befehl()
}

enum class MusikApp(val paket: String, val anzeige: String) {
    TIDAL("com.aspiro.tidal", "Tidal"),
    SPOTIFY("com.spotify.music", "Spotify")
}

object BefehlsParser {

    private val weiter = setOf("weiter", "nächster", "nächstes", "nächste", "skip", "überspringen", "next")
    private val zurueck = setOf("zurück", "vorheriger", "vorheriges", "vorherige", "nochmal", "back")
    private val pause = setOf("pause", "stopp", "stop", "anhalten", "halt")
    private val abspielen = setOf("play", "fortsetzen", "abspielen", "weiterspielen", "spiel weiter", "weiter spielen", "musik an")
    private val lauter = setOf("lauter")
    private val leiser = setOf("leiser")
    private val beenden = setOf("assistent aus", "assistent beenden", "hör auf zuzuhören")

    // "spiel(e) <x> [auf tidal|spotify]", "such(e) <x> ...", "hör(en) <x> ..."
    private val suchMuster = Regex(
        "^(?:bitte\\s+)?(?:spiel|spiele|spielt|such|suche|hör|höre|hören|play|leg|lege)\\s+(?:mal\\s+)?(?:den song\\s+|das lied\\s+|das album\\s+|lieder von\\s+|musik von\\s+|was von\\s+|etwas von\\s+)?(.+?)(?:\\s+(?:auf|bei|in|mit|über)\\s+(tidal|spotify|tital|title|tidel|spot if i))?(?:\\s+auf)?$"
    )

    fun parse(roh: String): Befehl {
        val text = roh.lowercase().trim().trimEnd('.', '!', '?')
        if (text.isEmpty()) return Befehl.Unbekannt(roh)

        if (text in beenden) return Befehl.Stopp
        if (text in abspielen) return Befehl.Abspielen
        // Ein- oder Zwei-Wort-Befehle: ein Schlüsselwort reicht
        val woerter = text.split(Regex("\\s+"))
        if (woerter.size <= 3) {
            if (woerter.any { it in weiter } && !text.startsWith("spiel")) return Befehl.Weiter
            if (woerter.any { it in zurueck }) return Befehl.Zurueck
            if (woerter.any { it in pause }) return Befehl.Pause
            if (woerter.any { it in lauter }) return Befehl.Lauter
            if (woerter.any { it in leiser }) return Befehl.Leiser
        }

        suchMuster.find(text)?.let { treffer ->
            val begriff = treffer.groupValues[1].trim()
            val app = when (treffer.groupValues[2]) {
                "" -> null
                "spotify", "spot if i" -> MusikApp.SPOTIFY
                else -> MusikApp.TIDAL
            }
            if (begriff.isNotEmpty() && begriff != "weiter") return Befehl.Suchen(begriff, app)
        }
        return Befehl.Unbekannt(roh)
    }
}
