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
import java.util.Arrays;

import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static vavi.sound.ymfm.YmFm.AccessClass.IO;
import static vavi.sound.ymfm.YmFm.bitfield;


abstract class Ssg {

    //
    // OVERRIDE INTERFACE
    //

    /**
     * SsgOverride
     * <p>
     * this class describes a simple interface to allow the internal SSG to be
     * overridden with another implementation
     */
    public interface Override {

        void ssg_reset();

        // read/write to the SSG registers

        byte ssg_read(int regNum);

        void ssg_write(int regNum, int data);

        /** notification when the prescale has changed */
        void ssg_prescale_changed();
    }

    //
    // REGISTER CLASS
    //

    //
    // SSG REGISTERS
    //

    //
    // SSG register map:
    //
    //      System-wide registers:
    //           06 ---xxxxx Noise period
    //           07 x------- I/O B in(0) or out(1)
    //              -x------ I/O A in(0) or out(1)
    //              --x----- Noise enable(0) or disable(1) for channel C
    //              ---x---- Noise enable(0) or disable(1) for channel B
    //              ----x--- Noise enable(0) or disable(1) for channel A
    //              -----x-- Tone enable(0) or disable(1) for channel C
    //              ------x- Tone enable(0) or disable(1) for channel B
    //              -------x Tone enable(0) or disable(1) for channel A
    //           0B xxxxxxxx Envelope period fine
    //           0C xxxxxxxx Envelope period coarse
    //           0D ----x--- Envelope shape: continue
    //              -----x-- Envelope shape: attack/decay
    //              ------x- Envelope shape: alternate
    //              -------x Envelope shape: hold
    //           0E xxxxxxxx 8-bit parallel I/O port A
    //           0F xxxxxxxx 8-bit parallel I/O port B
    //
    //      Per-channel registers:
    //     00,02,04 xxxxxxxx Tone period (fine) for channel A,B,C
    //     01,03,05 ----xxxx Tone period (coarse) for channel A,B,C
    //     08,09,0A ---x---- Mode: fixed(0) or variable(1) for channel A,B,C
    //              ----xxxx Amplitude for channel A,B,C
    //

    /** SsgRegisters */
    @Serdes
    protected static class Registers {

        // constants
        protected static final int OUTPUTS = 3;
        protected static final int CHANNELS = 3;
        protected static final int REGISTERS = 0x10;
        protected static final int ALL_CHANNELS = (1 << CHANNELS) - 1;

        /** Constructor */
        public Registers() {
        }

        /**
         * Resets the register state.
         */
        public void reset() {
            Arrays.fill(m_regdata, 0, REGISTERS, 0);
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

        public int read(int index) {
            return m_regdata[index];
        }

        public void write(int index, int data) {
            m_regdata[index] = data;
        }

        // system-wide registers

        public final int noise_period() {
            return bitfield(m_regdata[0x06], 0, 5);
        }

        public final int io_b_out() {
            return bitfield(m_regdata[0x07], 7);
        }

        public final int io_a_out() {
            return bitfield(m_regdata[0x07], 6);
        }

        public final int envelope_period() {
            return m_regdata[0x0b] | (m_regdata[0x0c] << 8);
        }

        public final int envelope_continue() {
            return bitfield(m_regdata[0x0d], 3);
        }

        public final int envelope_attack() {
            return bitfield(m_regdata[0x0d], 2);
        }

        public final int envelope_alternate() {
            return bitfield(m_regdata[0x0d], 1);
        }

        public final int envelope_hold() {
            return bitfield(m_regdata[0x0d], 0);
        }

        public final int io_a_data() {
            return m_regdata[0x0e];
        }

        public final int io_b_data() {
            return m_regdata[0x0f];
        }

        // per-channel registers

        public final int ch_noise_enable_n(int chOffs) {
            return bitfield(m_regdata[0x07], 3 + chOffs);
        }

        public final int ch_tone_enable_n(int chOffs) {
            return bitfield(m_regdata[0x07], 0 + chOffs);
        }

        public final int ch_tone_period(int chOffs) {
            return m_regdata[0x00 + 2 * chOffs] | (bitfield(m_regdata[0x01 + 2 * chOffs], 0, 4) << 8);
        }

        public final int ch_envelope_enable(int chOffs) {
            return bitfield(m_regdata[0x08 + chOffs], 4);
        }

        public final int ch_amplitude(int chOffs) {
            return bitfield(m_regdata[0x08 + chOffs], 0, 4);
        }

        // internal state

        /** register data */
        @Element(sequence = 1)
        private final int[] m_regdata = new int[REGISTERS];
    }

