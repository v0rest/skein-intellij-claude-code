package io.github.v0rest.skein.sound

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * [SystemSoundCalls] through CoreFoundation and AudioToolbox, loaded with JNA in the IDE's own process on the first
 * sound, which the player plays on a pooled thread. macOS only, and no Java Sound here - on macOS it opens the input
 * side too, and the system asks for the microphone.
 */
class MacSystemSoundCalls : SystemSoundCalls<Pointer> {
    @Suppress("FunctionName")
    interface CoreFoundationLib : Library {
        /** CFIndex is a C long, 64 bits on every Mac Skein runs on, and CF's Boolean is one byte. */
        fun CFURLCreateFromFileSystemRepresentation(allocator: Pointer?, buffer: ByteArray, length: Long, isDirectory: Byte): Pointer?

        fun CFRelease(cf: Pointer)
    }

    @Suppress("FunctionName")
    interface AudioToolbox : Library {
        fun AudioServicesCreateSystemSoundID(url: Pointer, id: IntByReference): Int

        fun AudioServicesPlaySystemSound(id: Int)

        fun AudioServicesDisposeSystemSoundID(id: Int): Int
    }

    // a failed load throws on every use, and each one beeps
    private val coreFoundation by lazy { Native.load("CoreFoundation", CoreFoundationLib::class.java) }
    private val audioToolbox by lazy { Native.load("AudioToolbox", AudioToolbox::class.java) }

    override fun url(path: String): Pointer? {
        val bytes = path.toByteArray(Charsets.UTF_8)
        return coreFoundation.CFURLCreateFromFileSystemRepresentation(null, bytes, bytes.size.toLong(), NOT_A_DIRECTORY)
    }

    override fun create(url: Pointer): SystemSoundCalls.Created {
        val id = IntByReference()
        val status = audioToolbox.AudioServicesCreateSystemSoundID(url, id)
        return SystemSoundCalls.Created(status, id.value)
    }

    override fun play(id: Int) = audioToolbox.AudioServicesPlaySystemSound(id)

    override fun dispose(id: Int) {
        audioToolbox.AudioServicesDisposeSystemSoundID(id)
    }

    override fun release(url: Pointer) = coreFoundation.CFRelease(url)

    private companion object {
        const val NOT_A_DIRECTORY: Byte = 0
    }
}

/**
 * [SoundOutput] on macOS, through the system's own alert sound service. It plays alert-length sounds in the formats it
 * knows, and a sound there cannot be cut short, which is fine for a second or two of alert.
 */
class MacSoundOutput : SoundOutput {
    private val sounds = SystemSounds(MacSystemSoundCalls())

    @Synchronized
    override fun start(path: String, facts: FileFacts, ended: (Any) -> Unit): Any? {
        sounds.play(path, facts)
        return null
    }

    override fun stop(sound: Any) = Unit

    @Synchronized
    override fun dispose() = sounds.disposeAll()
}
