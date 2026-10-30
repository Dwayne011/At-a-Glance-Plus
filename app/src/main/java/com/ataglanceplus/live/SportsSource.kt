package com.ataglanceplus.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import com.ataglanceplus.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Something the user follows: a team, or a racing series (Formula 1). */
data class Followed(
    val sport: String,
    /** The team's main league (eng.1, nba…), or the series (f1). */
    val league: String,
    /** The team's id, or null for a racing series. */
    val teamId: String?,
    val name: String,
    /** "Premier League", "NBA", "Formula 1". */
    val leagueName: String,
) {
    val key: String get() = if (teamId != null) "team:$sport:$teamId" else "series:$sport:$league"
    val isRacing: Boolean get() = teamId == null

    fun toJson(): JSONObject = JSONObject().put("sport", sport).put("league", league).put("team", teamId ?: JSONObject.NULL)
        .put("name", name).put("leagueName", leagueName)

    companion object {
        fun fromJson(o: JSONObject) = Followed(
            o.getString("sport"), o.getString("league"), o.optString("team").takeIf { !o.isNull("team") && it.isNotEmpty() },
            o.getString("name"), o.optString("leagueName"),
        )
    }
}

/** One side of a game, with the team's colours (ARGB) when the feed gives them. */
data class Side(
    val id: String,
    val name: String,
    val abbr: String,
    val score: String?,
    val logoUrl: String?,
    val winner: Boolean,
    val colour: Int? = null,
    val altColour: Int? = null,
)

/** A team game, from a schedule or, while live, from the game's summary. */
data class Game(
    val eventId: String,
    val sport: String,
    val league: String,
    val leagueName: String,
    val start: Long,
    /** "pre", "in" or "post". */
    val state: String,
    /** The feed's short status: "9:04 - 2nd", "67'", "HT", "FT", or a date before the game. */
    val status: String,
    val home: Side,
    val away: Side,
    val link: String?,
)

/** One session of a race weekend (practice, qualifying, sprint, race). */
data class RaceSession(
    val eventId: String,
    val eventName: String,
    /** "FP1", "Qual", "Race"… */
    val session: String,
    val start: Long,
    val state: String,
    val status: String,
    /** Leading drivers, e.g. "K. Antonelli". */
    val top: List<String>,
    val link: String?,
    /** The circuit's name and city, as ESPN gives them, for its outline. */
    val circuitName: String? = null,
    val circuitCity: String? = null,
)

/**
 * Scores, fixtures and results from ESPN's public site feed: free, no key, worldwide. It is
 * unofficial and could change without warning; failures keep the last data.
 */
object SportsSource {

    private const val TAG = "SportsSource"
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"

    private val _games = MutableStateFlow<Map<String, List<Game>>>(emptyMap())
    /** Recent results and upcoming fixtures by followed key, oldest first. */
    val games: StateFlow<Map<String, List<Game>>> = _games.asStateFlow()

    private val _races = MutableStateFlow<Map<String, List<RaceSession>>>(emptyMap())
    val races: StateFlow<Map<String, List<RaceSession>>> = _races.asStateFlow()

    /** When a game was first seen finished, so "after the game" counts from the real end. */
    private val finishedAt = ConcurrentHashMap<String, Long>()

    fun finishedAt(eventId: String): Long? = finishedAt[eventId]

    // --- Search ---

    /** A team found by name: enough to tell Arsenal (Premier League) from Arsenal (Women's Super League). */
    data class TeamMatch(val followed: Followed)

