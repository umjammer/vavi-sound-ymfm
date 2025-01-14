// BSD 3-Clause License
//
// Copyright (c) 2021, Aaron Giles
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this
//    list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// 3. Neither the name of the copyright holder nor the names of its
//    contributors may be used to endorse or promote products derived from
//    this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package vavi.sound.ymfm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import vavi.io.LittleEndianDataOutputStream;
import vavi.sound.ymfm.Misc.Ym2149;
import vavi.sound.ymfm.Opl.Ymf278b;
import vavi.sound.ymfm.Opn.Ym2203;
import vavi.sound.ymfm.Opn.Ym2608;
import vavi.sound.ymfm.Opn.Ym2610;
import vavi.sound.ymfm.YmFm.AccessClass;
import vavi.sound.ymfm.YmFm.Interface;
import vavi.sound.ymfm.YmFm.VgmChipBase;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;
import vavi.util.win32.WAVE.data;

import static java.lang.System.getLogger;
import static vavi.sound.ymfm.YmFm.AccessClass.PCM;


public abstract class YmFm {

    private static final Logger logger = getLogger(YmFm.class.getName());

    //*********************************************************
    //  DEBUGGING
    //*********************************************************
    public abstract static class Debug {

        private Debug() {}

        // masks to help isolate specific channels
        public static final int GLOBAL_FM_CHANNEL_MASK = 0xffff_ffff;
        public static final int GLOBAL_ADPCM_A_CHANNEL_MASK = 0xffff_ffff;
        public static final int GLOBAL_ADPCM_B_CHANNEL_MASK = 0xffff_ffff;
        public static final int GLOBAL_PCM_CHANNEL_MASK = 0xffff_ffff;

        // types of logging

        // helpers to write based on the log type
        public static final Logger log_fm_write = getLogger("LOG_FM_WRITES");
        public static final Logger log_keyon = getLogger("LOG_KEYON_EVENTS");
        public static final Logger log_unexpected_read_write = getLogger("LOG_UNEXPECTED_READ_WRITES");
    }

    //*********************************************************
    //  GLOBAL HELPERS
    //*********************************************************

    public interface TriConsumer<T, U, V> {

        void accept(T var1, U var2, V var3);
    }

    /**
     * bitfield - extract a bitfield from the given
     * value, starting at bit 'start' for a length of
     * 'length' bits
     */
    static int bitfield(int value, int start, int length /* = 1 */) {
        return (value >> start) & ((1 << length) - 1);
    }

    static int bitfield(int value, int start) {
        return bitfield(value, start, 1);
    }

    /**
     * clamp - clamp between the minimum and maximum
     * values provided
     */
    static int clamp(int value, int minval, int maxval) {
        if (value < minval)
            return minval;
        if (value > maxval)
            return maxval;
        return value;
    }

    /**
     * count_leading_zeros - return the number of
     * leading zeros in a 32-bit value; CPU-optimized
     * versions for various architectures are included
     * below
     */
    static int count_leading_zeros(int value) {
        if (value == 0)
            return 32;
        return Integer.numberOfLeadingZeros(value);
    }

    // Many of the Yamaha FM chips emit a floating-point value, which is sent to
    // a DAC for processing. The exact format of this floating-point value is
    // documented below. This description only makes sense if the "internal"
    // format treats sign as 1=positive and 0=negative, so the helpers below
    // presume that.
    //
    // Internal OPx data      16-bit signed data     Exp Sign Mantissa
    // =================      =================      === ==== ========
    // 1 1xxxxxxxx------  ->  0 1xxxxxxxx------  ->  111   1  1xxxxxxx
    // 1 01xxxxxxxx-----  ->  0 01xxxxxxxx-----  ->  110   1  1xxxxxxx
    // 1 001xxxxxxxx----  ->  0 001xxxxxxxx----  ->  101   1  1xxxxxxx
    // 1 0001xxxxxxxx---  ->  0 0001xxxxxxxx---  ->  100   1  1xxxxxxx
    // 1 00001xxxxxxxx--  ->  0 00001xxxxxxxx--  ->  011   1  1xxxxxxx
    // 1 000001xxxxxxxx-  ->  0 000001xxxxxxxx-  ->  010   1  1xxxxxxx
    // 1 000000xxxxxxxxx  ->  0 000000xxxxxxxxx  ->  001   1  xxxxxxxx
    // 0 111111xxxxxxxxx  ->  1 111111xxxxxxxxx  ->  001   0  xxxxxxxx
    // 0 111110xxxxxxxx-  ->  1 111110xxxxxxxx-  ->  010   0  0xxxxxxx
    // 0 11110xxxxxxxx--  ->  1 11110xxxxxxxx--  ->  011   0  0xxxxxxx
    // 0 1110xxxxxxxx---  ->  1 1110xxxxxxxx---  ->  100   0  0xxxxxxx
    // 0 110xxxxxxxx----  ->  1 110xxxxxxxx----  ->  101   0  0xxxxxxx
    // 0 10xxxxxxxx-----  ->  1 10xxxxxxxx-----  ->  110   0  0xxxxxxx
    // 0 0xxxxxxxx------  ->  1 0xxxxxxxx------  ->  111   0  0xxxxxxx

    /**
     * encode_fp - given a 32-bit signed input value
     * convert it to a signed 3.10 floating-point
     * value
     */
    static int encode_fp(int value) {
        // handle overflows first
        if (value < -32768)
            return (7 << 10) | 0x000;
        if (value > 32767)
            return (7 << 10) | 0x3ff;

        // we need to count the number of leading sign bits after the sign
        // we can use count_leading_zeros if we invert negative values
        int scanvalue = value ^ (value >> 31);

        // exponent is related to the number of leading bits starting from bit 14
        int exponent = 7 - count_leading_zeros(scanvalue << 17);

        // smallest exponent value allowed is 1
        exponent = Math.max(exponent, 1);

        // mantissa
        int mantissa = value >> (exponent - 1);

        // assemble into final form, inverting the sign
        return ((exponent << 10) | (mantissa & 0x3ff)) ^ 0x200;
    }