    //
    // SSG ENGINE
    //

    /** SsgEngine */
    @Serdes
    protected static class Engine extends YmFm.Interface {

        protected static final int OUTPUTS = Registers.OUTPUTS;
        protected static final int CHANNELS = Registers.CHANNELS;
        protected static final int CLOCK_DIVIDER = 8;

        //using output_data = Output<OUTPUTS>;
        public YmFm.Output outputFactory() {
            return new YmFm.Output(OUTPUTS);
        }

        /**
         * Constructor.
         */
        public Engine(YmFm.Interface intf) {
            m_intf = intf;
//			m_tone_count = {0, 0, 0};
//			m_tone_state = {0, 0, 0};
            m_envelope_count = 0;
            m_envelope_state = 0;
            m_noise_count = 0;
            m_noise_state = 1;
            m_override = null;

            m_regs = new Ssg.Registers();
        }

        /** Configures an override */
        public void override(Ssg.Override override) {
            m_override = override;
        }

        /**
         * Resets the engine state.
         */
        public void reset() {
            // defer to the override if present
            if (m_override != null) {
                m_override.ssg_reset();
                return;
            }

            // reset register state
            m_regs.reset();

            // reset engine state
            for (int chan = 0; chan < CHANNELS; chan++) {
                m_tone_count[chan] = 0;
                m_tone_state[chan] = 0;
            }
            m_envelope_count = 0;
            m_envelope_state = 0;
            m_noise_count = 0;
            m_noise_state = 1;
        }

        /**
         * Saves the data.
         */
        public void save(OutputStream os) throws IOException {
            // save register state
            m_regs.save(os);

            // save engine state
            Serdes.Util.serialize(this, os);
        }

        /**
         * Restores the data.
         */
        public void restore(InputStream is) throws IOException {
            // save register state
            m_regs.restore(is);

            // save engine state
            Serdes.Util.deserialize(is, this);
        }

        /**
         * Master clocking function.
         */
        public void clock() {
            // clock tones; tone period units are clock/16 but since we run at clock/8
            // that works out for us to toggle the state (50% duty cycle) at twice the
            // programmed period
            for (int chan = 0; chan < CHANNELS; chan++) {
                m_tone_count[chan]++;
                if (m_tone_count[chan] >= m_regs.ch_tone_period(chan)) {
                    m_tone_state[chan] ^= 1;
                    m_tone_count[chan] = 0;
                }
            }

            // clock noise; noise period units are clock/16 but since we run at clock/8,
            // our counter needs a right shift prior to compare; note that a period of 0
            // should produce an indentical result to a period of 1, so add a special
            // check against that case
            m_noise_count++;
            if ((m_noise_count >> 1) >= m_regs.noise_period() && m_noise_count != 1) {
                m_noise_state ^= (bitfield(m_noise_state, 0) ^ bitfield(m_noise_state, 3)) << 17;
                m_noise_state >>= 1;
                m_noise_count = 0;
            }

            // clock envelope; envelope period units are clock/8 (manual says clock/256
            // but that's for all 32 steps)
            m_envelope_count++;
            if (m_envelope_count >= m_regs.envelope_period()) {
                m_envelope_state++;
                m_envelope_count = 0;
            }
        }

        private static final int[] s_amplitudes = {
                0, 32, 78, 141, 178, 222, 262, 306,
                369, 441, 509, 585, 701, 836, 965, 1112,
                1334, 1595, 1853, 2146, 2576, 3081, 3576, 4135,
                5000, 6006, 7023, 8155, 9963, 11976, 14132, 16382
        };

