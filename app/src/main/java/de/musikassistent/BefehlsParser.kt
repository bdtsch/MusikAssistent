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

/**
 * Versteht deutsche und englische Befehle.
 * App-Namen werden unscharf erkannt, weil die Spracherkennung "Tidal"
 * gern als "Teidel", "Tidel", "Title", "Tai Dahl" usw. schreibt.
 */
object BefehlsParser {

    // ---------- Steuerbefehle (DE + EN) ----------
    private val weiter = setOf("weiter", "nächster", "nächstes", "nächste", "skip", "überspringen",
        "next", "skippen", "vor")
    private val zurueck = setOf("zurück", "vorheriger", "vorheriges", "vorherige", "nochmal",
        "back", "previous", "rewind", "again")
    private val pause = setOf("pause", "pausieren", "stopp", "stop", "anhalten", "halt", "aus")
    private val abspielen = setOf("play", "fortsetzen", "abspielen", "weiterspielen", "spiel weiter",
        "weiter spielen", "musik an", "resume", "continue", "play music", "start", "los")
    private val lauter = setOf("lauter", "louder", "volume up", "lauter machen", "mach lauter", "turn it up")
    private val leiser = setOf("leiser", "quieter", "volume down", "leiser machen", "mach leiser", "turn it down")
    private val beenden = setOf("assistent aus", "assistent beenden", "hör auf zuzuhören",
        "stop listening", "assistant off")

    // ---------- Wörter, mit denen eine Suche beginnt ----------
    private val startWoerter = listOf(
        "spiel", "spiele", "spielt", "spiel mal", "spiele mal", "such", "suche", "such mal",
        "hör", "höre", "hören", "leg", "lege", "leg mal", "starte", "mach",
        "play", "play me", "put on", "search", "search for", "find", "listen to", "start"
    ).sortedByDescending { it.length }

    // Füllwörter direkt nach dem Startwort
    private val fuellwoerter = listOf(
        "den song", "das lied", "das album", "die playlist", "lieder von", "musik von", "songs von",
        "was von", "etwas von", "irgendwas von", "den titel", "mir",
        "the song", "the album", "the playlist", "songs by", "music by", "something by", "some", "song", "album"
    ).sortedByDescending { it.length }

    private val praepositionen = setOf("auf", "bei", "in", "mit", "über", "via", "on", "with", "using", "from", "an")

    // Bekannte Fehlerkennungen
    private val tidalVarianten = setOf(
        "tidal", "teidel", "teidl", "taidl", "taidel", "taidal", "teidal", "tidel", "tidl", "tidle",
        "tydal", "tydel", "title", "titel", "tital", "tidall", "tieda", "theidel", "theydal", "tidals",
        "tai dahl", "tai dal", "tei del", "tai del", "die dell", "tie dal", "tight al", "tidal music"
    )
    private val spotifyVarianten = setOf(
        "spotify", "spotifei", "spotifai", "spotifi", "spotty", "spotty fly", "spot if i", "spot ify",
        "spotti", "spotifly", "spotfy", "sportify", "spotify music"
    )

    fun parse(roh: String): Befehl = parseAlle(listOf(roh))

    /** Mehrere Erkennungsvorschläge: nimmt den ersten, der einen sinnvollen Befehl ergibt. */
    fun parseAlle(vorschlaege: List<String>): Befehl {
        val ergebnisse = vorschlaege.filter { it.isNotBlank() }.map { parseEinzeln(it) }
        if (ergebnisse.isEmpty()) return Befehl.Unbekannt("")
        // Suchbefehle mit erkannter App bevorzugen (falls nur ein Vorschlag "Tidal" enthält)
        val ersterTreffer = ergebnisse.firstOrNull { it !is Befehl.Unbekannt } ?: return ergebnisse.first()
        if (ersterTreffer is Befehl.Suchen && ersterTreffer.app == null) {
            val mitApp = ergebnisse.filterIsInstance<Befehl.Suchen>().firstOrNull { it.app != null }
            if (mitApp != null) return ersterTreffer.copy(app = mitApp.app)
        }
        return ersterTreffer
    }