    /**
     * decode_fp - given a 3.10 floating-point value,
     * convert it to a signed 16-bit value
     */
    static int decode_fp(int value) {
        // invert the sign and the exponent
        value ^= 0x1e00;

        // shift mantissa up to 16 bits then apply inverted exponent
        return (int) ((value << 6) >> bitfield(value, 10, 3));
    }

    /**
     * roundtrip_fp - compute the result of a round
     * trip through the encode/decode process above
     */
    static int roundtrip_fp(int value) {
        // handle overflows first
        if (value < -32768)
            return -32768;
        if (value > 32767)
            return 32767;

        // we need to count the number of leading sign bits after the sign
        // we can use count_leading_zeros if we invert negative values
        int scanvalue = value ^ (value >> 31);

        // exponent is related to the number of leading bits starting from bit 14
        int exponent = 7 - count_leading_zeros(scanvalue << 17);

        // smallest exponent value allowed is 1
        exponent = Math.max(exponent, 1);

        // apply the shift back and forth to zero out bits that are lost
        exponent -= 1;
        int mask = (1 << exponent) - 1;
        return (int) (value & ~mask);
    }

    //*********************************************************
    // HELPER CLASSES
    //*********************************************************

    public interface Chip {
        YmFm.Output outputFactory();
        int getOutputs();
        int sample_rate(int clock);
        void reset();
        void save(OutputStream os) throws IOException;
        void restore(InputStream is) throws IOException;
        int read(int offset);
        void write(int offset, int data);
        void generate(Output output, int numSamples);
    }

    // various envelope states
    protected enum EnvelopeState {
        EG_DEPRESS,        // OPLL only; set EG_HAS_DEPRESS to enable
        EG_ATTACK,
        EG_DECAY,
        EG_SUSTAIN,
        EG_RELEASE,
        EG_REVERB,        // OPQ/OPZ only; set EG_HAS_REVERB to enable
        EG_STATES
    }

    // external I/O access classes
    public enum AccessClass {
        IO,
        ADPCM_A,
        ADPCM_B,
        PCM,
        CLASSES
    }

    //*********************************************************
    //  HELPER CLASSES
    //*********************************************************

    // ======================> Output

    // struct containing an array of output values
    @Serdes
    public static class Output {

        private final int numOutputs;
        private int pos = 0;

        Output(int numOutputs) {
            this.numOutputs = numOutputs;

            data = new int[numOutputs];
        }

        public int getNumOutputs() {
            return numOutputs;
        }

        // clear all outputs to 0
        Output clear() {
            for (int index = 0; index < numOutputs; index++)
                data[index] = 0;
            return this;
        }

        // clamp all outputs to a 16-bit signed value
        Output clamp16() {
            for (int index = 0; index < numOutputs; index++)
                data[index] = clamp(data[index], -32768, 32767);
            return this;
        }

        // run each output value through the floating-point processor
        Output roundtrip_fp() {
            for (int index = 0; index < numOutputs; index++)
                data[index] = YmFm.roundtrip_fp(data[index]);
            return this;
        }

        void inc() {
            pos++;
        }

        int pos() {
            return pos;
        }

        // internal state
        @Element
        public int[] data;
    }

    // ======================> WavFile

    // this class is a debugging helper that accumulates data and writes it to wav files
    public abstract static class WavFile implements AutoCloseable {

        protected abstract int getChannels();

        // construction
        public WavFile(int samplerate /* = 44100 */) {
            m_samplerate = samplerate;
        }

        // configuration
        public WavFile set_index(int index) {
            m_index = index;
            return this;
        }

        public WavFile set_samplerate(int samplerate) {
            m_samplerate = samplerate;
            return this;
        }

        // destruction
        @Override
        public void close() throws IOException {
            if (!m_buffer.isEmpty()) {
                // create file
                Path name = Path.of("wavlog-%02d.wav".formatted(m_index));
                LittleEndianDataOutputStream ledos = new LittleEndianDataOutputStream(Files.newOutputStream(name));

                // make the wav file header
                ledos.writeChars("RIFF");
                ledos.writeInt(m_buffer.size() * 2 + 44 - 8);
                ledos.writeChars("WAVE");
                ledos.writeChars("fmt ");
                ledos.writeInt(16);
                ledos.writeInt(1);
                ledos.writeInt(getChannels());
                ledos.writeInt(m_samplerate);
                ledos.writeInt(m_samplerate * 2 * getChannels());
                ledos.writeInt(2 * getChannels());
                ledos.writeInt(16);
                ledos.writeChars("data");
                ledos.writeInt(m_buffer.size() * 2 + 44 - 44);

                // write header then data
                for (int b : m_buffer) ledos.writeShort(b);
                ledos.close();
            }
        }

        // add data to the file
        //template<int Outputs>
        public void add(YmFm.Output output) {
            int[] sum = new int[getChannels()];
            for (int index = 0; index < output.getNumOutputs(); index++)
                sum[index % getChannels()] += output.data[index];
            for (int index = 0; index < getChannels(); index++)
                m_buffer.add(sum[index]);
        }

        // add data to the file, using a reference
        //template<int Outputs>
        public void add(YmFm.Output output, final Output ref) {
            int[] sum = new int[getChannels()];
            for (int index = 0; index < output.getNumOutputs(); index++)
                sum[index % getChannels()] += output.data[index] - ref.data[index];
            for (int index = 0; index < getChannels(); index++)
                m_buffer.add(sum[index]);
        }

        // internal state
        private int m_index;
        private int m_samplerate;
        List<Integer> m_buffer;
    }

    //*********************************************************
    //  INTERFACE CLASSES
    //*********************************************************

    // ======================> EngineCallbacks

