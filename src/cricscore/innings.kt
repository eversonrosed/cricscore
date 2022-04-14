package cricscore

import java.io.BufferedReader
import java.io.PrintStream
import java.time.Duration
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.*

class Innings(
    internal val team: Team,
    private val batting: MutableList<Batter>,
    private val bowling: MutableList<Bowler>,
    internal var runs: Int,
    internal var wickets: Int,
    private var extras: EnumMap<ExtraType, Int>,
    private val fow: MutableList<FallOfWicket>,
    private val oversList: MutableList<OverRecord>,
    private val startTime: LocalTime,
    private var endTime: LocalTime?,
) {
    private val maxWickets
        get() = team.roster.size - 1

    private val overs
        get() = numOvers(this.oversList)

    private val notOut
        get() = batting.filter { it.howOut == NotOut }

    /**
     * Index of the striker in the not-out list. Value is always 0 or 1.
     */
    private var strikerIndex = 0

    private val striker
        get() = notOut[strikerIndex]
    private val nonStriker
        get() = notOut[1 - strikerIndex]

    constructor(team: Team) : this(
        team,
        mutableListOf(), // no batters
        mutableListOf(), // no bowlers
        0, // no runs
        0, // no wickets
        EnumMap(
            mapOf(
                ExtraType.NOBALL to 0,
                ExtraType.WIDE to 0,
                ExtraType.BYE to 0,
                ExtraType.LEGBYE to 0
            )
        ), // no extras of any kind
        mutableListOf(), // FoW is empty
        mutableListOf(), // overs list is empty
        LocalTime.now().truncatedTo(ChronoUnit.MINUTES),
        null // end time not yet determined
    )

    fun run(
        matchContext: Match,
        target: Int,
        inbuf: BufferedReader,
        outbuf: PrintStream? = null
    ) {
        val prbuf = outbuf ?: System.out

        // match data
        val battingRoster = team.roster
        val fieldingRoster = if (matchContext.teams.first.name == team.name) {
            matchContext.teams.second.roster
        } else {
            matchContext.teams.first.roster
        }
        val battingTeam = team.name
        val maxOvers = Over(matchContext.oversPerInnings, 0)

        // bail out if input fails
        if (!addOpeners(battingTeam, battingRoster, inbuf, prbuf)) return

        while (wickets < maxWickets
            && overs < maxOvers
            && (target <= 0 || runs <= target)
        ) {
            val prompt = "$battingTeam $runs/$wickets ($overs ov)> "
            prbuf.print(prompt)
            val line = getLine(inbuf, outbuf) ?: break
            val words = line.split(' ')
            val cmd = words.getOrNull(0) ?: continue
            when (cmd) {
                "help" -> printCommands("innings")
                "over" -> {
                    if (oversList.isNotEmpty()
                        && oversList.last().legalBalls < 6
                    ) {
                        prbuf.println("Over in progress")
                        continue
                    }
                    val bname = words.getOrNull(1) ?: continue
                    val bfname = words.getOrNull(2) // optional
                    val bowlerName =
                        searchRoster(fieldingRoster, bname, bfname) ?: continue
                    val bowler = getBowler(bowlerName)
                    val overRecord = OverRecord()
                    oversList.add(overRecord)
                    bowler.oversList.add(overRecord)
                    val inningsOver = runOver(
                        overRecord,
                        bowlerName,
                        inbuf,
                        outbuf,
                        fieldingRoster,
                        bowler,
                        battingTeam,
                        battingRoster,
                        maxOvers
                    )
                    if (inningsOver) return
                }
                "resume" -> {
                    if (oversList.isEmpty()) continue
                    val overRecord = oversList.last()
                    if (overRecord.legalBalls == 6) continue
                    // there is exactly one
                    val bowler =
                        bowling.first { it.oversList.contains(overRecord) }
                    val bowlerName = bowler.name
                    val inningsOver = runOver(
                        overRecord,
                        bowlerName,
                        inbuf,
                        outbuf,
                        fieldingRoster,
                        bowler,
                        battingTeam,
                        battingRoster,
                        maxOvers
                    )
                    if (inningsOver) return
                }
                "declare" -> {
                    if (matchContext.oversPerInnings > 0) continue
                    else break
                }
                else -> continue
            }
        }
        endTime = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        prbuf.println("$battingTeam: $runs/$wickets in $overs overs")
    }

    private fun runOver(
        overRecord: OverRecord,
        bowlerName: Player,
        inbuf: BufferedReader,
        outbuf: PrintStream?,
        fieldingRoster: List<Player>,
        bowler: Bowler,
        battingTeam: String,
        battingRoster: List<Player>,
        maxOvers: Over
    ): Boolean {
        val prbuf = outbuf ?: System.out
        while (overRecord.legalBalls < 6) {
            val bprompt = "${
                Over(overs.overNumber, overs.ballsInOver + 1)
            } ov, $bowlerName to ${striker.player}> "
            prbuf.print(bprompt)
            val line = getLine(inbuf, outbuf) ?: break
            val words = line.split(' ')
            val cmd = words.getOrNull(0) ?: continue
            when (cmd) {
                "hurt" -> {
                    val who = words.getOrNull(1) ?: continue
                    val hurt = searchRoster(
                        notOut.map { it.player },
                        who,
                        words.getOrNull(2)
                    ) ?: continue
                    if (hurt == striker.player) {
                        striker.howOut = RetiredHurt
                    } else {
                        nonStriker.howOut = RetiredHurt
                    }
                    var success = false
                    while (!success) {
                        val batPrompt = "Next batter for $battingTeam: "
                        val batterName = getPlayer(
                            batPrompt,
                            battingRoster,
                            inbuf,
                            outbuf
                        ) ?: return true
                        success = getNewBatter(batterName, overs)
                    }
                }
                "exit" -> break
                "revert" -> if (overRecord.balls.isNotEmpty()) {
                    revertBall(
                        overRecord.balls.removeLast(),
                        overRecord,
                        bowler
                    )
                    prbuf.println("OK")
                }
                else -> {
                    val result = interpretBall(
                        line,
                        bowlerName,
                        fieldingRoster
                    ) ?: continue
                    striker.balls.add(result)
                    overRecord.balls.add(result)
                    handleBall(result, overRecord, bowler)
                    if (result is Wicket && wickets < maxWickets && overs < maxOvers) {
                        var success = false
                        while (!success) {
                            val batPrompt = "Next batter for $battingTeam: "
                            val batterName = getPlayer(
                                batPrompt,
                                battingRoster,
                                inbuf,
                                outbuf
                            ) ?: return true
                            success = getNewBatter(batterName, overs)
                        }
                    }
                    prbuf.println("OK")
                    if (wickets == maxWickets) {
                        return true
                    }
                }
            }
        }
        if (overRecord.legalBalls == 6) strikerIndex = 1 - strikerIndex
        return false
    }

    private fun revertBall(
        lastBall: Ball,
        overRecord: OverRecord,
        bowler: Bowler
    ) {
        when (lastBall) {
            is Extras -> {
                val r = lastBall.runs
                val type = lastBall.type
                val inningsExtras =
                    extras[type]!! // all four were set to zero initially
                runs -= r
                when (type) {
                    ExtraType.NOBALL -> {
                        if (r % 2 == 0) { // accounting for the penalty
                            strikerIndex = 1 - strikerIndex
                        }
                        bowler.noballs--
                        bowler.runs -= r
                        extras[type] = inningsExtras - 1
                        striker.runs -= r - 1
                    }
                    ExtraType.WIDE -> {
                        if (r % 2 == 0) { // accounting for the penalty
                            strikerIndex = 1 - strikerIndex
                        }
                        bowler.wides--
                        bowler.runs -= r
                        extras[type] = inningsExtras - r
                    }
                    ExtraType.BYE, ExtraType.LEGBYE -> {
                        if (r % 2 == 1) {
                            strikerIndex = 1 - strikerIndex
                        }
                        extras[type] = inningsExtras - r
                        overRecord.legalBalls--
                    }
                }
            }
            is Runs -> {
                val r = lastBall.runs
                if (r % 2 == 1) {
                    strikerIndex = 1 - strikerIndex
                }
                runs -= r
                striker.runs -= r
                bowler.runs -= r
                overRecord.legalBalls--
            }
            is Wicket -> {
                val scoring = lastBall.scoring
                revertBall(scoring, overRecord, bowler)
                wickets--
                bowler.wickets--
                val (whoIn, whoStayed) = if (lastBall.newBatOnStrike) {
                    Pair(striker, nonStriker)
                } else {
                    Pair(nonStriker, striker)
                }
                batting.remove(whoIn)
                val fow = fow.removeLast()
                val whoOut = batting.first { it.player == fow.player }
                whoOut.howOut = NotOut
                whoOut.overOut = null
                whoOut.timeOut = null
                // is the batter who was "out" later in the order than the not-out batter
                val outLater =
                    batting.indexOf(whoOut) > batting.indexOf(whoStayed)
                strikerIndex = if (outLater xor lastBall.newBatOnStrike)
                    1
                else
                    0
            }
        }
    }

    private fun handleBall(
        result: Ball,
        overRecord: OverRecord,
        bowler: Bowler
    ) {
        when (result) {
            is Runs -> {
                val r = result.runs
                runs += r
                striker.runs += r
                bowler.runs += r
                overRecord.legalBalls++
                if (r % 2 == 1) {
                    strikerIndex = 1 - strikerIndex
                }
            }
            is Extras -> {
                val r = result.runs
                val type = result.type
                val inningsExtras =
                    extras[type]!! // all four were set to zero initially
                runs += r
                when (type) {
                    ExtraType.NOBALL -> {
                        bowler.noballs++
                        bowler.runs += r
                        extras[type] = inningsExtras + 1
                        striker.runs += r - 1
                        if (r % 2 == 0) { // accounting for the penalty
                            strikerIndex = 1 - strikerIndex
                        }
                    }
                    ExtraType.WIDE -> {
                        bowler.wides++
                        bowler.runs += r
                        extras[type] = inningsExtras + r
                        if (r % 2 == 0) { // accounting for the penalty
                            strikerIndex = 1 - strikerIndex
                        }
                    }
                    ExtraType.BYE, ExtraType.LEGBYE -> {
                        extras[type] = inningsExtras + r
                        overRecord.legalBalls++
                        if (r % 2 == 1) {
                            strikerIndex = 1 - strikerIndex
                        }
                    }
                }
            }
            is Wicket -> {
                val scoring = result.scoring
                handleBall(scoring, overRecord, bowler)
                val dismissal = result.dismissal
                wickets++
                bowler.wickets++
                val whoOut = if (result.strikerOut) striker else nonStriker
                whoOut.howOut = dismissal
                whoOut.overOut = overs
                whoOut.timeOut = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
                fow.add(FallOfWicket(whoOut.player, wickets, runs, overs))
                // batters only cross on runouts, etc. if they fail on 1st, 3rd,
                // etc. run
                strikerIndex = if (result.newBatOnStrike) {
                    1
                } else {
                    0
                }
            }
        }
    }

    private fun duration(): Duration =
        if (endTime != null)
            Duration.between(startTime, endTime)
        else
            Duration.between(
                startTime,
                LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
            )

    /**
     * Gets the named bowler from the bowler list. If the bowler has not bowled
     * yet, adds them to the list and returns a reference to the list item.
     */
    private fun getBowler(bowlerName: Player): Bowler {
        // there is only one
        val bowler = bowling.firstOrNull { it.name == bowlerName }
        if (bowler == null) {
            bowling.add(Bowler(bowlerName))
            return bowling.last()
        }
        return bowler
    }

    /**
     * Gets the named batter from the bowler list, ensuring that the batter has
     * not batted or has retired hurt.
     */
    private fun getNewBatter(name: Player, overIn: Over): Boolean {
        // there is only one
        val batter = batting.firstOrNull { it.player == name }
        if (batter == null) {
            batting.add(
                Batter(
                    name,
                    LocalTime.now().truncatedTo(ChronoUnit.MINUTES),
                    overIn
                )
            )
            return true
        }
        if (batter.howOut is RetiredHurt) {
            batter.howOut = NotOut
            return true
        }
        return false
    }

    /**
     * Adds the openers to the batting list. Returns success value; failure
     * only occurs when input is taken from a file and EOF is reached.
     */
    private fun addOpeners(
        battingTeam: String,
        battingRoster: List<Player>,
        inbuf: BufferedReader,
        outbuf: PrintStream?
    ): Boolean {
        val prompt1 = "First batter for $battingTeam: "
        val bat1 =
            getPlayer(prompt1, battingRoster, inbuf, outbuf) ?: return false
        batting.add(
            Batter(
                bat1,
                LocalTime.now().truncatedTo(ChronoUnit.MINUTES),
                Over(0, 0)
            )
        )
        val prompt2 = "Second batter for $battingTeam: "
        val bat2 =
            getPlayer(prompt2, battingRoster, inbuf, outbuf) ?: return false
        batting.add(
            Batter(
                bat2,
                LocalTime.now().truncatedTo(ChronoUnit.MINUTES),
                Over(0, 0)
            )
        )
        return true
    }

    override fun toString(): String {
        val buf = StringBuilder()
        buf.appendLine("$team innings")
        buf.appendLine()

        buf.appendLine(
            String.format(
                "%-62s%8s%8s%8s%8s%8s%8s",
                "BATTING",
                "Runs",
                "Balls",
                "Minutes",
                "4s",
                "6s",
                "SR"
            )
        )
        for (bat in batting) {
            buf.appendLine(bat)
        }
        buf.appendLine(
            String.format(
                "%-31s%-31s%8d",
                "Extras",
                "(nb ${extras[ExtraType.NOBALL]}, w ${extras[ExtraType.WIDE]}, b ${extras[ExtraType.BYE]}, lb ${extras[ExtraType.LEGBYE]})",
                extras.values.sum()
            )
        )

        val totalScore =
            if (wickets < team.roster.size - 1) "$runs/$wickets" else "$runs"
        val runRate = String.format("%.2f", runs / overs)
        buf.appendLine(
            String.format(
                "%-31s%-31s%8s",
                "TOTAL",
                "($overs ov, RR: $runRate, ${duration().get(ChronoUnit.SECONDS) / 60} Mts)",
                totalScore
            )
        )
        buf.appendLine()

        val batList = batting.map { it.player }
        val didNotBat = team.roster.filter { !batList.contains(it) }
        if (didNotBat.isNotEmpty()) {
            buf.append("Did not bat: ")
            for (dnb in didNotBat) {
                buf.append("$dnb, ")
            }
            buf.deleteRange(buf.length - 2, buf.length)
            buf.appendLine()
        }

        if (fow.isNotEmpty()) {
            buf.append("Fall of wickets: ")
            for (fall in fow) {
                buf.append("$fall, ")
            }
            buf.deleteRange(buf.length - 2, buf.length)
            buf.appendLine()
        }
        buf.appendLine()

        buf.appendLine(
            String.format(
                "%-30s%8s%8s%8s%8s%8s%8s%8s%8s%8s%8s",
                "BOWLING",
                "Overs",
                "Maidens",
                "Runs",
                "Wickets",
                "Economy",
                "0s",
                "4s",
                "6s",
                "W",
                "NB"
            )
        )
        for (bowl in bowling) {
            buf.appendLine(bowl)
        }

        return buf.toString()
    }
}

