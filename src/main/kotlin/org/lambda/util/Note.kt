package org.lambda.util

import com.lambda.client.util.color.ColorHolder

enum class Note(val rainbow: ColorHolder) {
    F_SHARP_LOW(ColorHolder(119, 215, 0)),
    G_LOW(ColorHolder(149, 192, 0)),
    G_SHARP_LOW(ColorHolder(178, 165, 0)),
    A_LOW(ColorHolder(204, 134, 0)),
    A_SHARP_LOW(ColorHolder(226, 101, 0)),
    B_LOW(ColorHolder(243, 65, 0)),
    C_LOW(ColorHolder(252, 30, 0)),
    C_SHARP_LOW(ColorHolder(254, 0, 15)),
    D_LOW(ColorHolder(247, 0, 51)),
    D_SHARP_LOW(ColorHolder(232, 0, 90)),
    E_LOW(ColorHolder(207, 0, 131)),
    F_LOW(ColorHolder(174, 0, 169)),
    F_SHARP_HIGH(ColorHolder(134, 0, 204)),
    G_HIGH(ColorHolder(91, 0, 231)),
    G_SHARP_HIGH(ColorHolder(45, 0, 249)),
    A_HIGH(ColorHolder(2, 10, 254)),
    A_SHARP_HIGH(ColorHolder(0, 55, 246)),
    B_HIGH(ColorHolder(0, 104, 224)),
    C_HIGH(ColorHolder(0, 154, 188)),
    C_SHARP_HIGH(ColorHolder(0, 198, 141)),
    D_HIGH(ColorHolder(0, 233, 88)),
    D_SHARP_HIGH(ColorHolder(0, 252, 33)),
    E_HIGH(ColorHolder(31, 252, 0)),
    F_HIGH(ColorHolder(89, 232, 0)),
    F_SHARP_SUPER_HIGH(ColorHolder(148, 193, 0))
}

//enum class Note(val default: ColorHolder) {
//    F_SHARP_LOW(ColorHolder(85, 221, 192)),
//    G_LOW(ColorHolder(126, 196, 177)),
//    G_SHARP_LOW(ColorHolder(164, 164, 171)),
//    A_LOW(ColorHolder(196, 126, 177)),
//    A_SHARP_LOW(ColorHolder(221, 85, 192)),
//    B_LOW(ColorHolder(237, 44, 218)),
//    C_LOW(ColorHolder(243, 6, 6)),
//    C_SHARP_LOW(ColorHolder(237, 218, 44)),
//    D_LOW(ColorHolder(221, 192, 85)),
//    D_SHARP_LOW(ColorHolder(196, 177, 126)),
//    E_LOW(ColorHolder(164, 171, 164)),
//    F_LOW(ColorHolder(126, 177, 196)),
//    F_SHARP_HIGH(ColorHolder(85, 192, 221)),
//    G_HIGH(ColorHolder(44, 218, 237)),
//    G_SHARP_HIGH(ColorHolder(6, 6, 243)),
//    A_HIGH(ColorHolder(218, 44, 237)),
//    A_SHARP_HIGH(ColorHolder(192, 85, 221)),
//    B_HIGH(ColorHolder(177, 126, 196)),
//    C_HIGH(ColorHolder(171, 164, 164)),
//    C_SHARP_HIGH(ColorHolder(177, 196, 126)),
//    D_HIGH(ColorHolder(192, 221, 85)),
//    D_SHARP_HIGH(ColorHolder(218, 237, 44)),
//    E_HIGH(ColorHolder(6, 243, 6)),
//    F_HIGH(ColorHolder(44, 237, 218)),
//    F_SHARP_SUPER_HIGH(ColorHolder(85, 221, 192))
//}