    // this class represents functions in the engine that the YmFmInterface
    // needs to be able to call; it is represented here as a separate interface
    // that is independent of the actual engine implementation
    public interface EngineCallbacks {

        // timer callback; called by the interface when a timer fires
        void engine_timer_expired(int tnum);

        // check interrupts; called by the interface after synchronization
        void engine_check_interrupts();

        // mode register write; called by the interface after synchronization
        void engine_mode_write(int data);
    }

    // ======================> YmFmInterface

    // this class represents the interface between the fm_engine and the outside
    // world; it provides hooks for timers, synchronization, and I/O
    public abstract static class Interface {
        // the engine is our friend
//		template<typename RegisterType> friend class EngineBase;
//		EngineBase EngineBase;

        // the following functions must be implemented by any derived classes; the
        // default implementations are sufficient for some minimal operation, but will
        // likely need to be overridden to integrate with the outside world; they are
        // all prefixed with ymfm_ to reduce the likelihood of namespace collisions

        //
        // timing and synchronization
        //

        // the chip implementation calls this when a write happens to the mode
        // register, which could affect timers and interrupts; our responsibility
        // is to ensure the system is up to date before calling the engine's
        // engine_mode_write() method
        void ymfm_sync_mode_write(int data) {
            m_engine.engine_mode_write(data);
        }

        // the chip implementation calls this when the chip's status has changed,
        // which may affect the interrupt state; our responsibility is to ensure
        // the system is up to date before calling the engine's
        // engine_check_interrupts() method
        void ymfm_sync_check_interrupts() {
            m_engine.engine_check_interrupts();
        }

        // the chip implementation calls this when one of the two internal timers
        // has changed state; our responsibility is to arrange to call the engine's
        // engine_timer_expired() method after the provided number of clocks; if
        // duration_in_clocks is negative, we should cancel any outstanding timers
        void ymfm_set_timer(int tnum, int duration_in_clocks) {
        }

        // the chip implementation calls this to indicate that the chip should be
        // considered in a busy state until the given number of clocks has passed;
        // our responsibility is to compute and remember the ending time based on
        // the chip's clock for later checking
        void ymfm_set_busy_end(int clocks) {
        }

        // the chip implementation calls this to see if the chip is still currently
        // is a busy state, as specified by a previous call to ymfm_set_busy_end();
        // our responsibility is to compare the current time against the previously
        // noted busy end time and return true if we haven't yet passed it
        boolean ymfm_is_busy() {
            return false;
        }

        //
        // I/O functions
        //

        // the chip implementation calls this when the state of the IRQ signal has
        // changed due to a status change; our responsibility is to respond as
        // needed to the change in IRQ state, signaling any consumers
        void ymfm_update_irq(boolean asserted) {
        }

        // the chip implementation calls this whenever data is read from outside
        // of the chip; our responsibility is to provide the data requested
        int ymfm_external_read(AccessClass type, int address) {
            return 0;
        }

        // the chip implementation calls this whenever data is written outside
        // of the chip; our responsibility is to pass the written data on to any consumers
        void ymfm_external_write(AccessClass type, int address, int data) {
        }

        // pointer to engine callbacks -- this is set directly by the engine at
        // construction time
        EngineCallbacks m_engine;
    }

    // the values here are stored as 4.8 logarithmic values for 1/4 phase
    // this matches the internal format of the OPN chip, extracted from the die
    static final int[] s_sin_table = {
            0x859, 0x6c3, 0x607, 0x58b, 0x52e, 0x4e4, 0x4a6, 0x471, 0x443, 0x41a, 0x3f5, 0x3d3, 0x3b5, 0x398, 0x37e, 0x365,
            0x34e, 0x339, 0x324, 0x311, 0x2ff, 0x2ed, 0x2dc, 0x2cd, 0x2bd, 0x2af, 0x2a0, 0x293, 0x286, 0x279, 0x26d, 0x261,
            0x256, 0x24b, 0x240, 0x236, 0x22c, 0x222, 0x218, 0x20f, 0x206, 0x1fd, 0x1f5, 0x1ec, 0x1e4, 0x1dc, 0x1d4, 0x1cd,
            0x1c5, 0x1be, 0x1b7, 0x1b0, 0x1a9, 0x1a2, 0x19b, 0x195, 0x18f, 0x188, 0x182, 0x17c, 0x177, 0x171, 0x16b, 0x166,
            0x160, 0x15b, 0x155, 0x150, 0x14b, 0x146, 0x141, 0x13c, 0x137, 0x133, 0x12e, 0x129, 0x125, 0x121, 0x11c, 0x118,
            0x114, 0x10f, 0x10b, 0x107, 0x103, 0x0ff, 0x0fb, 0x0f8, 0x0f4, 0x0f0, 0x0ec, 0x0e9, 0x0e5, 0x0e2, 0x0de, 0x0db,
            0x0d7, 0x0d4, 0x0d1, 0x0cd, 0x0ca, 0x0c7, 0x0c4, 0x0c1, 0x0be, 0x0bb, 0x0b8, 0x0b5, 0x0b2, 0x0af, 0x0ac, 0x0a9,
            0x0a7, 0x0a4, 0x0a1, 0x09f, 0x09c, 0x099, 0x097, 0x094, 0x092, 0x08f, 0x08d, 0x08a, 0x088, 0x086, 0x083, 0x081,
            0x07f, 0x07d, 0x07a, 0x078, 0x076, 0x074, 0x072, 0x070, 0x06e, 0x06c, 0x06a, 0x068, 0x066, 0x064, 0x062, 0x060,
            0x05e, 0x05c, 0x05b, 0x059, 0x057, 0x055, 0x053, 0x052, 0x050, 0x04e, 0x04d, 0x04b, 0x04a, 0x048, 0x046, 0x045,
            0x043, 0x042, 0x040, 0x03f, 0x03e, 0x03c, 0x03b, 0x039, 0x038, 0x037, 0x035, 0x034, 0x033, 0x031, 0x030, 0x02f,
            0x02e, 0x02d, 0x02b, 0x02a, 0x029, 0x028, 0x027, 0x026, 0x025, 0x024, 0x023, 0x022, 0x021, 0x020, 0x01f, 0x01e,
            0x01d, 0x01c, 0x01b, 0x01a, 0x019, 0x018, 0x017, 0x017, 0x016, 0x015, 0x014, 0x014, 0x013, 0x012, 0x011, 0x011,
            0x010, 0x00f, 0x00f, 0x00e, 0x00d, 0x00d, 0x00c, 0x00c, 0x00b, 0x00a, 0x00a, 0x009, 0x009, 0x008, 0x008, 0x007,
            0x007, 0x007, 0x006, 0x006, 0x005, 0x005, 0x005, 0x004, 0x004, 0x004, 0x003, 0x003, 0x003, 0x002, 0x002, 0x002,
            0x002, 0x001, 0x001, 0x001, 0x001, 0x001, 0x001, 0x001, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000
    };

