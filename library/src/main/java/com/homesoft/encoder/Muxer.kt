package com.homesoft.encoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Point
import android.media.MediaCodecList
import android.media.MediaCodecList.REGULAR_CODECS
import android.util.Log
import androidx.annotation.RawRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException
import kotlin.math.pow
import kotlin.math.sqrt

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

class Muxer(private val context: Context, private val file: File) {
    constructor(context: Context, config: MuxerConfig) : this(context, config.file) {
        muxerConfig = config
    }

    companion object {
        private val TAG = Muxer::class.java.simpleName
    }

    // Initialize a default configuration
    private var muxerConfig: MuxerConfig = MuxerConfig(file)
    private var muxingCompletionListener: MuxingCompletionListener? = null

    /**
     * Build the Muxer with a custom [MuxerConfig]
     *
     * @param config: muxer configuration object
     */
    fun setMuxerConfig(config: MuxerConfig) {
        muxerConfig = config
    }

    fun getMuxerConfig() = muxerConfig

    /**
     * List containing images in any of the following formats:
     * [Bitmap] [@DrawRes Int] [Canvas]
     */
    fun mux(imageList: List<Any>,
            @RawRes audioTrack: Int? = null): MuxingResult {
        if (!isCodecSupported(muxerConfig.mimeType)) {
            val thrown = RuntimeException("Unsupported Muxer Mime Type")
            muxingCompletionListener?.onVideoError(thrown)
            return MuxingError(thrown.message.toString(), thrown)
        }

        // Returns on a callback a finished video
        Log.d(TAG, "Generating video")
        var success = true
        if (muxerConfig.videoWidth == 0 || muxerConfig.videoHeight == 0) {
            var width = 0
            var height = 0
            for (image in imageList) {
                val size = when (image) {
                    is Int -> {
                        val bitmap = BitmapFactory.decodeResource(context.resources, image)
                        Point(bitmap.width, bitmap.height)
                    }
                    is Bitmap -> Point(image.width, image.height)
                    is Canvas -> Point(image.width, image.height)
                    else -> continue
                }
                if (size.x > width) {
                    width = size.x
                }
                if (size.y > height) {
                    height = size.y
                }
            }
            val size = Point(width, height)
            val codecs = MediaCodecList(REGULAR_CODECS)
            var threshold = Double.MAX_VALUE
            for (info in codecs.codecInfos) {
                if (!info.isEncoder) {
                    continue
                }
                for (type in info.supportedTypes) {
                    if (type == muxerConfig.mimeType) {
                        val codecCapabilities = info.getCapabilitiesForType(type)
                        val videoCapabilities = codecCapabilities.videoCapabilities
                        val widthAlignment = videoCapabilities.widthAlignment
                        val heightAlignment = videoCapabilities.heightAlignment
                        val widthRange = videoCapabilities.supportedWidths
                        for (x in widthRange.lower until widthRange.upper + 1 step 2) {
                            if (x % widthAlignment != 0) {
                                continue
                            }
                            val heightRange = videoCapabilities.getSupportedHeightsFor(x)
                            for (y in heightRange.lower until heightRange.upper + 1 step 2) {
                                if (y % heightAlignment != 0) {
                                    continue
                                }
                                if (videoCapabilities.isSizeSupported(x, y)) {
                                    val point = Point(x, y)
                                    val temp = distance(size, point)
                                    if (temp < threshold) {
                                        width = x
                                        height = y
                                        threshold = temp
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "Video dimensions ${width}x${height}")
            muxerConfig.videoWidth = width
            muxerConfig.videoHeight = height
        }
        val frameBuilder = FrameBuilder(context, muxerConfig, audioTrack)

        try {
            frameBuilder.start()
        } catch (e: IOException) {
            Log.e(TAG, "Start Encoder Failed")
            e.printStackTrace()
            muxingCompletionListener?.onVideoError(e)
            return MuxingError("Start encoder failed", e)
        }

        for (image in imageList) {
            try {
                frameBuilder.createFrame(image)
            } catch (e: RuntimeException) {
                e.printStackTrace()
                success = false
                break
            }
        }

        // Release the video codec so we can mux in the audio frames separately
        success = frameBuilder.releaseVideoCodec() && success

        // Add audio
        success = frameBuilder.muxAudioFrames() && success

        // Release everything
        success = frameBuilder.releaseAudioExtractor() && success
        success = frameBuilder.releaseMuxer() && success

        if (!success) {
            val thrown = RuntimeException("Muxer Muxing Failed")
            muxingCompletionListener?.onVideoError(thrown)
            return MuxingError(thrown.message.toString(), thrown)
        }
        muxingCompletionListener?.onVideoSuccessful(file)
        return MuxingSuccess(file)
    }

    suspend fun muxAsync(imageList: List<Any>, @RawRes audioTrack: Int? = null): MuxingResult {
        return mux(imageList, audioTrack)
    }

    fun setOnMuxingCompletedListener(muxingCompletionListener: MuxingCompletionListener) {
        this.muxingCompletionListener = muxingCompletionListener
    }
}

fun isCodecSupported(mimeType: String?): Boolean {
    val codecs = MediaCodecList(REGULAR_CODECS)
    for (codec in codecs.codecInfos) {
        if (!codec.isEncoder) {
            continue
        }
        for (type in codec.supportedTypes) {
            if (type == mimeType) return true
        }
    }
    return false
}

fun distance(from: Point, to: Point): Double {
    return sqrt((from.x - to.x).toDouble().pow(2.0) + (from.y - to.y).toDouble().pow(2.0))
}
