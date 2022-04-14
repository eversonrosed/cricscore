package cricscore

import java.io.BufferedReader
import java.io.File
import java.io.PrintStream

fun printCommands(mode: String) {
    when (mode) {
        "top" -> {
            println("Top-level mode")
            println("| help - print this list")
            println("| team <file> - load a team from <file>")
            println("| match <team1> <team2> <overs> <innings> - start a match between <team1> and <team2>, with <overs> overs per innings and <innings> innings per side, and enter match mode (both teams must have rosters loaded)")
//            println("| history - list all files accessed from current session")
//            println("| import <filename> - import scorecard from a file and enter match mode")
            println("| quit - exit the program")
        }
        "match" -> {
            println("Match mode")
            println("| help - print this list")
            println("| bat <teamname> - start <teamname>'s batting innings and enter innings mode")
//            println("| resume <breakname> - resume the match from break <breakname> and enter innings mode (if there is no unfinished over) or ball-by-ball mode (if an over is unfinished)")
            println("| delete - delete most recent innings")
            println("| examine - enter examine mode")
            println("| end - exit to top level, all unsaved data is lost")
        }
        "innings" -> {
            println("Innings mode")
            println("| help - print this list")
            println("| over <bowler> - start an over bowled by <bowler> and enter ball-by-ball mode (no effect if an over is unfinished")
//            println("| break <name> - pause the innings, log the break under <name>, and exit to match mode")
            println("| resume - resume an unfinished over and enter ball-by-ball mode (no effect if there is no unfinished over)")
            println("| declare - end the current innings and exit to match mode (only available in unlimited-overs matches)")
        }
        "ball" -> {
            println("Ball-by-ball mode")
            println("| help - print this list")
            println("| <result> - enter <result> for the next delivery (see docs for interpretBall for details")
            println("| revert - erase the most recent ball")
            println("| hurt <batter> - retire <batter> hurt")
            println("| exit - pause the current over and exit to innings mode")
        }
        "examine" -> {
            println("Examine mode")
            println("| help - print this list")
            println("| print - print the scorecard")
            println("| save <filename> - save the scorecard to file <filename> and exit to top level")
//            println("| log - print the match log")
//            println("| export <filename> - save the match log to file <filename> and exit to top level")
            println("| exit - return to match mode")
        }
    }
}

/**
 * Loads a team roster from a file. The players' names must be in
 * `last, first` format. If parsing a line fails, that player is not added to
 * the list, but no other error is raised.
 */
fun loadTeam(fileName: String): Team? {
    val file = File(fileName)
    if (!file.isFile) return null
    val buf = file.bufferedReader()
    var line = getLine(buf)
    val teamName = line ?: return null
    line = getLine(buf)
    val teamAbbr = line ?: return null
    val players = mutableListOf<Player>()
    var captain: Player? = null
    var keeper: Player? = null
    line = getLine(buf) ?: return null
    while (line != null) {
        val first = line.takeWhile { it != ' ' }
        val player = if (first == "(c)") {
            val names = line.dropWhile { it != ' ' }.trim().split(", ")
            if (names.size < 2) continue
            captain = Player(names[0], names[1])
            captain
        } else if (first == "(wk)") {
            val names = line.dropWhile { it != ' ' }.trim().split(", ")
            if (names.size < 2) continue
            keeper = Player(names[0], names[1])
            keeper
        } else {
            val names = line.split(", ")
            if (names.size < 2) continue
            Player(names[0], names[1])
        }
        players.add(player)
        line = getLine(buf)
    }
    if (captain == null || keeper == null) return null
    return Team(teamName, teamAbbr, players, captain, keeper)
}

/**
 * Searches a roster of players by last name. If there are multiple players
 * with the same last name, searches by first name. Returns `null` if no match
 * is found.
 */
fun searchRoster(
    roster: List<Player>,
    lname: String,
    fname: String? = null
): Player? {
    val matches = mutableListOf<Player>()
    for (player in roster) {
        if (player.lname == lname) {
            matches.add(player)
        }
    }
    if (matches.size == 1) return matches[0]
    if (matches.size > 1 && fname != null) { // multiple players with the same last name
        for (player in matches) {
            if (player.fname == fname) {
                return player
            }
        }
    }
    return null
}

/**
 * Interprets a string as the result of a delivery, returning null if parsing fails.
 *
 * Examples:
 *
 * ".": dot ball
 *
 * "4": 4 runs
 *
 * "5w": 5 wides
 *
 * "W b": Wicket - bowled
 *
 * "W c Waugh SR": Wicket - caught SR Waugh
 *
 * "W 1 run-out ns" : Wicket - run out, 1 run scored, non-striker is out
 *
 * "W run-out cross" : Wicket - run out, no run scored, striker is out, batters crossed
 *
 * "W cross c Pant" : Wicket - caught Pant, batters crossed
 */