    /**
     * abs_sin_attenuation - given a sin (phase) input
     * where the range 0-2*PI is mapped onto 10 bits,
     * return the absolute value of sin(input),
     * logarithmically-adjusted and treated as an
     * attenuation value, in 4.8 fixed point format
     */
    static int abs_sin_attenuation(int input) {
        // if the top bit is set, we're in the second half of the curve
        // which is a mirror image, so invert the index
        if (bitfield(input, 8) != 0)
            input = ~input;

        // return the value from the table
        return s_sin_table[input & 0xff];
    }

    // as a nod to performance, the implicit 0x400 bit is pre-incorporated, and
    // the values are left-shifted by 2 so that a simple right shift is all that
    // is needed; also the order is reversed to save a NOT on the input
    private static int X(int a) {
        return a | 0x400 << 2;
    }

    static final int[] s_power_table = {
            X(0x3fa), X(0x3f5), X(0x3ef), X(0x3ea), X(0x3e4), X(0x3df), X(0x3da), X(0x3d4),
            X(0x3cf), X(0x3c9), X(0x3c4), X(0x3bf), X(0x3b9), X(0x3b4), X(0x3ae), X(0x3a9),
            X(0x3a4), X(0x39f), X(0x399), X(0x394), X(0x38f), X(0x38a), X(0x384), X(0x37f),
            X(0x37a), X(0x375), X(0x370), X(0x36a), X(0x365), X(0x360), X(0x35b), X(0x356),
            X(0x351), X(0x34c), X(0x347), X(0x342), X(0x33d), X(0x338), X(0x333), X(0x32e),
            X(0x329), X(0x324), X(0x31f), X(0x31a), X(0x315), X(0x310), X(0x30b), X(0x306),
            X(0x302), X(0x2fd), X(0x2f8), X(0x2f3), X(0x2ee), X(0x2e9), X(0x2e5), X(0x2e0),
            X(0x2db), X(0x2d6), X(0x2d2), X(0x2cd), X(0x2c8), X(0x2c4), X(0x2bf), X(0x2ba),
            X(0x2b5), X(0x2b1), X(0x2ac), X(0x2a8), X(0x2a3), X(0x29e), X(0x29a), X(0x295),
            X(0x291), X(0x28c), X(0x288), X(0x283), X(0x27f), X(0x27a), X(0x276), X(0x271),
            X(0x26d), X(0x268), X(0x264), X(0x25f), X(0x25b), X(0x257), X(0x252), X(0x24e),
            X(0x249), X(0x245), X(0x241), X(0x23c), X(0x238), X(0x234), X(0x230), X(0x22b),
            X(0x227), X(0x223), X(0x21e), X(0x21a), X(0x216), X(0x212), X(0x20e), X(0x209),
            X(0x205), X(0x201), X(0x1fd), X(0x1f9), X(0x1f5), X(0x1f0), X(0x1ec), X(0x1e8),
            X(0x1e4), X(0x1e0), X(0x1dc), X(0x1d8), X(0x1d4), X(0x1d0), X(0x1cc), X(0x1c8),
            X(0x1c4), X(0x1c0), X(0x1bc), X(0x1b8), X(0x1b4), X(0x1b0), X(0x1ac), X(0x1a8),
            X(0x1a4), X(0x1a0), X(0x19c), X(0x199), X(0x195), X(0x191), X(0x18d), X(0x189),
            X(0x185), X(0x181), X(0x17e), X(0x17a), X(0x176), X(0x172), X(0x16f), X(0x16b),
            X(0x167), X(0x163), X(0x160), X(0x15c), X(0x158), X(0x154), X(0x151), X(0x14d),
            X(0x149), X(0x146), X(0x142), X(0x13e), X(0x13b), X(0x137), X(0x134), X(0x130),
            X(0x12c), X(0x129), X(0x125), X(0x122), X(0x11e), X(0x11b), X(0x117), X(0x114),
            X(0x110), X(0x10c), X(0x109), X(0x106), X(0x102), X(0x0ff), X(0x0fb), X(0x0f8),
            X(0x0f4), X(0x0f1), X(0x0ed), X(0x0ea), X(0x0e7), X(0x0e3), X(0x0e0), X(0x0dc),
            X(0x0d9), X(0x0d6), X(0x0d2), X(0x0cf), X(0x0cc), X(0x0c8), X(0x0c5), X(0x0c2),
            X(0x0be), X(0x0bb), X(0x0b8), X(0x0b5), X(0x0b1), X(0x0ae), X(0x0ab), X(0x0a8),
            X(0x0a4), X(0x0a1), X(0x09e), X(0x09b), X(0x098), X(0x094), X(0x091), X(0x08e),
            X(0x08b), X(0x088), X(0x085), X(0x082), X(0x07e), X(0x07b), X(0x078), X(0x075),
            X(0x072), X(0x06f), X(0x06c), X(0x069), X(0x066), X(0x063), X(0x060), X(0x05d),
            X(0x05a), X(0x057), X(0x054), X(0x051), X(0x04e), X(0x04b), X(0x048), X(0x045),
            X(0x042), X(0x03f), X(0x03c), X(0x039), X(0x036), X(0x033), X(0x030), X(0x02d),
            X(0x02a), X(0x028), X(0x025), X(0x022), X(0x01f), X(0x01c), X(0x019), X(0x016),
            X(0x014), X(0x011), X(0x00e), X(0x00b), X(0x008), X(0x006), X(0x003), X(0x000)
    };

