package cricscore

import java.io.BufferedReader
import java.io.File
import java.io.PrintStream

data class Team(
    val name: String,
    val abbr: String,
    val roster: List<Player>,
    val captain: Player,
    val keeper: Player
) {
    fun matches(str: String): Boolean = name == str || abbr == str


    override fun toString(): String {
        return name
    }
}


sealed interface Result
object Tie : Result {
    override fun toString(): String {
        return "Match tied"
    }
}
object Draw : Result {
    override fun toString(): String {
        return "Match drawn"
    }
}
data class Victory(
    val winner: Team,
    val loser: Team,
    val margin: Int,
    val method: VictoryMethod
) : Result {
    override fun toString(): String {
        return "$winner beat $loser by ${printMargin()}"
    }

    private fun printMargin(): String {
        val suffix = if (margin > 1) "s" else ""
        return when (method) {
            VictoryMethod.RUNS -> "$margin run$suffix"
            VictoryMethod.WICKETS -> "$margin wicket$suffix"
            VictoryMethod.INNINGS -> "an innings and $margin run$suffix"
        }
    }
}

enum class VictoryMethod {
    RUNS, WICKETS, INNINGS
}

class Match(
    internal val teams: Pair<Team, Team>,
    internal val oversPerInnings: Int,
    private val inningsPerSide: Int,
    private val innings: MutableList<Innings>,
) {
    private val complete: Boolean
        get() {
            val firstTeamInnings =
                innings.filter { it.team.name == teams.first.name }
            val secondTeamInnings =
                innings.filter { it.team.name == teams.second.name }
            if (inningsPerSide == 2) {
                if (firstTeamInnings.size == 2 && secondTeamInnings.size == 2) {
                    return true
                }
                if (firstTeamInnings.size == 2) {
                    return secondTeamInnings.sumOf { it.runs } > firstTeamInnings.sumOf { it.runs }
                }
                if (secondTeamInnings.size == 2) {
                    return firstTeamInnings.sumOf { it.runs } > secondTeamInnings.sumOf { it.runs }
                }
                return false
            } else if (inningsPerSide == 1) {
                return firstTeamInnings.size == 1 && secondTeamInnings.size == 1
            } else {
                return false // this should never happen
            }
        }

    constructor(
        teams: Pair<Team, Team>,
        oversPerInnings: Int,
        inningsPerSide: Int
    ) : this(
        teams,
        oversPerInnings,
        inningsPerSide,
        mutableListOf()
    )

    fun run(inbuf: BufferedReader, outbuf: PrintStream? = null) {
        val prbuf = outbuf ?: System.out
        val prompt = "${teams.first.name} v ${teams.second.name}> "
        while (!complete) {
            prbuf.print(prompt)
            val line = getLine(inbuf, outbuf) ?: break
            val words = line.split(' ')
            val cmd = words.getOrNull(0) ?: continue
            when (cmd) {
                "help" -> printCommands("match")
                "bat" -> {
                    if (complete) continue
                    val battingName = line.dropWhile { it != ' ' }.trim()
                    val battingTeam = if (teams.first.matches(battingName)) {
                        teams.first
                    } else if (teams.second.matches(battingName)) {
                        teams.second
                    } else {
                        continue
                    }
                    val teamInnings = Innings(battingTeam)
                    val target = if (innings.size < 2 * inningsPerSide - 1) {
                        0
                    } else {
                        val battingRuns =
                            innings.filter { it.team.name == battingName }
                                .sumOf { it.runs }
                        val bowlingRuns =
                            innings.filter { it.team.name != battingName }
                                .sumOf { it.runs }
                        bowlingRuns - battingRuns
                    }
                    teamInnings.run(this, target, inbuf, outbuf)
                    innings.add(teamInnings)
                }
                "delete" -> if (innings.isNotEmpty()) innings.removeLast()
                "examine" -> examine(inbuf, outbuf)
                "end" -> break
                else -> continue
            }
        }
        prbuf.println("Match is complete, entering examine mode")
        examine(inbuf, outbuf)
    }

    private fun examine(inbuf: BufferedReader, outbuf: PrintStream? = null) {
        val prbuf = outbuf ?: System.out
        val prompt = "Examining ${teams.first.name} v ${teams.second.name}> "
        while (true) {
            prbuf.print(prompt)
            val line = getLine(inbuf, outbuf) ?: break
            val words = line.split(' ')
            val cmd = words.getOrNull(0) ?: continue
            when (cmd) {
                "help" -> printCommands("examine")
                "print" -> prbuf.println(this)
                "save" -> {
                    val fileName = words.getOrNull(1) ?: continue
                    PrintStream(File(fileName)).println(this)
                }
//                "log" -> printLog(prbuf)
//                "export" -> {
//                    val fileName = words.getOrNull(1) ?: continue
//                    printLog(PrintStream(File(fileName)))
//                }
                "exit" -> break
                else -> continue
            }
        }
    }

    private fun matchResult(): Result? {
        if (!complete) return null
        if (inningsPerSide == 1) {
            val firstInningsRuns = innings[0].runs
            val secondInningsRuns = innings[1].runs
            return if (firstInningsRuns > secondInningsRuns) {
                Victory(
                    innings[0].team,
                    innings[1].team,
                    firstInningsRuns - secondInningsRuns,
                    VictoryMethod.RUNS
                )
            } else if (secondInningsRuns > firstInningsRuns) {
                Victory(
                    innings[1].team,
                    innings[0].team,
                    10 - innings[1].wickets,
                    VictoryMethod.WICKETS
                )
            } else {
                Tie
            }
        } else if (inningsPerSide == 2) {
            val firstTeamInnings =
                innings.filter { it.team.name == teams.first.name }
            val secondTeamInnings =
                innings.filter { it.team.name == teams.second.name }
            if (firstTeamInnings.size == 2 && secondTeamInnings.size == 2) {
                val batLastRuns =
                    innings.filter { it.team.name == innings[3].team.name }
                        .sumOf { it.runs }
                val fieldLastRuns =
                    innings.filter { it.team.name != innings[3].team.name }
                        .sumOf { it.runs }
                return if (fieldLastRuns > batLastRuns) {
                    Victory(
                        innings[2].team,
                        innings[3].team,
                        fieldLastRuns - batLastRuns,
                        VictoryMethod.RUNS
                    )
                } else if (batLastRuns > fieldLastRuns) {
                    Victory(
                        innings[3].team,
                        innings[2].team,
                        10 - innings[3].wickets,
                        VictoryMethod.WICKETS
                    )
                } else if (innings[3].wickets == innings[3].team.roster.size - 1) {
                    Tie
                } else {
                    Draw
                }
            }
            if (firstTeamInnings.size == 2) {
                val margin =
                    secondTeamInnings.sumOf { it.runs } - firstTeamInnings.sumOf { it.runs }
                Victory(
                    teams.second,
                    teams.first,
                    margin,
                    VictoryMethod.INNINGS
                )
            }
            if (secondTeamInnings.size == 2) {
                val margin =
                    firstTeamInnings.sumOf { it.runs } - secondTeamInnings.sumOf { it.runs }
                Victory(
                    teams.first,
                    teams.second,
                    margin,
                    VictoryMethod.INNINGS
                )
            }
        }
        return null // should never happen
    }

    override fun toString(): String {
        val buf = StringBuilder()
        buf.appendLine("${teams.first.name} v ${teams.second.name}\n")
        for (inns in innings) {
            buf.append(inns)
            buf.appendLine()
            buf.appendLine()
        }
        buf.appendLine(matchResult())
        return buf.toString()
    }
}
