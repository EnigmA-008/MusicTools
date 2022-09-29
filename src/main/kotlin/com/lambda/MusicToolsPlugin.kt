package com.lambda

import com.lambda.client.plugin.api.Plugin
import com.lambda.modules.NoteESP

internal object MusicToolsPlugin : Plugin() {

    override fun onLoad() {
        // Load any modules, commands, or HUD elements here
        modules.add(NoteESP)
    }

    override fun onUnload() {
        // Here you can unregister threads etc...
    }
}