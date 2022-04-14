package cricscore

import java.io.File
import java.io.PrintStream

// Features to be added
// match log print/export, load card from match log
// breaks in innings
// stats engine

fun main(args: Array<String>) {
    val infile = if (args.isNotEmpty()) File(args[0]) else null
    val inbuf = if (infile?.isFile == true)
        infile.bufferedReader()
    else
        System.`in`.bufferedReader()

    val outbuf = if (args.size >= 2 && args[1] != "_")
        PrintStream(File(args[1]))
    else null

    val prbuf = outbuf ?: System.out

    val teams = mutableMapOf<String, Team>()
    for (file in args.drop(2)) {
        val team = loadTeam(file) ?: continue
        teams[team.abbr] = team
        prbuf.println("$team loaded")
    }

    while (true) {
        prbuf.print("cricscore> ")
        val line = getLine(inbuf, outbuf) ?: break
        val words = line.split(' ')
        val cmd = words.getOrNull(0) ?: continue
        when (cmd) {
            "help" -> printCommands("top")
            "team" -> {
                val file = words.getOrNull(1) ?: continue
                val team = loadTeam(file) ?: continue
                teams[team.abbr] = team
                prbuf.println("$team loaded")
            }
            "match" -> {
                val teamAbbr1 = words.getOrNull(1) ?: continue
                val team1 = teams[teamAbbr1] ?: continue
                val teamAbbr2 = words.getOrNull(2) ?: continue
                val team2 = teams[teamAbbr2] ?: continue
                val oversPerInnings =
                    words.getOrNull(3)?.toIntOrNull() ?: continue
                val inningsPerSide =
                    words.getOrNull(4)?.toIntOrNull() ?: continue
                if (!(inningsPerSide == 1
                            || oversPerInnings == 0 && inningsPerSide == 2)
                )
                    continue
                val match =
                    Match(Pair(team1, team2), oversPerInnings, inningsPerSide)
                match.run(inbuf, outbuf)
            }
            "quit" -> break
            else -> continue
        }
    }
    inbuf.close()
    outbuf?.close()
}
