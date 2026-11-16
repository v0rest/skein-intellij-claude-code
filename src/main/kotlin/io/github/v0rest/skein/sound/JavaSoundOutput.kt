package io.github.v0rest.skein.sound

import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/**
 * [SoundOutput] through Java Sound, for Windows and Linux only - on macOS it opens the input side too, and the system
 * asks for the microphone. A sound is a [Clip], which the player closes when it stops or when the next one cuts it.
 */
class JavaSoundOutput : SoundOutput {
    override fun start(path: String, facts: FileFacts, ended: (Any) -> Unit): Any? {
        val clip = open(path)
        try {
            clip.addLineListener { event -> if (event.type == LineEvent.Type.STOP) ended(clip) }
            clip.start()
        } catch (e: Exception) {
            clip.close()
            throw e
        }
        return clip
    }

    override fun stop(sound: Any) {
        (sound as? Clip)?.close()
    }

    override fun dispose() = Unit

    /**
     * The file as a clip, open and ready to start. A clip takes PCM, so anything else Java Sound can decode (μ-law in an
     * `.au`, say) is turned into it first. Throws for anything that cannot play, the caller beeps then.
     */
    private fun open(path: String): Clip {
        // buffered, since Java Sound tries its readers one after another and each has to step back to the start
        Files.newInputStream(Path.of(path)).buffered().use { input ->
            AudioSystem.getAudioInputStream(input).use { source ->
                val pcm = if (source.format.encoding == AudioFormat.Encoding.PCM_SIGNED || source.format.encoding == AudioFormat.Encoding.PCM_UNSIGNED) {
                    source
                } else {
                    AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, source)
                }
                pcm.use {
                    val clip = AudioSystem.getClip()
                    try {
                        clip.open(pcm)
                    } catch (e: Exception) {
                        clip.close()
                        throw e
                    }
                    return clip
                }
            }
        }
    }
}
