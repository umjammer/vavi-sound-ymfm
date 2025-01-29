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
import java.lang.System.Logger.Level;

import vavi.sound.ymfm.Fm.EngineBase;
import vavi.sound.ymfm.Fm.OpDataCache;
import vavi.sound.ymfm.Fm.RegistersBase;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static vavi.sound.ymfm.Opz.TEMPORARY_DEBUG_PRINTS;
import static vavi.sound.ymfm.YmFm.AccessClass.IO;
import static vavi.sound.ymfm.YmFm.Debug.log_unexpected_read_write;
import static vavi.sound.ymfm.YmFm.bitfield;


abstract class Opx {

    private Opx() {
    }

    //
    // REGISTER CLASSES
    //

    //
    // OPX register map:
    //
    //      System-wide registers:
    //
    //     Per-channel registers (channel in address bits 0-2)
    //
    //     Per-operator registers (4 banks):
    //        00-0F x------- Enable
    //              -xxxx--- EXT out
    //              -------x Key on
    //        10-1F xxxxxxxx LFO frequency
    //        20-2F xx------ AM sensitivity (0-3)
    //              --xxx--- PM sensitivity (0-7)
    //              ------xx LFO waveform (0=disable, 1=saw, 2=
    //        30-3F -xxx---- Detune (0-7)
    //              ----xxxx Multiple (0-15)
    //        40-4F -xxxxxxx Total level (0-127)
    //        50-5F xxx----- Key scale (0-7)
    //              ---xxxxx Attack rate (0-31)
    //        60-6F ---xxxxx Decay rate (0-31)
    //        70-7F ---xxxxx Sustain rate (0-31)
    //        80-8F xxxx---- Sustain level (0-15)
    //              ----xxxx Release rate (0-15)
    //        90-9F xxxxxxxx Frequency number (low 8 bits)
    //        A0-AF xxxx---- Block (0-15)
    //              ----xxxx Frequency number (high 4 bits)
    //        B0-BF x------- Acc on
    //              -xxx---- Feedback level (0-7)
    //              -----xxx Waveform (0-7, 7=PCM)
    //        C0-CF ----xxxx Algorithm (0-15)
    //        D0-DF xxxx---- CH0 level (0-15)
    //              ----xxxx CH1 level (0-15)
    //        E0-EF xxxx---- CH2 level (0-15)
    //              ----xxxx CH3 level (0-15)
    //

    /** opx_registers */
    static class Registers extends RegistersBase {

        // LFO waveforms are 256 entries long
        static final int LFO_WAVEFORM_LENGTH = 256;

        // constants
        protected static final int OUTPUTS = 8;
        protected static final int CHANNELS = 24;
        protected static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
        protected static final int OPERATORS = CHANNELS * 2;
        protected static final int WAVEFORMS = 8;
        protected static final int REGISTERS = 0x800;
        protected static final int DEFAULT_PRESCALE = 8;
        protected static final int EG_CLOCK_DIVIDER = 2;
        protected static final int CSM_TRIGGER_MASK = ALL_CHANNELS;
        protected static final int REG_MODE = 0x14;
        protected static final int STATUS_TIMERA = 0x01;
        protected static final int STATUS_TIMERB = 0x02;
        protected static final int STATUS_BUSY = 0x80;
        protected static final int STATUS_IRQ = 0;

        {
            getParams().put("OUTPUTS", OUTPUTS);
            getParams().put("CHANNELS", CHANNELS);
            getParams().put("ALL_CHANNELS", ALL_CHANNELS);
            getParams().put("OPERATORS", OPERATORS);
            getParams().put("WAVEFORMS", WAVEFORMS);
            getParams().put("REGISTERS", REGISTERS);
            getParams().put("DEFAULT_PRESCALE", DEFAULT_PRESCALE);
            getParams().put("EG_CLOCK_DIVIDER", EG_CLOCK_DIVIDER);
            getParams().put("CSM_TRIGGER_MASK", CSM_TRIGGER_MASK);
            getParams().put("REG_MODE", REG_MODE);
            getParams().put("STATUS_TIMERA", STATUS_TIMERA);
            getParams().put("STATUS_TIMERB", STATUS_TIMERB);
            getParams().put("STATUS_BUSY", STATUS_BUSY);
            getParams().put("STATUS_IRQ", STATUS_IRQ);
        }

        /**
         * Constructor.
         */
        public Registers() {
        }

        /** Resets to initial state */
        @Override
        public void reset() {
        }

        /** Saves */
        @Override
        public void save(OutputStream os) {
        }

        /** Restores */
        @Override
        public void restore(InputStream is) {
        }

        /** Maps channel number to register offset */
        @Override
        public int channel_offset(int chNum) {
            assert (chNum < CHANNELS);
            return chNum;
        }