    private fun normalisieren(s: String) = s.lowercase()
        .replace(Regex("[.,!?;:„“\"']"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseEinzeln(roh: String): Befehl {
        var text = normalisieren(roh)
        if (text.isEmpty()) return Befehl.Unbekannt(roh)
        text = text.removePrefix("bitte ").removeSuffix(" bitte").removePrefix("please ").trim()

        if (text in beenden) return Befehl.Stopp
        if (text in abspielen) return Befehl.Abspielen
        if (text in lauter) return Befehl.Lauter
        if (text in leiser) return Befehl.Leiser

        val woerter = text.split(" ")
        val istSuche = startWoerter.any { text == it || text.startsWith("$it ") }

        // Kurze Befehle: ein Schlüsselwort reicht
        if (woerter.size <= 3 && !(istSuche && woerter.size >= 2 && woerter[0] != "next")) {
            if (text.contains("next song") || text.contains("nächstes lied")) return Befehl.Weiter
            if (woerter.any { it in weiter }) return Befehl.Weiter
            if (woerter.any { it in zurueck }) return Befehl.Zurueck
            if (woerter.any { it in pause }) return Befehl.Pause
            if (woerter.any { it in lauter }) return Befehl.Lauter
            if (woerter.any { it in leiser }) return Befehl.Leiser
        }
        if (text == "next song" || text == "skip song" || text == "skip this song") return Befehl.Weiter
        if (text == "previous song" || text == "last song" || text == "vorheriges lied") return Befehl.Zurueck

        if (!istSuche) {
            // Auch "Linkin Park auf Tidal" ohne "spiel" erlauben
            val (rest, app) = appAbtrennen(text)
            if (app != null && rest.isNotBlank()) return Befehl.Suchen(rest, app)
            return Befehl.Unbekannt(roh)
        }

        // Startwort entfernen
        val start = startWoerter.first { text == it || text.startsWith("$it ") }
        var rest = text.removePrefix(start).trim()
        rest = rest.removePrefix("mal ").trim()

        // App heraustrennen (am Ende: "... auf tidal", oder am Anfang: "auf tidal ...")
        val (ohneApp, app) = appAbtrennen(rest)
        rest = ohneApp

        // Füllwörter vorne entfernen
        for (f in fuellwoerter) {
            if (rest.startsWith("$f ")) { rest = rest.removePrefix(f).trim(); break }
        }
        rest = rest.trim()

        if (rest.isEmpty()) {
            // "spiel tidal" → einfach App fortsetzen
            return if (app != null) Befehl.Suchen("", app) else Befehl.Abspielen
        }
        if (rest == "weiter" || rest == "on") return Befehl.Abspielen
        return Befehl.Suchen(rest, app)
    }

    /** Trennt "<suche> auf <app>" bzw. "auf <app> <suche>" auf. */
    private fun appAbtrennen(text: String): Pair<String, MusikApp?> {
        val w = text.split(" ").toMutableList()
        if (w.isEmpty()) return text to null

        // Am Ende: bis zu 3 Wörter als App-Namen probieren, davor eine Präposition
        for (laenge in 3 downTo 1) {
            if (w.size < laenge) continue
            val kandidat = w.takeLast(laenge).joinToString(" ")
            val app = appErkennen(kandidat) ?: continue
            var vorne = w.dropLast(laenge)
            if (vorne.isNotEmpty() && vorne.last() in praepositionen) vorne = vorne.dropLast(1)
            else if (laenge == 1 && vorne.isNotEmpty() && kandidat !in tidalVarianten && kandidat !in spotifyVarianten) {
                // ohne Präposition nur bei eindeutigen Namen trennen
                continue
            }
            return vorne.joinToString(" ") to app
        }
        // Am Anfang: "auf tidal linkin park"
        if (w.size >= 2 && w[0] in praepositionen) {
            for (laenge in 3 downTo 1) {
                if (w.size < laenge + 1) continue
                val kandidat = w.subList(1, 1 + laenge).joinToString(" ")
                val app = appErkennen(kandidat) ?: continue
                return w.drop(1 + laenge).joinToString(" ") to app
            }
        }
        return text to null
    }

    private fun appErkennen(s: String): MusikApp? {
        val k = s.trim()
        if (k in tidalVarianten) return MusikApp.TIDAL
        if (k in spotifyVarianten) return MusikApp.SPOTIFY
        val kompakt = k.replace(" ", "")
        if (kompakt.length in 4..8 && (kompakt.startsWith("t") || kompakt.startsWith("d")) &&
            (abstand(kompakt, "tidal") <= 2 || abstand(kompakt, "teidel") <= 2)) return MusikApp.TIDAL
        if (kompakt.length in 5..10 && kompakt.startsWith("sp") && abstand(kompakt, "spotify") <= 2) return MusikApp.SPOTIFY
        return null
    }

    /** Levenshtein-Abstand für die unscharfe Erkennung. */
    private fun abstand(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var vorher = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, vorher + if (a[i - 1] == b[j - 1]) 0 else 1)
                vorher = tmp
            }
        }
        return d[b.length]
    }
}
