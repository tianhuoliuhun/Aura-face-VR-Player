package com.example.vr

/**
 * v119 拆分：从 VRPlayerScreen.kt 搬出的音频处理器。
 *
 * 立体声左右声道交换（供 VR 双目/头盔佩戴方式不同导致的声道错位场景使用）。
 */
class StereoChannelSwappingAudioProcessor : androidx.media3.common.audio.BaseAudioProcessor() {
    @Volatile
    var isSwappingEnabled = false

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount != 2) {
            return androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)

        if (isSwappingEnabled) {
            // PCM_16BIT stereo: 4 bytes per frame (Left 2 bytes, Right 2 bytes)
            while (inputBuffer.remaining() >= 4) {
                val l0 = inputBuffer.get()
                val l1 = inputBuffer.get()
                val r0 = inputBuffer.get()
                val r1 = inputBuffer.get()

                // Swap Left and Right channels
                outputBuffer.put(r0)
                outputBuffer.put(r1)
                outputBuffer.put(l0)
                outputBuffer.put(l1)
            }
            // Put residue bytes if any
            while (inputBuffer.hasRemaining()) {
                outputBuffer.put(inputBuffer.get())
            }
        } else {
            outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
    }
}
