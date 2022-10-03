package com.lambda.modules.util

import com.lambda.client.util.color.ColorHolder

enum class NoteBlockState(val color: ColorHolder) {
    RECORDED(ColorHolder(124, 196, 120)), // light green
    MATCHING(ColorHolder(52, 207, 67)), // green
    UNKNOWN_PITCH(ColorHolder(207, 194, 52)), // yellow
    POSITION_NOT_RECORDED(ColorHolder(200, 200, 200)), // white
    INCORRECT_PITCH(ColorHolder(230, 76, 53)), // red
    INCORRECT_INSTRUMENT(MATCHING.color), // "matching" because pitch is correct and note block should thus be highlighted green
    INCORRECT_PITCH_AND_INSTRUMENT(INCORRECT_PITCH.color),
    MISSING(ColorHolder(255, 71, 246)) // magenta
}