private operator fun Int.div(overs: Over): Double {
    val decimalOvers =
        overs.overNumber.toDouble() + overs.ballsInOver.toDouble() / 6
    return toDouble() / decimalOvers
}

class Batter(
    val player: Player,
    val timeIn: LocalTime,
    val overIn: Over,
    var timeOut: LocalTime?,
    var overOut: Over?,
    var balls: MutableList<Ball>,
    var runs: Int,
    var howOut: HowOut,
) {
    constructor(name: Player, timeIn: LocalTime, overIn: Over) : this(
        name, timeIn, overIn,
        null, // no time out
        null, // no over out
        mutableListOf(),  // no balls faced
        0, // no runs scored
        NotOut
    )

    private fun duration(): Duration =
        if (timeOut != null)
            Duration.between(timeIn, timeOut)
        else
            Duration.between(
                timeIn,
                LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
            )

    override fun toString(): String {
        val minutes = duration().get(ChronoUnit.SECONDS) / 60
        val fours = balls.count {
            if (it is Runs) {
                it.runs == 4
            } else if (it is Extras) {
                it.type == ExtraType.NOBALL && it.runs == 5
            } else {
                false
            }
        }
        val sixes = balls.count {
            if (it is Runs) {
                it.runs == 6
            } else if (it is Extras) {
                it.type == ExtraType.NOBALL && it.runs == 7
            } else {
                false
            }
        }
        return String.format(
            "%-31s%-31s%8d%8d%8d%8d%8d%8.2f",
            player,
            howOut,
            runs,
            balls.size,
            minutes,
            fours,
            sixes,
            100 * runs.toDouble() / balls.size
        )
    }
}

