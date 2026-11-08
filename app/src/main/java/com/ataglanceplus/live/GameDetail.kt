package com.ataglanceplus.live

import org.json.JSONArray
import org.json.JSONObject

/**
 * A game in detail, from ESPN's summary: what each sport has of team stats, players' box score
 * lines, line-ups and formations, the match's events (goals with scorer and assist, cards,
 * substitutions), scoring plays, and each team's leaders. Empty parts are left empty; the
 * dashboard shows only what there is.
 */
data class GameDetail(
    /** Team stats in pairs: the label, then the home and away values ("Possession", "52.6", "47.4"). */
    val teamStats: List<Triple<String, String, String>>,
    /** Box score per team (home first): the column labels and each player's line. */
    val boxScores: List<BoxScore>,
    /** Line-ups per team (home first): formation, starters and substitutes. */
    val lineups: List<Lineup>,
    /** Goals, cards and substitutions, in order. */
    val events: List<Event>,
    /** Scoring plays for sports that list them (American football, hockey, baseball). */
    val scoring: List<Event>,
    /** Each team's leaders (home first): "Points", "J. Tatum", "40", his number. */
    val leaders: List<Pair<String, List<Leader>>>,
    /** Score by period (quarters, halves, innings), home first; empty where the feed has none. */
    val periods: List<List<String>> = emptyList(),
    /** Each team's season record ("8-3"), home first, where the feed gives one. */
    val records: List<String?> = emptyList(),
) {
    data class BoxScore(val team: String, val labels: List<String>, val players: List<PlayerLine>)
    /** One player's line, with his shirt or jersey number and position where the feed gives them. */
    data class PlayerLine(val name: String, val starter: Boolean, val stats: List<String>, val number: String? = null, val position: String? = null)
    data class Leader(val category: String, val name: String, val value: String, val number: String? = null)
    data class Lineup(val team: String, val formation: String?, val starters: List<Player>, val subs: List<Player>)
    data class Player(val name: String, val number: String?, val position: String?, val subbedIn: Boolean, val subbedOut: Boolean)

    /** Something that happened: when, what, who (and who assisted), and for which team. */
    data class Event(val clock: String, val kind: Kind, val text: String, val team: String?, val scoring: Boolean, val number: String? = null)

    enum class Kind { GOAL, YELLOW, RED, SUB, SCORE, OTHER }

    val isEmpty: Boolean get() = teamStats.isEmpty() && boxScores.isEmpty() && lineups.isEmpty() && events.isEmpty() && scoring.isEmpty() && leaders.isEmpty()

    companion object {
        fun parse(root: JSONObject): GameDetail {
            // Home first throughout, so the dashboard can lay both teams out the same way.
            fun homeFirst(array: JSONArray?): List<JSONObject> =
                (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }.sortedBy { if (it.optString("homeAway") == "home") 0 else 1 }
            fun abbr(o: JSONObject?) = o?.optString("abbreviation")?.ifEmpty { null } ?: o?.optString("displayName").orEmpty()

            val box = root.optJSONObject("boxscore")
            val teams = homeFirst(box?.optJSONArray("teams"))
            val teamStats = if (teams.size == 2) {
                val home = teams[0].optJSONArray("statistics") ?: JSONArray()
                val away = teams[1].optJSONArray("statistics") ?: JSONArray()
                val awayByName = (0 until away.length()).associate { away.getJSONObject(it).let { s -> s.optString("name") to s.optString("displayValue") } }
                (0 until home.length()).map { home.getJSONObject(it) }.mapNotNull { s ->
                    val label = s.optString("label").ifEmpty { s.optString("name") }.ifEmpty { return@mapNotNull null }
                    Triple(label.replaceFirstChar { it.uppercase() }, s.optString("displayValue"), awayByName[s.optString("name")].orEmpty())
                }
            } else emptyList()

            // Box score order follows the teams' display order; home first where it is known.
            val homeAbbr = teams.firstOrNull()?.optJSONObject("team")?.let { abbr(it) }
            val playersArray = box?.optJSONArray("players")
            val boxScores = (0 until (playersArray?.length() ?: 0)).map { playersArray!!.getJSONObject(it) }
                .sortedBy { if (abbr(it.optJSONObject("team")) == homeAbbr) 0 else 1 }
                .mapNotNull { team ->
                    val stats = team.optJSONArray("statistics")?.optJSONObject(0) ?: return@mapNotNull null
                    val labels = stats.optJSONArray("labels")?.let { l -> (0 until l.length()).map { l.getString(it) } } ?: return@mapNotNull null
                    val athletes = stats.optJSONArray("athletes") ?: JSONArray()
                    val players = (0 until athletes.length()).map { athletes.getJSONObject(it) }.mapNotNull { a ->
                        val values = a.optJSONArray("stats") ?: return@mapNotNull null
                        if (values.length() == 0) return@mapNotNull null // did not play
                        val athlete = a.optJSONObject("athlete")
                        GameDetail.PlayerLine(
                            athlete?.optString("shortName")?.ifEmpty { null } ?: athlete?.optString("displayName").orEmpty(),
                            a.optBoolean("starter"),
                            (0 until values.length()).map { values.optString(it) },
                            athlete?.optString("jersey")?.ifEmpty { null },
                            athlete?.optJSONObject("position")?.optString("abbreviation")?.ifEmpty { null },
                        )
                    }
                    BoxScore(abbr(team.optJSONObject("team")), labels, players)
                }

            val lineups = homeFirst(root.optJSONArray("rosters")).mapNotNull { r ->
                val roster = r.optJSONArray("roster") ?: return@mapNotNull null
                val players = (0 until roster.length()).map { roster.getJSONObject(it) }
                fun player(p: JSONObject) = Player(
                    p.optJSONObject("athlete")?.optString("displayName").orEmpty(),
                    p.optString("jersey").ifEmpty { null },
                    p.optJSONObject("position")?.optString("abbreviation")?.ifEmpty { null },
                    p.optBoolean("subbedIn"),
                    p.optBoolean("subbedOut"),
                )
                val starters = players.filter { it.optBoolean("starter") }.sortedBy { it.optString("formationPlace").toIntOrNull() ?: 99 }.map(::player)
                val subs = players.filter { !it.optBoolean("starter") }.map(::player)
                if (starters.isEmpty() && subs.isEmpty()) null
                else Lineup(abbr(r.optJSONObject("team")), r.optString("formation").ifEmpty { null }, starters, subs)
            }

            // Shirt numbers by player id, for goals and cards (their entries carry only the id).
            val jerseys = HashMap<String, String>()
            root.optJSONArray("rosters")?.let { rs ->
                for (i in 0 until rs.length()) {
                    val roster = rs.getJSONObject(i).optJSONArray("roster") ?: continue
                    for (j in 0 until roster.length()) {
                        val p = roster.getJSONObject(j)
                        val id = p.optJSONObject("athlete")?.optString("id").orEmpty()
                        val jersey = p.optString("jersey")
                        if (id.isNotEmpty() && jersey.isNotEmpty()) jerseys[id] = jersey
                    }
                }
            }

            val key = root.optJSONArray("keyEvents")
            val events = (0 until (key?.length() ?: 0)).map { key!!.getJSONObject(it) }.mapNotNull { e ->
                val type = e.optJSONObject("type")?.optString("type").orEmpty()
                val kind = when {
                    type.contains("goal") -> Kind.GOAL
                    type.contains("yellow-card") -> Kind.YELLOW
                    type.contains("red-card") -> Kind.RED
                    type.contains("substitution") -> Kind.SUB
                    else -> return@mapNotNull null
                }
                val people = e.optJSONArray("participants")?.let { p -> (0 until p.length()).mapNotNull { p.getJSONObject(it).optJSONObject("athlete")?.optString("displayName") } }.orEmpty()
                val text = when (kind) {
                    Kind.GOAL -> people.firstOrNull()?.let { scorer -> scorer + (people.getOrNull(1)?.let { " (assist $it)" } ?: "") + if (type.contains("own")) " (own goal)" else "" }
                    Kind.SUB -> if (people.size >= 2) "${people[0]} on, ${people[1]} off" else people.firstOrNull()
                    else -> people.firstOrNull()
                } ?: e.optString("shortText")
                val firstId = e.optJSONArray("participants")?.optJSONObject(0)?.optJSONObject("athlete")?.optString("id")
                Event(e.optJSONObject("clock")?.optString("displayValue").orEmpty(), kind, text, e.optJSONObject("team")?.optString("displayName"), kind == Kind.GOAL, firstId?.let { jerseys[it] })
            }

            val plays = root.optJSONArray("scoringPlays")
            val scoring = (0 until (plays?.length() ?: 0)).map { plays!!.getJSONObject(it) }.map { p ->
                val period = p.optJSONObject("period")?.optInt("number")?.takeIf { it > 0 }?.let { "P$it " }.orEmpty()
                Event(period + p.optJSONObject("clock")?.optString("displayValue").orEmpty(), Kind.SCORE, p.optString("text"), abbr(p.optJSONObject("team")), true)
            }

            val leadersArray = root.optJSONArray("leaders")
            val leaders = (0 until (leadersArray?.length() ?: 0)).map { leadersArray!!.getJSONObject(it) }
                .sortedBy { if (abbr(it.optJSONObject("team")) == homeAbbr) 0 else 1 }
                .mapNotNull { t ->
                    val categories = t.optJSONArray("leaders") ?: return@mapNotNull null
                    val rows = (0 until categories.length()).map { categories.getJSONObject(it) }.mapNotNull { c ->
                        val top = c.optJSONArray("leaders")?.optJSONObject(0) ?: return@mapNotNull null
                        val athlete = top.optJSONObject("athlete")
                        Leader(c.optString("displayName"), athlete?.optString("displayName").orEmpty(), top.optString("displayValue"), athlete?.optString("jersey")?.ifEmpty { null })
                    }
                    if (rows.isEmpty()) null else abbr(t.optJSONObject("team")) to rows
                }

            val competitors = homeFirst(root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors"))
            val periods = competitors.map { c ->
                val lines = c.optJSONArray("linescores")
                (0 until (lines?.length() ?: 0)).map { lines!!.getJSONObject(it).let { l -> l.optString("displayValue").ifEmpty { l.optString("value") } } }
            }.takeIf { it.size == 2 && it.all { p -> p.isNotEmpty() } }.orEmpty()
            val records = competitors.map { c -> c.optJSONArray("record")?.optJSONObject(0)?.optString("summary")?.ifEmpty { null } }

            return GameDetail(teamStats, boxScores, lineups, events, scoring, leaders, periods, records)
        }
    }
}
