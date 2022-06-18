package org.lambda.util

import net.minecraftforge.event.world.NoteBlockEvent

data class NoteBlockInformation(val pitch: Int, val instrument: NoteBlockEvent.Instrument, val noteBlockState: NoteBlockState) {
    constructor(noteBlockState: NoteBlockState) : this(0, NoteBlockEvent.Instrument.PIANO, noteBlockState)
}