package de.musikassistent

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/** Ein konkreter Song aus dem Katalog. */
data class Kandidat(val titel: String, val kuenstler: String, val album: String, val rang: Long)

/** Was mit einer Suche passieren soll. */
sealed class SuchEntscheidung {
    /** Eindeutig: direkt abspielen. [kandidat] ist null bei Künstler/Album/Playlist oder ohne Netz. */
    data class Spielen(val anfrage: String, val art: SuchArt, val kandidat: Kandidat?) : SuchEntscheidung()
    /** Mehrdeutig: nachfragen. */
    data class Nachfragen(val kandidaten: List<Kandidat>) : SuchEntscheidung()
}

/**
 * Löst einen gesprochenen Suchbegriff über die freie Deezer-Suche (ohne Konto, ohne Schlüssel)
 * in konkrete Songs auf. Damit weiß der Assistent, ob "Hello" von Adele oder Lionel Richie gemeint ist.
 * Abgespielt wird trotzdem in Tidal/Spotify – Deezer dient nur als Musiklexikon.
 */
object SongSuche {

    private const val TAG = "SongSuche"

    /** Muss im Hintergrund-Thread laufen (Netzwerk). */
    fun entscheiden(begriff: String, art: SuchArt): SuchEntscheidung {
        if (art == SuchArt.ALBUM || art == SuchArt.PLAYLIST || art == SuchArt.KUENSTLER)
            return SuchEntscheidung.Spielen(begriff, art, null)

        val treffer = try { suchen(begriff) } catch (e: Exception) {
            Log.w(TAG, "Deezer nicht erreichbar", e)
            return SuchEntscheidung.Spielen(begriff, art, null)
        }
        return auswerten(begriff, art, treffer)
    }

    /** Reine Entscheidungslogik (ohne Netz, damit testbar). */
    fun auswerten(begriff: String, art: SuchArt, treffer: List<Kandidat>): SuchEntscheidung {
        if (treffer.isEmpty()) return SuchEntscheidung.Spielen(begriff, art, null)

        val q = norm(begriff)
        val qWoerter = q.split(" ").filter { it.isNotEmpty() }.toSet()

        // 1) Künstlername gesagt ("spiel Linkin Park") → Künstler abspielen, nicht nachfragen
        if (art == SuchArt.UNBEKANNT) {
            val kuenstlerTreffer = treffer.count { norm(it.kuenstler) == q }
            if (kuenstlerTreffer >= 2 || norm(treffer.first().kuenstler) == q)
                return SuchEntscheidung.Spielen(begriff, SuchArt.KUENSTLER, null)
        }

        // 2) Titel UND Künstler gesagt ("spiel Numb Linkin Park", "Numb von Linkin Park") → eindeutig
        val beides = treffer.firstOrNull { k ->
            val t = norm(k.titel); val a = norm(k.kuenstler)
            t.isNotEmpty() && a.isNotEmpty() && q.contains(t) &&
                (q.contains(a) || a.split(" ").filter { it.length > 2 }.any { it in qWoerter })
        }
        if (beides != null) return SuchEntscheidung.Spielen(begriff, SuchArt.SONG, beides)

        // 3) Nur Titel gesagt: gibt es mehrere bekannte Songs mit genau diesem Titel?
        val gleicherTitel = treffer.filter { norm(it.titel) == q }
            .distinctBy { norm(it.kuenstler) }
            .sortedByDescending { it.rang }
        if (gleicherTitel.isNotEmpty()) {
            val erster = gleicherTitel.first()
            val ernsthafteKonkurrenz = gleicherTitel.drop(1).filter { it.rang >= erster.rang * 0.35 }
            return if (ernsthafteKonkurrenz.isEmpty())
                SuchEntscheidung.Spielen(begriff, SuchArt.SONG, erster)
            else
                SuchEntscheidung.Nachfragen((listOf(erster) + ernsthafteKonkurrenz).take(3))
        }

        // 4) Nichts passt genau (verhört, Teil des Titels, ...) → die besten Vorschläge anbieten
        val beste = treffer.distinctBy { norm(it.titel) + "|" + norm(it.kuenstler) }.take(3)
        val ersterPasstGut = aehnlich(norm(beste.first().titel), q) >= 0.75
        return if (ersterPasstGut || beste.size == 1)
            SuchEntscheidung.Spielen(begriff, SuchArt.SONG, beste.first())
        else
            SuchEntscheidung.Nachfragen(beste)
    }

    private fun suchen(begriff: String): List<Kandidat> {
        val url = URL("https://api.deezer.com/search/track?limit=15&q=" + URLEncoder.encode(begriff, "UTF-8"))
        val con = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        val text = try { con.inputStream.bufferedReader().readText() } finally { con.disconnect() }
        val daten = JSONObject(text).optJSONArray("data") ?: return emptyList()
        return (0 until daten.length()).map { i ->
            val o = daten.getJSONObject(i)
            Kandidat(
                titel = o.optString("title_short", o.optString("title")),
                kuenstler = o.optJSONObject("artist")?.optString("name").orEmpty(),
                album = o.optJSONObject("album")?.optString("title").orEmpty(),
                rang = o.optLong("rank")
            )
        }
    }

    /** Kleinbuchstaben, ohne Akzente, ohne Klammerzusätze wie "(Remastered 2011)". */
    fun norm(s: String): String {
        val ohneKlammern = s.replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(Regex(" - .*(remaster|live|version|edit|mix).*", RegexOption.IGNORE_CASE), " ")
        return Normalizer.normalize(ohneKlammern.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9ß ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** 0..1 – wie ähnlich zwei Texte sind. */
    fun aehnlich(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var vorher = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, vorher + if (a[i - 1] == b[j - 1]) 0 else 1)
                vorher = tmp
            }
        }
        return 1.0 - d[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