        /** Maps operator number to register offset */
        @Override
        public int operator_offset(int opNum) {
            assert (opNum < OPERATORS);
            return opNum;
        }

        /** Returns an array of operator indices for each channel */
        @Override
        public final void operator_map(int[][] dest) {
        }

        // handle writes to the register array */
        @Override
        public boolean write(int index, int data, int[] chan, int[] opMask) {
            return false;
        }

        // clock the noise and LFO, if present, returning LFO PM value */
        @Override
        public int clock_noise_and_lfo() {
            return -1;
        }

        // return the AM offset from LFO for the given channel */
        @Override
        public final int lfo_am_offset(int chOffs) {
            return -1;
        }

        // return the current noise state, gated by the noise clock */
        @Override
        public final int noise_state() {
            return m_noise_state;
        }

        // caching helpers */
        @Override
        public void cache_operator_data(int chOffs, int opOffs, OpDataCache cache) {
        }

        // compute the phase step, given a PM value */
        @Override
        public int compute_phase_step(int chOffs, int opOffs, OpDataCache cache, int lfo_raw_pm) {
            return -1;
        }

        // log a key-on event */
        @Override
        public String log_keyOn(int chOffs, int opOffs) {
            return null;
        }

        // system-wide registers

        public final int noise_frequency() {
            return byte_(0x0f, 0, 5);
        }

        @Override
        public final int noise_enable() {
            return byte_(0x0f, 7, 1);
        }

        @Override
        public final int timer_a_value() {
            return word(0x10, 0, 8, 0x11, 0, 2);
        }

        @Override
        public final int timer_b_value() {
            return byte_(0x12, 0, 8);
        }

        @Override
        public final int csm() {
            return byte_(0x14, 7, 1);
        }

        @Override
        public final int reset_timer_b() {
            return byte_(0x14, 5, 1);
        }

        @Override
        public final int reset_timer_a() {
            return byte_(0x14, 4, 1);
        }

        @Override
        public final int enable_timer_b() {
            return byte_(0x14, 3, 1);
        }

        @Override
        public final int enable_timer_a() {
            return byte_(0x14, 2, 1);
        }

        @Override
        public final int load_timer_b() {
            return byte_(0x14, 1, 1);
        }

        @Override
        public final int load_timer_a() {
            return byte_(0x14, 0, 1);
        }

        /** fake */
        public final int lfo2_pm_depth() {
            return byte_(0x148, 0, 7);
        }

        public final int lfo2_rate() {
            return byte_(0x16, 0, 8);
        }

        public final int lfo2_am_depth() {
            return byte_(0x17, 0, 7);
        }

        public final int lfo_rate() {
            return byte_(0x18, 0, 8);
        }

        public final int lfo_am_depth() {
            return byte_(0x19, 0, 7);
        }

        /** fake */
        public final int lfo_pm_depth() {
            return byte_(0x149, 0, 7);
        }

        public final int output_bits() {
            return byte_(0x1b, 6, 2);
        }

        public final int lfo2_sync() {
            return byte_(0x1b, 5, 1);
        }

        public final int lfo_sync() {
            return byte_(0x1b, 4, 1);
        }

        public final int lfo2_waveform() {
            return byte_(0x1b, 2, 2);
        }

        public final int lfo_waveform() {
            return byte_(0x1b, 0, 2);
        }

        // per-channel registers

        public final int ch_volume(int choffs) {
            return byte_(0x00, 0, 8, choffs);
        }

        @Override
        public final int ch_output_any(int chOffs) {
            return byte_(0x20, 7, 1, chOffs) | byte_(0x30, 0, 1, chOffs);
        }

        @Override
        public final int ch_output_0(int chOffs) {
            return byte_(0x30, 0, 1, chOffs);
        }

        @Override
        public final int ch_output_1(int chOffs) {
            return byte_(0x20, 7, 1, chOffs) | byte_(0x30, 0, 1, chOffs);
        }

        @Override
        public final int ch_output_2(int chOffs) {
            return 0;
        }

        @Override
        public final int ch_output_3(int chOffs) {
            return 0;
        }

        public final int ch_key_on(int choffs) {
            return byte_(0x20, 6, 1, choffs);
        }

        @Override
        public final int ch_feedback(int chOffs) {
            return byte_(0x20, 3, 3, chOffs);
        }

        @Override
        public final int ch_algorithm(int chOffs) {
            return byte_(0x20, 0, 3, chOffs);
        }

        public final int ch_block_freq(int choffs) {
            return word(0x28, 0, 7, 0x30, 2, 6, choffs);
        }

        public final int ch_lfo_pm_sens(int choffs) {
            return byte_(0x38, 4, 3, choffs);
        }

        public final int ch_lfo_am_sens(int choffs) {
            return byte_(0x38, 0, 2, choffs);
        }

