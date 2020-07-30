package rat.poison.scripts

import com.badlogic.gdx.math.MathUtils.clamp
import com.sun.jna.Memory
import org.jire.arrowhead.keyPressed
import rat.poison.curSettings
import rat.poison.game.*
import rat.poison.game.CSGO.clientDLL
import rat.poison.game.CSGO.csgoEXE
import rat.poison.game.CSGO.engineDLL
import rat.poison.game.entity.*
import rat.poison.game.forEntities
import rat.poison.game.netvars.NetVarOffsets
import rat.poison.game.netvars.NetVarOffsets.flSimulationTime
import rat.poison.game.offsets.ClientOffsets.dwIndex
import rat.poison.game.offsets.ClientOffsets.dwInput
import rat.poison.game.offsets.EngineOffsets.dwClientState_LastOutgoingCommand
import rat.poison.game.offsets.EngineOffsets.dwGlobalVars
import rat.poison.scripts.aim.calcTarget
import rat.poison.utils.Angle
import rat.poison.utils.Structs.*
import rat.poison.utils.Vector
import rat.poison.utils.every
import rat.poison.utils.extensions.uint
import rat.poison.utils.generalUtil.strToBool
import rat.poison.utils.notInGame
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

var btRecords = Array(64) { Array(13) { BacktrackTable() } }
data class BacktrackTable(var simtime: Float = 0f, var headPos: Angle = Angle(), var absPos: Angle = Angle(), var alpha: Float = 100f)

var bestBacktrackTarget = -1L

fun sendPacket(bool: Boolean) { //move outta here
    val byte = if (bool) 1.toByte() else 0.toByte()
    engineDLL[0xD41DA] = byte //Bitch ass lil coder signature wont work
}

fun setupBacktrack() = every(4) {
    if (notInGame || !curSettings["ENABLE_BACKTRACK"].strToBool() || me <= 0) {
        btRecords = Array(64) { Array(13) { BacktrackTable() } }
        if (engineDLL.byte(0xD41DA) == 0.toByte()) {
            sendPacket(true)
        }
        return@every
    }

    constructRecords()
}

fun attemptBacktrack(): Boolean {
    if (((curSettings["BACKTRACK_SPOTTED"].strToBool() && bestBacktrackTarget.spotted()) || !curSettings["BACKTRACK_SPOTTED"].strToBool()) && bestBacktrackTarget > 0L) {

        //Get/set vars
        val meWep = me.weapon()

        if (!meWep.gun || !me.weaponEntity().canFire()) return false

        val curSequenceNumber = csgoEXE.int(clientState + dwClientState_LastOutgoingCommand) + 1
        sendPacket(false)

        val input = memToInput(csgoEXE.read(clientDLL.address + dwInput, 253)!!)

        val userCMDptr = input.pCommands + (curSequenceNumber % 150) * 0x64
        val verifiedUserCMDptr = input.pVerifiedCommands + (curSequenceNumber % 150) * 0x68
        val oldUserCMDptr = input.pCommands + ((curSequenceNumber - 1) % 150) * 0x64

        while (csgoEXE.int(userCMDptr + 0x4) < curSequenceNumber) {
            Thread.sleep(1)
        }

        //Check invalid?
        val oldUserCMD = memToUserCMD(csgoEXE.read(oldUserCMDptr, 100)!!)
        var userCMD = memToUserCMD(csgoEXE.read(userCMDptr, 100)!!)

        userCMD = fixUserCMD(userCMD, oldUserCMD)

        val bestTime = bestSimTime()

        if (bestTime == -1f) {
            sendPacket(true)
            return false
        }

        userCMD.iButtons = userCMD.iButtons or 1 // << 1 =|= IN_ATTACK
        userCMD.iTickCount = timeToTicks(bestTime)

        userCMDToMem(userCMDptr, userCMD)
        userCMDToMem(verifiedUserCMDptr, userCMD)

        sendPacket(true)
        Thread.sleep(10)
        return true
    }
    return false
}

fun constructRecords() {
    var bestFov = 5F
    val clientAngle = clientState.angle()
    val meTeam = me.team()

    forEntities(EntityType.CCSPlayer) {
        val ent = it.entity

        if (ent.dead() || ent == me || ent.team() == meTeam || ent.dormant()) return@forEntities

        //Best target shit
        val pos = ent.bones(6)
        val fov = calcTarget(bestFov, bestBacktrackTarget, pos, clientAngle, 10F, 6, ovrStatic = true)[0] as Float
        if (fov < bestFov && fov > 0) {
            bestFov = fov
            bestBacktrackTarget = ent
        }

        //Create records
        val entSimTime = csgoEXE.float(ent + flSimulationTime)
        val entID = (csgoEXE.uint(ent + dwIndex) - 1).toInt()
        val tick = getGlobalVars().tickCount % 13

        if (entID in 0..63 && tick < 13) {
            val record = btRecords[entID][tick]

            val boneMemory: Memory by lazy {
                Memory(3984)
            }

            csgoEXE.read(ent.boneMatrix(), boneMemory)
            record.headPos = boneMemory.bones(8).apply {
                z += 5
            }
            record.absPos = ent.absPosition().apply {
                z -= 5
            }

            record.alpha = 100f
            record.simtime = entSimTime

            btRecords[entID][tick] = record
        }

        return@forEntities
    }

    if (bestFov == 5F) {
        bestBacktrackTarget = -1L
    }
}