    /**
     * attenuation_to_volume - given a 5.8 fixed point
     * logarithmic attenuation value, return a 13-bit
     * linear volume
     */
    static int attenuation_to_volume(int input) {
        // the values here are 10-bit mantissas with an implied leading bit
        // this matches the internal format of the OPN chip, extracted from the die


        // look up the fractional part, then shift by the whole
        return s_power_table[input & 0xff] >> (input >> 8);
    }

    static final int[] s_increment_table = {
            0x00000000, 0x00000000, 0x10101010, 0x10101010,  // 0-3    (0x00-0x03)
            0x10101010, 0x10101010, 0x11101110, 0x11101110,  // 4-7    (0x04-0x07)
            0x10101010, 0x10111010, 0x11101110, 0x11111110,  // 8-11   (0x08-0x0B)
            0x10101010, 0x10111010, 0x11101110, 0x11111110,  // 12-15  (0x0C-0x0F)
            0x10101010, 0x10111010, 0x11101110, 0x11111110,  // 16-19  (0x10-0x13)
            0x10101010, 0x10111010, 0x11101110, 0x11111110,  // 20-23  (0x14-0x17)
            0x10101010, 0x10111010, 0x11101110, 0x11111110,  // 24-27  (0x18-0x1B)
            0x10101010, 0x10111010, 0x11101110, 0x11111110,  // 28-31  (0x1C-0x1F)
            0x10101010, 0x10111010, 0x11101110, 0x11111110,  // 32-35  (0x20-0x23)
            0x10101010, 0x10111010, 0x11101110, 0x11111110,  // 36-39  (0x24-0x27)
            0x10101010, 0x10111010, 0x11101110, 0x11111110,  // 40-43  (0x28-0x2B)
            0x10101010, 0x10111010, 0x11101110, 0x11111110,  // 44-47  (0x2C-0x2F)
            0x11111111, 0x21112111, 0x21212121, 0x22212221,  // 48-51  (0x30-0x33)
            0x22222222, 0x42224222, 0x42424242, 0x44424442,  // 52-55  (0x34-0x37)
            0x44444444, 0x84448444, 0x84848484, 0x88848884,  // 56-59  (0x38-0x3B)
            0x88888888, 0x88888888, 0x88888888, 0x88888888   // 60-63  (0x3C-0x3F)
    };

    /**
     * attenuation_increment - given a 6-bit ADSR
     * rate value and a 3-bit stepping index,
     * return a 4-bit increment to the attenutaion
     * for this step (or for the attack case, the
     * fractional scale factor to decrease by)
     */
    static int attenuation_increment(int rate, int index) {
        return bitfield(s_increment_table[rate], 4 * index, 4);
    }

    static final int[][] s_detune_adjustment = {
            {0, 0, 1, 2}, {0, 0, 1, 2}, {0, 0, 1, 2}, {0, 0, 1, 2},
            {0, 1, 2, 2}, {0, 1, 2, 3}, {0, 1, 2, 3}, {0, 1, 2, 3},
            {0, 1, 2, 4}, {0, 1, 3, 4}, {0, 1, 3, 4}, {0, 1, 3, 5},
            {0, 2, 4, 5}, {0, 2, 4, 6}, {0, 2, 4, 6}, {0, 2, 5, 7},
            {0, 2, 5, 8}, {0, 3, 6, 8}, {0, 3, 6, 9}, {0, 3, 7, 10},
            {0, 4, 8, 11}, {0, 4, 8, 12}, {0, 4, 9, 13}, {0, 5, 10, 14},
            {0, 5, 11, 16}, {0, 6, 12, 17}, {0, 6, 13, 19}, {0, 7, 14, 20},
            {0, 8, 16, 22}, {0, 8, 16, 22}, {0, 8, 16, 22}, {0, 8, 16, 22}
    };

    /**
     * detune_adjustment - given a 5-bit key code
     * value and a 3-bit detune parameter, return a
     * 6-bit signed phase displacement; this table
     * has been verified against Nuked's equations,
     * but the equations are rather complicated, so
     * we'll keep the simplicity of the table
     */
    static int detune_adjustment(int detune, int keycode) {
        int result = s_detune_adjustment[keycode][detune & 3];
        return bitfield(detune, 2) != 0 ? -result : result;
    }

