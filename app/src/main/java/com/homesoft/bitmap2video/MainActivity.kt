package com.homesoft.bitmap2video

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.homesoft.bitmap2video.FileUtils.getVideoFile
import com.homesoft.bitmap2video.FileUtils.shareVideo
import com.homesoft.bitmap2video.databinding.ActivityMainBinding
import com.homesoft.encoder.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/*
 * Copyright (C) 2020 Israel Flores
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

class MainActivity : AppCompatActivity() {
    companion object {
        private val TAG: String = MainActivity::class.java.simpleName
        private val imageArray: List<Int> = listOf(
                R.raw.im1,
                R.raw.im2,
                R.raw.im3,
                R.raw.im4
        )
    }
    private lateinit var binding: ActivityMainBinding

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private var videoFile: File? = null
    private var muxerConfig: MuxerConfig? = null
    private var mimeType = MediaFormat.MIMETYPE_VIDEO_AVC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.avc.isEnabled = isCodecSupported(mimeType)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1234)
        }

        setListeners()
    }

    private fun setListeners() {
        binding.btMake.setOnClickListener {
            binding.btMake.isEnabled = false

            basicVideoCreation()
        }

        binding.avc.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) setCodec(MediaFormat.MIMETYPE_VIDEO_AVC)
        }

        binding.hevc.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) setCodec(MediaFormat.MIMETYPE_VIDEO_HEVC)
        }

        binding.btPlay.setOnClickListener {
            videoFile?.run {
                binding.player.setVideoPath(this.absolutePath)
                binding.player.start()
            }
        }

        binding.btShare.setOnClickListener {
            Log.i(TAG, "Sharing video...")
            muxerConfig?.run {
                shareVideo(this@MainActivity, file, mimeType)
            }
        }
    }

    private fun setCodec(codec: String) {
        if (isCodecSupported(codec)) {
            mimeType = codec
            muxerConfig?.mimeType = mimeType
        } else {
            Toast.makeText(this@MainActivity, "AVC Codec not supported", Toast.LENGTH_SHORT)
                    .show()
        }
    }

    // Basic implementation
    private fun basicVideoCreation() {
        videoFile = getVideoFile(this@MainActivity, "test.mp4")
        videoFile?.run {
            muxerConfig = MuxerConfig(this, 600, 600, mimeType, 3, 1F, 1500000)
            val muxer = Muxer(this@MainActivity, muxerConfig!!)

            createVideo(muxer) // using callbacks
            // or
            createVideoAsync(muxer) // using co-routines
        }
    }

    // Callback-style approach
    private fun createVideo(muxer: Muxer) {
        muxer.setOnMuxingCompletedListener(object : MuxingCompletionListener {
            override fun onVideoSuccessful(file: File) {
                Log.d(TAG, "Video muxed - file path: ${file.absolutePath}")
                onMuxerCompleted()
            }

            override fun onVideoError(error: Throwable) {
                Log.e(TAG, "There was an error muxing the video")
                onMuxerCompleted()
            }
        })

        // Needs to happen on a background thread (long-running process)
        lifecycleScope.launch(Dispatchers.IO) {
            muxer.mux(imageArray, R.raw.bensound_happyrock)
        }
    }

    // Coroutine approach
    private fun createVideoAsync(muxer: Muxer) {
        scope.launch {
            when (val result = muxer.muxAsync(imageArray, R.raw.bensound_happyrock)) {
                is MuxingSuccess -> {
                    Log.i(TAG, "Video muxed - file path: ${result.file.absolutePath}")
                    onMuxerCompleted()
                }
                is MuxingError -> {
                    Log.e(TAG, "There was an error muxing the video")
                    binding.btMake.isEnabled = true
                }
            }
        }
    }

    private fun onMuxerCompleted() {
        runOnUiThread {
            binding.btMake.isEnabled = true
            binding.btPlay.isEnabled = true
            binding.btShare.isEnabled = true
        }
    }
}
