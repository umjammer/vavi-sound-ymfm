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
import java.util.Arrays;

import vavi.sound.ymfm.YmFm.Debug;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static java.lang.System.getLogger;
import static vavi.sound.ymfm.YmFm.AccessClass.ADPCM_A;
import static vavi.sound.ymfm.YmFm.AccessClass.ADPCM_B;
import static vavi.sound.ymfm.YmFm.Debug.log_keyon;
import static vavi.sound.ymfm.YmFm.bitfield;
import static vavi.sound.ymfm.YmFm.clamp;


abstract class Adpcm {

    private static final Logger logger = getLogger(Adpcm.class.getName());

    private Adpcm() {
    }

    //
    // INTERFACE CLASSES
    //

    //
    // ADPCM "A" REGISTERS
    //

    //
    // ADPCM-A register map:
    //
    //      System-wide registers:
    //           00 x------- Dump (disable=1) or keyon (0) control
    //              --xxxxxx Mask of channels to dump or keyon
    //           01 --xxxxxx Total level
    //           02 xxxxxxxx Test register
    //        08-0D x------- Pan left
    //              -x------ Pan right
    //              ---xxxxx Instrument level
    //        10-15 xxxxxxxx Start address (low)
    //        18-1D xxxxxxxx Start address (high)
    //        20-25 xxxxxxxx End address (low)
    //        28-2D xxxxxxxx End address (high)
    //

    /** RegistersA */
    @Serdes
    protected static class RegistersA {

        // constants
        protected static final int OUTPUTS = 2;
        protected static final int CHANNELS = 6;
        protected static final int REGISTERS = 0x30;
        protected static final int ALL_CHANNELS = (1 << CHANNELS) - 1;

        /** Constructor */
        RegistersA() {
        }

        /**
         * Resets the register state.
         */
        public void reset() {
            Arrays.fill(m_regdata, 0, REGISTERS, 0);

            // initialize the pans to on by default, and max instrument volume;
            // some neogeo homebrews (for example ffeast) rely on this
            m_regdata[0x08] = m_regdata[0x09] = m_regdata[0x0a] =
                    m_regdata[0x0b] = m_regdata[0x0c] = m_regdata[0x0d] = (byte) 0xdf;
        }

        /**
         * Saves the data.
         */
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
        }