        /** fake */
        public final int ch_lfo2_pm_sens(int choffs) {
            return byte_(0x140, 4, 3, choffs);
        }

        /** fake */
        public final int ch_lfo2_am_sens(int choffs) {
            return byte_(0x140, 0, 2, choffs);
        }

        // per-operator registers

        public final int op_detune(int opoffs) {
            return byte_(0x40, 4, 3, opoffs);
        }

        public final int op_multiple(int opoffs) {
            return byte_(0x40, 0, 4, opoffs);
        }

        public final int op_fix_range(int opoffs) {
            return byte_(0x40, 4, 3, opoffs);
        }

        final int op_fix_frequency(int opoffs) {
            return byte_(0x40, 0, 4, opoffs);
        }

        /** fake */
        final int op_waveform(int opoffs) {
            return byte_(0x100, 4, 3, opoffs);
        }

        /** fake */
        public final int op_fine(int opoffs) {
            return byte_(0x100, 0, 4, opoffs);
        }

        public final int op_total_level(int opoffs) {
            return byte_(0x60, 0, 7, opoffs);
        }

        public final int op_ksr(int opoffs) {
            return byte_(0x80, 6, 2, opoffs);
        }

        public final int op_fix_mode(int opoffs) {
            return byte_(0x80, 5, 1, opoffs);
        }

        public final int op_attack_rate(int opoffs) {
            return byte_(0x80, 0, 5, opoffs);
        }

        @Override
        public final int op_lfo_am_enable(int opOffs) {
            return byte_(0xa0, 7, 1, opOffs);
        }

        public final int op_decay_rate(int opoffs) {
            return byte_(0xa0, 0, 5, opoffs);
        }

        public final int op_detune2(int opoffs) {
            return byte_(0xc0, 6, 2, opoffs);
        }

        public final int op_sustain_rate(int opoffs) {
            return byte_(0xc0, 0, 5, opoffs);
        }

        /** fake */
        public final int op_eg_shift(int opoffs) {
            return byte_(0x120, 6, 2, opoffs);
        }

        /** fake */
        public final int op_reverb_rate(int opoffs) {
            return byte_(0x120, 0, 3, opoffs);
        }

        public final int op_sustain_level(int opoffs) {
            return byte_(0xe0, 4, 4, opoffs);
        }

        public final int op_release_rate(int opoffs) {
            return byte_(0xe0, 0, 4, opoffs);
        }

        /** Returns a bitfield extracted from a byte */
        protected int byte_(int offset, int start, int count, int extra_offset /* = 0 */) {
            return bitfield(m_regdata[offset + extra_offset], start, count);
        }

        /** Returns a bitfield extracted from a byte */
        protected int byte_(int offset, int start, int count) {
            return byte_(offset, start, count, 0);
        }

        /** Returns a bitfield extracted from a pair of bytes, MSBs listed first */
        protected int word(int offset1, int start1, int count1, int offset2, int start2, int count2, int extra_offset /* = 0 */) {
            return (byte_(offset1, start1, count1, extra_offset) << count2) | byte_(offset2, start2, count2, extra_offset);
        }

        /** Returns a bitfield extracted from a pair of bytes, MSBs listed first */
        protected int word(int offset1, int start1, int count1, int offset2, int start2, int count2) {
            return word(offset1, start1, count1, offset2, start2, count2, 0);
        }

        // internal state

        /** LFO counter */
        protected int[] m_lfo_counter = new int[2];
        /** noise LFSR state */
        protected int m_noise_lfsr;
        /** noise counter */
        protected byte m_noise_counter;
        /** latched noise state */
        protected byte m_noise_state;
        /** latched LFO noise value */
        protected byte m_noise_lfo;
        /** current LFO AM value */
        protected byte[] m_lfo_am = new byte[2];
        /** register data */
        protected final byte[] m_regdata = new byte[REGISTERS];
        /** phase substep for fixed frequency */
        protected int[] m_phase_substep = new int[OPERATORS];
        /** LFO waveforms; AM in low 8, PM in upper 8 */
        protected int[][] m_lfo_waveform = new int[4][LFO_WAVEFORM_LENGTH];
        /** waveforms */
        protected int[][] m_waveform = new int[WAVEFORMS][WAVEFORM_LENGTH];
    }

    //
    // IMPLEMENTATION CLASSES
    //

    /** ymf271 */
    @Serdes
    public static class YmF271 implements YmFm.Chip {