    static final int[] s_phase_step = {
            41568, 41600, 41632, 41664, 41696, 41728, 41760, 41792, 41856, 41888, 41920, 41952, 42016, 42048, 42080, 42112,
            42176, 42208, 42240, 42272, 42304, 42336, 42368, 42400, 42464, 42496, 42528, 42560, 42624, 42656, 42688, 42720,
            42784, 42816, 42848, 42880, 42912, 42944, 42976, 43008, 43072, 43104, 43136, 43168, 43232, 43264, 43296, 43328,
            43392, 43424, 43456, 43488, 43552, 43584, 43616, 43648, 43712, 43744, 43776, 43808, 43872, 43904, 43936, 43968,
            44032, 44064, 44096, 44128, 44192, 44224, 44256, 44288, 44352, 44384, 44416, 44448, 44512, 44544, 44576, 44608,
            44672, 44704, 44736, 44768, 44832, 44864, 44896, 44928, 44992, 45024, 45056, 45088, 45152, 45184, 45216, 45248,
            45312, 45344, 45376, 45408, 45472, 45504, 45536, 45568, 45632, 45664, 45728, 45760, 45792, 45824, 45888, 45920,
            45984, 46016, 46048, 46080, 46144, 46176, 46208, 46240, 46304, 46336, 46368, 46400, 46464, 46496, 46528, 46560,
            46656, 46688, 46720, 46752, 46816, 46848, 46880, 46912, 46976, 47008, 47072, 47104, 47136, 47168, 47232, 47264,
            47328, 47360, 47392, 47424, 47488, 47520, 47552, 47584, 47648, 47680, 47744, 47776, 47808, 47840, 47904, 47936,
            48032, 48064, 48096, 48128, 48192, 48224, 48288, 48320, 48384, 48416, 48448, 48480, 48544, 48576, 48640, 48672,
            48736, 48768, 48800, 48832, 48896, 48928, 48992, 49024, 49088, 49120, 49152, 49184, 49248, 49280, 49344, 49376,
            49440, 49472, 49504, 49536, 49600, 49632, 49696, 49728, 49792, 49824, 49856, 49888, 49952, 49984, 50048, 50080,
            50144, 50176, 50208, 50240, 50304, 50336, 50400, 50432, 50496, 50528, 50560, 50592, 50656, 50688, 50752, 50784,
            50880, 50912, 50944, 50976, 51040, 51072, 51136, 51168, 51232, 51264, 51328, 51360, 51424, 51456, 51488, 51520,
            51616, 51648, 51680, 51712, 51776, 51808, 51872, 51904, 51968, 52000, 52064, 52096, 52160, 52192, 52224, 52256,
            52384, 52416, 52448, 52480, 52544, 52576, 52640, 52672, 52736, 52768, 52832, 52864, 52928, 52960, 52992, 53024,
            53120, 53152, 53216, 53248, 53312, 53344, 53408, 53440, 53504, 53536, 53600, 53632, 53696, 53728, 53792, 53824,
            53920, 53952, 54016, 54048, 54112, 54144, 54208, 54240, 54304, 54336, 54400, 54432, 54496, 54528, 54592, 54624,
            54688, 54720, 54784, 54816, 54880, 54912, 54976, 55008, 55072, 55104, 55168, 55200, 55264, 55296, 55360, 55392,
            55488, 55520, 55584, 55616, 55680, 55712, 55776, 55808, 55872, 55936, 55968, 56032, 56064, 56128, 56160, 56224,
            56288, 56320, 56384, 56416, 56480, 56512, 56576, 56608, 56672, 56736, 56768, 56832, 56864, 56928, 56960, 57024,
            57120, 57152, 57216, 57248, 57312, 57376, 57408, 57472, 57536, 57568, 57632, 57664, 57728, 57792, 57824, 57888,
            57952, 57984, 58048, 58080, 58144, 58208, 58240, 58304, 58368, 58400, 58464, 58496, 58560, 58624, 58656, 58720,
            58784, 58816, 58880, 58912, 58976, 59040, 59072, 59136, 59200, 59232, 59296, 59328, 59392, 59456, 59488, 59552,
            59648, 59680, 59744, 59776, 59840, 59904, 59936, 60000, 60064, 60128, 60160, 60224, 60288, 60320, 60384, 60416,
            60512, 60544, 60608, 60640, 60704, 60768, 60800, 60864, 60928, 60992, 61024, 61088, 61152, 61184, 61248, 61280,
            61376, 61408, 61472, 61536, 61600, 61632, 61696, 61760, 61824, 61856, 61920, 61984, 62048, 62080, 62144, 62208,
            62272, 62304, 62368, 62432, 62496, 62528, 62592, 62656, 62720, 62752, 62816, 62880, 62944, 62976, 63040, 63104,
            63200, 63232, 63296, 63360, 63424, 63456, 63520, 63584, 63648, 63680, 63744, 63808, 63872, 63904, 63968, 64032,
            64096, 64128, 64192, 64256, 64320, 64352, 64416, 64480, 64544, 64608, 64672, 64704, 64768, 64832, 64896, 64928,
            65024, 65056, 65120, 65184, 65248, 65312, 65376, 65408, 65504, 65536, 65600, 65664, 65728, 65792, 65856, 65888,
            65984, 66016, 66080, 66144, 66208, 66272, 66336, 66368, 66464, 66496, 66560, 66624, 66688, 66752, 66816, 66848,
            66944, 66976, 67040, 67104, 67168, 67232, 67296, 67328, 67424, 67456, 67520, 67584, 67648, 67712, 67776, 67808,
            67904, 67936, 68000, 68064, 68128, 68192, 68256, 68288, 68384, 68448, 68512, 68544, 68640, 68672, 68736, 68800,
            68896, 68928, 68992, 69056, 69120, 69184, 69248, 69280, 69376, 69440, 69504, 69536, 69632, 69664, 69728, 69792,
            69920, 69952, 70016, 70080, 70144, 70208, 70272, 70304, 70400, 70464, 70528, 70560, 70656, 70688, 70752, 70816,
            70912, 70976, 71040, 71104, 71136, 71232, 71264, 71360, 71424, 71488, 71552, 71616, 71648, 71744, 71776, 71872,
            71968, 72032, 72096, 72160, 72192, 72288, 72320, 72416, 72480, 72544, 72608, 72672, 72704, 72800, 72832, 72928,
            72992, 73056, 73120, 73184, 73216, 73312, 73344, 73440, 73504, 73568, 73632, 73696, 73728, 73824, 73856, 73952,
            74080, 74144, 74208, 74272, 74304, 74400, 74432, 74528, 74592, 74656, 74720, 74784, 74816, 74912, 74944, 75040,
            75136, 75200, 75264, 75328, 75360, 75456, 75488, 75584, 75648, 75712, 75776, 75840, 75872, 75968, 76000, 76096,
            76224, 76288, 76352, 76416, 76448, 76544, 76576, 76672, 76736, 76800, 76864, 76928, 77024, 77120, 77152, 77248,
            77344, 77408, 77472, 77536, 77568, 77664, 77696, 77792, 77856, 77920, 77984, 78048, 78144, 78240, 78272, 78368,
            78464, 78528, 78592, 78656, 78688, 78784, 78816, 78912, 78976, 79040, 79104, 79168, 79264, 79360, 79392, 79488,
            79616, 79680, 79744, 79808, 79840, 79936, 79968, 80064, 80128, 80192, 80256, 80320, 80416, 80512, 80544, 80640,
            80768, 80832, 80896, 80960, 80992, 81088, 81120, 81216, 81280, 81344, 81408, 81472, 81568, 81664, 81696, 81792,
            81952, 82016, 82080, 82144, 82176, 82272, 82304, 82400, 82464, 82528, 82592, 82656, 82752, 82848, 82880, 82976
    };