        /**
         * Restores the data.
         */
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
        }

        /** map channel number to register offset */
        public static int channel_offset(int chNum) {
            assert (chNum < CHANNELS);
            return chNum;
        }

        // direct read/write access

        public void write(int index, int data) {
            m_regdata[index] = data;
        }

        // system-wide registers

        public final int dump() {
            return bitfield(m_regdata[0x00], 7);
        }

        public final int dump_mask() {
            return bitfield(m_regdata[0x00], 0, 6);
        }

        public final int total_level() {
            return bitfield(m_regdata[0x01], 0, 6);
        }

        public final int test() {
            return m_regdata[0x02];
        }

        // per-channel registers

        public final int ch_pan_left(int chOffs) {
            return bitfield(m_regdata[chOffs + 0x08], 7);
        }

        public final int ch_pan_right(int chOffs) {
            return bitfield(m_regdata[chOffs + 0x08], 6);
        }

        public final int ch_instrument_level(int chOffs) {
            return bitfield(m_regdata[chOffs + 0x08], 0, 5);
        }

        public final int ch_start(int chOffs) {
            return m_regdata[chOffs + 0x10] | (m_regdata[chOffs + 0x18] << 8);
        }

        public final int ch_end(int chOffs) {
            return m_regdata[chOffs + 0x20] | (m_regdata[chOffs + 0x28] << 8);
        }

        // per-channel writes

        public void write_start(int chOffs, int address) {
            write(chOffs + 0x10, address & 0xff);
            write(chOffs + 0x18, (address & 0xff00) >> 8);
        }

        public void write_end(int chOffs, int address) {
            write(chOffs + 0x20, address & 0xff);
            write(chOffs + 0x28, (address & 0xff00) >> 8);
        }

        // internal state

        /** register data */
        @Element(sequence = 1)
        private final int[] m_regdata = new int[REGISTERS];
    }

    //
    // ADPCM "A" CHANNEL
    //

    /** ChannelA */
    @Serdes
    static class ChannelA {

        /**
         * Constructor.
         */
        public ChannelA(Adpcm.EngineA owner, int chOffs, int addrShift) {
            m_chOffs = chOffs;
            m_address_shift = addrShift;
            m_playing = false;
            m_curNibble = 0;
            m_curByte = 0;
            m_curAddress = 0;
            m_accumulator = 0;
            m_step_index = 0;
            m_regs = owner.regs();
            m_owner = owner;
        }

        /**
         * Resets the channel state.
         */
        public void reset() {
            m_playing = false;
            m_curNibble = 0;
            m_curByte = 0;
            m_curAddress = 0;
            m_accumulator = 0;
            m_step_index = 0;
        }

        /**
         * Saves the data.
         */
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
        }

        /**
         * Restores the data.
         */
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
        }

        /**
         * Signals key on/off.
         */
        public void keyOnOff(boolean on) {
            // QUESTION: repeated key ons restart the sample?
            m_playing = on;
            if (m_playing) {
                m_curAddress = m_regs.ch_start(m_chOffs) << m_address_shift;
logger.log(Level.DEBUG, "adpcmA: %d".formatted(m_curAddress));
                m_curNibble = 0;
                m_curByte = 0;
                m_accumulator = 0;
                m_step_index = 0;

                // don't log masked channels
                if (((Debug.GLOBAL_ADPCM_A_CHANNEL_MASK >> m_chOffs) & 1) != 0)
                    log_keyon.log(Level.DEBUG, "KeyOn ADPCM-A%d: pan=%d%d start=%04X end=%04X level=%02X".formatted(
                            m_chOffs,
                            m_regs.ch_pan_left(m_chOffs),
                            m_regs.ch_pan_right(m_chOffs),
                            m_regs.ch_start(m_chOffs),
                            m_regs.ch_end(m_chOffs),
                            m_regs.ch_instrument_level(m_chOffs)));
            }
        }

        private static final int[] s_steps = {
                16, 17, 19, 21, 23, 25, 28,
                31, 34, 37, 41, 45, 50, 55,
                60, 66, 73, 80, 88, 97, 107,
                118, 130, 143, 157, 173, 190, 209,
                230, 253, 279, 307, 337, 371, 408,
                449, 494, 544, 598, 658, 724, 796,
                876, 963, 1060, 1166, 1282, 1411, 1552
        };

        private static final int[] s_step_inc = {-1, -1, -1, -1, 2, 5, 7, 9};

        /**
         * Masters clocking function.
         */
        public boolean clock() {
            // if not playing, just output 0
            if (!m_playing) {
                m_accumulator = 0;
                return false;
            }

            // if we're about to read nibble 0, fetch the data
            int data;
            if (m_curNibble == 0) {
                // stop when we hit the end address; apparently only low 20 bits are used for
                // comparison on the YM2610: this affects sample playback in some games, for
                // example twinspri character select screen music will skip some samples if
                // this is not correct
                //
                // note also: end address is inclusive, so wait until we are about to fetch
                // the sample just after the end before stopping; this is needed for nitd's
                // jump sound, for example
                int end = (m_regs.ch_end(m_chOffs) + 1) << m_address_shift;
                if (((m_curAddress ^ end) & 0xf_ffff) == 0) {
                    m_playing = false;
                    m_accumulator = 0;
                    return true;
                }

                m_curByte = m_owner.intf().ymfm_external_read(ADPCM_A, m_curAddress++);
                data = m_curByte >> 4;
                m_curNibble = 1;
            }

            // otherwise just extract from the previosuly-fetched byte
            else {
                data = m_curByte & 0xf;
                m_curNibble = 0;
            }

            // compute the ADPCM delta
            int delta = (2 * bitfield(data, 0, 3) + 1) * s_steps[m_step_index] / 8;
            if (bitfield(data, 3) != 0)
                delta = -delta;

            // the 12-bit accumulator wraps on the ym2610 and ym2608 (like the msm5205)
            m_accumulator = (m_accumulator + delta) & 0xfff;

            // adjust ADPCM step
            m_step_index = clamp(m_step_index + s_step_inc[bitfield(data, 0, 3)], 0, 48);

            return false;
        }

        /**
         * Returns the computed output value, with panning applied
         */
        //template<int NumOutputs>
        public final void output(YmFm.Output output) {
            // volume combines instrument and total levels
            int vol = (m_regs.ch_instrument_level(m_chOffs) ^ 0x1f) + (m_regs.total_level() ^ 0x3f);

            // if combined is maximum, don't add to outputs
            if (vol >= 63)
                return;

            // convert into a shift and a multiplier
            // QUESTION: verify this from other sources
            int mul = 15 - (vol & 7);
            int shift = 4 + 1 + (vol >> 3);

            // m_accumulator is a 12-bit value; shift up to sign-extend;
            // the downshift is incorporated into 'shift'
            int value = ((((short) (m_accumulator << 4)) * mul) >> shift) & ~3;

            // apply to left/right as appropriate
            if (output.getNumOutputs() == 1 || m_regs.ch_pan_left(m_chOffs) != 0)
                output.data[0] += value;
            if (output.getNumOutputs() > 1 && m_regs.ch_pan_right(m_chOffs) != 0)
                output.data[1] += value;
        }

        // internal state

        /** channel offset */
        private final int m_chOffs;
        /** address bits shift-left */
        private final int m_address_shift;
        /** currently playing? */
        @Element(sequence = 1)
        private boolean m_playing;
        /** index of the current nibble */
        @Element(sequence = 2)
        private int m_curNibble;
        /** current byte of data */
        @Element(sequence = 3)
        private int m_curByte;
        /** current address */
        @Element(sequence = 4)
        private int m_curAddress;
        /** accumulator */
        @Element(sequence = 5)
        private int m_accumulator;
        /** index in the stepping table */
        @Element(sequence = 6)
        private int m_step_index;
        /** reference to registers */
        private final Adpcm.RegistersA m_regs;
        /** reference to our owner */
        private final Adpcm.EngineA m_owner;
    }

    //
    // ADPCM "A" ENGINE
    //

    /** EngineA */
    protected static class EngineA {

        protected static final int CHANNELS = RegistersA.CHANNELS;

        /**
         * Constructor.
         */
        public EngineA(YmFm.Interface intf, int addrShift) {
            m_intf = intf;

            // create the channels
            for (int chNum = 0; chNum < CHANNELS; chNum++)
                m_channel[chNum] = new ChannelA(this, chNum, addrShift);
        }

        /**
         * Resets the engine state.
         */
        public void reset() {
            // reset register state
            m_regs.reset();

            // reset each channel
            for (var chan : m_channel)
                chan.reset();
        }

        /**
         * Saves the data.
         */
        public void save(OutputStream os) throws IOException {
            // save register state
            m_regs.save(os);

            // save channel state
            for (int chNum = 0; chNum < CHANNELS; chNum++)
                m_channel[chNum].save(os);
        }

        /**
         * Restores the data.
         */
        public void restore(InputStream is) throws IOException {
            // save register state
            m_regs.restore(is);

            // save channel state
            for (int chNum = 0; chNum < CHANNELS; chNum++)
                m_channel[chNum].restore(is);
        }

        /**
         * Master clocking function.
         */
        public int clock(int chanMask) {
            // clock each channel, setting a bit in result if it finished
            int result = 0;
            for (int chNum = 0; chNum < CHANNELS; chNum++)
                if (bitfield(chanMask, chNum) != 0)
                    if (m_channel[chNum].clock())
                        result |= 1 << chNum;

            // return the bitmask of completed samples
            return result;
        }

        /**
         * Master update function.
         */
        //template<int NumOutputs>
        public void output(YmFm.Output output, int chanMask) {
            // mask out some channels for debug purposes
            chanMask &= Debug.GLOBAL_ADPCM_A_CHANNEL_MASK;

            // compute the output of each channel
            for (int chNum = 0; chNum < CHANNELS; chNum++)
                if (bitfield(chanMask, chNum) != 0)
                    m_channel[chNum].output(output);
        }

        //template void EngineA.output<1>(Output<1> output, int chanMask);
        //template void EngineA.output<2>(Output<2> output, int chanMask);

        /**
         * Handles writes to the ADPCM-A registers.
         */
        public void write(int regNum, int data) {
            // store the raw value to the register array;
            // most writes are passive, consumed only when needed
            m_regs.write(regNum, data);

            // actively handle writes to the control register
            if (regNum == 0x00)
                for (int chNum = 0; chNum < CHANNELS; chNum++)
                    if (bitfield(data, chNum) != 0)
                        m_channel[chNum].keyOnOff(bitfield(~data, 7) != 0);
        }

        /** set the start/end address for a channel (for hardcoded YM2608 percussion) */
        public void set_start_end(int chNum, int start, int end) {
            int chOffs = RegistersA.channel_offset(chNum);
            m_regs.write_start(chOffs, start);
            m_regs.write_end(chOffs, end);
        }

        /** return a reference to our interface */
        public YmFm.Interface intf() {
            return m_intf;
        }

        /** return a reference to our registers */
        public Adpcm.RegistersA regs() {
            return m_regs;
        }

        // internal state

        /** reference to the interface */
        private final YmFm.Interface m_intf;
        /** array of channels */
        private final Adpcm.ChannelA[] m_channel = new Adpcm.ChannelA[CHANNELS];
        /** registers */
        private final Adpcm.RegistersA m_regs = new Adpcm.RegistersA();
    }

    //
    // ADPCM "B" REGISTERS
    //

    //
    // ADPCM-B register map:
    //
    //      System-wide registers:
    //           00 x------- Start of synthesis/analysis
    //              -x------ Record
    //              --x----- External/manual driving
    //              ---x---- Repeat playback
    //              ----x--- Speaker off
    //              -------x Reset
    //           01 x------- Pan left
    //              -x------ Pan right
    //              ----x--- Start conversion
    //              -----x-- DAC enable
    //              ------x- DRAM access (1=8-bit granularity; 0=1-bit)
    //              -------x RAM/ROM (1=ROM, 0=RAM)
    //           02 xxxxxxxx Start address (low)
    //           03 xxxxxxxx Start address (high)
    //           04 xxxxxxxx End address (low)
    //           05 xxxxxxxx End address (high)
    //           06 xxxxxxxx Prescale value (low)
    //           07 -----xxx Prescale value (high)
    //           08 xxxxxxxx CPU data/buffer
    //           09 xxxxxxxx Delta-N frequency scale (low)
    //           0a xxxxxxxx Delta-N frequency scale (high)
    //           0b xxxxxxxx Level control
    //           0c xxxxxxxx Limit address (low)
    //           0d xxxxxxxx Limit address (high)
    //           0e xxxxxxxx DAC data [YM2608/10]
    //           0f xxxxxxxx PCM data [YM2608/10]
    //           0e xxxxxxxx DAC data high [Y8950]
    //           0f xx------ DAC data low [Y8950]
    //           10 -----xxx DAC data exponent [Y8950]
    //

    /** RegistersB */
    @Serdes
    protected static class RegistersB {

        // constants

        protected static final int REGISTERS = 0x11;

        /** Constructor. */
        public RegistersB() {
        }

        /**
         * Resets the register state.
         */
        public void reset() {
            Arrays.fill(m_regData, 0, REGISTERS, 0);

            // default limit to wide open
            m_regData[0x0c] = m_regData[0x0d] = 0xff;
        }

        /**
         * Saves the data.
         */
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
        }

        /**
         * Restores the data.
         */
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
        }

        // direct read/write access

        public void write(int index, int data) {
            m_regData[index] = data;
        }

        // system-wide registers

        public final int execute() {
            return bitfield(m_regData[0x00], 7);
        }

        public final int record() {
            return bitfield(m_regData[0x00], 6);
        }

        public final int external() {
            return bitfield(m_regData[0x00], 5);
        }

        public final int repeat() {
            return bitfield(m_regData[0x00], 4);
        }

        public final int speaker() {
            return bitfield(m_regData[0x00], 3);
        }

        public final int resetflag() {
            return bitfield(m_regData[0x00], 0);
        }

        public final int pan_left() {
            return bitfield(m_regData[0x01], 7);
        }

        public final int pan_right() {
            return bitfield(m_regData[0x01], 6);
        }

        public final int start_conversion() {
            return bitfield(m_regData[0x01], 3);
        }

        public final int dac_enable() {
            return bitfield(m_regData[0x01], 2);
        }

        public final int dram_8bit() {
            return bitfield(m_regData[0x01], 1);
        }

        public final int rom_ram() {
            return bitfield(m_regData[0x01], 0);
        }

        public final int start() {
            return m_regData[0x02] | (m_regData[0x03] << 8);
        }

        public final int end() {
            return m_regData[0x04] | (m_regData[0x05] << 8);
        }

        public final int prescale() {
            return m_regData[0x06] | (bitfield(m_regData[0x07], 0, 3) << 8);
        }

        public final int cpudata() {
            return m_regData[0x08];
        }

        public final int delta_n() {
            return m_regData[0x09] | (m_regData[0x0a] << 8);
        }

        public final int level() {
            return m_regData[0x0b];
        }

        public final int limit() {
            return m_regData[0x0c] | (m_regData[0x0d] << 8);
        }

        public final int dac() {
            return m_regData[0x0e];
        }

        public final int pcm() {
            return m_regData[0x0f];
        }

        // internal state

        /** register data */
        @Element(sequence = 1)
        private final int[] m_regData = new int[REGISTERS];
    }

    //
    // ADPCM "B" CHANNEL
    //

    /** ChannelB */
    @Serdes
    protected static class ChannelB {

        static final int STEP_MIN = 127;
        static final int STEP_MAX = 24576;

        public static final byte STATUS_EOS = 0x01;
        public static final byte STATUS_BRDY = 0x02;
        public static final byte STATUS_PLAYING = 0x04;

        /**
         * Constructor.
         */
        public ChannelB(Adpcm.EngineB owner, int addrShift) {
            m_address_shift = addrShift;
            m_status = STATUS_BRDY;
            m_curNibble = 0;
            m_curByte = 0;
            m_dummy_read = 0;
            m_position = 0;
            m_curAddress = 0;
            m_accumulator = 0;
            m_prev_accum = 0;
            m_adpcm_step = STEP_MIN;
            m_regs = owner.regs();
            m_owner = owner;
        }

        /**
         * Resets the channel state.
         */
        public void reset() {
            m_status = STATUS_BRDY;
            m_curNibble = 0;
            m_curByte = 0;
            m_dummy_read = 0;
            m_position = 0;
            m_curAddress = 0;
            m_accumulator = 0;
            m_prev_accum = 0;
            m_adpcm_step = STEP_MIN;
        }

        /**
         * Saves the data.
         */
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
        }

        /**
         * Restores the data.
         */
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
        }

