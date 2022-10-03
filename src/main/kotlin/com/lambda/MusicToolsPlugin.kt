package com.lambda

import com.lambda.client.plugin.api.Plugin
import com.lambda.modules.NoteBlockTransfer
import com.lambda.modules.NoteESP

internal object MusicToolsPlugin : Plugin() {

    override fun onLoad() {
        // Load any modules, commands, or HUD elements here
        modules.add(NoteESP)
        modules.add(NoteBlockTransfer)
    }

    override fun onUnload() {
        // Here you can unregister threads etc...
    }
}