class Bowler(
    val name: Player,
    var oversList: MutableList<OverRecord>,
    var maidens: Int,
    var runs: Int,
    var wickets: Int,
    var wides: Int,
    var noballs: Int,
) {
    constructor(name: Player) : this(
        name,
        mutableListOf(), // no overs bowled
        0, // no maidens
        0, // no runs
        0, // no wickets
        0, // no wides
        0 // no no-balls
    )

    override fun toString(): String {
        val balls = oversList.flatMap { it.balls }
        val dots = balls.count() {
            it is Runs && it.runs == 0
        }
        val fours = balls.count {
            when (it) {
                is Runs -> {
                    it.runs == 4
                }
                is Extras -> {
                    it.type == ExtraType.NOBALL && it.runs == 5
                }
                else -> {
                    false
                }
            }
        }
        val sixes = balls.count {
            when (it) {
                is Runs -> {
                    it.runs == 6
                }
                is Extras -> {
                    it.type == ExtraType.NOBALL && it.runs == 7
                }
                else -> {
                    false
                }
            }
        }
        return String.format(
            "%-30s%8s%8d%8d%8d%8.2f%8d%8d%8d%8d%8d",
            name,
            numOvers(oversList),
            maidens,
            runs,
            wickets,
            runs / numOvers(oversList),
            dots,
            fours,
            sixes,
            wides,
            noballs
        )
    }
}