fun bestSimTime(): Float {
    if (bestBacktrackTarget <= 0L) {
        return -1f
    }

    var best = -1f
    val targetID = (csgoEXE.uint(bestBacktrackTarget + dwIndex)-1).toInt()

    if (targetID < 0) return -1f

    val validRecords = getValidRecords(targetID)
    val minMaxIDX = getRangeRecords(targetID)

    if (minMaxIDX[0] == Int.MAX_VALUE || minMaxIDX[1] == -1) return -1f

    val minRecord = btRecords[targetID][minMaxIDX[0]]
    val maxRecord = btRecords[targetID][minMaxIDX[1]]

    val minHeadPos = Vector(); val maxHeadPos = Vector(); val minAbsPos = Vector(); val maxAbsPos = Vector()

    if (worldToScreen(minRecord.headPos, minHeadPos) && worldToScreen(minRecord.absPos, minAbsPos) && worldToScreen(maxRecord.headPos, maxHeadPos) && worldToScreen(maxRecord.absPos, maxAbsPos)) {
        val w = (minAbsPos.y - minHeadPos.y) / 4F
        val minMidX = (minAbsPos.x + minHeadPos.x) / 2F
        val maxMidX = (maxAbsPos.x + maxAbsPos.x) / 2F

        var sign = -1

        if (minMidX > maxMidX) {
            sign = 1
        }

        //val topLeft = Vector(minHeadPos.x - (w / 1.5F) * sign, minHeadPos.y, minHeadPos.z)
        val topRight = Vector(maxHeadPos.x + (w / 1.5F) * sign, maxHeadPos.y, maxHeadPos.z)

        val bottomLeft = Vector(minMidX - w * sign, minAbsPos.y, minAbsPos.z)
        val bottomRight = Vector(maxMidX + w * sign, maxAbsPos.y, maxAbsPos.z)

        val punch = me.punch()
        val curFov = csgoEXE.int(me + NetVarOffsets.m_iDefaultFov)
        val rccFov1 = atan((CSGO.gameWidth.toFloat()/ CSGO.gameHeight.toFloat()) * 0.75 * tan(Math.toRadians(curFov/2.0)))
        val rccFov2 = (CSGO.gameWidth /2) / tan(rccFov1).toFloat()

        val centerX = (CSGO.gameWidth / 2) - tan(Math.toRadians(punch.y.toDouble())).toFloat() * rccFov2
        val centerY = (CSGO.gameHeight / 2) - tan(Math.toRadians(punch.x.toDouble())).toFloat() * rccFov2

        //Implement proper convex polygon check
        if (inRange(centerX, bottomLeft.x, bottomRight.x) && inRange(centerY, topRight.y, bottomRight.y)) {//If middle of screen + recoil is inside polygon
            var bestMinX = Float.MAX_VALUE

            for (i in validRecords) {
                val record = btRecords[targetID][i]
                val w2s = Vector()
                worldToScreen(record.headPos, w2s)

                val centerDist = abs(centerX - w2s.x)
                if (centerDist < bestMinX) {
                    bestMinX = centerDist

                    best = record.simtime
                }
            }
        }
    }

    return best
}

fun isValidTick(tick: Int): Boolean {
    val gvars = getGlobalVars()
    val delta = gvars.tickCount - tick
    val deltaTime = delta * gvars.intervalPerTick
    val max = clamp(curSettings["BACKTRACK_MS"].toFloat()/1000f, 0F, .19F)

    return abs(deltaTime) <= max
}

fun timeToTicks(time: Float): Int {
    return (.5f + time / getGlobalVars().intervalPerTick).toInt()
}

fun getGlobalVars(): GlobalVars {
    return memToGlobalVars(csgoEXE.read(engineDLL.address + dwGlobalVars, 64)!!)
}

fun inRange(value: Float, num1: Float, num2: Float): Boolean {
    val min: Float
    val max: Float

    if (num1 > num2) {
        max = num1
        min = num2
    } else {
        max = num2
        min = num1
    }

    return value > min && value < max
}