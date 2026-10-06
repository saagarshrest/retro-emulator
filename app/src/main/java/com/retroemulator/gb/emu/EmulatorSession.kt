package com.retroemulator.gb.emu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log


/**
 * Runs the emulator on a dedicated thread.
 *
 * With sound on, pacing comes from blocking writes into the AudioTrack, so emulation speed tracks the
 * audio clock exactly and never crackles from drift. With sound off (or fast-forward) frames are paced
 * against System.nanoTime().
 *
 * All access to the [Machine] from other threads must go through [withMachine].
 */
class EmulatorSession(
    initial: Machine,
    private val sampleRate: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the emulator thread with the finished [width] x [height] frame; copy it, don't keep it. */
        fun onFrame(pixels: IntArray, width: Int, height: Int)
        fun onFps(fps: Int) {}
        fun onRumble() {}
        /** Called on the emulator thread when battery-backed RAM should be persisted. */
        fun onBatterySave(data: ByteArray) {}
    }

    private val lock = Object()
    private val pauseLock = Object()
    private var machine: Machine = initial

    /** Currently held buttons (Joypad bit mask). Newly pressed buttons are latched for a few frames. */
    @Volatile var input = 0
        set(value) {
            synchronized(inputLock) { latchedPresses = latchedPresses or (value and field.inv()) }
            field = value
        }
    private val inputLock = Any()
    private var latchedPresses = 0
    private val holdUntil = LongArray(Buttons.COUNT)
    private var frameCount = 0L

    @Volatile var fastForward = false
    @Volatile var fastForwardSpeed = 4
    @Volatile var soundEnabled = true
    @Volatile var volume = 1f
        set(v) {
            field = v
            audio?.setVolume(v)
        }

    @Volatile private var running = false
    @Volatile private var paused = true
    private var thread: Thread? = null
    @Volatile private var audio: AudioTrack? = null
    private var pausedAtMillis = 0L
    private var rtcRemainderMillis = 0L

    fun <T> withMachine(block: (Machine) -> T): T = synchronized(lock) { block(machine) }

    /** Swaps in a new machine (used by reset). */
    fun replaceMachine(next: Machine) = synchronized(lock) { machine = next }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "Emulator").also { it.start() }
    }

    fun resume() {
        synchronized(pauseLock) {
            if (!paused) return
            if (pausedAtMillis > 0) {
                // Time spent paused still passes for the cartridge's real-time clock.
                val elapsed = System.currentTimeMillis() - pausedAtMillis + rtcRemainderMillis
                if (elapsed > 0) {
                    synchronized(lock) { machine.advanceClock(elapsed / 1000) }
                    rtcRemainderMillis = elapsed % 1000
                }
                pausedAtMillis = 0
            }
            paused = false
            pauseLock.notifyAll()
        }
    }

    /** Pauses and waits for the current frame to finish, so callers may then touch the machine safely. */
    fun pause() {
        synchronized(pauseLock) {
            if (paused) return
            paused = true
            pausedAtMillis = System.currentTimeMillis()
        }
        synchronized(lock) {}
    }

    val isPaused: Boolean get() = paused

    fun stop() {
        running = false
        synchronized(pauseLock) { pauseLock.notifyAll() }
        thread?.join(2000)
        thread = null
    }

    /**
     * Held buttons plus any press that started since the last frame, kept down for at least
     * [MIN_PRESS_FRAMES] frames so very short taps are never lost between two joypad polls.
     */
    private fun effectiveInput(): Int {
        val latched = synchronized(inputLock) {
            val l = latchedPresses
            latchedPresses = 0
            l
        }
        frameCount++
        var mask = input
        for (bit in 0 until Buttons.COUNT) {
            if (latched and (1 shl bit) != 0) holdUntil[bit] = frameCount + MIN_PRESS_FRAMES
            if (holdUntil[bit] > frameCount) mask = mask or (1 shl bit)
        }
        return mask
    }

    private fun createAudio(): AudioTrack? = try {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val frameBytes = (sampleRate / 60 + 1) * 4
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, frameBytes * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { it.setVolume(volume) }
    } catch (e: Exception) {
        Log.w(TAG, "Audio unavailable", e)
        null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val track = createAudio()
        audio = track
        val samples = ShortArray(16384)
        val silence = ShortArray(32768)
        var nextFrame = System.nanoTime()
        var fpsFrames = 0
        var fpsStart = System.nanoTime()
        var dirtyFrames = 0
        var trackPlaying = false
        var frameNanos = (1_000_000_000.0 / machine.frameRate).toLong()

        try {
            while (running) {
                if (paused) {
                    if (trackPlaying) {
                        track?.pause()
                        track?.flush()
                        trackPlaying = false
                    }
                    synchronized(pauseLock) {
                        while (paused && running) pauseLock.wait()
                    }
                    nextFrame = System.nanoTime()
                    fpsStart = nextFrame
                    fpsFrames = 0
                    continue
                }

                val ff = fastForward
                val useAudio = soundEnabled && !ff && track != null
                var sampleCount = 0
                var rumble = false
                var batteryData: ByteArray? = null
                val buttons = effectiveInput()
                synchronized(lock) {
                    val m = machine
                    m.setButtons(buttons)
                    m.runFrame()
                    sampleCount = m.drainSamples(samples)
                    listener.onFrame(m.frameBuffer, m.screenWidth, m.screenHeight)
                    rumble = m.consumeRumble()
                    frameNanos = (1_000_000_000.0 / m.frameRate).toLong()
                    if (m.hasBattery && m.batteryDirty) {
                        // Debounce: games write save RAM in bursts.
                        if (++dirtyFrames >= 90) {
                            m.batteryDirty = false
                            batteryData = m.batteryData()
                            dirtyFrames = 0
                        }
                    } else {
                        dirtyFrames = 0
                    }
                }
                if (rumble) listener.onRumble()
                batteryData?.let { listener.onBatterySave(it) }

                if (useAudio) {
                    if (!trackPlaying) {
                        // Pre-fill with silence so blocking writes pace emulation from the first frame;
                        // an empty buffer would otherwise let the game race ahead for a few frames.
                        val spare = track!!.bufferSizeInFrames - 2 * (sampleRate / 60)
                        if (spare > 0) {
                            val n = minOf(spare * 2, silence.size)
                            track.write(silence, 0, n, AudioTrack.WRITE_NON_BLOCKING)
                        }
                        track.play()
                        trackPlaying = true
                    }
                    track!!.write(samples, 0, sampleCount, AudioTrack.WRITE_BLOCKING)
                    nextFrame = System.nanoTime()
                } else {
                    if (trackPlaying) {
                        track?.pause()
                        track?.flush()
                        trackPlaying = false
                    }
                    val speed = if (ff) fastForwardSpeed else 1
                    nextFrame += frameNanos / speed
                    val wait = nextFrame - System.nanoTime()
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    } else if (wait < -100_000_000L) {
                        nextFrame = System.nanoTime() // fell far behind; don't try to catch up
                    }
                }

                fpsFrames++
                val now = System.nanoTime()
                if (now - fpsStart >= 1_000_000_000L) {
                    listener.onFps((fpsFrames * 1_000_000_000L / (now - fpsStart)).toInt())
                    fpsFrames = 0
                    fpsStart = now
                }
            }
        } catch (e: InterruptedException) {
            // shutting down
        } finally {
            track?.let {
                try { it.pause(); it.flush() } catch (_: Exception) {}
                it.release()
            }
            audio = null
        }
    }

    companion object {
        private const val TAG = "EmulatorSession"
        private const val MIN_PRESS_FRAMES = 3

        fun preferredSampleRate(context: Context): Int {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val native = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            return if (native != null && native in 22050..96000) native else 48000
        }
    }
}
