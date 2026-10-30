package com.adownloader.app

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.VideoView

class PlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val video = VideoView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setMediaController(MediaController(this@PlayerActivity).also { it.setAnchorView(this) })
            setVideoURI(uri)
            setOnPreparedListener { it.isLooping = false; start() }
        }
        setContentView(video)
    }
}