    /**
     * opm_key_code_to_phase_step - converts an
     * OPM concatenated block (3 bits), keycode
     * (4 bits) and key fraction (6 bits) to a 0.10
     * phase step, after applying the given delta;
     * this applies to OPM and OPZ, so it lives here
     * in a central location
     */
    static int opm_key_code_to_phase_step(int block_freq, int delta) {
        // The phase step is essentially the fnum in OPN-speak. To compute this table,
        // we used the standard formula for computing the frequency of a note, and
        // then converted that frequency to fnum using the formula documented in the
        // YM2608 manual.
        //
        // However, the YM2608 manual describes everything in terms of a nominal 8MHz
        // clock, which produces an FM clock of:
        //
        //    8000000 / 24(operators) / 6(prescale) = 55555Hz FM clock
        //
        // Whereas the descriptions for the YM2151 use a nominal 3.579545MHz clock:
        //
        //    3579545 / 32(operators) / 2(prescale) = 55930Hz FM clock
        //
        // To correct for this, the YM2608 formula was adjusted to use a clock of
        // 8053920Hz, giving this equation for the fnum:
        //
        //    fnum = (double(144) * freq * (1 << 20)) / double(8053920) / 4;
        //
        // Unfortunately, the computed table differs in a few spots from the data
        // verified from an actual chip. The table below comes from David Viens'
        // analysis, used with his permission.

        // extract the block (octave) first
        int block = bitfield(block_freq, 10, 3);

        // the keycode (bits 6-9) is "gappy", mapping 12 values over 16 in each
        // octave; to correct for this, we multiply the 4-bit value by 3/4 (or
        // rather subtract 1/4); note that a (invalid) value of 15 will bleed into
        // the next octave -- this is confirmed
        int adjusted_code = bitfield(block_freq, 6, 4) - bitfield(block_freq, 8, 2);

        // now re-insert the 6-bit fraction
        int eff_freq = (adjusted_code << 6) | bitfield(block_freq, 0, 6);

        // now that the gaps are removed, add the delta
        eff_freq += delta;

        // handle over/underflow by adjusting the block:
        if (eff_freq >= 768) {
            // minimum delta is -512 (PM), so we can only underflow by 1 octave
            if (eff_freq < 0) {
                eff_freq += 768;
                if (block-- == 0)
                    return s_phase_step[0] >> 7;
            }

            // maximum delta is +512+608 (PM+detune), so we can overflow by up to 2 octaves
            else {
                eff_freq -= 768;
                if (eff_freq >= 768) {
                    block++;
                    eff_freq -= 768;
                }
                if (block++ >= 7)
                    return s_phase_step[767];
            }
        }

        // look up the phase shift for the key code, then shift by octave
        return s_phase_step[eff_freq] >> (block ^ 7);
    }

    static final byte[][] s_lfo_pm_shifts = {
            {0x77, 0x77, 0x77, 0x77, 0x77, 0x77, 0x77, 0x77},
            {0x77, 0x77, 0x77, 0x77, 0x72, 0x72, 0x72, 0x72},
            {0x77, 0x77, 0x77, 0x72, 0x72, 0x72, 0x17, 0x17},
            {0x77, 0x77, 0x72, 0x72, 0x17, 0x17, 0x12, 0x12},
            {0x77, 0x77, 0x72, 0x17, 0x17, 0x17, 0x12, 0x07},
            {0x77, 0x77, 0x17, 0x12, 0x07, 0x07, 0x02, 0x01},
            {0x77, 0x77, 0x17, 0x12, 0x07, 0x07, 0x02, 0x01},
            {0x77, 0x77, 0x17, 0x12, 0x07, 0x07, 0x02, 0x01}
    };

    /**
     * opn_lfo_pm_phase_adjustment - given the 7 most
     * significant frequency number bits, plus a 3-bit
     * PM depth value and a signed 5-bit raw PM value,
     * return a signed PM adjustment to the frequency;
     * algorithm written to match Nuked behavior
     */
    static int opn_lfo_pm_phase_adjustment(int fnum_bits, int pm_sensitivity, int lfo_raw_pm) {
        // this table encodes 2 shift values to apply to the top 7 bits
        // of fnum; it is effectively a cheap multiply by a constant
        // value containing 0-2 bits
        // look up the relevant shifts
        int abs_pm = (lfo_raw_pm < 0) ? -lfo_raw_pm : lfo_raw_pm;
        final int shifts = s_lfo_pm_shifts[pm_sensitivity][bitfield(abs_pm, 0, 3)];

        // compute the adjustment
        int adjust = (fnum_bits >> bitfield(shifts, 0, 4)) + (fnum_bits >> bitfield(shifts, 4, 4));
        if (pm_sensitivity > 5)
            adjust <<= pm_sensitivity - 5;
        adjust >>= 2;

        // every 16 cycles it inverts sign
        return (lfo_raw_pm < 0) ? -adjust : adjust;
    }

    // --- practical use

    // run this many dummy clocks of each chip before generating
    static int EXTRA_CLOCKS = 0;

    /**
     * abstract base class for a Yamaha chip; we keep a list of these for processing
     * as new commands come in
     */
    protected abstract static class VgmChipBase extends YmFm.Interface {