        protected static class FmEngine extends EngineBase<Opx.Registers> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, Opx.Registers.class);
            }
        }

        private final int OUTPUTS;

        //public using output_data = fm_engine.output_data;
        @Override
        public YmFm.Output outputFactory() {
            return m_fm.outputFactory();
        }

        @Override
        public final int getOutputs() {
            return OUTPUTS;
        }

        /**
         * Constructor.
         */
        public YmF271(YmFm.Interface intf) {
            m_address = 0;
            m_fm = new FmEngine(intf);

            OUTPUTS = (int) m_fm.getRegisterType().getParams().get("OUTPUTS");
        }

        /**
         * Reset the system.
         */
        @Override
        public void reset() {
            // reset the engines
            m_fm.reset();
        }

        /**
         * Saves the data.
         */
        @Override
        public void save(OutputStream os) throws IOException {
            m_fm.save(os);
            Serdes.Util.serialize(this, os);
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            m_fm.restore(is);
            Serdes.Util.deserialize(is, this);
        }

        // pass-through helpers

        @Override
        public int sample_rate(int input_clock) {
            return m_fm.sample_rate(input_clock);
        }

        public void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /**
         * Reads the status register.
         */
        public int read_status() {
            int result = m_fm.status();
            if (m_fm.intf().ymfm_is_busy())
                result |= Opz.Registers.STATUS_BUSY;
            return result;
        }

        /**
         * Handles a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0xff;
            switch (offset & 1) {
                case 0: // data port (unused)
                    log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from YM2414 offset %d".formatted(offset & 3));
                    break;

                case 1: // status port, YM2203 compatible
                    result = read_status();
                    break;
            }
            return result;
        }

        /**
         * Handles a write to the address register.
         */
        public void write_address(int data) {
            // just set the address
            m_address = data;
        }

        /**
         * Handles a write to the register interface.
         */
        public void write_data(int data) {
            // write the FM register
            m_fm.write(m_address, data);
            if (TEMPORARY_DEBUG_PRINTS != 0) {
                switch (m_address & 0xe0) {
                    case 0x00:
                        System.out.printf("CTL %02X = %02X%n", m_address, data);
                        break;

                    case 0x20:
                        switch (m_address & 0xf8) {
                            case 0x20:
                                System.out.printf("R/FBL/ALG %d = %02X%n", m_address & 7, data);
                                break;
                            case 0x28:
                                System.out.printf("KC %d = %02X%n", m_address & 7, data);
                                break;
                            case 0x30:
                                System.out.printf("KF/M %d = %02X%n", m_address & 7, data);
                                break;
                            case 0x38:
                                System.out.printf("PMS/AMS %d = %02X%n", m_address & 7, data);
                                break;
                        }
                        break;

                    case 0x40:
                        if (bitfield(data, 7) == 0)
                            System.out.printf("DT1/MUL %d.%d = %02X%n", m_address & 7, (m_address >> 3) & 3, data);
                        else
                            System.out.printf("OW/FINE %d.%d = %02X%n", m_address & 7, (m_address >> 3) & 3, data);
                        break;

                    case 0x60:
                        System.out.printf("TL %d.%d = %02X%n", m_address & 7, (m_address >> 3) & 3, data);
                        break;

                    case 0x80:
                        System.out.printf("KRS/FIX/AR %d.%d = %02X%n", m_address & 7, (m_address >> 3) & 3, data);
                        break;

                    case 0xa0:
                        System.out.printf("A/D1R %d.%d = %02X%n", m_address & 7, (m_address >> 3) & 3, data);
                        break;

                    case 0xc0:
                        if (bitfield(data, 5) == 0)
                            System.out.printf("DT2/D2R %d.%d = %02X%n", m_address & 7, (m_address >> 3) & 3, data);
                        else
                            System.out.printf("EGS/REV %d.%d = %02X%n", m_address & 7, (m_address >> 3) & 3, data);
                        break;

                    case 0xe0:
                        System.out.printf("D1L/RR %d.%d = %02X%n", m_address & 7, (m_address >> 3) & 3, data);
                        break;
                }
            }

            // special cases
            if (m_address == 0x1b) {
                // writes to register 0x1B send the upper 2 bits to the output lines
                m_fm.intf().ymfm_external_write(IO, 0, data >> 6);
            }

            // mark busy for a bit
            m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());
        }

        /**
         * Handles a write to the register interface.
         */
        @Override
        public void write(int offset, int data) {
            switch (offset & 1) {
                case 0: // address port
                    write_address(data);
                    break;

                case 1: // data port
                    write_data(data);
                    break;
            }
        }

        /**
         * Generates one sample of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm.clock(Opz.Registers.ALL_CHANNELS);

                // update the FM content; YMF271 is full 14-bit with no intermediate clipping
                m_fm.output(output[samp].clear(), 0, 32767, Opz.Registers.ALL_CHANNELS);

                // unsure about YMF271 outputs; assume it is like YM2151
                output[samp].roundtrip_fp();
            }
        }

        // internal state

        /** address register */
        @Element
        protected int m_address;
        /** core FM engine */
        protected final FmEngine m_fm;
    }
}