        /**
         * Outputs the current state.
         */
        public void output(YmFm.Output output) {
            // volume to amplitude table, taken from MAME's implementation but biased
            // so that 0 == 0

            // compute the envelope volume
            int envelope_volume;
            if (((m_regs.envelope_hold() | (m_regs.envelope_continue() ^ 1)) != 0) && m_envelope_state >= 32) {
                m_envelope_state = 32;
                envelope_volume = (((m_regs.envelope_attack() ^ m_regs.envelope_alternate()) & m_regs.envelope_continue()) != 0) ? 31 : 0;
            } else {
                int attack = m_regs.envelope_attack();
                if (m_regs.envelope_alternate() != 0)
                    attack ^= bitfield(m_envelope_state, 5);
                envelope_volume = (m_envelope_state & 31) ^ (attack != 0 ? 0 : 31);
            }

            // iterate over channels
            for (int chan = 0; chan < 3; chan++) {
                // noise depends on the noise state, which is the LSB of m_noise_state
                int noise_on = m_regs.ch_noise_enable_n(chan) | m_noise_state;

                // tone depends on the current tone state
                int tone_on = m_regs.ch_tone_enable_n(chan) | m_tone_state[chan];

                // if neither tone nor noise enabled, return 0
                int volume;
                if ((noise_on & tone_on) == 0)
                    volume = 0;

                    // if the envelope is enabled, use its amplitude
                else if (m_regs.ch_envelope_enable(chan) != 0)
                    volume = envelope_volume;

                    // otherwise, scale the tone amplitude up to match envelope values
                    // according to the datasheet, amplitude 15 maps to envelope 31
                else {
                    volume = m_regs.ch_amplitude(chan) * 2;
                    if (volume != 0)
                        volume |= 1;
                }

                // convert to amplitude
                output.data[chan] = s_amplitudes[volume];
            }
        }

        /**
         * Handles reads from the SSG registers.
         */
        public int read(int regNum) {
            // defer to the override if present
            if (m_override != null)
                return m_override.ssg_read(regNum);

            // read from the I/O ports call the handlers if they are configured for input
            if (regNum == 0x0e && m_regs.io_a_out() == 0)
                return m_intf.ymfm_external_read(IO, 0);
            else if (regNum == 0x0f && m_regs.io_b_out() == 0)
                return m_intf.ymfm_external_read(IO, 1);

            // otherwise just return the register value
            return m_regs.read(regNum);
        }

        /**
         * Handles writes to the SSG registers.
         */
        public void write(int regNum, int data) {
            // defer to the override if present
            if (m_override != null) {
                m_override.ssg_write(regNum, data);
                return;
            }

            // store the raw value to the register array;
            // most writes are passive, consumed only when needed
            m_regs.write(regNum, data);

            // writes to the envelope shape register reset the state
            if (regNum == 0x0d)
                m_envelope_state = 0;

                // writes to the I/O ports call the handlers if they are configured for output
            else if (regNum == 0x0e && m_regs.io_a_out() != 0)
                m_intf.ymfm_external_write(IO, 0, data);
            else if (regNum == 0x0f && m_regs.io_b_out() != 0)
                m_intf.ymfm_external_write(IO, 1, data);
        }

        /** Returns a reference to our interface */
        public YmFm.Interface intf() {
            return m_intf;
        }

        /** return a reference to our registers */
        public Ssg.Registers regs() {
            return m_regs;
        }

        /** true if we are overridden */
        public final boolean overridden() {
            return (m_override != null);
        }

        /** indicate the prescale has changed */
        public void prescale_changed() {
            if (m_override != null) m_override.ssg_prescale_changed();
        }

        // internal state

        /** reference to the interface */
        private final YmFm.Interface m_intf;
        /** current tone counter */
        @Element(sequence = 1)
        private final int[] m_tone_count = new int[3];
        /** current tone state */
        @Element(sequence = 2)
        private final int[] m_tone_state = new int[3];
        /** envelope counter */
        @Element(sequence = 3)
        private int m_envelope_count;
        /** envelope state */
        @Element(sequence = 4)
        private int m_envelope_state;
        /** current noise counter */
        @Element(sequence = 5)
        private int m_noise_count;
        /** current noise state */
        @Element(sequence = 6)
        private int m_noise_state;
        /** registers */
        private final Ssg.Registers m_regs;
        /** override interface */
        private Ssg.Override m_override;
    }
}
