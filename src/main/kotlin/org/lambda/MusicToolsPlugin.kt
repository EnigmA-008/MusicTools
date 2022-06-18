package org.lambda

import com.lambda.client.plugin.api.Plugin

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