        /** construction */
        protected VgmChipBase(int clock, Class<? extends YmFm.Chip> type) {
            m_type = type;
        }

        /** simple getters */
        public final Class<? extends YmFm.Chip> type() {
            return m_type;
        }

        public final void setName(String name) {
            m_name = name;
        }

        public abstract int sample_rate();

        /** required methods for derived classes to implement */
        public abstract void write(int reg, int data);

        public abstract void generate(long output_start, long output_step, int[] buffer);

        /** write data to the ADPCM-A buffer */
        public void write_data(AccessClass type, int base, int length, byte[] src, int offset) {
            int end = base + length;
            if (m_data[type.ordinal()] == null) {
                m_data[type.ordinal()] = new Output(end);
            } else if (end > m_data[type.ordinal()].data.length) {
                int[] newData = new int[end];
                for (int i = 0; i < m_data[type.ordinal()].data.length; i++)
                    newData[i] = m_data[type.ordinal()].data[i];
                m_data[type.ordinal()].data = newData;
            }
logger.log(Level.DEBUG, "%s: d:%d <- s:%d, %d".formatted(type, base, offset, length));
            for (int i = 0; i < src.length; i++)
                m_data[type.ordinal()].data[base + i] = src[i + offset] & 0xff;
        }

        // seek within the PCM stream
        public void seek_pcm(int pos) {
            m_pcm_offset = pos;
        }

        public int read_pcm() {
            var pcm = m_data[PCM.ordinal()];
            return (m_pcm_offset < pcm.data.length) ? pcm.data[m_pcm_offset++] : 0;
        }

        // internal state
        protected Class<? extends YmFm.Chip> m_type;
        protected String m_name;
        protected YmFm.Output[] m_data = new YmFm.Output[AccessClass.values().length];
        protected int m_pcm_offset;
    }

    /**
     * actual chip-specific implementation class; includes implementation of the
     * YmFmInterface as needed for vgmplay purposes
     */
    public static class VgmChip extends VgmChipBase {

        // construction
        public VgmChip(int clock, Class<? extends YmFm.Chip> c) {
            super(clock, c);
            try {
                m_chip = c.getDeclaredConstructor(Interface.class).newInstance(this);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            m_clock = clock;
            m_clocks = 0;
            m_step = 0x1_0000_0000L / m_chip.sample_rate(clock);
            m_pos = 0;
            m_output = m_chip.outputFactory();

            m_chip.reset();

            for (int clock_ = 0; clock_ < EXTRA_CLOCKS; clock_++)
                m_chip.generate(m_output, 1);
        }

        /** */
        public final void reset() {
            m_chip.reset();
        }

        @Override
        public final int sample_rate() {
            return m_chip.sample_rate(m_clock);
        }

        /** Handles a register write: just queue for now */
        @Override
        public void write(int reg, int data) {
            m_queue.add(new int[] {reg, data});
        }

        /** Generates one output sample of output */
        @Override
        public void generate(long output_start, long output_step, int[] buffer) {
            int addr1 = 0xffff, addr2 = 0xffff;
            int data1 = 0, data2 = 0;

            // see if there is data to be written; if so, extract it and dequeue
            if (!m_queue.isEmpty()) {
                var front = m_queue.get(0);
                addr1 = 0 + 2 * ((front[0] >> 8) & 3);
                data1 = front[0] & 0xff;
                addr2 = addr1 + ((m_type == Ym2149.class) ? 2 : 1);
                data2 = front[1];
                m_queue.remove(m_queue.get(0));
            }

            // write to the chip
            if (addr1 != 0xffff) {
                logger.log(Level.TRACE, "%10.5f: %s %03X=%02X".formatted((double) output_start / (double) (1L << 32), m_name, data1 + 0x100 * (addr1 / 2), data2));
                m_chip.write(addr1, data1);
                m_chip.write(addr2, data2);
            }

            // generate at the appropriate sample rate
            for (; m_pos <= output_start; m_pos += m_step) {
                m_chip.generate(m_output, 1);
            }

            int OUTPUTS = m_chip.getOutputs();
//logger.log(Level.DEBUG, m_type + ", " + OUTPUTS + ", " + m_output.data.length);
            int p = 0; // buffer
            // add the final result to the buffer
            if (m_type == Ym2203.class) {
                int out0 = m_output.data[0];
                int out1 = m_output.data[1 % OUTPUTS];
                int out2 = m_output.data[2 % OUTPUTS];
                int out3 = m_output.data[3 % OUTPUTS];
                buffer[p++] += out0 + out1 + out2 + out3;
                buffer[p++] += out0 + out1 + out2 + out3;
            } else if (m_type == Ym2608.class || m_type == Ym2610.class) {
                int out0 = m_output.data[0];
                int out1 = m_output.data[1 % OUTPUTS];
                int out2 = m_output.data[2 % OUTPUTS];
                buffer[p++] += out0 + out2;
                buffer[p++] += out1 + out2;
            } else if (m_type == Ymf278b.class) {
                buffer[p++] += m_output.data[4 % OUTPUTS];
                buffer[p++] += m_output.data[5 % OUTPUTS];
            } else if (OUTPUTS == 1) {
                buffer[p++] += m_output.data[0];
                buffer[p++] += m_output.data[0];
            } else {
                buffer[p++] += m_output.data[0];
                buffer[p++] += m_output.data[1 % OUTPUTS];
            }
            m_clocks++;
        }

        /** Handles a read from the buffer */
        public int ymfm_external_read(AccessClass type, int offset) {
            var data = m_data[type.ordinal()];
            return (offset < data.data.length) ? data.data[offset] : 0;
        }

        // internal state
        protected YmFm.Chip m_chip;
        protected int m_clock;
        protected long m_clocks;
        protected YmFm.Output m_output;
        long m_step;
        long m_pos;
        protected List<int[]> m_queue = new ArrayList<>();
    }
}