    fun searchTeams(query: String): List<TeamMatch> {
        if (query.isBlank()) return emptyList()
        val root = getJson("https://site.web.api.espn.com/apis/search/v2?query=" + URLEncoder.encode(query.trim(), "UTF-8") + "&limit=12&type=team")
            ?: return emptyList()
        val results = root.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).flatMap { i ->
            val group = results.getJSONObject(i)
            if (group.optString("type") != "team") return@flatMap emptyList()
            val contents = group.optJSONArray("contents") ?: return@flatMap emptyList()
            (0 until contents.length()).mapNotNull { j ->
                val c = contents.getJSONObject(j)
                val teamId = c.optString("uid").substringAfter("t:", "").ifEmpty { return@mapNotNull null }
                val sport = c.optString("sport").ifEmpty { return@mapNotNull null }
                val league = c.optString("defaultLeagueSlug").ifEmpty { return@mapNotNull null }
                TeamMatch(Followed(sport, league, teamId, c.optString("displayName"), c.optString("subtitle")))
            }
        }
    }

    // --- Refresh ---

    /** Schedules for every followed team and series. A few requests each; call every half hour or so. */
    suspend fun refreshSchedules(context: Context) = withContext(Dispatchers.IO) {
        val followed = Prefs.sportsFollowed(context)
        val games = HashMap<String, List<Game>>()
        val races = HashMap<String, List<RaceSession>>()
        followed.forEach { f ->
            try {
                if (f.isRacing) races[f.key] = raceSessions(f) ?: _races.value[f.key].orEmpty()
                else games[f.key] = teamGames(f) ?: _games.value[f.key].orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "schedule failed for ${f.key}", e)
            }
        }
        _games.value = games
        _races.value = races
        loadLogos(context)
        runCatching { loadColours(context) }.onFailure { Log.w(TAG, "team colours failed", it) }
    }

    /**
     * Live detail for games under way or about to start (from 15 minutes before), from each
     * game's summary. Returns whether anything is live or imminent, so the caller polls again.
     */
    suspend fun refreshLive(context: Context): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var active = false
        _games.value.forEach { (key, list) ->
            list.filter { it.state == "in" || (it.state == "pre" && it.start - now < 15 * 60_000L && now - it.start < 6 * 3_600_000L) }
                .forEach { game ->
                    active = true
                    val fresh = summary(game) ?: return@forEach
                    if (fresh.state == "post") finishedAt.putIfAbsent(fresh.eventId, now)
                    _games.update { all -> all + (key to all[key].orEmpty().map { if (it.eventId == fresh.eventId) fresh else it }) }
                }
        }
        _races.value.forEach { (key, sessions) ->
            val live = sessions.filter { it.state == "in" || (it.state == "pre" && it.start - now < 15 * 60_000L && now - it.start < 4 * 3_600_000L) }
            if (live.isNotEmpty()) {
                active = true
                Prefs.sportsFollowed(context).firstOrNull { it.key == key }?.let { f ->
                    raceSessions(f)?.let { fresh ->
                        fresh.filter { it.state == "post" }.forEach { finishedAt.putIfAbsent(it.eventId + it.session, now) }
                        _races.update { it + (key to fresh) }
                    }
                }
            }
        }
        active
    }

    private fun teamGames(f: Followed): List<Game>? {
        // Football clubs play in several competitions; "all" covers league, cups and Europe.
        val league = if (f.sport == "soccer") "all" else f.league
        val base = "$BASE/${f.sport}/$league/teams/${f.teamId}/schedule"
        val urls = if (f.sport == "soccer") listOf(base, "$base?fixture=true") else listOf(base)
        val all = urls.mapNotNull { getJson(it) }
        if (all.isEmpty()) return null
        return all.flatMap { root ->
            val events = root.optJSONArray("events") ?: JSONArray()
            (0 until events.length()).mapNotNull { gameFrom(events.getJSONObject(it), f) }
        }.distinctBy { it.eventId }.sortedBy { it.start }
    }

    private fun gameFrom(e: JSONObject, f: Followed): Game? {
        val competition = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val status = competition.optJSONObject("status")?.optJSONObject("type") ?: return null
        val competitors = competition.optJSONArray("competitors") ?: return null
        val sides = (0 until competitors.length()).map { competitors.getJSONObject(it) }
        val home = sides.firstOrNull { it.optString("homeAway") == "home" } ?: sides.getOrNull(0) ?: return null
        val away = sides.firstOrNull { it.optString("homeAway") == "away" } ?: sides.getOrNull(1) ?: return null
        val leagueObj = e.optJSONObject("league")
        return Game(
            eventId = e.optString("id"),
            sport = f.sport,
            league = leagueObj?.optString("slug")?.ifEmpty { null } ?: f.league,
            leagueName = leagueObj?.optString("name")?.ifEmpty { null } ?: f.leagueName,
            start = parseTime(e.optString("date")) ?: return null,
            state = status.optString("state"),
            status = status.optString("shortDetail"),
            home = side(home),
            away = side(away),
            link = e.optJSONArray("links")?.optJSONObject(0)?.optString("href")?.ifEmpty { null },
        )
    }

    private fun side(o: JSONObject): Side {
        val team = o.optJSONObject("team") ?: JSONObject()
        // Scores are a plain string in summaries and an object in schedules.
        val score = when (val s = o.opt("score")) {
            is JSONObject -> s.optString("displayValue").ifEmpty { null }
            is String -> s.ifEmpty { null }
            else -> null
        }
        // The logo made for dark backgrounds, where there is one: it sits on a dark team circle.
        val logos = team.optJSONArray("logos")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        fun rel(l: JSONObject) = l.optJSONArray("rel")?.let { r -> (0 until r.length()).map { r.optString(it) } }.orEmpty()
        val logo = (logos.firstOrNull { rel(it).let { r -> "dark" in r && r.size == 2 } } ?: logos.firstOrNull())
            ?.optString("href")?.ifEmpty { null }
            ?: team.optString("logo").ifEmpty { null }
        return Side(
            id = team.optString("id"),
            name = team.optString("shortDisplayName").ifEmpty { team.optString("displayName") },
            abbr = team.optString("abbreviation").ifEmpty { team.optString("shortDisplayName").take(3).uppercase() },
            score = score,
            logoUrl = logo,
            winner = o.optBoolean("winner"),
            colour = colour(team.optString("color")),
            altColour = colour(team.optString("alternateColor")),
        )
    }

    private fun colour(hex: String): Int? = hex.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }

    // --- Team colours ---

    /** Colours by "sport/league/team id": the main and the alternate, from the team's own page. */
    private val colours = ConcurrentHashMap<String, Pair<Int?, Int?>>()

    /** [side]'s colours in [game]: from the feed when it gave them, else looked up earlier; null until known. */
    fun colours(game: Game, side: Side): Pair<Int?, Int?>? =
        if (side.colour != null) side.colour to side.altColour else colours["${game.sport}/${game.league}/${side.id}"]

    /**
     * Fetches the colours of teams the schedules did not give (they name only the followed team's),
     * once per team, kept in settings.
     */
    private fun loadColours(context: Context) {
        val prefs = Prefs.get(context)
        if (colours.isEmpty()) {
            runCatching { JSONObject(prefs.getString(KEY_COLOURS, "{}")!!) }.getOrNull()?.let { saved ->
                saved.keys().forEach { key ->
                    val pair = saved.getJSONArray(key)
                    colours[key] = (if (pair.isNull(0)) null else pair.getInt(0)) to (if (pair.isNull(1)) null else pair.getInt(1))
                }
            }
        }
        var changed = false
        _games.value.values.flatten().flatMap { game -> listOf(game.home, game.away).map { game to it } }
            .filter { (game, side) -> side.colour == null && side.id.isNotEmpty() && !colours.containsKey("${game.sport}/${game.league}/${side.id}") }
            .distinctBy { (game, side) -> "${game.sport}/${game.league}/${side.id}" }
            .forEach { (game, side) ->
                val team = getJson("$BASE/${game.sport}/${game.league}/teams/${side.id}")?.optJSONObject("team") ?: return@forEach
                colours["${game.sport}/${game.league}/${side.id}"] = colour(team.optString("color")) to colour(team.optString("alternateColor"))
                changed = true
            }
        if (changed) {
            val saved = JSONObject()
            colours.forEach { (key, pair) -> saved.put(key, JSONArray().put(pair.first ?: JSONObject.NULL).put(pair.second ?: JSONObject.NULL)) }
            prefs.edit().putString(KEY_COLOURS, saved.toString()).apply()
        }
    }

    private const val KEY_COLOURS = "sports_team_colours"

    /** [game] in detail (box score, line-ups, events), for the dashboard; null if the feed did not answer. */
    fun detail(game: Game): GameDetail? =
        getJson("$BASE/${game.sport}/${game.league}/summary?event=${game.eventId}")?.let { runCatching { GameDetail.parse(it) }.getOrNull() }

    /** The followed keys whose fixtures include [eventId] (two, when two followed teams meet). */
    fun keysFor(eventId: String): Set<String> =
        _games.value.filterValues { games -> games.any { it.eventId == eventId } }.keys +
            _races.value.filterValues { sessions -> sessions.any { it.eventId == eventId } }.keys

    /** Whether the user hides scores for any followed team or series in [eventId]. */
    fun scoresHidden(context: Context, eventId: String): Boolean =
        keysFor(eventId).any { it in Prefs.sportsHidden(context) }

    private fun summary(game: Game): Game? {
        val root = getJson("$BASE/${game.sport}/${game.league}/summary?event=${game.eventId}") ?: return null
        val competition = root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val status = competition.optJSONObject("status")?.optJSONObject("type") ?: return null
        val competitors = competition.optJSONArray("competitors") ?: return null
        val sides = (0 until competitors.length()).map { competitors.getJSONObject(it) }
        val home = sides.firstOrNull { it.optString("homeAway") == "home" } ?: return null
        val away = sides.firstOrNull { it.optString("homeAway") == "away" } ?: return null
        return game.copy(
            state = status.optString("state").ifEmpty { game.state },
            status = status.optString("shortDetail").ifEmpty { game.status },
            home = side(home).let { it.copy(logoUrl = it.logoUrl ?: game.home.logoUrl) },
            away = side(away).let { it.copy(logoUrl = it.logoUrl ?: game.away.logoUrl) },
        )
    }

    /** The race weekend under way or next, from the series calendar. */
    private fun raceSessions(f: Followed): List<RaceSession>? {
        val board = getJson("$BASE/${f.sport}/${f.league}/scoreboard") ?: return null
        val calendar = board.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar") ?: JSONArray()
        val now = System.currentTimeMillis()
        // The current weekend if it has not ended (plus a day for results), else the next one.
        val entry = (0 until calendar.length()).mapNotNull { calendar.optJSONObject(it) }
            .firstOrNull { (parseTime(it.optString("endDate")) ?: 0L) + 24 * 3_600_000L > now }
        val root = entry?.let { e ->
            val day = e.optString("startDate").take(10).replace("-", "")
            getJson("$BASE/${f.sport}/${f.league}/scoreboard?dates=$day")
        } ?: board
        val event = root.optJSONArray("events")?.optJSONObject(0) ?: return emptyList()
        val name = event.optString("shortName").ifEmpty { event.optString("name") }
        val link = event.optJSONArray("links")?.optJSONObject(0)?.optString("href")
        val circuit = event.optJSONObject("circuit")
        val circuitName = circuit?.optString("fullName")?.ifEmpty { null }
        val circuitCity = circuit?.optJSONObject("address")?.optString("city")?.ifEmpty { null }
        val competitions = event.optJSONArray("competitions") ?: return emptyList()
        return (0 until competitions.length()).mapNotNull { i ->
            val c = competitions.getJSONObject(i)
            val type = c.optJSONObject("status")?.optJSONObject("type") ?: return@mapNotNull null
            val drivers = c.optJSONArray("competitors") ?: JSONArray()
            val top = (0 until drivers.length()).map { drivers.getJSONObject(it) }
                .sortedBy { it.optInt("order", 99) }
                .take(3)
                .mapNotNull { it.optJSONObject("athlete")?.optString("shortName")?.ifEmpty { null } }
            RaceSession(
                eventId = event.optString("id"),
                eventName = name,
                session = c.optJSONObject("type")?.optString("abbreviation")?.ifEmpty { null } ?: "Session",
                start = parseTime(c.optString("date")) ?: return@mapNotNull null,
                state = type.optString("state"),
                status = type.optString("shortDetail"),
                top = top,
                link = link,
                circuitName = circuitName,
                circuitCity = circuitCity,
            )
        }.sortedBy { it.start }
    }

    // --- Logos ---

    private val logos = ConcurrentHashMap<String, Bitmap>()

    /** A team's logo, if already downloaded (cards are drawn without waiting on the network). */
    fun logo(url: String?): Bitmap? = url?.let { logos[it] }

    private fun loadLogos(context: Context) {
        val dir = File(context.cacheDir, "logos").apply { mkdirs() }
        val urls = _games.value.values.flatten().flatMap { listOf(it.home.logoUrl, it.away.logoUrl) }.filterNotNull().distinct()
        urls.filter { !logos.containsKey(it) }.forEach { url ->
            val file = File(dir, url.hashCode().toUInt().toString() + ".png")
            val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.path) else runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
                try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()?.let { full ->
                // Cards carry their images to the launcher on every update: keep them small.
                val small = if (full.width > 96) full.scale(96, 96 * full.height / full.width) else full
                runCatching { file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                small
            }
            if (bitmap != null) logos[url] = bitmap
        }
    }

    // --- Helpers ---

    private fun parseTime(text: String): Long? = runCatching {
        // The feed writes "2026-11-01T16:30Z" (no seconds).
        Instant.parse(if (text.length == 17) text.dropLast(1) + ":00Z" else text).toEpochMilli()
    }.getOrNull()

    private fun getJson(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AtAGlancePlus")
        }
        return try {
            if (connection.responseCode != 200) null
            else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "request failed: $url", e)
            null
        } finally {
            connection.disconnect()
        }
    }
}