//        /** Signal key on/off */
//		public void keyOnOff(boolean on)

        private static final int[] s_step_scale = {57, 57, 57, 57, 77, 102, 128, 153};

        /**
         * Master clocking function.
         */
        public void clock() {
            // only process if active and not recording (which we don't support)
            if (m_regs.execute() == 0 || m_regs.record() != 0 || (m_status & STATUS_PLAYING) == 0) {
                m_status &= ~STATUS_PLAYING;
                return;
            }

            // otherwise, advance the step
            int position = m_position + m_regs.delta_n();
            m_position = position;
            if (position < 0x1_0000)
                return;

            // if we're about to process nibble 0, fetch sample
            if (m_curNibble == 0) {
                // playing from RAM/ROM
                if (m_regs.external() != 0)
                    m_curByte = m_owner.intf().ymfm_external_read(ADPCM_B, m_curAddress);
            }

            // extract the nibble from our current byte
            int data = (m_curByte << (4 * m_curNibble)) >> 4;
            m_curNibble ^= 1;

            // we just processed the last nibble
            if (m_curNibble == 0) {
                // if playing from RAM/ROM, check the end/limit address or advance
                if (m_regs.external() != 0) {
                    // handle the sample end, either repeating or stopping
                    if (at_end()) {
                        // if repeating, go back to the start
                        if (m_regs.repeat() != 0)
                            load_start();

                            // otherwise, done; set the EOS bit
                        else {
                            m_accumulator = 0;
                            m_prev_accum = 0;
                            m_status = (m_status & ~STATUS_PLAYING) | STATUS_EOS;
                            log_keyon.log(Level.DEBUG, "%s".formatted("ADPCM EOS"));
                            return;
                        }
                    }

                    // wrap at the limit address
                    else if (at_limit())
                        m_curAddress = 0;

                        // otherwise, advance the current address
                    else {
                        m_curAddress++;
                        m_curAddress &= 0xff_ffff;
                    }
                }

                // if CPU-driven, copy the next byte and request more
                else {
                    m_curByte = m_regs.cpudata();
                    m_status |= STATUS_BRDY;
                }
            }

            // remember previous value for interpolation
            m_prev_accum = m_accumulator;

            // forecast to next forecast: 1/8, 3/8, 5/8, 7/8, 9/8, 11/8, 13/8, 15/8
            int delta = (2 * bitfield(data, 0, 3) + 1) * m_adpcm_step / 8;
            if (bitfield(data, 3) != 0)
                delta = -delta;

            // add and clamp to 16 bits
            m_accumulator = clamp(m_accumulator + delta, -32768, 32767);

            // scale the ADPCM step: 0.9, 0.9, 0.9, 0.9, 1.2, 1.6, 2.0, 2.4
            m_adpcm_step = clamp((m_adpcm_step * s_step_scale[bitfield(data, 0, 3)]) / 64, STEP_MIN, STEP_MAX);
        }

        /**
         * Returns the computed output value, with panning applied.
         */
        //template<int NumOutputs>
        public final void output(YmFm.Output output, int rShift) {
            // mask out some channels for debug purposes
            if ((Debug.GLOBAL_ADPCM_B_CHANNEL_MASK & 1) == 0)
                return;

            // do a linear interpolation between samples
            int result = (m_prev_accum * ((m_position ^ 0xffff) + 1) + m_accumulator * m_position) >> 16;

            // apply volume (level) in a linear fashion and reduce
            result = (result * m_regs.level()) >> (8 + rShift);

            // apply to left/right
            if (output.getNumOutputs() == 1 || m_regs.pan_left() != 0)
                output.data[0] += result;
            if (output.getNumOutputs() > 1 && m_regs.pan_right() != 0)
                output.data[1] += result;
        }

        /** return the status register */
        public final int status() {
            return m_status;
        }

        /**
         * Handles special register reads.
         */
        public int read(int regNum) {
            int result = 0;

            // register 8 reads over the bus under some conditions
            if (regNum == 0x08 && m_regs.execute() == 0 && m_regs.record() == 0 && m_regs.external() != 0) {
                // two dummy reads are consumed first
                if (m_dummy_read != 0) {
                    load_start();
                    m_dummy_read--;
                }

                // read the data
                else {
                    // read from outside of the chip
                    result = m_owner.intf().ymfm_external_read(ADPCM_B, m_curAddress++);

                    // did we hit the end? if so, signal EOS
                    if (at_end()) {
                        m_status = STATUS_EOS | STATUS_BRDY;
                        log_keyon.log(Level.DEBUG, "%s".formatted("ADPCM EOS"));
                    } else {
                        // signal ready
                        m_status = STATUS_BRDY;
                    }

                    // wrap at the limit address
                    if (at_limit())
                        m_curAddress = 0;
                }
            }
            return result;
        }

        /**
         * Handles special register writes.
         */
        public void write(int regNum, int value) {
            // register 0 can do a reset; also use writes here to reset the
            // dummy read counter
            if (regNum == 0x00) {
                if (m_regs.execute() == 0) {
                    load_start();

                    // don't log masked channels
                    if ((Debug.GLOBAL_ADPCM_B_CHANNEL_MASK & 1) != 0)
                        log_keyon.log(Level.DEBUG, "KeyOn ADPCM-B: rep=%d spk=%d pan=%d%d dac=%d 8b=%d rom=%d ext=%d rec=%d start=%04X end=%04X pre=%04X dn=%04X lvl=%02X lim=%04X".formatted(
                                m_regs.repeat(),
                                m_regs.speaker(),
                                m_regs.pan_left(),
                                m_regs.pan_right(),
                                m_regs.dac_enable(),
                                m_regs.dram_8bit(),
                                m_regs.rom_ram(),
                                m_regs.external(),
                                m_regs.record(),
                                m_regs.start(),
                                m_regs.end(),
                                m_regs.prescale(),
                                m_regs.delta_n(),
                                m_regs.level(),
                                m_regs.limit()));
                } else
                    m_status &= ~STATUS_EOS;
                if (m_regs.resetflag() == 0)
                    reset();
                if (m_regs.external() == 0)
                    m_dummy_read = 2;
            }

            // register 8 writes over the bus under some conditions
            else if (regNum == 0x08) {
                // if writing from the CPU during execute, clear the ready flag
                if (m_regs.execute() != 0 && m_regs.record() == 0 && m_regs.external() == 0)
                    m_status &= ~STATUS_BRDY;

                    // if writing during "record", pass through as data
                else if (m_regs.execute() == 0 && m_regs.record() != 0 && m_regs.external() != 0) {
                    // clear out dummy reads and set start address
                    if (m_dummy_read != 0) {
                        load_start();
                        m_dummy_read = 0;
                    }

                    // did we hit the end? if so, signal EOS
                    if (at_end()) {
                        log_keyon.log(Level.DEBUG, "%s".formatted("ADPCM EOS"));
                        m_status = STATUS_EOS | STATUS_BRDY;
                    }

                    // otherwise, write the data and signal ready
                    else {
                        m_owner.intf().ymfm_external_write(ADPCM_B, m_curAddress++, value);
                        m_status = STATUS_BRDY;
                    }
                }
            }
        }

        /**
         * Computes the current address shift amount based on register settings.
         */
        private int address_shift() {
            // if a constant address shift, just provide that
            if (m_address_shift != 0)
                return m_address_shift;

            // if ROM or 8-bit DRAM, shift is 5 bits
            if (m_regs.rom_ram() != 0)
                return 5;
            if (m_regs.dram_8bit() != 0)
                return 5;

            // otherwise, shift is 2 bits
            return 2;
        }

        /**
         * Loads the start address and initialize the state.
         */
        private void load_start() {
            m_status = (m_status & ~STATUS_EOS) | STATUS_PLAYING;
            m_curAddress = m_regs.external() != 0 ? (m_regs.start() << address_shift()) : 0;
            m_curNibble = 0;
            m_curByte = 0;
            m_position = 0;
            m_accumulator = 0;
            m_prev_accum = 0;
            m_adpcm_step = STEP_MIN;
        }

        /** limit checker; stops at the last byte of the chunk described by address_shift() */
        private boolean at_limit() {
            return (m_curAddress == (((m_regs.limit() + 1) << address_shift()) - 1));
        }

        /** end checker; stops at the last byte of the chunk described by address_shift() */
        private boolean at_end() {
            return (m_curAddress == (((m_regs.end() + 1) << address_shift()) - 1));
        }

        // internal state

        /** address bits shift-left */
        private final int m_address_shift;
        /** currently playing? */
        @Element(sequence = 1)
        private int m_status;
        /** index of the current nibble */
        @Element(sequence = 2)
        private int m_curNibble;
        /** current byte of data */
        @Element(sequence = 3)
        private int m_curByte;
        /** dummy read tracker */
        @Element(sequence = 4)
        private int m_dummy_read;
        /** current fractional position */
        @Element(sequence = 5)
        private int m_position;
        /** current address */
        @Element(sequence = 6)
        private int m_curAddress;
        /** accumulator */
        @Element(sequence = 7)
        private int m_accumulator;
        /** previous accumulator (for linear interp) */
        @Element(sequence = 8)
        private int m_prev_accum;
        /** next forecast */
        @Element(sequence = 9)
        private int m_adpcm_step;
        /** reference to registers */
        private final Adpcm.RegistersB m_regs;
        /** reference to our owner */
        private final Adpcm.EngineB m_owner;
    }

    //
    // ADPCM "B" ENGINE
    //

    /** EngineB */
    protected static class EngineB {

        /**
         * Constructor.
         */
        public EngineB(YmFm.Interface intf, int addrShift /* = 0 */) {
            m_intf = intf;

            // create the channel (only one supported for now, but leaving possibilities open)
            m_channel = new Adpcm.ChannelB(this, addrShift);
        }

        /**
         * Resets the engine state.
         */
        public void reset() {
            // reset registers
            m_regs.reset();

            // reset each channel
            m_channel.reset();
        }

        /**
         * Saves the data.
         */
        public void save(OutputStream os) throws IOException {
            // save our state
            m_regs.save(os);

            // save channel state
            m_channel.save(os);
        }

        /**
         * Restores the data.
         */
        public void restore(InputStream is) throws IOException {
            // save our state
            m_regs.restore(is);

            // save channel state
            m_channel.restore(is);
        }

        /**
         * Master clocking function.
         */
        public void clock() {
            // clock each channel, setting a bit in result if it finished
            m_channel.clock();
        }

        /**
         * Master output function.
         */
        //template<int NumOutputs>
        public void output(YmFm.Output output, int rShift) {
            // compute the output of each channel
            m_channel.output(output, rShift);
        }

        //template void EngineB.output<1>(Output<1> output, int rShift);
        //template void EngineB.output<2>(Output<2> output, int rShift);

        /** read from the ADPCM-B registers */
        public int read(int regNum) {
            return m_channel.read(regNum);
        }

        /**
         * Handles writes to the ADPCM-B registers.
         */
        public void write(int regNum, int data) {
            // store the raw value to the register array;
            // most writes are passive, consumed only when needed
            m_regs.write(regNum, data);

            // let the channel handle any special writes
            m_channel.write(regNum, data);
        }

        /** status */
        public final int status() {
            return m_channel.status();
        }

        /** Returns a reference to our interface */
        public YmFm.Interface intf() {
            return m_intf;
        }

        /** Returns a reference to our registers */
        public Adpcm.RegistersB regs() {
            return m_regs;
        }

        // internal state

        /** reference to our interface */
        private final YmFm.Interface m_intf;
        /** channel pointer */
        private final Adpcm.ChannelB m_channel;
        /** registers */
        private final Adpcm.RegistersB m_regs = new Adpcm.RegistersB();
    }
}
