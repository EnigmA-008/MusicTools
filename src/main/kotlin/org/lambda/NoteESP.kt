package org.lambda

import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.RenderOverlayEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.module.Category
import com.lambda.client.module.modules.render.StorageESP
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.TickTimer
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.graphics.GlStateUtils
import com.lambda.client.util.graphics.ProjectionUtils
import com.lambda.client.util.graphics.font.FontRenderAdapter
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.safeListener
import net.minecraft.block.BlockNote
import net.minecraft.network.play.server.SPacketBlockAction
import net.minecraft.util.math.BlockPos
import net.minecraftforge.event.world.NoteBlockEvent
import org.lambda.util.Note
import org.lwjgl.opengl.GL11
import java.util.*
import java.util.concurrent.ConcurrentHashMap

internal object NoteESP : PluginModule(
    name = "NoteESP",
    category = Category.RENDER,
    description = "Shows note block pitch",
    pluginMain = MusicToolsPlugin
)

//TODO: remove rendering when block is broken

{
    private val page by setting("Page", Page.SETTINGS)

    private val boxRange by setting("Render Range for box", 32, 0..265, 4, { (renderMode == RenderMode.RANGE || renderMode == RenderMode.TUNINGMODE) && page == Page.SETTINGS }, description = "Range for Rendering of the box")
    private val textRange by setting("Render Range for text ", 32, 0..256, 4, { (renderMode == RenderMode.RANGE || renderMode == RenderMode.TUNINGMODE) && page == Page.SETTINGS }, description = "Range for Rendering of the text")
    private val renderMode by setting("Rendering", RenderMode.RANGE, { page == Page.SETTINGS }, description = "Changes Render Mode")
    private val tuningRange by setting("Tuning Range", 2, 2..6, 1, { renderMode == RenderMode.TUNINGMODE && page == Page.SETTINGS }, description = "Rendering for Y Level below feet")
    private val reset = setting("Reset", false, { page == Page.SETTINGS }, description = "Resets cached notes")
    private val debug by setting("Debug", false, { page == Page.SETTINGS }, description = "Debug messages in chat")

    private val filled by setting("Filled", true, { page == Page.RENDER }, description = "Renders surfaces")
    private val outline by setting("Outline", true, { page == Page.RENDER }, description = "Renders outline")
    private val alphaFilled by setting("Alpha Filled", 26, 0..255, 1, { filled && page == Page.RENDER}, description = "Alpha for surfaces")
    private val alphaOutline by setting("Alpha Outline", 26, 0..255, 1, { outline && page == Page.RENDER}, description = "Alpha for outline")
    private val thickness by setting("Outline Thickness", 2f, .25f..4f, .25f, { outline && page == Page.RENDER}, description = "Changes thickness of the outline")
    private val textScale by setting("Text Scale", 1f, .0f..4f, .25f, { page == Page.RENDER }, description = "Changes Text Scale")
    private val colorScheme by setting("Color Scheme", ColorScheme.DEFAULT, { page == Page.RENDER }, description = "Changes Color Scheme")

    private val cachedMusicData = ConcurrentHashMap<BlockPos, MusicData>()
    private val renderer = ESPRenderer()
//    private val updateTimer = TickTimer()
//    private const val updateDelay = 1000

    private enum class Page {
        SETTINGS, RENDER
    }

    enum class ColorScheme {
        DEFAULT, RAINBOW
    }

    enum class RenderMode {
        RANGE, TUNINGMODE
    }

    // reset button
    init {
        reset.consumers.add { _, it ->
            if (it) {
                cachedMusicData.clear()
            }
            false
        }

        //listens to played note block
        safeListener<PacketEvent.Receive> { event ->
            if (event.packet is SPacketBlockAction) {
                val packet = (event.packet as SPacketBlockAction)

                if (world.getBlockState(packet.blockPosition).block is BlockNote) {
                    val instrument = NoteBlockEvent.Instrument.values()[packet.data1]
                    val note = Note.values()[packet.data2]

                    cachedMusicData[packet.blockPosition] = MusicData(note, instrument)

                    if (debug) {
                        MessageSendHelper.sendChatMessage("Instrument: ${instrument.name} Pos: (${packet.blockPosition.asString()}) Pitch: ${note.name}")
                    }
                }
            }
        }

        //renders box
        safeListener<RenderWorldEvent> {
            renderer.aFilled = if (filled) alphaFilled else 0
            renderer.aOutline = if (outline) alphaOutline else 0
            renderer.thickness = thickness

            cachedMusicData.forEach {
                if (renderMode == RenderMode.RANGE) {
                    if (player.getPositionEyes(1f).distanceTo(it.key.toVec3dCenter()) < boxRange) {
                        renderer.add(it.key, it.value.color)
                    }
                }
                if (renderMode == RenderMode.TUNINGMODE) {
                    if ((it.key.y > player.getPositionEyes(1f).y.toInt() - tuningRange - 2) &&
                        (player.getPositionEyes(1f).distanceTo(it.key.toVec3dCenter()) < boxRange)) {
                        renderer.add(it.key, it.value.color)
                    }
                }
            }
            renderer.render(true)
        }

        //renders text overlay
        safeListener<RenderOverlayEvent> {
            GlStateUtils.rescaleActual()

            cachedMusicData.forEach { it ->
                if (renderMode == RenderMode.RANGE) {
                    if (player.getPositionEyes(1f).distanceTo(it.key.toVec3dCenter()) < textRange) {
                        GL11.glPushMatrix()

                        val screenPos = ProjectionUtils.toScreenPos(it.key.toVec3dCenter())

                        GL11.glTranslated(screenPos.x, screenPos.y, 0.0)
                        GL11.glScalef(textScale * 2f, textScale * 2f, 1f)

                        val centerValue = FontRenderAdapter.getStringWidth(it.value.note.ordinal.toString()) / -2f
                        val centerKey = FontRenderAdapter.getStringWidth(it.value.instrument.name) / -2f

                        FontRenderAdapter.drawString(it.value.note.ordinal.toString(), centerValue, 0f, color = it.value.color)
                        FontRenderAdapter.drawString(it.value.instrument.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }, centerKey, FontRenderAdapter.getFontHeight(), color = it.value.color)

                        GL11.glPopMatrix()
                    }
                }
                if (renderMode == RenderMode.TUNINGMODE) {
                    if ((it.key.y > player.getPositionEyes(1f).y.toInt() - tuningRange - 2) &&
                        (player.getPositionEyes(1f).distanceTo(it.key.toVec3dCenter()) < boxRange)) {
                        GL11.glPushMatrix()

                        val screenPos = ProjectionUtils.toScreenPos(it.key.toVec3dCenter())

                        GL11.glTranslated(screenPos.x, screenPos.y, 0.0)
                        GL11.glScalef(textScale * 2f, textScale * 2f, 1f)

                        val centerValue = FontRenderAdapter.getStringWidth(it.value.note.ordinal.toString()) / -2f
                        val centerKey = FontRenderAdapter.getStringWidth(it.value.instrument.name) / -2f

                        FontRenderAdapter.drawString(it.value.note.ordinal.toString(), centerValue, 0f, color = it.value.color)
                        FontRenderAdapter.drawString(it.value.instrument.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }, centerKey, FontRenderAdapter.getFontHeight(), color = it.value.color)

                        GL11.glPopMatrix()
                    }
                }
            }
        }
//        safeListener<RenderWorldEvent> {
//            renderer.render(false)
//
//            if (updateTimer.tick(updateDelay.toLong())) {
//                updateRenderer()
//            }
//        }
    }

    private class MusicData(val note: Note, val instrument: NoteBlockEvent.Instrument) {
        val color = if (colorScheme == ColorScheme.DEFAULT) note.default else note.rainbow
    }
}