class OverRecord(
    var balls: MutableList<Ball>,
    var legalBalls: Int,
) {
    constructor() : this(
        mutableListOf(), // no balls bowled
        0 // no legal balls
    )
}

sealed interface HowOut
object NotOut : HowOut {
    override fun toString(): String = "not out"
}

object RetiredHurt : HowOut {
    override fun toString(): String = "retired hurt"
}

sealed interface Ball
sealed interface ScoringBall : Ball
data class Runs(val runs: Int) : ScoringBall
data class Extras(val type: ExtraType, val runs: Int) : ScoringBall
data class Wicket(
    val dismissal: Dismissal,
    val scoring: ScoringBall = Runs(0), // usually the striker is out with no runs scored
    val strikerOut: Boolean = true,
    val newBatOnStrike: Boolean = true,
) : Ball

sealed interface Dismissal : HowOut
data class Caught(val fielder: Player, val bowler: Player) : Dismissal {
    override fun toString(): String = "c $fielder b $bowler"
}

data class Bowled(val bowler: Player) : Dismissal {
    override fun toString(): String = "b $bowler"
}

data class LBW(val bowler: Player) : Dismissal {
    override fun toString(): String = "lbw b $bowler"
}

object RunOut : Dismissal {
    override fun toString(): String = "run out"
}

