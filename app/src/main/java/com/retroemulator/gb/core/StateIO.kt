package com.retroemulator.gb.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Binary writer for save states. Each component writes a tag first so corrupt data is detected on load. */
class StateWriter {
    private val bos = ByteArrayOutputStream(256 * 1024)
    private val out = DataOutputStream(bos)

    fun tag(name: String) = out.writeInt(name.hashCode())
    fun int(v: Int) = out.writeInt(v)
    fun long(v: Long) = out.writeLong(v)
    fun bool(v: Boolean) = out.writeBoolean(v)
    fun double(v: Double) = out.writeDouble(v)
    fun bytes(b: ByteArray) {
        out.writeInt(b.size)
        out.write(b)
    }
    fun ints(a: IntArray) {
        out.writeInt(a.size)
        for (v in a) out.writeInt(v)
    }
    fun toByteArray(): ByteArray {
        out.flush()
        return bos.toByteArray()
    }
}

class StateReader(data: ByteArray) {
    private val inp = DataInputStream(ByteArrayInputStream(data))

    fun tag(name: String) {
        if (inp.readInt() != name.hashCode()) throw IOException("Save state is corrupt (section $name)")
    }
    fun int(): Int = inp.readInt()
    fun long(): Long = inp.readLong()
    fun bool(): Boolean = inp.readBoolean()
    fun double(): Double = inp.readDouble()
    fun bytesInto(dst: ByteArray) {
        val n = inp.readInt()
        if (n != dst.size) throw IOException("Save state size mismatch ($n != ${dst.size})")
        inp.readFully(dst)
    }
    fun bytes(): ByteArray {
        val n = inp.readInt()
        if (n < 0 || n > 64 * 1024 * 1024) throw IOException("Save state is corrupt")
        return ByteArray(n).also { inp.readFully(it) }
    }
    fun intsInto(dst: IntArray) {
        val n = inp.readInt()
        if (n != dst.size) throw IOException("Save state size mismatch ($n != ${dst.size})")
        for (i in dst.indices) dst[i] = inp.readInt()
    }
}