fun interpretBall(
    ballString: String,
    bowler: Player,
    fieldingTeam: List<Player>
): Ball? {
    // wicket: W $scoring* ((c|st) $fielder|run-out|obstructing|ret|b|lbw|hit-wicket) $striker* $cross*
    // timed out and retired hurt are handled separately
    if (ballString.isEmpty()) return null
    if (ballString.first() == 'W') {
        val words = ballString.split(' ').drop(1) // details of the wicket
        if (words.isEmpty()) return null

        val maybeScoring = interpretScoring(words[0])

        // -1 if not caught or stumped (this works because only one is possible)
        val cStIndex = words.indexOf("c") + words.indexOf("st") + 1
        if (cStIndex > -1) {
            // find the fielder
            val fname = words.getOrNull(cStIndex + 1) ?: return null
            val ffname = words.getOrNull(cStIndex + 2) // first name optional
            val fielder =
                searchRoster(fieldingTeam, fname, ffname) ?: return null
            return when (words[cStIndex]) {
                "c" -> {
                    val crossed = words.getOrNull(cStIndex - 1) == "cross"
                    Wicket(Caught(fielder, bowler), Runs(0), true, crossed)
                }
                "st" -> {
                    val scoring = when (maybeScoring) {
                        Extras(ExtraType.WIDE, 1) -> maybeScoring
                        else -> Runs(0)
                    }
                    Wicket(Stumped(fielder, bowler), scoring)
                }
                else -> null
            }
        } else {
            val index = if (maybeScoring == null) 0 else 1
            val scoring = maybeScoring ?: Runs(0)
            val (strikerOut, newBatOnStrike) =
                when (words.getOrNull(index + 1)) {
                    "ns" -> Pair(false, true)
                    "cross" -> Pair(true, false)
                    "ns,cross", "cross,ns" -> Pair(false, true)
                    else -> Pair(true, false)
                }
            return when (words.getOrNull(index)) {
                "b" -> Wicket(Bowled(bowler))
                "lbw" -> Wicket(LBW(bowler))
                "hit-wicket" -> Wicket(HitWicket(bowler))
                "run-out" -> Wicket(RunOut, scoring, strikerOut, newBatOnStrike)
                "obstructing" -> Wicket(
                    Obstructing,
                    scoring,
                    strikerOut,
                    newBatOnStrike
                )
                "ret" -> Wicket(RetiredOut, scoring, strikerOut, newBatOnStrike)
                else -> null
            }
        }

    }

    return interpretScoring(ballString)
}

private fun interpretScoring(ballString: String): ScoringBall? {
    if (ballString.isEmpty()) return null
    // dot ball: .
    if (ballString == ".") return Runs(0)
    // runs: $num
    ballString.toIntOrNull()?.let { return Runs(it) }

    // extras: $num$type
    val intPart = ballString.replace("[^0-9]".toRegex(), "")
    if (intPart.isNotEmpty()) {
        val runs = intPart.toInt()
        return when (ballString.substring(intPart.length)) {
            "nb" -> Extras(ExtraType.NOBALL, runs)
            "w" -> Extras(ExtraType.WIDE, runs)
            "b" -> Extras(ExtraType.BYE, runs)
            "lb" -> Extras(ExtraType.LEGBYE, runs)
            else -> null
        }
    }

    // parsing failed
    return null
}

/**
 * Gets a line from a buffer and optionally echoes it to another buffer.
 */
fun getLine(
    inbuf: BufferedReader,
    outbuf: PrintStream? = null
): String? {
    val input = inbuf.readLine() ?: return null
    outbuf?.println(input)
    return input
}

/**
 * Gets a player name from a roster by taking input from a buffer. Optionally,
 * output is echoed to another buffer.
 */
fun getPlayer(
    prompt: String,
    roster: List<Player>,
    inbuf: BufferedReader,
    outbuf: PrintStream? = null
): Player? {
    var result: Player? = null
    val prbuf = outbuf ?: System.out
    while (result == null) {
        prbuf?.println(prompt)
        val line = getLine(inbuf, outbuf) ?: return null
        if (line == "end") return null
        val names = line.split(", ")
        if (names.isEmpty()) continue
        result = searchRoster(roster, names[0], names.getOrNull(1))
    }
    return result
}

/**
 * Computes the number of overs in a list of over records.
 */
fun numOvers(oversList: MutableList<OverRecord>): Over =
    if (oversList.isNotEmpty() && oversList.last().legalBalls < 6) {
        Over(oversList.size - 1, oversList.last().legalBalls)
    } else {
        Over(oversList.size, 0)
    }
