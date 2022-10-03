package com.lambda.modules

import com.lambda.MusicToolsPlugin
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.RenderOverlayEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.event.listener.asyncListener
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.CircularArray
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.graphics.RenderUtils2D
import com.lambda.client.util.math.RotationUtils
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.world.getMiningSide
import com.lambda.modules.util.NoteBlockInformation
import com.lambda.modules.util.NoteBlockState
import net.minecraft.block.BlockNote
import net.minecraft.block.state.IBlockState
import net.minecraft.init.Blocks
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.network.play.server.SPacketBlockAction
import net.minecraft.network.play.server.SPacketBlockChange
import net.minecraft.network.play.server.SPacketTimeUpdate
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.Rotation
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.world.NoteBlockEvent
import net.minecraftforge.event.world.WorldEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap
import kotlin.concurrent.schedule
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Automates the transfer of note block songs and music machines to a server.
 * @author GitHub: minecraft-simon, Mail: minecraft_simon@gmx.net, Discord: Simon#4530, MC-IGN: minecraft_simon
 */
object NoteBlockTransfer : PluginModule(
    name = "NoteBlockTransfer",
    category = Category.MISC,
    description = "Automates the transfer of note block songs and music machines to a server",
    alwaysListening = true, // this is needed because the module needs to reset its state on WorldEvent.Load
    pluginMain = MusicToolsPlugin
) {

    private val mode = setting("Mode", Mode.RECORD)

    private val startRecording = setting("Start Recording", false, { mode.value == Mode.RECORD }, description = "Record all note blocks being played")
    private val startRecreating = setting("Start Recreating", false, { mode.value == Mode.RECREATE }, description = "Transfer recorded note blocks to un-tuned note blocks")
    private val rotation = setting("Rotate", 0, 0..270, 90, { mode.value == Mode.RECREATE }, unit = "°", description = "Rotate recording around the alignment point")
    private val flip = setting("Flip", false, { mode.value == Mode.RECREATE }, description = "Flip the recording horizontally")
    private val enableAutoTuner = setting("Enable Auto-Tuner", false, { mode.value == Mode.RECREATE }, description = "Automatically tune all note blocks that are close to you")
    private val tuningSpeed = setting("Tuning Speed", 100, 20..500, 10, { mode.value == Mode.RECREATE }, unit = "%", description = "Set the speed of the Auto-Tuner (you can go above 100% but higher values are more likely to get you kicked from a server)")
    private val printStatus = setting("Print Status Updates", true, description = "Show status messages in chat (only client side)")
    private val showESP = setting("Show ESP", true, description = "Draw colored boxes around note blocks to indicate their status")

    // cancelled settings
    // auto rotate and reach through walls both work on 2b2t without issues, so the option of turning them off does not need to be implemented for now
    //private val autoTunerSettings = setting("Auto Tuner Settings", false, { mode.value == Mode.RECREATE }, description = "Modify parameters of the auto-tuner")
    //private val autoRotate = setting(" Auto Rotate", true, { mode.value == Mode.RECREATE && autoTunerSettings.value }, description = "Send player rotation packets to automatically look at note blocks")
    //private val reachThroughWalls = setting(" Reach Through Walls", true, { mode.value == Mode.RECREATE && autoTunerSettings.value && autoRotate.value }, description = "Tune note blocks even if they are hidden behind other blocks")

    private var alignmentPos = BlockPos(0, 0, 0)
    private var alignmentPosSet = false

    private val recordedNoteBlocks = ConcurrentHashMap<BlockPos, NoteBlockInformation>() // used during recording, BlockPos is relative to alignmentPos
    private val previouslyRecordedNoteBlocks = ConcurrentHashMap<BlockPos, NoteBlockInformation>() // allows restoring a previous recording
    private val template = ConcurrentHashMap<BlockPos, NoteBlockInformation>() // contains the (rotated and flipped) recording, BlockPos is absolute in world
    private val cachedNoteBlocks = ConcurrentHashMap<BlockPos, NoteBlockInformation>() // will contain all note blocks played during recreation, BlockPos is absolute
    private val clicksToSend = ConcurrentHashMap<BlockPos, Int>() // how many right clicks do note blocks need to be tuned, BlockPos is absolute
    private var world: World? = null
    private var serverIP = ""

    private val lightESP = ESPRenderer() // used for recorded or matching note blocks
    private val boldESP = ESPRenderer() // used for incorrect note blocks, is more visible than lightESP
    private val alignmentEspColor = ColorHolder(0, 234, 255)

    // fields relating to auto-tuner timing
    private var tickCycle = 0 // used for CPS-calculation, cycles from 0 to 19
    private var lowTpsWarning = false // used to show a warning in chat if the tps is low
    private var serverNotRespondingWarning = false // used to show a warning in chat if the server is not responding

    // fields relating to server tps synchronization
    private var recentTpsValues = CircularArray<Double>(3) // used to find the lowest recent tps value
    private var lastServerTimeUpdateMillis = System.currentTimeMillis()
    private var lastClickSentMillis = System.currentTimeMillis()
    private var lastServerResponseMillis = System.currentTimeMillis() // is updated on time update or note played

    private var printStatusDelayActive = false
    private var printStatusAgain = false

    private var time = System.currentTimeMillis()

    private enum class Mode {
        RECORD, RECREATE
    }

    init {
        startTimer()

        onDisable {
            startRecording.value = false
            startRecreating.value = false
        }

        mode.listeners.add {
            enableAutoTuner.value = false
        }

        startRecording.listeners.add {
            alignmentPosSet = false

            // cancel if world is not loaded
            if (startRecording.value && !checkWorld()) {
                startRecording.value = false
                return@add
            }

            // automatically enables module if it is disabled
            if (startRecording.value && !isEnabled) {
                enable()
            }

            if (startRecording.value) {
                startRecreating.value = false
                // save previous recording in case it needs to be restored
                if (recordedNoteBlocks.isNotEmpty()) {
                    previouslyRecordedNoteBlocks.clear()
                    previouslyRecordedNoteBlocks.putAll(recordedNoteBlocks)
                }
                recordedNoteBlocks.clear()
                sendChatMessage("Right click the block you want to use as alignment point.")
            } else {
                if (recordedNoteBlocks.isNotEmpty()) {
                    val block = if (recordedNoteBlocks.size == 1) "block" else "blocks"
                    sendChatMessage("Finished recording ${recordedNoteBlocks.size} note $block.")
                } else {
                    if (previouslyRecordedNoteBlocks.isNotEmpty()) {
                        recordedNoteBlocks.putAll(previouslyRecordedNoteBlocks)
                        val block = if (previouslyRecordedNoteBlocks.size == 1) "block" else "blocks"
                        sendChatMessage("0 note blocks were recorded. Your previous recording of ${previouslyRecordedNoteBlocks.size} note $block was loaded instead.")
                    } else {
                        sendChatMessage("0 note blocks were recorded.")
                    }
                }
            }
        }

        startRecreating.listeners.add {
            alignmentPosSet = false
            enableAutoTuner.value = false

            // cancel if world is not loaded
            if (startRecreating.value && !checkWorld()) {
                startRecreating.value = false
                return@add
            }

            // automatically enables module if it is disabled
            if (startRecreating.value && !isEnabled) {
                enable()
            }

            if (startRecreating.value) {
                startRecording.value = false
                template.clear()
                cachedNoteBlocks.clear()
                clicksToSend.clear()
                if (recordedNoteBlocks.isNotEmpty()) {
                    sendChatMessage("Right click the block that matches your alignment point.")
                } else {
                    sendChatMessage("No note block data to recreate. Please make a recording first.")
                    startRecreating.value = false
                }
            }
        }

        rotation.listeners.add {
            if (startRecreating.value && alignmentPosSet) {
                createTemplateFromRecording()
            }
        }

        flip.listeners.add {
            if (startRecreating.value && alignmentPosSet) {
                createTemplateFromRecording()
            }
        }

        enableAutoTuner.listeners.add {
            if (enableAutoTuner.value) {
                if (!startRecreating.value) {
                    sendChatMessage("Please enable 'Start Recreating' first.")
                    enableAutoTuner.value = false
                } else if (!alignmentPosSet) {
                    sendChatMessage("Please set your alignment point first.")
                    enableAutoTuner.value = false
                }
            }
        }

        asyncListener<WorldEvent.Load> { event ->
            // store world for block checking
            this.world = event.world
            // reset module state on world load
            mode.value = Mode.RECORD
            startRecording.value = false
            startRecreating.value = false
            rotation.value = 0
            flip.value = false
            enableAutoTuner.value = false
            tuningSpeed.value = 100
            alignmentPosSet = false
        }

        safeListener<PlayerInteractEvent.RightClickBlock> { event ->
            if (isEnabled && (startRecording.value || startRecreating.value)) {
                selectBlock(event.pos)
            }
        }

        safeListener<PacketEvent.Receive> { event ->
            if (isEnabled) {
                // store world reference if it was not stored by WorldEvent.Load
                // this is necessary if plugin was installed from the GUI
                if (this@NoteBlockTransfer.world == null) {
                    this@NoteBlockTransfer.world = world
                }
                serverIP = mc.currentServerData?.serverIP ?: ""

                // note block played
                val packet = event.packet
                if (packet is SPacketBlockAction) {
                    if (world.getBlockState(packet.blockPosition).block is BlockNote) {
                        notePlayed(packet)
                    }
                }

                // block changed
                if (packet is SPacketBlockChange && startRecreating.value) {
                    blockChanged(packet.blockPosition, packet.blockState)
                }

                // time update
                if (packet is SPacketTimeUpdate) {
                    val timeNow = System.currentTimeMillis()
                    val diff = timeNow - lastServerTimeUpdateMillis
                    val tps = (20000.0 / diff).coerceIn(0.0, 20.0)
                    recentTpsValues.add(tps)
                    lastServerTimeUpdateMillis = timeNow
                    lastServerResponseMillis = timeNow
                }

            }
        }

        safeListener<RenderWorldEvent> {
            if (isEnabled) {
                renderESP()
            }
        }

        safeListener<RenderOverlayEvent> {
            if (isEnabled) {
                renderTextOverlays()
            }
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (isEnabled) {
                if (it.phase == TickEvent.Phase.END) {
                    clientTick()
                }
            }
        }
    }

    private fun checkWorld(): Boolean {
        if (world == null) {
            sendChatMessage("&cModule failed to initialize. Please restart Minecraft or contact me on Discord to resolve the issue: &bSimon#4530")
            return false
        }
        return true
    }

    /**
     * Used for measuring milliseconds between two points in time
     * only used during development for performance testing
     */
    private fun startTimer() {
        time = System.currentTimeMillis()
    }

    /**
     * Prints elapsed milliseconds in chat
     * only used during development for performance testing
     */
    private fun stopTimer() {
        val timeNow = System.currentTimeMillis()
        val diff = timeNow - time
        if (diff > 60) {
            MessageSendHelper.sendChatMessage("&6Timer: $diff millis")
        }
        time = timeNow
    }

    private fun selectBlock(blockPos: BlockPos) {
        if (world != null && (startRecording.value || startRecreating.value) && !alignmentPosSet) {
            val illegalAlignmentBlock =
                when (world!!.getBlockState(blockPos).block) {
                    Blocks.NOTEBLOCK -> "note block"
                    Blocks.STONE_BUTTON -> "button"
                    Blocks.WOODEN_BUTTON -> "button"
                    Blocks.LEVER -> "lever"
                    Blocks.UNPOWERED_REPEATER -> "repeater"
                    Blocks.POWERED_REPEATER -> "repeater"
                    else -> ""
                }

            if (illegalAlignmentBlock.isNotEmpty()) {
                sendChatMessage("&cA $illegalAlignmentBlock cannot be used as alignment point. Please choose a different block.")
                return
            }
        }

        if (startRecording.value && !alignmentPosSet) {
            alignmentPos = blockPos
            alignmentPosSet = true
            sendChatMessage("Alignment point set. Play all note blocks to be recorded.")
        }
        if (startRecreating.value && !alignmentPosSet) {
            alignmentPosSet = true
            alignmentPos = blockPos
            sendChatMessage("Alignment point set. Play all note blocks you want to tune and then enable the Auto-Tuner.")
            createTemplateFromRecording()
        }
    }

    private fun createTemplateFromRecording() {
        template.clear()
        cachedNoteBlocks.clear()
        clicksToSend.clear()
        for (recordedNoteBlock in recordedNoteBlocks) {
            val newRelativePosition = recalculateRelativePosition(recordedNoteBlock.key)
            val newAbsolutePosition = alignmentPos.add(newRelativePosition)
            template[newAbsolutePosition] = recordedNoteBlock.value
            if (world!!.getBlockState(newAbsolutePosition).block == Blocks.NOTEBLOCK) {
                cachedNoteBlocks[newAbsolutePosition] = NoteBlockInformation(NoteBlockState.UNKNOWN_PITCH)
            } else {
                cachedNoteBlocks[newAbsolutePosition] = NoteBlockInformation(NoteBlockState.MISSING)
            }
        }
        requestStatusPrint()
    }

    private fun recalculateRelativePosition(blockPos: BlockPos): BlockPos {
        val blockRotation =
            when (rotation.value) {
                90 -> Rotation.CLOCKWISE_90
                180 -> Rotation.CLOCKWISE_180
                270 -> Rotation.COUNTERCLOCKWISE_90
                else -> Rotation.NONE
            }

        // if recording should be flipped, the x coordinate gets multiplied with -1
        val flipMultiplier =
            if (flip.value) -1
            else 1

        return BlockPos(blockPos.x * flipMultiplier, blockPos.y, blockPos.z).rotate(blockRotation)
    }

    private fun notePlayed(packet: SPacketBlockAction) {
        val absolutePosition = packet.blockPosition
        val instrument = NoteBlockEvent.Instrument.values()[packet.data1]
        val pitch = packet.data2

        lastServerResponseMillis = System.currentTimeMillis()

        if (startRecording.value && alignmentPosSet) {
            val relativePosition = absolutePosition.subtract(alignmentPos)
            recordedNoteBlocks[relativePosition] = NoteBlockInformation(pitch, instrument, NoteBlockState.RECORDED)
            requestStatusPrint()
        }

        if (startRecreating.value && alignmentPosSet) {
            val sourceNoteBlock = template[absolutePosition]
            var noteBlockState = NoteBlockState.POSITION_NOT_RECORDED
            if (sourceNoteBlock !== null) { // only proceed if blockPos was recorded beforehand
                // determine note block state used for esp color
                noteBlockState =
                    if (pitch != sourceNoteBlock.pitch && instrument !== sourceNoteBlock.instrument) {
                        NoteBlockState.INCORRECT_PITCH_AND_INSTRUMENT
                    } else if (pitch != sourceNoteBlock.pitch) {
                        NoteBlockState.INCORRECT_PITCH
                    } else if (instrument !== sourceNoteBlock.instrument) {
                        NoteBlockState.INCORRECT_INSTRUMENT
                    } else {
                        NoteBlockState.MATCHING
                    }

                // calculate how many right clicks are necessary to tune this note block
                if (pitch != sourceNoteBlock.pitch) {
                    val difference = sourceNoteBlock.pitch - packet.data2
                    val clicks = if (difference >= 0) difference else 25 + difference
                    if (clicksToSend[absolutePosition] == null) {
                        clicksToSend[absolutePosition] = clicks
                    }
                } else {
                    // the correct note was played
                    // check if the note block has clicks to send left
                    // if yes, wait a second and check again
                    val clicks = clicksToSend[absolutePosition]
                    if (clicks !== null && clicks > 0) {
                        Timer("CheckPitchAgain", false).schedule(1000) {
                            val cachedNoteBlock = cachedNoteBlocks[absolutePosition]
                            if (cachedNoteBlock != null) {
                                val cachedState = cachedNoteBlock.noteBlockState
                                if (cachedState == NoteBlockState.MATCHING || cachedState == NoteBlockState.INCORRECT_INSTRUMENT) {
                                    // pitch is still matching, remove entry from clicks to send
                                    clicksToSend.remove(absolutePosition)
                                }
                            }
                        }
                    }
                }
            }

            cachedNoteBlocks[absolutePosition] = NoteBlockInformation(pitch, instrument, noteBlockState)
            requestStatusPrint()
        }

    }

    private fun blockChanged(blockPos: BlockPos, blockState: IBlockState) {
        val blockInCache = cachedNoteBlocks[blockPos]
        val block = blockState.block
        var cacheChanged = false

        // first comment line: describes what happens in game
        // second comment line: describes logic of how fields get checked and changed
        // third comment line: additional clarification

        // note block gets placed in magenta spot, should turn yellow
        // note block -> exists in template & has state "missing" in cache? -> changes cache to unknown
        if (block == Blocks.NOTEBLOCK && template.containsKey(blockPos) && blockInCache!!.noteBlockState == NoteBlockState.MISSING) {
            cachedNoteBlocks[blockPos] = NoteBlockInformation(NoteBlockState.UNKNOWN_PITCH)
            cacheChanged = true
        }

        // note block in template gets broken, should turn magenta
        // non-note-block -> exists in template? -> changes cache to missing
        if (block !== Blocks.NOTEBLOCK && template.containsKey(blockPos)) {
            cachedNoteBlocks[blockPos] = NoteBlockInformation(NoteBlockState.MISSING)
            cacheChanged = true
        }

        // block gets placed above note block, should turn yellow
        // non-air -> block below exists in template & block below is note block? -> changes cache to unknown
        // block below must be note block to not overwrite other states (like missing)
        if (block !== Blocks.AIR && template.containsKey(blockPos.down()) && world!!.getBlockState(blockPos.down()).block == Blocks.NOTEBLOCK) {
            cachedNoteBlocks[blockPos.down()] = NoteBlockInformation(NoteBlockState.UNKNOWN_PITCH)
            cacheChanged = true
        }

        // note block with white highlight gets broken, should remove highlight
        // non-note-block -> does not exist in template & exists in cache? -> remove from cache
        if (block !== Blocks.NOTEBLOCK && !template.containsKey(blockPos) && cachedNoteBlocks.containsKey(blockPos)) {
            cachedNoteBlocks.remove(blockPos)
            cacheChanged = true
        }

        // block below note block changes, should turn yellow
        // any change -> block above exists in template & block above is note block? -> changes cache to unknown
        if (template.containsKey(blockPos.up()) && world!!.getBlockState(blockPos.up()).block == Blocks.NOTEBLOCK) {
            cachedNoteBlocks[blockPos.up()] = NoteBlockInformation(NoteBlockState.UNKNOWN_PITCH)
            cacheChanged = true
        }

        if (cacheChanged) {
            requestStatusPrint()
        }
    }

    private fun renderESP() {
        lightESP.aFilled = 10
        lightESP.aOutline = 60
        boldESP.aFilled = 50
        boldESP.aOutline = 255

        if (alignmentPosSet) {
            // draw recorded note blocks
            if (startRecording.value && showESP.value) {
                for (recordedNoteBlock in recordedNoteBlocks) {
                    val absolutePos = alignmentPos.add(recordedNoteBlock.key)
                    lightESP.add(absolutePos, recordedNoteBlock.value.noteBlockState.color)
                }
            }

            // draw cached note blocks
            if (startRecreating.value && showESP.value) {
                for (cachedNoteBlock in cachedNoteBlocks) {
                    val position = cachedNoteBlock.key
                    val noteBlockInformation = cachedNoteBlock.value
                    val state = noteBlockInformation.noteBlockState

                    if (state == NoteBlockState.INCORRECT_INSTRUMENT || state == NoteBlockState.INCORRECT_PITCH_AND_INSTRUMENT) {
                        boldESP.add(position.down(), NoteBlockState.INCORRECT_PITCH.color)
                    }
                    if (state == NoteBlockState.MATCHING || state == NoteBlockState.INCORRECT_INSTRUMENT) {
                        lightESP.add(position, noteBlockInformation.noteBlockState.color)
                    } else {
                        boldESP.add(position, noteBlockInformation.noteBlockState.color)
                    }
                }
            }

            // draw alignment point
            boldESP.add(alignmentPos, alignmentEspColor)
        }

        lightESP.render(true)
        boldESP.render(true)
    }

    private fun renderTextOverlays() {
        // omitted since it causes visual glitches
    }

    private fun SafeClientEvent.clientTick() {
        // cancel if auto tuner is disabled or if there are no packets to send
        if (!enableAutoTuner.value || clicksToSend.isEmpty() || mc.isGamePaused || player.isSneaking) return

        val packetsPerTick = calculatePacketsPerTick()

        // this calculation allows sending an arbitrary amount of packets per second, using client ticks as clock
        val packetsForThisTick = ((tickCycle + 1) * packetsPerTick).roundToInt() - (tickCycle * packetsPerTick).roundToInt()
        val eyePos = player.getPositionEyes(1f)

        // make copy of clicksToSend to remove entries from it
        val clicksToSendCopy = ConcurrentHashMap(clicksToSend)

        // one loop for each packet to send this tick
        for (i in 0 until packetsForThisTick) {
            val noteBlockInReachToTunePair = getNoteBlockInReachToTune(eyePos, clicksToSendCopy)
            if (noteBlockInReachToTunePair != null) { // if there is a note block in reach to tune
                val blockInReach = noteBlockInReachToTunePair.first
                val side = noteBlockInReachToTunePair.second
                // rotate to look at note block
                sendPlayerPacket {
                    rotate(RotationUtils.getRotationTo(eyePos, blockInReach.toVec3dCenter()))
                }
                // send click packet
                val usePacket = CPacketPlayerTryUseItemOnBlock(blockInReach, side, EnumHand.MAIN_HAND, 0f, 0f, 0f)
                connection.sendPacket(usePacket)
                lastClickSentMillis = System.currentTimeMillis()
                player.swingArm(EnumHand.MAIN_HAND)

                // decrease clicks to send by one for this note block
                val clicksLeft = clicksToSend[blockInReach]!! - 1
                clicksToSend[blockInReach] = clicksLeft
                // if all right-clicks are sent, wait a moment and check if the note block is actually tuned correctly
                if (clicksLeft == 0) {
                    val lowestTps = recentTpsValues.stream().min(Double::compareTo).get()
                    val waitMillis = (10000 / lowestTps).roundToLong() // make waiting time dependant on server TPS
                    Timer("CheckPitchAgain", false).schedule(waitMillis) {
                        clicksToSend.remove(blockInReach)
                        val cachedNoteBlock = cachedNoteBlocks[blockInReach]!!
                        val cachedState = cachedNoteBlock.noteBlockState
                        if (cachedState == NoteBlockState.INCORRECT_PITCH || cachedState == NoteBlockState.INCORRECT_PITCH_AND_INSTRUMENT) {
                            val difference = template[blockInReach]!!.pitch - cachedNoteBlocks[blockInReach]!!.pitch
                            val clicks = if (difference >= 0) difference else 25 + difference
                            if (clicks > 0) {
                                // note block is not tuned correctly, return it to the list
                                clicksToSend[blockInReach] = clicks
                            }
                        }
                    }
                }
                // remove note block to not send it again this tick
                clicksToSendCopy.remove(blockInReach)
            }
        }

        tickCycle++
        if (tickCycle > 20) tickCycle = 0
    }

    /**
     * Calculate packets to send per tick
     */
    private fun calculatePacketsPerTick(): Double {
        // adjust tuning speed based on server TPS
        val lowestTps = recentTpsValues.stream().min(Double::compareTo).get()
        val multiplier = lowestTps / 20.0
        val adjustedTuningSpeed = tuningSpeed.value * multiplier
        var packetsPerTick = adjustedTuningSpeed / 100
        lowTpsWarning = lowestTps < 15 // I know it is dirty to set this here, but I don't care

        // pause auto-tuner if server has not responded for a certain amount of time
        if (lastClickSentMillis > lastServerResponseMillis) {
            val millisSinceLastResponse = System.currentTimeMillis() - lastServerResponseMillis
            if (millisSinceLastResponse > 500) {
                sendChatMessage("millisSinceLastResponse: $millisSinceLastResponse")
                packetsPerTick = 0.0
            }
            serverNotRespondingWarning = millisSinceLastResponse > 1000  // I know it is dirty to set this here, but I don't care
        }

        return packetsPerTick
    }

    /**
     * Returns a random note block in the player's proximity that can be tuned.
     *
     * @param eyePos the player's eye position
     * @param clicksToSendCopy the number of right-clicks to send for note blocks
     * @return a pair of the note block and the side of the block that is in the player's proximity
     */
    private fun SafeClientEvent.getNoteBlockInReachToTune(eyePos: Vec3d, clicksToSendCopy: ConcurrentHashMap<BlockPos, Int>): Pair<BlockPos, EnumFacing>? {
        // shuffle so that all blocks in reach get clicked in a random order
        // this prevents the auto-tuner from getting stuck on one note block that does not react to tuning attempts
        val shuffledBlockPositions = clicksToSendCopy.keys.shuffled()
        for (blockPos in shuffledBlockPositions) {
            val clicks = clicksToSendCopy[blockPos]
            if (clicks == null || clicks <= 0) continue // skip if element has been removed or no clicks are left to send

            // check if block is reachable
            val squareDistance = blockPos.toVec3dCenter().squareDistanceTo(eyePos)
            if (squareDistance < 25) {
                // check if a side is accessible and return it as well
                val miningSide = getMiningSide(blockPos) ?: continue
                return Pair(blockPos, miningSide) // success if block is in reach
            }
        }
        return null
    }

    /**
     *  Throttle frequency of printed status updates to one per second
     */
    private fun requestStatusPrint() {
        if (!this.printStatus.value) return
        if (printStatusDelayActive) {
            printStatusAgain = true
        } else {
            printStatusDelayActive = true
            printStatus()
            Timer("disableDelayTimer", false).schedule(1000) {
                printStatusDelayActive = false
                if (printStatusAgain) {
                    printStatusAgain = false
                    requestStatusPrint()
                }
            }

        }
    }

    /**
     * Prints the current status of the auto-tuner to the chat
     */
    private fun printStatus() {
        if (mode.value == Mode.RECORD) {
            val block = if (recordedNoteBlocks.size == 1) "block" else "blocks"
            sendChatMessage("${recordedNoteBlocks.size} note $block recorded.")
        } else {
            var status = ""
            val cachedStates = numberOfNoteBlocksPerState()

            val unknown = cachedStates[NoteBlockState.UNKNOWN_PITCH]
            status += if (unknown != null) "&6Unknown: $unknown, " else ""
            val matching = cachedStates[NoteBlockState.MATCHING]
            status += if (matching != null) "&2Matching: $matching, " else ""
            val incorrectPitch = cachedStates[NoteBlockState.INCORRECT_PITCH]
            status += if (incorrectPitch != null) "&cIncorrect Pitch: $incorrectPitch, " else ""
            val incorrectInstrument = cachedStates[NoteBlockState.INCORRECT_INSTRUMENT]
            status += if (incorrectInstrument != null) "&cIncorrect Instrument: $incorrectInstrument, " else ""
            val incorrectPitchAndInstrument = cachedStates[NoteBlockState.INCORRECT_PITCH_AND_INSTRUMENT]
            status += if (incorrectPitchAndInstrument != null) "&cIncorrect Pitch and Instrument: $incorrectPitchAndInstrument, " else ""
            val missing = cachedStates[NoteBlockState.MISSING]
            status += if (missing != null) "&dMissing: $missing, " else ""
            val positionNotRecorded = cachedStates[NoteBlockState.POSITION_NOT_RECORDED]
            status += if (positionNotRecorded != null) "&7Position not Recorded: $positionNotRecorded, " else ""

            status += "&r&lTotal: ${cachedNoteBlocks.size}"

            if (serverNotRespondingWarning) {
                sendChatMessage("&6Auto-Tuner is paused because the server is not responding")
            } else if (lowTpsWarning) {
                sendChatMessage("&6Auto-Tuner is slowed down due to server-lag")
            }

            if (tuningSpeed.value > 160 && serverIP.equals("2b2t.org", true)) {
                sendChatMessage("&6Warning: Your current tuning-speed of ${tuningSpeed.value}% is likely to get you kicked from 2b2t.org. Please consider using a value below 160%")
            }

            sendChatMessage(status)
        }
    }

    /**
     * Returns a map of note block states to the number of note blocks of that state
     *
     * @return a map of note block states to the number of note blocks of that state
     */
    private fun numberOfNoteBlocksPerState(): HashMap<NoteBlockState, Int> {
        val stateMap = HashMap<NoteBlockState, Int>()
        for (cachedNoteBlock in cachedNoteBlocks) {
            stateMap[cachedNoteBlock.value.noteBlockState] = (stateMap[cachedNoteBlock.value.noteBlockState] ?: 0) + 1
        }
        return stateMap
    }

    private fun sendChatMessage(message: String) {
        MessageSendHelper.sendChatMessage("&b♩♫ ⟶ ♩♫&r $message")
    }

}