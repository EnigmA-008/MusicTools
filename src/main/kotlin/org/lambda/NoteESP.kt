package org.lambda

import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.RenderOverlayEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.graphics.GlStateUtils
import com.lambda.client.util.graphics.ProjectionUtils
import com.lambda.client.util.graphics.font.FontRenderAdapter
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.toVec3d
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.safeListener
import com.lambda.event.listener.listener
import net.minecraft.block.BlockNote
import net.minecraft.network.play.server.SPacketBlockAction
import net.minecraft.util.math.BlockPos
import net.minecraftforge.event.world.NoteBlockEvent
import org.lambda.ExampleLabelHud.updateText
import org.lambda.util.Note
import org.lwjgl.opengl.GL11
import java.util.concurrent.ConcurrentHashMap

internal object NoteESP : PluginModule(
    name = "NoteESP",
    category = Category.RENDER,
    description = "Shows note block pitch",
    pluginMain = MusicToolsPlugin
)

//TODO: render only for own y to own y - 2 > vague ideas, but i have no idea how
//TODO: range for rendering > found something in the search module but have no idea how to adapt this
//TODO: remove rendering when block is broken > no idea how to check regularly if the block is still there

{
    private val filled by setting("Filled", true, description = "Renders surfaces")
    private val outline by setting("Outline", true, description = "Renders outline")
    private val alphaFilled by setting("Alpha Filled", 26, 0..255, 1, { filled }, description = "Alpha for surfaces")
    private val alphaOutline by setting("Alpha Outline", 26, 0..255, 1, { outline }, description = "Alpha for outline")
    private val thickness by setting("Outline Thickness", 2f, .25f..4f, .25f, { outline })
    private val textScale by setting("Text Scale", 1f, .0f..4f, .25f)
    private val colorScheme by setting("Color Scheme", ColorScheme.DEFAULT, description = "Changes Color Scheme")
    private val range by setting("Search Range", 128, 0..256, 8, description = "Range for Rendering")
    private val reset = setting("Reset", false, description = "Resets cached notes")
    private val debug by setting("Debug", false, description = "Debug messages in chat")

    private val cachedMusicData = ConcurrentHashMap<BlockPos, MusicData>()
    private val renderer = ESPRenderer()

    enum class ColorScheme {
        DEFAULT, RAINBOW
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
                if (player.getPositionEyes(1f).distanceTo(it.key.toVec3dCenter()) < range) {
                    renderer.add(it.key, it.value.color)
                }
            }
            renderer.render(true)
        }

        //renders text overlay
        listener<RenderOverlayEvent> {
            GlStateUtils.rescaleActual()

            cachedMusicData.forEach {
                GL11.glPushMatrix()

                val screenPos = ProjectionUtils.toScreenPos(it.key.toVec3dCenter())

                GL11.glTranslated(screenPos.x, screenPos.y, 0.0)
                GL11.glScalef(textScale * 2f, textScale * 2f, 1f)

                val centerValue = FontRenderAdapter.getStringWidth(it.value.note.ordinal.toString()) / -2f
                val centerKey = FontRenderAdapter.getStringWidth(it.value.instrument.name) / -2f

                FontRenderAdapter.drawString(it.value.note.ordinal.toString(), centerValue, 0f, color = it.value.color)
                FontRenderAdapter.drawString(it.value.instrument.name, centerKey, FontRenderAdapter.getFontHeight(), color = it.value.color)

                GL11.glPopMatrix()
            }
        }
    }

    private class MusicData(val note: Note, val instrument: NoteBlockEvent.Instrument) {
        val color = if (colorScheme == ColorScheme.DEFAULT) note.default else note.rainbow
    }
}