data class Stumped(val keeper: Player, val bowler: Player) : Dismissal {
    override fun toString(): String = "st $keeper b $bowler"
}

data class HitWicket(val bowler: Player) : Dismissal {
    override fun toString(): String = "hit wicket b $bowler"
}

object Obstructing : Dismissal {
    override fun toString(): String = "obstructing the field"
}

object RetiredOut : Dismissal {
    override fun toString(): String = "retired out"
}

enum class ExtraType {
    NOBALL, WIDE, BYE, LEGBYE
}

data class FallOfWicket(
    val player: Player,
    val wicket: Int,
    val runs: Int,
    val over: Over,
) {
    override fun toString(): String = "$wicket-$runs ($player, $over ov)"
}

data class Over(
    val overNumber: Int,
    val ballsInOver: Int,
) {
    operator fun plus(rhs: Over): Over {
        val sumBalls = this.ballsInOver + rhs.ballsInOver
        return Over(
            this.overNumber + rhs.overNumber + sumBalls / 6,
            sumBalls % 6
        )
    }

    operator fun compareTo(rhs: Over): Int =
        if (this.overNumber != rhs.overNumber) {
            this.overNumber - rhs.overNumber
        } else {
            this.ballsInOver - rhs.ballsInOver
        }

    override fun toString(): String {
        return "$overNumber.$ballsInOver"
    }
}

data class Player(val lname: String, val fname: String) {
    override fun toString(): String = "$fname $lname"
}
