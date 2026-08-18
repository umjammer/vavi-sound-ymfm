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
import java.util.Arrays;

import vavi.sound.ymfm.Adpcm.ChannelB;
import vavi.sound.ymfm.Fm.EngineBase;
import vavi.sound.ymfm.Fm.OpDataCache;
import vavi.sound.ymfm.Fm.RegistersBase;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static vavi.sound.ymfm.Opl.OplRegistersBase.opl_compute_phase_step;
import static vavi.sound.ymfm.YmFm.AccessClass.IO;
import static vavi.sound.ymfm.YmFm.Debug.log_unexpected_read_write;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_ATTACK;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_DECAY;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_DEPRESS;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_RELEASE;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_SUSTAIN;
import static vavi.sound.ymfm.YmFm.abs_sin_attenuation;
import static vavi.sound.ymfm.YmFm.bitfield;


public abstract class Opl {

    private Opl() {}

    private static final int[] fnum_to_atten = {0, 24, 32, 37, 40, 43, 45, 47, 48, 50, 51, 52, 53, 54, 55, 56};

    /**
     * Converts an OPL concatenated block (3 bits) and fnum
     * (10 bits) into an attenuation offset; values
     * here are for 6dB/octave, in 0.75dB units
     * (matching total level LSB)
     */
    private static int opl_key_scale_atten(int block, int fnum_4msb) {
        // this table uses the top 4 bits of FNUM and are the maximal values
        // (for when block == 7). Values for other blocks can be computed by
        // subtracting 8 for each block below 7.
        int result = fnum_to_atten[fnum_4msb] - 8 * (block ^ 7);
        return Math.max(0, result);
    }

    //
    // REGISTER CLASSES
    //

    //
    // OPL REGISTERS
    //

    //
    // OPL/OPL2/OPL3/OPL4 register map:
    //
    //      System-wide registers:
    //           01 xxxxxxxx Test register
    //              --x----- Enable OPL compatibility mode [OPL2 only] (0 = enable)
    //           02 xxxxxxxx Timer A value (4 * OPN)
    //           03 xxxxxxxx Timer B value
    //           04 x------- RST
    //              -x------ Mask timer A
    //              --x----- Mask timer B
    //              ------x- Load timer B
    //              -------x Load timer A
    //           08 x------- CSM mode [OPL/OPL2 only]
    //              -x------ Note select
    //           BD x------- AM depth
    //              -x------ PM depth
    //              --x----- Rhythm enable
    //              ---x---- Bass drum key on
    //              ----x--- Snare drum key on
    //              -----x-- Tom key on
    //              ------x- Top cymbal key on
    //              -------x High hat key on
    //          101 --xxxxxx Test register 2 [OPL3 only]
    //          104 --x----- Channel 6 4-operator mode [OPL3 only]
    //              ---x---- Channel 5 4-operator mode [OPL3 only]
    //              ----x--- Channel 4 4-operator mode [OPL3 only]
    //              -----x-- Channel 3 4-operator mode [OPL3 only]
    //              ------x- Channel 2 4-operator mode [OPL3 only]
    //              -------x Channel 1 4-operator mode [OPL3 only]
    //          105 -------x New [OPL3 only]
    //              ------x- New2 [OPL4 only]
    //
    //     Per-channel registers (channel in address bits 0-3)
    //     Note that all these apply to address+100 as well on OPL3+
    //        A0-A8 xxxxxxxx F-number (low 8 bits)
    //        B0-B8 --x----- Key on
    //              ---xxx-- Block (octvate, 0-7)
    //              ------xx F-number (high two bits)
    //        C0-C8 x------- CHD output (to DO0 pin) [OPL3+ only]
    //              -x------ CHC output (to DO0 pin) [OPL3+ only]
    //              --x----- CHB output (mixed right, to DO2 pin) [OPL3+ only]
    //              ---x---- CHA output (mixed left, to DO2 pin) [OPL3+ only]
    //              ----xxx- Feedback level for operator 1 (0-7)
    //              -------x Operator connection algorithm
    //
    //     Per-operator registers (operator in bits 0-5)
    //     Note that all these apply to address+100 as well on OPL3+
    //        20-35 x------- AM enable
    //              -x------ PM enable (VIB)
    //              --x----- EG type
    //              ---x---- Key scale rate
    //              ----xxxx Multiple value (0-15)
    //        40-55 xx------ Key scale level (0-3)
    //              --xxxxxx Total level (0-63)
    //        60-75 xxxx---- Attack rate (0-15)
    //              ----xxxx Decay rate (0-15)
    //        80-95 xxxx---- Sustain level (0-15)
    //              ----xxxx Release rate (0-15)
    //        E0-F5 ------xx Wave select (0-3) [OPL2 only]
    //              -----xxx Wave select (0-7) [OPL3+ only]
    //

    /** OplRegistersBase */
    @Serdes
    protected abstract static class OplRegistersBase extends RegistersBase {

        protected final int revision;

        protected final boolean IsOpl2;
        protected final boolean IsOpl2Plus;
        protected final boolean IsOpl3Plus;
        protected final boolean IsOpl4Plus;

        // constants
        protected final int OUTPUTS;
        protected final int CHANNELS;
        protected final int ALL_CHANNELS;
        protected final int OPERATORS;
        protected final int WAVEFORMS;
        protected final int REGISTERS;
        protected static final int REG_MODE = 0x04;
        protected final int DEFAULT_PRESCALE;
        protected static final int EG_CLOCK_DIVIDER = 1;
        protected final int CSM_TRIGGER_MASK;
        protected final boolean DYNAMIC_OPS;
        protected final boolean MODULATOR_DELAY;
        protected static final int STATUS_TIMERA = 0x40;
        protected static final int STATUS_TIMERB = 0x20;
        protected static final int STATUS_BUSY = 0;
        protected static final int STATUS_IRQ = 0x80;

        /**
         * constructor
         */
        protected OplRegistersBase(int revision) {
            this.revision = revision;

            IsOpl2 = (revision == 2);
            IsOpl2Plus = (revision >= 2);
            IsOpl3Plus = (revision >= 3);
            IsOpl4Plus = (revision >= 4);

            OUTPUTS = IsOpl3Plus ? 4 : 1;
            CHANNELS = IsOpl3Plus ? 18 : 9;
            ALL_CHANNELS = (1 << CHANNELS) - 1;
            OPERATORS = CHANNELS * 2;
            WAVEFORMS = IsOpl3Plus ? 8 : (IsOpl2Plus ? 4 : 1);
            REGISTERS = IsOpl3Plus ? 0x200 : 0x100;
            DEFAULT_PRESCALE = IsOpl4Plus ? 19 : (IsOpl3Plus ? 8 : 4);
            CSM_TRIGGER_MASK = ALL_CHANNELS;
            DYNAMIC_OPS = IsOpl3Plus;
            MODULATOR_DELAY = !IsOpl3Plus;

            getParams().put("OUTPUTS", OUTPUTS);
            getParams().put("CHANNELS", CHANNELS);
            getParams().put("ALL_CHANNELS", ALL_CHANNELS);
            getParams().put("OPERATORS", OPERATORS);
            getParams().put("WAVEFORMS", WAVEFORMS);
            getParams().put("REGISTERS", REGISTERS);
            getParams().put("REG_MODE", REG_MODE);
            getParams().put("DEFAULT_PRESCALE", DEFAULT_PRESCALE);
            getParams().put("EG_CLOCK_DIVIDER", EG_CLOCK_DIVIDER);
            getParams().put("CSM_TRIGGER_MASK", CSM_TRIGGER_MASK);
            getParams().put("DYNAMIC_OPS", DYNAMIC_OPS);
            getParams().put("MODULATOR_DELAY", MODULATOR_DELAY);
            getParams().put("STATUS_TIMERA", STATUS_TIMERA);
            getParams().put("STATUS_TIMERB", STATUS_TIMERB);
            getParams().put("STATUS_BUSY", STATUS_BUSY);
            getParams().put("STATUS_IRQ", STATUS_IRQ);

            m_regdata = new int[REGISTERS];
            m_waveform = new int[WAVEFORMS][WAVEFORM_LENGTH];

            m_lfo_am_counter = 0;
            m_lfo_pm_counter = 0;
            m_noise_lfsr = 1;
            m_lfo_am = 0;

            // create these pointers to appease overzealous compilers checking array
            // bounds in unreachable code (looking at you, clang)
            int[] wf0 = m_waveform[0];
            int[] wf1 = m_waveform[1 % WAVEFORMS];
            int[] wf2 = m_waveform[2 % WAVEFORMS];
            int[] wf3 = m_waveform[3 % WAVEFORMS];
            int[] wf4 = m_waveform[4 % WAVEFORMS];
            int[] wf5 = m_waveform[5 % WAVEFORMS];
            int[] wf6 = m_waveform[6 % WAVEFORMS];
            int[] wf7 = m_waveform[7 % WAVEFORMS];

            // create the waveforms
            for (int index = 0; index < WAVEFORM_LENGTH; index++)
                wf0[index] = abs_sin_attenuation(index) | (bitfield(index, 9) << 15);

            if (WAVEFORMS >= 4) {
                int zeroVal = wf0[0];
                for (int index = 0; index < WAVEFORM_LENGTH; index++) {
                    wf1[index] = bitfield(index, 9) != 0 ? zeroVal : wf0[index];
                    wf2[index] = wf0[index] & 0x7fff;
                    wf3[index] = bitfield(index, 8) != 0 ? zeroVal : (wf0[index] & 0x7fff);
                    if (WAVEFORMS >= 8) {
                        wf4[index] = bitfield(index, 9) != 0 ? zeroVal : wf0[index * 2];
                        wf5[index] = bitfield(index, 9) != 0 ? zeroVal : wf0[(index * 2) & 0x1ff];
                        wf6[index] = bitfield(index, 9) << 15;
                        wf7[index] = (bitfield(index, 9) != 0 ? (index ^ 0x13ff) : index) << 3;
                    }
                }
            }

            // OPL3/OPL4 have dynamic operators, so initialize the fourop_enable value here
            // since operator_map() is called right away, prior to reset()
            if (revision > 2)
                m_regdata[0x104 % REGISTERS] = 0;
        }

        /**
         * Resets to initial state.
         */
        @Override
        public void reset() {
            Arrays.fill(m_regdata, 0, REGISTERS, 0);
        }

        /**
         * Saves the data.
         */
        @Override
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
        }

        /** Maps channel number to register offset */
        @Override
        public int channel_offset(int chNum) {
            assert (chNum < CHANNELS);
            if (!IsOpl3Plus)
                return chNum;
            else
                return (chNum % 9) + 0x100 * (chNum / 9);
        }

        /** Maps operator number to register offset */
        @Override
        public int operator_offset(int opNum) {
            assert (opNum < OPERATORS);
            if (!IsOpl3Plus)
                return opNum + 2 * (opNum / 6);
            else
                return (opNum % 18) + 2 * ((opNum % 18) / 6) + 0x100 * (opNum / 18);
        }

        /**
         * An array of operator indices for each channel
         * OPL/OPL2 has a fixed map, all 2 operators
         */
        protected static final int[] s_fixed_map = {
                operator_list(0, 3),  // Channel 0 operators
                operator_list(1, 4),  // Channel 1 operators
                operator_list(2, 5),  // Channel 2 operators
                operator_list(6, 9),  // Channel 3 operators
                operator_list(7, 10),  // Channel 4 operators
                operator_list(8, 11),  // Channel 5 operators
                operator_list(12, 15),  // Channel 6 operators
                operator_list(13, 16),  // Channel 7 operators
                operator_list(14, 17),  // Channel 8 operators
        };

        /**
         * Return an array of operator
         * indices for each channel; for OPL this is fixed
         */
        @Override
        public final void operator_map(int[][] dest) {
            if (revision <= 2) {
                dest[0] = s_fixed_map;
            } else {
                // OPL3/OPL4 can be configured for 2 or 4 operators
                int fourOp = fourOp_enable();

                dest[0][0] = bitfield(fourOp, 0) != 0 ? operator_list(0, 3, 6, 9) : operator_list(0, 3);
                dest[0][1] = bitfield(fourOp, 1) != 0 ? operator_list(1, 4, 7, 10) : operator_list(1, 4);
                dest[0][2] = bitfield(fourOp, 2) != 0 ? operator_list(2, 5, 8, 11) : operator_list(2, 5);
                dest[0][3] = bitfield(fourOp, 0) != 0 ? operator_list() : operator_list(6, 9);
                dest[0][4] = bitfield(fourOp, 1) != 0 ? operator_list() : operator_list(7, 10);
                dest[0][5] = bitfield(fourOp, 2) != 0 ? operator_list() : operator_list(8, 11);
                dest[0][6] = operator_list(12, 15);
                dest[0][7] = operator_list(13, 16);
                dest[0][8] = operator_list(14, 17);

                dest[0][9] = bitfield(fourOp, 3) != 0 ? operator_list(18, 21, 24, 27) : operator_list(18, 21);
                dest[0][10] = bitfield(fourOp, 4) != 0 ? operator_list(19, 22, 25, 28) : operator_list(19, 22);
                dest[0][11] = bitfield(fourOp, 5) != 0 ? operator_list(20, 23, 26, 29) : operator_list(20, 23);
                dest[0][12] = bitfield(fourOp, 3) != 0 ? operator_list() : operator_list(24, 27);
                dest[0][13] = bitfield(fourOp, 4) != 0 ? operator_list() : operator_list(25, 28);
                dest[0][14] = bitfield(fourOp, 5) != 0 ? operator_list() : operator_list(26, 29);
                dest[0][15] = operator_list(30, 33);
                dest[0][16] = operator_list(31, 34);
                dest[0][17] = operator_list(32, 35);
            }
        }

        /** OPL4 apparently can read back FM registers? */
        public final int read(int address) {
            return m_regdata[address];
        }

        /**
         * Handles writes to the register array
         */
        @Override
        public boolean write(int index, int data, int[] channel, int[] opMask) {
            assert (index < REGISTERS);

            // writes to the mode register with high bit set ignore the low bits
            if (index == REG_MODE && bitfield(data, 7) != 0)
                m_regdata[index] |= 0x80;
            else
                m_regdata[index] = data;

            // handle writes to the rhythm keyons
            if (index == 0xbd) {
                channel[0] = RHYTHM_CHANNEL;
                opMask[0] = bitfield(data, 5) != 0 ? bitfield(data, 0, 5) : 0;
                return true;
            }

            // handle writes to the channel keyons
            if ((index & 0xf0) == 0xb0) {
                channel[0] = index & 0x0f;
                if (channel[0] < 9) {
                    if (IsOpl3Plus)
                        channel[0] += 9 * bitfield(index, 8);
                    opMask[0] = bitfield(data, 5) != 0 ? 15 : 0;
                    return true;
                }
            }
            return false;
        }

        private static final int[] pm_scale = {
                8, 4, 0, -4, -8, -4, 0, 4
        };

        protected static int opl_clock_noise_and_lfo(int[] noise_lfsr, int[] lfo_am_counter, int[] lfo_pm_counter, int[] lfo_am, int am_depth, int pm_depth) {
            // OPL has a 23-bit noise generator for the rhythm section, running at
            // a constant rate, used only for percussion input
            noise_lfsr[0] <<= 1;
            noise_lfsr[0] |= bitfield(noise_lfsr[0], 23) ^ bitfield(noise_lfsr[0], 9) ^ bitfield(noise_lfsr[0], 8) ^ bitfield(noise_lfsr[0], 1);

            // OPL has two fixed-frequency LFOs, one for AM, one for PM

            // the AM LFO has 210*64 steps; at a nominal 50kHz output,
            // this equates to a period of 50000/(210*64) = 3.72Hz
            int am_counter = lfo_am_counter[0]++;
            if (am_counter >= 210 * 64 - 1)
                lfo_am_counter[0] = 0;

            // low 8 bits are fractional; depth 0 is divided by 2, while depth 1 is times 2
            int shift = 9 - 2 * am_depth;

            // AM value is the upper bits of the value, inverted across the midpoint
            // to produce a triangle
            lfo_am[0] = (((am_counter < 105 * 64) ? am_counter : (210 * 64 + 63 - am_counter)) >> shift) & 0xff;

            // the PM LFO has 8192 steps, or a nominal period of 6.1Hz
            int pm_counter = (lfo_pm_counter[0] + 1) & 0xffff;

            // PM LFO is broken into 8 chunks, each lasting 1024 steps; the PM value
            // depends on the upper bits of FNUM, so this value is a fraction and
            // sign to apply to that value, as a 1.3 value
            return pm_scale[bitfield(pm_counter, 10, 3)] >> (pm_depth ^ 1);
        }

        /**
         * Clocks the noise and LFO, handling clock division, depth, and waveform
         * computations
         */
        @Override
        public int clock_noise_and_lfo() {
            int[] a1 = new int[1];
            int[] a2 = new int[1];
            int[] a3 = new int[1];
            int[] a4 = new int[1];
            int r = opl_clock_noise_and_lfo(a1, a2, a3, a4, lfo_am_depth(), lfo_pm_depth());
            m_noise_lfsr = a1[0];
            m_lfo_am_counter = a2[0];
            m_lfo_pm_counter = a3[0];
            m_lfo_am = a4[0];
            return r;
        }

        /** Resets the LFO */
        public void reset_lfo() {
            m_lfo_am_counter = m_lfo_pm_counter = 0;
        }

        /**
         * Returns the AM offset from LFO for the given channel
         * on OPL this is just a fixed value
         */
        @Override
        public final int lfo_am_offset(int chOffs) {
            return m_lfo_am;
        }

        /** Returns LFO/noise states */
        @Override
        public final int noise_state() {
            return m_noise_lfsr >> 23;
        }

        /**
         * Fills the operator cache with prefetched data; note that this code is
         * also used by ymopna_registers, so it must handle upper channels cleanly
         */
        @Override
        public void cache_operator_data(int chOffs, int opOffs, OpDataCache cache) {
            // set up the easy stuff
            cache.waveform = m_waveform[op_waveform(opOffs) % WAVEFORMS];

            // get frequency from the channel
            int block_freq = cache.block_freq = ch_block_freq(chOffs);

            // compute the keycode: block_freq is:
            //
            //     111  |
            //     21098|76543210
            //     BBBFF|FFFFFFFF
            //     ^^^??
            //
            // the 4-bit keycode uses the top 3 bits plus one of the next two bits
            int keycode = bitfield(block_freq, 10, 3) << 1;

            // lowest bit is determined by note_select(); note that it is
            // actually reversed from what the manual says, however
            keycode |= bitfield(block_freq, 9 - note_select(), 1);

            // no detune adjustment on OPL
            cache.detune = 0;

            // multiple value, as an x.1 value (0 means 0.5)
            // replace the low bit with a table lookup to give 0,1,2,3,4,5,6,7,8,9,10,10,12,12,15,15
            int multiple = op_multiple(opOffs);
            cache.multiple = ((multiple & 0xe) | bitfield(0xc2aa, multiple)) * 2;
            if (cache.multiple == 0)
                cache.multiple = 1;

            // phase step, or PHASE_STEP_DYNAMIC if PM is active; this depends on block_freq, detune,
            // and multiple, so compute it after we've done those
            if (op_lfo_pm_enable(opOffs) == 0)
                cache.phase_step = compute_phase_step(chOffs, opOffs, cache, 0);
            else
                cache.phase_step = OpDataCache.PHASE_STEP_DYNAMIC;

            // total level, scaled by 8
            cache.total_level = op_total_level(opOffs) << 3;

            // pre-add key scale level
            int ksl = op_ksl(opOffs);
            if (ksl != 0)
                cache.total_level += opl_key_scale_atten(bitfield(block_freq, 10, 3), bitfield(block_freq, 6, 4)) << ksl;

            // 4-bit sustain level, but 15 means 31 so effectively 5 bits
            cache.eg_sustain = op_sustain_level(opOffs);
            cache.eg_sustain |= (cache.eg_sustain + 1) & 0x10;
            cache.eg_sustain <<= 5;

            // determine KSR adjustment for envelope rates
            int ksrVal = keycode >> (2 * (op_ksr(opOffs) ^ 1));
            cache.eg_rate[EG_ATTACK.ordinal()] = effective_rate(op_attack_rate(opOffs) * 4, ksrVal);
            cache.eg_rate[EG_DECAY.ordinal()] = effective_rate(op_decay_rate(opOffs) * 4, ksrVal);
            cache.eg_rate[EG_SUSTAIN.ordinal()] = op_eg_sustain(opOffs) != 0 ? 0 : effective_rate(op_release_rate(opOffs) * 4, ksrVal);
            cache.eg_rate[EG_RELEASE.ordinal()] = effective_rate(op_release_rate(opOffs) * 4, ksrVal);
            cache.eg_rate[EG_DEPRESS.ordinal()] = 0x3f;
        }

        protected static int opl_compute_phase_step(int block_freq, int multiple, int lfo_raw_pm) {
            // OPL phase calculation has no detuning, but uses FNUMs like
            // the OPN version, and computes PM a bit differently

            // extract frequency number as a 12-bit fraction
            int fnum = bitfield(block_freq, 0, 10) << 2;

            // apply the phase adjustment based on the upper 3 bits
            // of FNUM and the PM depth parameters
            fnum += (lfo_raw_pm * bitfield(block_freq, 7, 3)) >> 1;

            // keep fnum to 12 bits
            fnum &= 0xfff;

            // apply block shift to compute phase step
            int block = bitfield(block_freq, 10, 3);
            int phase_step = (fnum << block) >> 2;

            // apply frequency multiplier (which is cached as an x.1 value)
            return (phase_step * multiple) >> 1;
        }

        /**
         * Computes the phase step.
         */
        @Override
        public int compute_phase_step(int chOffs, int opOffs, OpDataCache cache, int lfo_raw_pm) {
            return opl_compute_phase_step(cache.block_freq, cache.multiple, op_lfo_pm_enable(opOffs) != 0 ? lfo_raw_pm : 0);
        }

        /**
         * Logs a key-on event.
         */
        @Override
        public String log_keyOn(int chOffs, int opOffs) {
            int chNum = (chOffs & 15) + 9 * bitfield(chOffs, 8);
            int opNum = (opOffs & 31) - 2 * ((opOffs & 31) / 8) + 18 * bitfield(opOffs, 8);

            StringBuilder buffer = new StringBuilder();

            buffer.append("%2d.%02d freq=%04X fb=%d alg=%X mul=%X tl=%02X ksr=%d ns=%d ksl=%d adr=%X/%X/%X sl=%X sus=%d".formatted(
                    chNum, opNum,
                    ch_block_freq(chOffs),
                    ch_feedback(chOffs),
                    ch_algorithm(chOffs),
                    op_multiple(opOffs),
                    op_total_level(opOffs),
                    op_ksr(opOffs),
                    note_select(),
                    op_ksl(opOffs),
                    op_attack_rate(opOffs),
                    op_decay_rate(opOffs),
                    op_release_rate(opOffs),
                    op_sustain_level(opOffs),
                    op_eg_sustain(opOffs)));

            if (OUTPUTS > 1)
                buffer.append(" out=%c%c%c%c".formatted(
                        ch_output_0(chOffs) != 0 ? 'L' : '-',
                        ch_output_1(chOffs) != 0 ? 'R' : '-',
                        ch_output_2(chOffs) != 0 ? '0' : '-',
                        ch_output_3(chOffs) != 0 ? '1' : '-'));
            if (op_lfo_am_enable(opOffs) != 0)
                buffer.append(" am=%d".formatted(lfo_am_depth()));
            if (op_lfo_pm_enable(opOffs) != 0)
                buffer.append(" pm=%d".formatted(lfo_pm_depth()));
            if (waveform_enable() != 0 && op_waveform(opOffs) != 0)
                buffer.append(" wf=%d".formatted(op_waveform(opOffs)));
            if (is_rhythm(chOffs))
                buffer.append(" rhy=1");
            if (DYNAMIC_OPS) {
                int[][] map = new int[1][];
                operator_map(map);
                if (bitfield(map[0][chNum], 16, 8) != 0xff)
                    buffer.append(" 4op");
            }

            return buffer.toString();
        }

        // system-wide registers

        public final int test() {
            return byte_(0x01, 0, 8);
        }

        public final int waveform_enable() {
            return IsOpl2 ? byte_(0x01, 5, 1) : (IsOpl3Plus ? 1 : 0);
        }

        /** 8->10 bits */
        @Override
        public final int timer_a_value() {
            return byte_(0x02, 0, 8) * 4;
        }

        @Override
        public final int timer_b_value() {
            return byte_(0x03, 0, 8);
        }

        @Override
        public final int status_mask() {
            return byte_(0x04, 0, 8) & 0x78;
        }

        @Override
        public final int irq_reset() {
            return byte_(0x04, 7, 1);
        }

        @Override
        public final int reset_timer_b() {
            return byte_(0x04, 7, 1) | byte_(0x04, 5, 1);
        }

        @Override
        public final int reset_timer_a() {
            return byte_(0x04, 7, 1) | byte_(0x04, 6, 1);
        }

        @Override
        public final int enable_timer_b() {
            return 1;
        }

        @Override
        public final int enable_timer_a() {
            return 1;
        }

        @Override
        public final int load_timer_b() {
            return byte_(0x04, 1, 1);
        }

        @Override
        public final int load_timer_a() {
            return byte_(0x04, 0, 1);
        }

        @Override
        public final int csm() {
            return IsOpl3Plus ? 0 : byte_(0x08, 7, 1);
        }

        public final int note_select() {
            return byte_(0x08, 6, 1);
        }

        public final int lfo_am_depth() {
            return byte_(0xbd, 7, 1);
        }

        public final int lfo_pm_depth() {
            return byte_(0xbd, 6, 1);
        }

        @Override
        public final int rhythm_enable() {
            return byte_(0xbd, 5, 1);
        }

        public final int rhythm_keyOn() {
            return byte_(0xbd, 4, 0);
        }

        public final int newFlag() {
            return IsOpl3Plus ? byte_(0x105, 0, 1) : 0;
        }

        public final int new2flag() {
            return IsOpl4Plus ? byte_(0x105, 1, 1) : 0;
        }

        public final int fourOp_enable() {
            return IsOpl3Plus ? byte_(0x104, 0, 6) : 0;
        }

        // per-channel registers

        public final int ch_block_freq(int chOffs) {
            return word(0xb0, 0, 5, 0xa0, 0, 8, chOffs);
        }

        @Override
        public final int ch_feedback(int chOffs) {
            return byte_(0xc0, 1, 3, chOffs);
        }

        @Override
        public final int ch_algorithm(int chOffs) {
            return byte_(0xc0, 0, 1, chOffs) | (IsOpl3Plus ? (8 | (byte_(0xc3, 0, 1, chOffs) << 1)) : 0);
        }

        @Override
        public final int ch_output_any(int chOffs) {
            return newFlag() != 0 ? byte_(0xc0 + chOffs, 4, 4) : 1;
        }

        @Override
        public final int ch_output_0(int chOffs) {
            return newFlag() != 0 ? byte_(0xc0 + chOffs, 4, 1) : 1;
        }

        @Override
        public final int ch_output_1(int chOffs) {
            return newFlag() != 0 ? byte_(0xc0 + chOffs, 5, 1) : (IsOpl3Plus ? 1 : 0);
        }

        @Override
        public final int ch_output_2(int chOffs) {
            return newFlag() != 0 ? byte_(0xc0 + chOffs, 6, 1) : 0;
        }

        @Override
        public final int ch_output_3(int chOffs) {
            return newFlag() != 0 ? byte_(0xc0 + chOffs, 7, 1) : 0;
        }

        // per-operator registers

        @Override
        public final int op_lfo_am_enable(int opOffs) {
            return byte_(0x20, 7, 1, opOffs);
        }

        public final int op_lfo_pm_enable(int opOffs) {
            return byte_(0x20, 6, 1, opOffs);
        }

        public final int op_eg_sustain(int opOffs) {
            return byte_(0x20, 5, 1, opOffs);
        }

        public final int op_ksr(int opOffs) {
            return byte_(0x20, 4, 1, opOffs);
        }

        public final int op_multiple(int opOffs) {
            return byte_(0x20, 0, 4, opOffs);
        }

        public final int op_ksl(int opOffs) {
            int temp = byte_(0x40, 6, 2, opOffs);
            return bitfield(temp, 1) | (bitfield(temp, 0) << 1);
        }

        public final int op_total_level(int opOffs) {
            return byte_(0x40, 0, 6, opOffs);
        }

        public final int op_attack_rate(int opOffs) {
            return byte_(0x60, 4, 4, opOffs);
        }

        public final int op_decay_rate(int opOffs) {
            return byte_(0x60, 0, 4, opOffs);
        }

        public final int op_sustain_level(int opOffs) {
            return byte_(0x80, 4, 4, opOffs);
        }

        public final int op_release_rate(int opOffs) {
            return byte_(0x80, 0, 4, opOffs);
        }

        public final int op_waveform(int opOffs) {
            return waveform_enable() == 1 ? byte_(0xe0, 0, newFlag() != 0 ? 3 : 2, opOffs) : 0;
        }

        /** Returns a bitfield extracted from a byte */
        protected final int byte_(int offset, int start, int count) {
            return byte_(offset, start, count, 0);
        }

        /** Returns a bitfield extracted from a byte */
        protected final int byte_(int offset, int start, int count, int extra_offset /* = 0 */) {
            return bitfield(m_regdata[offset + extra_offset], start, count);
        }

        /** Returns a bitfield extracted from a pair of bytes, MSBs listed first */
        protected final int word(int offset1, int start1, int count1, int offset2, int start2, int count2, int extra_offset /* = 0 */) {
            return (byte_(offset1, start1, count1, extra_offset) << count2) | byte_(offset2, start2, count2, extra_offset);
        }

        /** Helper to determine if the this channel is an active rhythm channel */
        protected final boolean is_rhythm(int choffs) {
            return rhythm_enable() != 0 && (choffs >= 6 && choffs <= 8);
        }

        // internal state

        /** LFO AM counter */
        @Element(sequence = 1)
        protected int m_lfo_am_counter;
        /** LFO PM counter */
        @Element(sequence = 2)
        protected int m_lfo_pm_counter;
        /** noise LFSR state */
        @Element(sequence = 3)
        protected int m_noise_lfsr;
        /** current LFO AM value */
        @Element(sequence = 4)
        protected int m_lfo_am;
        /** register data */
        @Element(sequence = 5)
        protected final int[] m_regdata;
        /** waveforms */
        protected final int[][] m_waveform;
    }

    protected static class OplRegisters extends OplRegistersBase {

        OplRegisters() {
            super(1);
        }
    }

    protected static class Opl2Registers extends OplRegistersBase {

        Opl2Registers() {
            super(2);
        }
    }

    protected static class Opl3Registers extends OplRegistersBase {

        Opl3Registers() {
            super(3);
        }
    }

    protected static class Opl4Registers extends OplRegistersBase {

        Opl4Registers() {
            super(4);
        }
    }

    //
    // OPLL SPECIFICS
    //

    //
    // OPLL register map:
    //
    //      System-wide registers:
    //           0E --x----- Rhythm enable
    //              ---x---- Bass drum key on
    //              ----x--- Snare drum key on
    //              -----x-- Tom key on
    //              ------x- Top cymbal key on
    //              -------x High hat key on
    //           0F xxxxxxxx Test register
    //
    //     Per-channel registers (channel in address bits 0-3)
    //        10-18 xxxxxxxx F-number (low 8 bits)
    //        20-28 --x----- Sustain on
    //              ---x---- Key on
    //              --- xxx- Block (octvate, 0-7)
    //              -------x F-number (high bit)
    //        30-38 xxxx---- Instrument selection
    //              ----xxxx Volume
    //
    //     User instrument registers (for carrier, modulator operators)
    //        00-01 x------- AM enable
    //              -x------ PM enable (VIB)
    //              --x----- EG type
    //              ---x---- Key scale rate
    //              ----xxxx Multiple value (0-15)
    //           02 xx------ Key scale level (carrier, 0-3)
    //              --xxxxxx Total level (modulator, 0-63)
    //           03 xx------ Key scale level (modulator, 0-3)
    //              ---x---- Rectified wave (carrier)
    //              ----x--- Rectified wave (modulator)
    //              -----xxx Feedback level for operator 1 (0-7)
    //        04-05 xxxx---- Attack rate (0-15)
    //              ----xxxx Decay rate (0-15)
    //        06-07 xxxx---- Sustain level (0-15)
    //              ----xxxx Release rate (0-15)
    //
    //     Internal (fake) registers:
    //        40-48 xxxxxxxx Current instrument base address
    //        4E-5F xxxxxxxx Current instrument base address + operator slot (0/1)
    //        70-FF xxxxxxxx Data for instruments (1-16 plus 3 drums)
    //

    /** opll_registers */
    @Serdes
    protected static class OpllRegisters extends RegistersBase {

        protected static final int OUTPUTS = 2;
        protected static final int CHANNELS = 9;
        protected static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
        protected static final int OPERATORS = CHANNELS * 2;
        protected static final int WAVEFORMS = 2;
        protected static final int REGISTERS = 0x40;
        protected static final int REG_MODE = 0x3f;
        protected static final int DEFAULT_PRESCALE = 4;
        protected static final int EG_CLOCK_DIVIDER = 1;
        protected static final int CSM_TRIGGER_MASK = 0;
        protected static final boolean EG_HAS_DEPRESS = true;
        protected static final boolean MODULATOR_DELAY = true;
        protected static final int STATUS_TIMERA = 0;
        protected static final int STATUS_TIMERB = 0;
        protected static final int STATUS_BUSY = 0;
        protected static final int STATUS_IRQ = 0;

        // OPLL-specific constants
        protected static final int INSTDATA_SIZE = 0x90;

        /**
         * Constructor.
         */
        protected OpllRegisters() {
            m_lfo_am_counter = 0;
            m_lfo_pm_counter = 0;
            m_noise_lfsr = 1;
            m_lfo_am = 0;

            getParams().put("OUTPUTS", OUTPUTS);
            getParams().put("CHANNELS", CHANNELS);
            getParams().put("ALL_CHANNELS", ALL_CHANNELS);
            getParams().put("OPERATORS", OPERATORS);
            getParams().put("WAVEFORMS", WAVEFORMS);
            getParams().put("REGISTERS", REGISTERS);
            getParams().put("REG_MODE", REG_MODE);
            getParams().put("DEFAULT_PRESCALE", DEFAULT_PRESCALE);
            getParams().put("EG_CLOCK_DIVIDER", EG_CLOCK_DIVIDER);
            getParams().put("CSM_TRIGGER_MASK", CSM_TRIGGER_MASK);
            getParams().put("EG_HAS_DEPRESS", EG_HAS_DEPRESS);
            getParams().put("MODULATOR_DELAY", MODULATOR_DELAY);
            getParams().put("STATUS_TIMERA", STATUS_TIMERA);
            getParams().put("STATUS_TIMERB", STATUS_TIMERB);
            getParams().put("STATUS_BUSY", STATUS_BUSY);
            getParams().put("STATUS_IRQ", STATUS_IRQ);

            // create the waveforms
            for (int index = 0; index < WAVEFORM_LENGTH; index++)
                m_waveform[0][index] = abs_sin_attenuation(index) | (bitfield(index, 9) << 15);

            int zeroval = m_waveform[0][0];
            for (int index = 0; index < WAVEFORM_LENGTH; index++)
                m_waveform[1][index] = bitfield(index, 9) != 0 ? zeroval : m_waveform[0][index];

            // initialize the instruments to something sane
            for (int choffs = 0; choffs < CHANNELS; choffs++)
                m_chinst[choffs] = m_regdata;
            for (int opoffs = 0; opoffs < OPERATORS; opoffs++)
                m_opinst[opoffs] = Arrays.copyOfRange(m_regdata, bitfield(opoffs, 0), m_regdata.length);
        }

        /**
         * Resets to initial state.
         */
        @Override
        public void reset() {
            Arrays.fill(m_regdata, 0, REGISTERS, 0);
        }

        /**
         * Saves the data.
         */
        @Override
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
        }

        /** Maps channel number to register offset. */
        @Override
        public int channel_offset(int chNum) {
            assert (chNum < CHANNELS);
            return chNum;
        }

        /** Maps operator number to register offset. */
        @Override
        public int operator_offset(int opNum) {
            assert (opNum < OPERATORS);
            return opNum;
        }

        /** An array of operator indices for each channel */
        protected static final int[] s_fixed_map = {
                operator_list(0, 1),  // Channel 0 operators
                operator_list(2, 3),  // Channel 1 operators
                operator_list(4, 5),  // Channel 2 operators
                operator_list(6, 7),  // Channel 3 operators
                operator_list(8, 9),  // Channel 4 operators
                operator_list(10, 11),  // Channel 5 operators
                operator_list(12, 13),  // Channel 6 operators
                operator_list(14, 15),  // Channel 7 operators
                operator_list(16, 17),  // Channel 8 operators
        };

        /**
         * Returns an array of operator indices for each channel;
         * for OPLL this is fixed.
         */
        @Override
        public final void operator_map(int[][] dest) {
            dest[0] = s_fixed_map;
        }

        /** Reads a register value */
        public final int read(int address) {
            return m_regdata[address];
        }

        /**
         * Handles writes to the register array;
         * note that this code is also used by
         * ymopl3_registers, so it must handle upper
         * channels cleanly.
         */
        @Override
        public boolean write(int index, int data, int[] channel, int[] opMask) {
            // unclear the address is masked down to 6 bits or if writes above
            // the register top are ignored; assuming the latter for now
            if (index >= REGISTERS)
                return false;

            // write the new data
            m_regdata[index] = data;

            // handle writes to the rhythm keyons
            if (index == 0x0e) {
                channel[0] = RHYTHM_CHANNEL;
                opMask[0] = bitfield(data, 5) != 0 ? bitfield(data, 0, 5) : 0;
                return true;
            }

            // handle writes to the channel keyons
            if ((index & 0xf0) == 0x20) {
                channel[0] = index & 0x0f;
                if (channel[0] < CHANNELS) {
                    opMask[0] = bitfield(data, 4) != 0 ? 3 : 0;
                    return true;
                }
            }
            return false;
        }

        /**
         * Clocks the noise and LFO, handling clock division, depth, and waveform
         * computations.
         */
        @Override
        public int clock_noise_and_lfo() {
            // implementation is the same as OPL with fixed depths
            int[] a1 = new int[1];
            int[] a2 = new int[1];
            int[] a3 = new int[1];
            int[] a4 = new int[1];
            int r = OplRegistersBase.opl_clock_noise_and_lfo(a1, a2, a3, a4, 1, 1);
            m_noise_lfsr = a1[0];
            m_lfo_am_counter = a2[0];
            m_lfo_pm_counter = a3[0];
            m_lfo_am = a4[0];
            return r;
        }

        /** reset the LFO */
        public void reset_lfo() {
            m_lfo_am_counter = m_lfo_pm_counter = 0;
        }

        /**
         * Returns the AM offset from LFO for the given channel
         * on OPL this is just a fixed value
         */
        @Override
        public final int lfo_am_offset(int chOffs) {
            return m_lfo_am;
        }

        /** Returns LFO/noise states */
        @Override
        public final int noise_state() {
            return m_noise_lfsr >> 23;
        }

        /**
         * Fills the operator cache with prefetched data; note that this code is
         * also used by ymopna_registers, so it must handle upper channels cleanly
         */
        @Override
        public void cache_operator_data(int chOffs, int opOffs, OpDataCache cache) {
            // first set up the instrument data
            int instrument = ch_instrument(chOffs);
            if (rhythm_enable() != 0 && chOffs >= 6)
                m_chinst[chOffs] = Arrays.copyOfRange(m_instdata, 8 * (15 + (chOffs - 6)), m_instdata.length);
            else
                m_chinst[chOffs] = (instrument == 0) ? m_regdata : Arrays.copyOfRange(m_instdata, 8 * (instrument - 1), m_instdata.length);
            m_opinst[opOffs] = Arrays.copyOfRange(m_chinst[chOffs], bitfield(opOffs, 0), m_instdata.length);

            // set up the easy stuff
            cache.waveform = m_waveform[op_waveform(opOffs) % WAVEFORMS];

            // get frequency from the channel
            int block_freq = cache.block_freq = ch_block_freq(chOffs);

            // compute the keycode: block_freq is:
            //
            //     11  |
            //     1098|76543210
            //     BBBF|FFFFFFFF
            //     ^^^^
            //
            // the 4-bit keycode uses the top 4 bits
            int keycode = bitfield(block_freq, 8, 4);

            // no detune adjustment on OPLL
            cache.detune = 0;

            // multiple value, as an x.1 value (0 means 0.5)
            // replace the low bit with a table lookup to give 0,1,2,3,4,5,6,7,8,9,10,10,12,12,15,15
            int multiple = op_multiple(opOffs);
            cache.multiple = ((multiple & 0xe) | bitfield(0xc2aa, multiple)) * 2;
            if (cache.multiple == 0)
                cache.multiple = 1;

            // phase step, or PHASE_STEP_DYNAMIC if PM is active; this depends on
            // block_freq, detune, and multiple, so compute it after we've done those
            if (op_lfo_pm_enable(opOffs) == 0)
                cache.phase_step = compute_phase_step(chOffs, opOffs, cache, 0);
            else
                cache.phase_step = OpDataCache.PHASE_STEP_DYNAMIC;

            // total level, scaled by 8; for non-rhythm operator 0, this is the total
            // level from the instrument data; for other operators it is 4*volume
            if (bitfield(opOffs, 0) == 1 || (rhythm_enable() != 0 && chOffs >= 7))
                cache.total_level = op_volume(opOffs) * 4;
            else
                cache.total_level = ch_total_level(chOffs);
            cache.total_level <<= 3;

            // pre-add key scale level
            int ksl = op_ksl(opOffs);
            if (ksl != 0)
                cache.total_level += opl_key_scale_atten(bitfield(block_freq, 9, 3), bitfield(block_freq, 5, 4)) << ksl;

            // 4-bit sustain level, but 15 means 31 so effectively 5 bits
            cache.eg_sustain = op_sustain_level(opOffs);
            cache.eg_sustain |= (cache.eg_sustain + 1) & 0x10;
            cache.eg_sustain <<= 5;

            // The envelope diagram in the YM2413 datasheet gives values for these
            // in ms from 0->48dB. The attack/decay tables give values in ms from
            // 0->96dB, so to pick an equivalent decay rate, we want to find the
            // closest match that is 2x the 0->48dB value:
            //
            //     DP =   10ms (0->48db) ->   20ms (0->96db); decay of 12 gives   19.20ms
            //     RR =  310ms (0->48db) ->  620ms (0->96db); decay of  7 gives  613.76ms
            //     RS = 1200ms (0->48db) -> 2400ms (0->96db); decay of  5 gives 2455.04ms
            //
            // The envelope diagram for percussive sounds (eg_sustain() == 0) also uses
            // "RR" to mean both the constant RR above and the Release Rate specified in
            // the instrument data. In this case, Relief Pitcher's credit sound bears out
            // that the Release Rate is used during sustain, and that the constant RR
            // (or RS) is used during the release phase.
            final int DP = 12 * 4;
            final int RR = 7 * 4;
            final int RS = 5 * 4;

            // determine KSR adjustment for envelope rates
            int ksrVal = keycode >> (2 * (op_ksr(opOffs) ^ 1));
            cache.eg_rate[EG_DEPRESS.ordinal()] = DP;
            cache.eg_rate[EG_ATTACK.ordinal()] = effective_rate(op_attack_rate(opOffs) * 4, ksrVal);
            cache.eg_rate[EG_DECAY.ordinal()] = effective_rate(op_decay_rate(opOffs) * 4, ksrVal);
            if (op_eg_sustain(opOffs) != 0) {
                cache.eg_rate[EG_SUSTAIN.ordinal()] = 0;
                cache.eg_rate[EG_RELEASE.ordinal()] = ch_sustain(chOffs) != 0 ? RS : effective_rate(op_release_rate(opOffs) * 4, ksrVal);
            } else {
                cache.eg_rate[EG_SUSTAIN.ordinal()] = effective_rate(op_release_rate(opOffs) * 4, ksrVal);
                cache.eg_rate[EG_RELEASE.ordinal()] = ch_sustain(chOffs) != 0 ? RS : RR;
            }
        }

        /**
         * Computes the phase step.
         */
        @Override
        public int compute_phase_step(int chOffs, int opOffs, OpDataCache cache, int lfo_raw_pm) {
            // phase step computation is the same as OPL but the block_freq has one
            // more bit, which we shift in
            return opl_compute_phase_step(cache.block_freq << 1, cache.multiple, op_lfo_pm_enable(opOffs) != 0 ? lfo_raw_pm : 0);
        }

        /**
         * Logs a key-on event.
         */
        @Override
        public String log_keyOn(int chOffs, int opOffs) {
            int chnum = chOffs;
            int opnum = opOffs;

            StringBuilder buffer = new StringBuilder();

            buffer.append("%d.%02d freq=%04X inst=%X fb=%d mul=%X".formatted(
                    chnum, opnum,
                    ch_block_freq(chOffs),
                    ch_instrument(chOffs),
                    ch_feedback(chOffs),
                    op_multiple(opOffs)));

            if (bitfield(opOffs, 0) == 1 || (is_rhythm(chOffs) && chOffs >= 6))
                buffer.append(" vol=%X".formatted(op_volume(opOffs)));
            else
                buffer.append(" tl=%02X".formatted(ch_total_level(chOffs)));

            buffer.append(" ksr=%d ksl=%d adr=%X/%X/%X sl=%X sus=%d/%d".formatted(
                    op_ksr(opOffs),
                    op_ksl(opOffs),
                    op_attack_rate(opOffs),
                    op_decay_rate(opOffs),
                    op_release_rate(opOffs),
                    op_sustain_level(opOffs),
                    op_eg_sustain(opOffs),
                    ch_sustain(chOffs)));

            if (op_lfo_am_enable(opOffs) != 0)
                buffer.append(" am=1");
            if (op_lfo_pm_enable(opOffs) != 0)
                buffer.append(" pm=1");
            if (op_waveform(opOffs) != 0)
                buffer.append(" wf=1");
            if (is_rhythm(chOffs))
                buffer.append(" rhy=1");

            return buffer.toString();
        }

        /** Set the instrument data */
        public void set_instrument_data(int[] data) {
            System.arraycopy(data, 0, m_instdata, 0, INSTDATA_SIZE);
        }

        // system-wide registers

        @Override
        public final int rhythm_enable() {
            return byte_(0x0e, 5, 1);
        }

        public final int rhythm_keyOn() {
            return byte_(0x0e, 4, 0);
        }

        public final int test() {
            return byte_(0x0f, 0, 8);
        }

        public final int waveform_enable() {
            return 1;
        }

        @Override
        public final int timer_a_value() {
            return 0;
        }

        @Override
        public final int timer_b_value() {
            return 0;
        }

        @Override
        public final int status_mask() {
            return 0;
        }

        @Override
        public final int irq_reset() {
            return 0;
        }

        @Override
        public final int reset_timer_b() {
            return 0;
        }

        @Override
        public final int reset_timer_a() {
            return 0;
        }

        @Override
        public final int enable_timer_b() {
            return 0;
        }

        @Override
        public final int enable_timer_a() {
            return 0;
        }

        @Override
        public final int load_timer_b() {
            return 0;
        }

        @Override
        public final int load_timer_a() {
            return 0;
        }

        @Override
        public final int csm() {
            return 0;
        }

        // per-channel registers

        public final int ch_block_freq(int chOffs) {
            return word(0x20, 0, 4, 0x10, 0, 8, chOffs);
        }

        public final int ch_sustain(int chOffs) {
            return byte_(0x20, 5, 1, chOffs);
        }

        public final int ch_total_level(int chOffs) {
            return instChByte_(0x02, 0, 6, chOffs);
        }

        @Override
        public final int ch_feedback(int chOffs) {
            return instChByte_(0x03, 0, 3, chOffs);
        }

        @Override
        public final int ch_algorithm(int chOffs) {
            return 0;
        }

        public final int ch_instrument(int chOffs) {
            return byte_(0x30, 4, 4, chOffs);
        }

        @Override
        public final int ch_output_any(int chOffs) {
            return 1;
        }

        @Override
        public final int ch_output_0(int chOffs) {
            return !is_rhythm(chOffs) ? 1 : 0;
        }

        @Override
        public final int ch_output_1(int chOffs) {
            return is_rhythm(chOffs) ? 1 : 0;
        }

        @Override
        public final int ch_output_2(int chOffs) {
            return 0;
        }

        @Override
        public final int ch_output_3(int chOffs) {
            return 0;
        }

        // per-operator registers

        @Override
        public final int op_lfo_am_enable(int opOffs) {
            return instOpByte_(0x00, 7, 1, opOffs);
        }

        public final int op_lfo_pm_enable(int opOffs) {
            return instOpByte_(0x00, 6, 1, opOffs);
        }

        public final int op_eg_sustain(int opOffs) {
            return instOpByte_(0x00, 5, 1, opOffs);
        }

        public final int op_ksr(int opOffs) {
            return instOpByte_(0x00, 4, 1, opOffs);
        }

        public final int op_multiple(int opOffs) {
            return instOpByte_(0x00, 0, 4, opOffs);
        }

        public final int op_ksl(int opOffs) {
            return instOpByte_(0x02, 6, 2, opOffs);
        }

        public final int op_waveform(int opOffs) {
            return instChByte_(0x03, 3 + bitfield(opOffs, 0), 1, opOffs >> 1);
        }

        public final int op_attack_rate(int opOffs) {
            return instOpByte_(0x04, 4, 4, opOffs);
        }

        public final int op_decay_rate(int opOffs) {
            return instOpByte_(0x04, 0, 4, opOffs);
        }

        public final int op_sustain_level(int opOffs) {
            return instOpByte_(0x06, 4, 4, opOffs);
        }

        public final int op_release_rate(int opOffs) {
            return instOpByte_(0x06, 0, 4, opOffs);
        }

        public final int op_volume(int opOffs) {
            return byte_(0x30, 4 * bitfield(~opOffs, 0), 4, opOffs >> 1);
        }

        /** Returns a bitfield extracted from a byte */
        private int byte_(int offset, int start, int count) {
            return byte_(offset, start, count, 0);
        }

        /** Returns a bitfield extracted from a byte */
        private int byte_(int offset, int start, int count, int extra_offset /* = 0 */) {
            return bitfield(m_regdata[offset + extra_offset], start, count);
        }

        /** Returns a bitfield extracted from a pair of bytes, MSBs listed first */
        private int word(int offset1, int start1, int count1, int offset2, int start2, int count2, int extra_offset /* = 0 */) {
            return (byte_(offset1, start1, count1, extra_offset) << count2) | byte_(offset2, start2, count2, extra_offset);
        }

        // helpers to read from instrument channel/operator data

        private int instChByte_(int offset, int start, int count, int chOffs) {
            return bitfield(m_chinst[chOffs][offset], start, count);
        }

        private int instOpByte_(int offset, int start, int count, int opOffs) {
            return bitfield(m_opinst[opOffs][offset], start, count);
        }

        // helper to determine if this channel is an active rhythm channel
        private boolean is_rhythm(int choffs) {
            return rhythm_enable() != 0 && choffs >= 6;
        }

        // internal state

        /** LFO AM counter */
        @Element(sequence = 1)
        private int m_lfo_am_counter;
        /** LFO PM counter */
        @Element(sequence = 2)
        private int m_lfo_pm_counter;
        /** noise LFSR state */
        @Element(sequence = 3)
        private int m_noise_lfsr;
        /** current LFO AM value */
        @Element(sequence = 4)
        private int m_lfo_am;
        /** pointer to instrument data for each channel */
        private final int[][] m_chinst = new int[CHANNELS][];
        /** pointer to instrument data for each operator */
        private final int[][] m_opinst = new int[OPERATORS][];
        /** register data */
        @Element(sequence = 5)
        private final int[] m_regdata = new int[REGISTERS];
        /** instrument data */
        private final int[] m_instdata = new int[INSTDATA_SIZE];
        /** waveforms */
        private final int[][] m_waveform = new int[WAVEFORMS][WAVEFORM_LENGTH];
    }

    //
    // OPL IMPLEMENTATION CLASSES
    //

    //
    //  YM3526
    //

    /** Ym3526 */
    @Serdes
    public static class Ym3526 implements YmFm.Chip {

        //using output_data = fm_engine.output_data;
        protected static class FmEngine extends EngineBase<OplRegisters> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, OplRegisters.class);
            }
        }

        //public static final int OUTPUTS = fm_engine.OUTPUTS;
        @Override
        public YmFm.Output outputFactory() {
            return m_fm.outputFactory();
        }

        @Override
        public final int getOutputs(){
            return (int) m_fm.getRegisterType().getParams().get("OUTPUTS");
        }

        /**
         * Constructor.
         */
        public Ym3526(YmFm.Interface intf) {
            m_address = 0;
            m_fm = new FmEngine(intf);
        }

        /**
         * Resets the system.
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
            Serdes.Util.serialize(this, os);
            m_fm.save(os);
        }

        /**
         * Restore the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
            m_fm.restore(is);
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
            return m_fm.sample_rate(input_clock);
        }

        public void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /**
         * Reads the status register.
         */
        public int read_status() {
            return m_fm.status() | 0x06;
        }

        /**
         * Handles a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0xff;
            switch (offset & 1) {
                case 0: // status port
                    result = read_status();
                    break;

                case 1: // when A0=1 datasheet says "the data on the bus are not guaranteed"
                    break;
            }
            return result;
        }

        /**
         * Handles a write to the address register.
         */
        public void write_address(int data) {
            // YM3526 doesn't expose a busy signal, and the datasheets don't indicate
            // delays, but all other OPL chips need 12 cycles for address writes
            m_fm.intf().ymfm_set_busy_end(12 * m_fm.clock_prescale());

            // just set the address
            m_address = data;
        }

        /**
         * Handles a write to the register interface.
         */
        public void write_data(int data) {
            // YM3526 doesn't expose a busy signal, and the datasheets don't indicate
            // delays, but all other OPL chips need 84 cycles for data writes
            m_fm.intf().ymfm_set_busy_end(84 * m_fm.clock_prescale());

            // write to FM
            m_fm.write(m_address, data);
        }

        /**
         * Handles a write to the register interface.
         */
        @Override
        public void write(int offset, int data) {
            switch ((offset & 1) != 0 ? 1 : 0) {
                case 0: // address port
                    write_address(data);
                    break;

                case 1: // data port
                    write_data(data);
                    break;
            }
        }

        /**
         * Generate samples of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // update the FM content; mixing details for YM3526 need verification
                m_fm.output(output[samp].clear(), 1, 32767, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // YM3526 uses an external DAC (YM3014) with mantissa/exponent format
                // convert to 10.3 floating point value and back to simulate truncation
                output[samp].roundtrip_fp();
            }
        }

        // internal state

        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** core FM engine */
        protected final FmEngine m_fm;
    }

    //
    // Y8950
    //

    /** y8950 */
    @Serdes
    public static class Y8950 implements YmFm.Chip {

        //using fm_engine = fm_engine_base<opl_registers>;
        protected static class FmEngine extends EngineBase<OplRegisters> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, OplRegisters.class);
            }
        }

        //using output_data = fm_engine.output_data;
        @Override
        public YmFm.Output outputFactory() {
            return m_fm.outputFactory();
        }

        @Override
        public final int getOutputs() {
            return (int) m_fm.getRegisterType().getParams().get("OUTPUTS");
        }

        public static final int STATUS_ADPCM_B_PLAYING = 0x01;
        public static final int STATUS_ADPCM_B_BRDY = 0x08;
        public static final int STATUS_ADPCM_B_EOS = 0x10;
        public static final int ALL_IRQS = STATUS_ADPCM_B_BRDY | STATUS_ADPCM_B_EOS | OplRegisters.STATUS_TIMERA | OplRegisters.STATUS_TIMERB;

        /**
         * Constructor.
         */
        public Y8950(YmFm.Interface intf) {
            m_address = 0;
            m_io_ddr = 0;
            m_fm = new FmEngine(intf);
            m_adpcm_b = new Adpcm.EngineB(intf, 0);
        }

        /**
         * Resets the system.
         */
        @Override
        public void reset() {
            // reset the engines
            m_fm.reset();
            m_adpcm_b.reset();
        }

        /**
         * Saves the data.
         */
        @Override
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
            m_fm.save(os);
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
            m_fm.restore(is);
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
            return m_fm.sample_rate(input_clock);
        }

        public void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /**
         * Reads the status register.
         */
        public int read_status() {
            // start with current FM status, masking out bits we might set
            int status = m_fm.status() & ~(STATUS_ADPCM_B_EOS | STATUS_ADPCM_B_BRDY | STATUS_ADPCM_B_PLAYING);

            // insert the live ADPCM status bits
            int adpcm_status = m_adpcm_b.status();
            if ((adpcm_status & ChannelB.STATUS_EOS) != 0)
                status |= STATUS_ADPCM_B_EOS;
            if ((adpcm_status & ChannelB.STATUS_BRDY) != 0)
                status |= STATUS_ADPCM_B_BRDY;
            if ((adpcm_status & ChannelB.STATUS_PLAYING) != 0)
                status |= STATUS_ADPCM_B_PLAYING;

            // run it through the FM engine to handle interrupts for us
            return m_fm.set_reset_status(status, ~status);
        }

        /**
         * Reads the data port.
         */
        public int read_data() {
            int result = 0xff;
            switch (m_address) {
                case 0x05:  // keyboard in
                    result = m_fm.intf().ymfm_external_read(IO, 1);
                    break;

                case 0x09:  // ADPCM data
                case 0x1a:
                    result = m_adpcm_b.read(m_address - 0x07);
                    break;

                case 0x19:  // I/O data
                    result = m_fm.intf().ymfm_external_read(IO, 0);
                    break;

                default:
                    log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from Y8950 data port %02X".formatted(m_address));
                    break;
            }
            return result;
        }

        /**
         * Handles a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0xff;
            switch (offset & 1) {
                case 0: // status port
                    result = read_status();
                    break;

                case 1: // when A0=1 datasheet says "the data on the bus are not guaranteed"
                    result = read_data();
                    break;
            }
            return result;
        }

        /**
         * Handles a write to the address register.
         */
        public void write_address(int data) {
            // Y8950 doesn't expose a busy signal, but it does indicate that
            // address writes should be no faster than every 12 clocks
            m_fm.intf().ymfm_set_busy_end(12 * m_fm.clock_prescale());

            // just set the address
            m_address = data;
        }

        /**
         * Handles a write to the register interface.
         */
        public void write_data(int data) {
            // Y8950 doesn't expose a busy signal, but it does indicate that
            // data writes should be no faster than every 12 clocks for
            // registers 00-1A, or every 84 clocks for other registers
            m_fm.intf().ymfm_set_busy_end(((m_address <= 0x1a) ? 12 : 84) * m_fm.clock_prescale());

            // handle special addresses
            switch (m_address) {
                case 0x04:  // IRQ control
                    m_fm.write(m_address, data);
                    read_status();
                    break;

                case 0x06:  // keyboard out
                    m_fm.intf().ymfm_external_write(IO, 1, data);
                    break;

                case 0x08:  // split FM/ADPCM-B
                    m_adpcm_b.write(m_address - 0x07, (data & 0x0f) | 0x80);
                    m_fm.write(m_address, data & 0xc0);
                    break;

                case 0x07:  // ADPCM-B registers
                case 0x09:
                case 0x0a:
                case 0x0b:
                case 0x0c:
                case 0x0d:
                case 0x0e:
                case 0x0f:
                case 0x10:
                case 0x11:
                case 0x12:
                case 0x15:
                case 0x16:
                case 0x17:
                    m_adpcm_b.write(m_address - 0x07, data);
                    break;

                case 0x18:  // I/O direction
                    m_io_ddr = data & 0x0f;
                    break;

                case 0x19:  // I/O data
                    m_fm.intf().ymfm_external_write(IO, 0, data & m_io_ddr);
                    break;

                default:    // everything else to FM
                    m_fm.write(m_address, data);
                    break;
            }
        }

        /**
         * Handle a write to the register interface.
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
         * Generate samples of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));
                m_adpcm_b.clock();

                // update the FM content; clipping need verification
                m_fm.output(output[samp].clear(), 1, 32767, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // mix in the ADPCM; ADPCM-B is stereo, but only one channel
                // not sure how it's wired up internally
                m_adpcm_b.output(output[samp], 3);

                // Y8950 uses an external DAC (YM3014) with mantissa/exponent format
                // convert to 10.3 floating point value and back to simulate truncation
                output[samp].roundtrip_fp();
            }
        }

        // internal state

        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** data direction register for I/O */
        @Element(sequence = 2)
        protected int m_io_ddr;
        /** core FM engine */
        protected final FmEngine m_fm;
        /** ADPCM-B engine */
        protected final Adpcm.EngineB m_adpcm_b;
    }

    //
    // OPL2 IMPLEMENTATION CLASSES
    //

    //
    // YM3812
    //

    /** ym3812 */
    @Serdes
    public static class Ym3812 implements YmFm.Chip {

        protected static class FmEngine extends EngineBase<Opl2Registers> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, Opl2Registers.class);
            }
        }

        //using output_data = fm_engine.output_data;
        @Override
        public YmFm.Output outputFactory() {
            return m_fm.outputFactory();
        }

        @Override
        public final int getOutputs(){
            return (int) m_fm.getRegisterType().getParams().get("OUTPUTS");
        }

        /**
         * Constructor.
         */
        public Ym3812(YmFm.Interface intf) {
            m_address = 0;
            m_fm = new FmEngine(intf);
        }

        /**
         * Resets the system.
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
            Serdes.Util.serialize(this, os);
            m_fm.save(os);
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
            m_fm.restore(is);
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
            return m_fm.sample_rate(input_clock);
        }

        public void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /**
         * Reads the status register.
         */
        public int read_status() {
            return m_fm.status() | 0x06;
        }

        /**
         * Handles a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0xff;
            switch (offset & 1) {
                case 0: // status port
                    result = read_status();
                    break;

                case 1: // "inhibit" according to datasheet
                    break;
            }
            return result;
        }

        /**
         * Handles a write to the address register.
         */
        public void write_address(int data) {
            // YM3812 doesn't expose a busy signal, but it does indicate that
            // address writes should be no faster than every 12 clocks
            m_fm.intf().ymfm_set_busy_end(12 * m_fm.clock_prescale());

            // just set the address
            m_address = data;
        }

        /**
         * Handles a write to the register interface.
         */
        public void write_data(int data) {
            // YM3812 doesn't expose a busy signal, but it does indicate that
            // data writes should be no faster than every 84 clocks
            m_fm.intf().ymfm_set_busy_end(84 * m_fm.clock_prescale());

            // write to FM
            m_fm.write(m_address, data);
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
         * Generate samples of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // update the FM content; mixing details for YM3812 need verification
                m_fm.output(output[samp].clear(), 1, 32767, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // YM3812 uses an external DAC (YM3014) with mantissa/exponent format
                // convert to 10.3 floating point value and back to simulate truncation
                output[samp].roundtrip_fp();
            }
        }

        // internal state

        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** core FM engine */
        protected final FmEngine m_fm;
    }

    //
    // OPL3 IMPLEMENTATION CLASSES
    //

    //
    // YMF262
    //

    /** ymf262 */
    @Serdes
    public static class Ymf262 implements YmFm.Chip {

        protected static class FmEngine extends EngineBase<Opl3Registers> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, Opl3Registers.class);
            }
        }

        //using output_data = fm_engine.output_data;
        @Override
        public YmFm.Output outputFactory() {
            return m_fm.outputFactory();
        }

        @Override
        public final int getOutputs(){
            return (int) m_fm.getRegisterType().getParams().get("OUTPUTS");
        }

        /**
         * Constructor.
         */
        public Ymf262(YmFm.Interface intf) {
            m_address = 0;
            m_fm = new FmEngine(intf);
        }

        /**
         * Resets the system.
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
            Serdes.Util.serialize(this, os);
            m_fm.save(os);
        }

        /**
         * Restore the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
            m_fm.restore(is);
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
            return m_fm.sample_rate(input_clock);
        }

        public void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /**
         * Reads the status register.
         */
        public int read_status() {
            return m_fm.status();
        }

        /**
         * Handle a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0xff;
            switch (offset & 3) {
                case 0: // status port
                    result = read_status();
                    break;

                case 1:
                case 2:
                case 3:
                    log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from YMF262 offset %d".formatted(offset & 3));
                    break;
            }
            return result;
        }

        /**
         * Handles a write to the address register.
         */
        public void write_address(int data) {
            // YMF262 doesn't expose a busy signal, but it does indicate that
            // address writes should be no faster than every 32 clocks
            m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());

            // just set the address
            m_address = data;
        }

        /**
         * Handles a write to the data register.
         */
        public void write_data(int data) {
            // YMF262 doesn't expose a busy signal, but it does indicate that
            // data writes should be no faster than every 32 clocks
            m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());

            // write to FM
            m_fm.write(m_address, data);
        }

        /**
         * Handles a write to the upper address register.
         */
        public void write_address_hi(int data) {
            // YMF262 doesn't expose a busy signal, but it does indicate that
            // address writes should be no faster than every 32 clocks
            m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());

            // just set the address
            m_address = data | 0x100;

            // tests reveal that in compatibility mode, upper bit is masked
            // except for register 0x105
            if (m_fm.regs().newFlag() == 0 && m_address != 0x105)
                m_address &= 0xff;
        }

        /**
         * Handles a write to the register interface.
         */
        @Override
        public void write(int offset, int data) {
            switch (offset & 3) {
                case 0: // address port
                    write_address(data);
                    break;

                case 1: // data port
                    write_data(data);
                    break;

                case 2: // address port
                    write_address_hi(data);
                    break;

                case 3: // data port
                    write_data(data);
                    break;
            }
        }

        /**
         * Generate samples of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // update the FM content; mixing details for YMF262 need verification
                m_fm.output(output[samp].clear(), 0, 32767, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // YMF262 output is 16-bit offset serial via YAC512 DAC
                output[samp].clamp16();
            }
        }

        // internal state

        /** address register@Element */
        protected int m_address;
        /** core FM engine */
        protected final FmEngine m_fm;
    }

    //
    //  YMF289B
    //

    /**
     * ymf289b
     *
     * YMF289B is a YMF262 with the following changes:
     * <ul>
     *  <li>"Power down" mode added</li>
     *  <li>Bulk register clear added</li>
     *  <li>Busy flag added to the status register</li>
     *  <li>Shorter busy times</li>
     *  <li>All registers can be read</li>
     *  <li>Only 2 outputs exposed</li>
     * </ul>
     */
    @Serdes
    public static class Ymf289b implements YmFm.Chip {

        protected static final int STATUS_BUSY_FLAGS = 0x05;

        protected static class FmEngine extends EngineBase<Opl3Registers> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, Opl3Registers.class);
            }
        }

        //using output_data = fm_engine.output_data;
        @Override
        public YmFm.Output outputFactory() {
            return m_fm.outputFactory();
        }

        private static final int OUTPUTS = 2;

        @Override
        public final int getOutputs(){
            return OUTPUTS;
        }

        /**
         * Constructor.
         */
        public Ymf289b(YmFm.Interface intf) {
            m_address = 0;
            m_fm = new FmEngine(intf);
        }

        /**
         * Resets the system.
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
            Serdes.Util.serialize(this, os);
            m_fm.save(os);
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
            m_fm.restore(is);
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
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

            // YMF289B adds a busy flag
            if (ymf289b_mode() && m_fm.intf().ymfm_is_busy())
                result |= STATUS_BUSY_FLAGS;
            return result;
        }

        /**
         * Reads the data register.
         */
        public int read_data() {
            int result = 0xff;

            // YMF289B can read register data back
            if (ymf289b_mode())
                result = m_fm.regs().read(m_address);
            return result;
        }

        /**
         * Handles a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0xff;
            switch (offset & 3) {
                case 0: // status port
                    result = read_status();
                    break;

                case 1:    // data port
                    result = read_data();
                    break;

                case 2:
                case 3:
                    log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from YMF289B offset %d".formatted(offset & 3));
                    break;
            }
            return result;
        }

        /**
         * Handles a write to the address register.
         */
        public void write_address(int data) {
            m_address = data;

            // count busy time
            m_fm.intf().ymfm_set_busy_end(56);
        }

        /**
         * Handle a write to the data register.
         */
        public void write_data(int data) {
            // write to FM
            m_fm.write(m_address, data);

            // writes to 0x108 with the CLR flag set clear the registers
            if (m_address == 0x108 && bitfield(data, 2) != 0)
                m_fm.regs().reset();

            // count busy time
            m_fm.intf().ymfm_set_busy_end(56);
        }

        /**
         * Handles a write to the upper address register.
         */
        public void write_address_hi(int data) {
            // just set the address
            m_address = data | 0x100;

            // tests reveal that in compatibility mode, upper bit is masked
            // except for register 0x105
            if (m_fm.regs().newFlag() == 0 && m_address != 0x105)
                m_address &= 0xff;

            // count busy time
            m_fm.intf().ymfm_set_busy_end(56);
        }

        /**
         * Handles a write to the register interface.
         */
        @Override
        public void write(int offset, int data) {
            switch (offset & 3) {
                case 0: // address port
                    write_address(data);
                    break;

                case 1: // data port
                    write_data(data);
                    break;

                case 2: // address port
                    write_address_hi(data);
                    break;

                case 3: // data port
                    write_data(data);
                    break;
            }
        }

        /**
         * Generates samples of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // update the FM content; mixing details for YMF262 need verification
                YmFm.Output full = m_fm.outputFactory();
                m_fm.output(full.clear(), 0, 32767, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // YMF278B output is 16-bit offset serial via YAC512 DAC, but
                // only 2 of the 4 outputs are exposed
                output[samp].data[0] = full.data[0];
                output[samp].data[1] = full.data[1];
                output[samp].clamp16();
            }
        }

        // internal helpers

        protected boolean ymf289b_mode() {
            return ((m_fm.regs().read(0x105) & 0x04) != 0);
        }

        // internal state

        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** core FM engine */
        protected final FmEngine m_fm;
    }

    //
    // OPL4 IMPLEMENTATION CLASSES
    //

    //
    //  YMF278B
    //

    /** ymf278b */
    @Serdes
    public static class Ymf278b implements YmFm.Chip {

        // Using the nominal datasheet frequency of 33.868MHz, the output of the
        // chip will be clock/768 = 44.1kHz. However, the FM engine is clocked
        // internally at clock/(19*36), or 49.515kHz, so the FM output needs to
        // be downsampled. We treat this as needing to clock the FM engine an
        // extra tick every few samples. The exact ratio is 768/(19*36) or
        // 768/684 = 192/171. So if we always clock the FM once, we'll have
        // 192/171 - 1 = 21/171 left. Thus we count 21 for each sample and when
        // it gets above 171, we tick an extra time.
        protected static final int FM_EXTRA_SAMPLE_THRESH = 171;
        protected static final int FM_EXTRA_SAMPLE_STEP = 192 - FM_EXTRA_SAMPLE_THRESH;

        protected static class FmEngine extends EngineBase<Opl4Registers> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, Opl4Registers.class);
            }
        }

        private static final int OUTPUTS = 6;

        //using output_data = YmFm.Output<OUTPUTS>;
        @Override
        public YmFm.Output outputFactory() {
            return new YmFm.Output(OUTPUTS);
        }

        @Override
        public final int getOutputs(){
            return OUTPUTS;
        }

        protected static final int STATUS_BUSY = 0x01;
        protected static final int STATUS_LD = 0x02;

        /**
         * Constructor.
         */
        public Ymf278b(YmFm.Interface intf) {
            m_address = 0;
            m_fm_pos = 0;
            m_load_remaining = 0;
            m_next_status_id = false;
            m_fm = new FmEngine(intf);
            m_pcm = new Pcm.Engine(intf);
        }

        /**
         * Resets the system.
         */
        @Override
        public void reset() {
            // reset the engines
            m_fm.reset();
            m_pcm.reset();

            // next status read will return ID
            m_next_status_id = true;
        }

        /**
         * Saves the data.
         */
        @Override
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
            m_fm.save(os);
            m_pcm.save(os);
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
            m_fm.restore(is);
            m_pcm.restore(is);
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
            return input_clock / 768;
        }

        public void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /**
         * Reads the status register.
         */
        public int read_status() {
            int result;

            // first status read after initialization returns a chip ID, which
            // varies based on the "new" flags, indicating the mode
            if (m_next_status_id) {
                if (m_fm.regs().new2flag() != 0)
                    result = 0x02;
                else if (m_fm.regs().newFlag() != 0)
                    result = 0x00;
                else
                    result = 0x06;
                m_next_status_id = false;
            } else {
                result = m_fm.status();
                if (m_fm.intf().ymfm_is_busy())
                    result |= STATUS_BUSY;
                if (m_load_remaining != 0)
                    result |= STATUS_LD;

                // if new2 flag is not set, we're in OPL2 or OPL3 mode
                if (m_fm.regs().new2flag() == 0)
                    result &= ~(STATUS_BUSY | STATUS_LD);
            }
            return result;
        }

        /**
         * Handles a write to the PCM data register.
         */
        public int read_data_pcm() {
            // read from PCM
            if (bitfield(m_address, 9) != 0) {
                int result = m_pcm.read(m_address & 0xff);
                if ((m_address & 0xff) == 0x02)
                    result |= 0x20;

                return result;
            }
            return 0;
        }

        /**
         * Handle a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0xff;
            switch (offset & 7) {
                case 0: // status port
                    result = read_status();
                    break;

                case 5: // PCM data port
                    result = read_data_pcm();
                    break;

                default:
                    log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from ymf278b offset %d".formatted(offset & 3));
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
         * Handle a write to the data register.
         */
        public void write_data(int data) {
            // write to FM
            if (bitfield(m_address, 9) == 0) {
                int old = m_fm.regs().new2flag();
                m_fm.write(m_address, data);

                // changing NEW2 from 0->1 causes the next status read to
                // return the chip ID
                if (old == 0 && m_fm.regs().new2flag() != 0)
                    m_next_status_id = true;
            }

            // BUSY goes for 56 clocks on FM writes
            m_fm.intf().ymfm_set_busy_end(56);
        }

        /**
         * Handle a write to the upper address register.
         */
       public void write_address_hi(int data) {
            // just set the address
            m_address = data | 0x100;

            // YMF262, in compatibility mode, treats the upper bit as masked
            // except for register 0x105; assuming YMF278B works the same way?
            if (m_fm.regs().newFlag() == 0 && m_address != 0x105)
                m_address &= 0xff;
       }

        /**
         * Handles a write to the upper address register.
         */
        public void write_address_pcm(int data) {
            // just set the address
            m_address = data | 0x200;
        }

        /**
         * Handles a write to the PCM data register.
         */
        public void write_data_pcm(int data) {
            // ignore data writes if new2 is not yet set
            if (m_fm.regs().new2flag() != 0)
                return;

            // write to FM
            if (bitfield(m_address, 9) != 0) {
                int addr = m_address & 0xff;
                m_pcm.write(addr, data);

                // writes to the waveform number cause loads to happen for "about 300usec"
                // which is ~13 samples at the nominal output frequency of 44.1kHz
                if (addr >= 0x08 && addr <= 0x1f)
                    m_load_remaining = 13;
            }

            // BUSY goes for 88 clocks on PCM writes
            m_fm.intf().ymfm_set_busy_end(88);
        }

        /**
         * Handles a write to the register interface.
         */
        @Override
        public void write(int offset, int data) {
            switch (offset & 7) {
                case 0: // address port
                    write_address(data);
                    break;

                case 1: // data port
                    write_data(data);
                    break;

                case 2: // address port
                    write_address_hi(data);
                    break;

                case 3: // data port
                    write_data(data);
                    break;

                case 4: // PCM address port
                    write_address_pcm(data);
                    break;

                case 5: // PCM address port
                    write_data_pcm(data);
                    break;

                default:
                    log_unexpected_read_write.log(Level.DEBUG, "Unexpected write to ymf278b offset %d".formatted(offset & 7));
                    break;
            }
        }

        private static final int[] s_mix_scale = {0x7fa, 0x5a4, 0x3fd, 0x2d2, 0x1fe, 0x169, 0xff, 0};

        /**
         * Generate one sample of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            int pcm_l = s_mix_scale[m_pcm.regs().mix_pcm_l()];
            int pcm_r = s_mix_scale[m_pcm.regs().mix_pcm_r()];
            int fm_l = s_mix_scale[m_pcm.regs().mix_fm_l()];
            int fm_r = s_mix_scale[m_pcm.regs().mix_fm_r()];

            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm_pos += FM_EXTRA_SAMPLE_STEP;
                if (m_fm_pos >= FM_EXTRA_SAMPLE_THRESH) {
                    m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));
                    m_fm_pos -= FM_EXTRA_SAMPLE_THRESH;
                }
                m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));
                m_pcm.clock(Pcm.Engine.ALL_CHANNELS);

                // update the FM content; mixing details for YMF278B need verification
                YmFm.Output fmout = m_fm.outputFactory();
                m_fm.output(fmout.clear(), 0, 32767, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // update the PCM content
                YmFm.Output pcmout = new YmFm.Output(Pcm.Registers.OUTPUTS);
                m_pcm.output(pcmout.clear(), Pcm.Engine.ALL_CHANNELS);

                // DO0 output: FM channels 2+3 only
                output[samp].data[0] = fmout.data[2];
                output[samp].data[1] = fmout.data[3];

                // DO1 output: wavetable channels 2+3 only
                output[samp].data[2] = pcmout.data[2];
                output[samp].data[3] = pcmout.data[3];

                // DO2 output: mixed FM channels 0+1 and wavetable channels 0+1
                output[samp].data[4] = (fmout.data[0] * fm_l + pcmout.data[0] * pcm_l) >> 11;
                output[samp].data[5] = (fmout.data[1] * fm_r + pcmout.data[1] * pcm_r) >> 11;

                // YMF278B output is 16-bit 2s complement serial
                output[samp].clamp16();
            }

            // decrement the load waiting count
            if (m_load_remaining > 0)
                m_load_remaining -= Math.min(m_load_remaining, numSamples);
        }

        // internal state

        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** FM resampling position */
        @Element(sequence = 2)
        protected int m_fm_pos;
        /** how many more samples until LD flag clears */
        @Element(sequence = 3)
        protected int m_load_remaining;
        /** flag to track which status ID to return */
        @Element(sequence = 4)
        protected boolean m_next_status_id;
        /** core FM engine */
        protected final FmEngine m_fm;
        /** core PCM engine */
        protected final Pcm.Engine m_pcm;
    }

    //
    // OPLL IMPLEMENTATION CLASSES
    //

    //
    // OPLL BASE
    //

    /** opll_base */
    @Serdes
    static class OpllBase implements YmFm.Chip {

        protected static class FmEngine extends EngineBase<OpllRegisters> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, OpllRegisters.class);
            }
        }

        //using output_data = fm_engine.output_data;
        @Override
        public YmFm.Output outputFactory() {
            return m_fm.outputFactory();
        }

        @Override
        public final int getOutputs(){
            return  OpllRegisters.OUTPUTS;
        }

        /**
         * Constructor.
         */
        public OpllBase(YmFm.Interface intf, int[] data) {
            m_address = 0;
            m_fm = new FmEngine(intf);

            m_fm.regs().set_instrument_data(data);
        }

        // configuration

        public void set_instrument_data(int[] data) {
            m_fm.regs().set_instrument_data(data);
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
            Serdes.Util.serialize(this, os);
            m_fm.save(os);
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
            m_fm.restore(is);
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
            return m_fm.sample_rate(input_clock);
        }

        public void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /** Doesn't really have any, but provide these for consistency */
        public int read_status() {
            return 0x00;
        }

        @Override
        public int read(int offset) {
            return 0x00;
        }

        /**
         * Handles a write to the address register.
         */
        public void write_address(int data) {
            // OPLL doesn't expose a busy signal, but datasheets are pretty consistent
            // in indicating that address writes should be no faster than every 12 clocks
            m_fm.intf().ymfm_set_busy_end(12);

            // just set the address
            m_address = data;
        }

        /**
         * Handles a write to the register interface.
         */
        public void write_data(int data) {
            // OPLL doesn't expose a busy signal, but datasheets are pretty consistent
            // in indicating that address writes should be no faster than every 84 clocks
            m_fm.intf().ymfm_set_busy_end(84);

            // write to FM
            m_fm.write(m_address, data);
        }

        /**
         * Handle a write to the register interface.
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
         * Generate one sample of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm.clock(OpllRegisters.ALL_CHANNELS);

                // update the FM content; OPLL has a built-in 9-bit DAC
                m_fm.output(output[samp].clear(), 5, 256, OpllRegisters.ALL_CHANNELS);

                // final output is multiplexed; we don't simulate that here except
                // to average over everything
                output[samp].data[0] = (output[samp].data[0] * 128) / 9;
                output[samp].data[1] = (output[samp].data[1] * 128) / 9;
            }
        }

        // internal state

        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** core FM engine */
        protected final FmEngine m_fm;
    }

    //
    // YM2413
    //

    /** ym2413 */
    @Serdes
    public static class Ym2413 extends OpllBase implements YmFm.Chip {

        /** table below taken from https://github.com/plgDavid/misc/wiki/Copyright-free-OPLL(x)-ROM-patches */
        private static final int[] s_default_instruments = {
                // April 2015 David Viens, tweaked May 19-21th 2015 Hubert Lamontagne
                0x71, 0x61, 0x1E, 0x17, 0xEF, 0x7F, 0x00, 0x17, // Violin
                0x13, 0x41, 0x1A, 0x0D, 0xF8, 0xF7, 0x23, 0x13, // Guitar
                0x13, 0x01, 0x99, 0x00, 0xF2, 0xC4, 0x11, 0x23, // Piano
                0x31, 0x61, 0x0E, 0x07, 0x98, 0x64, 0x70, 0x27, // Flute
                0x22, 0x21, 0x1E, 0x06, 0xBF, 0x76, 0x00, 0x28, // Clarinet
                0x31, 0x22, 0x16, 0x05, 0xE0, 0x71, 0x0F, 0x18, // Oboe
                0x21, 0x61, 0x1D, 0x07, 0x82, 0x8F, 0x10, 0x07, // Trumpet
                0x23, 0x21, 0x2D, 0x14, 0xFF, 0x7F, 0x00, 0x07, // Organ
                0x41, 0x61, 0x1B, 0x06, 0x64, 0x65, 0x10, 0x17, // Horn
                0x61, 0x61, 0x0B, 0x18, 0x85, 0xFF, 0x81, 0x07, // Synthesizer
                0x13, 0x01, 0x83, 0x11, 0xFA, 0xE4, 0x10, 0x04, // Harpsichord
                0x17, 0x81, 0x23, 0x07, 0xF8, 0xF8, 0x22, 0x12, // Vibraphone
                0x61, 0x50, 0x0C, 0x05, 0xF2, 0xF5, 0x29, 0x42, // Synthesizer Bass
                0x01, 0x01, 0x54, 0x03, 0xC3, 0x92, 0x03, 0x02, // Acoustic Bass
                0x41, 0x41, 0x89, 0x03, 0xF1, 0xE5, 0x11, 0x13, // Electric Guitar
                0x01, 0x01, 0x18, 0x0F, 0xDF, 0xF8, 0x6A, 0x6D, // rhythm 1
                0x01, 0x01, 0x00, 0x00, 0xC8, 0xD8, 0xA7, 0x48, // rhythm 2
                0x05, 0x01, 0x00, 0x00, 0xF8, 0xAA, 0x59, 0x55  // rhythm 3
        };

        public Ym2413(YmFm.Interface intf) {
            this(intf, null);
        }

        /**
         * Constructor.
         */
        public Ym2413(YmFm.Interface intf, int[] instrument_data /* = null */) {
            super(intf, (instrument_data != null) ? instrument_data : s_default_instruments);
        }
    }

    //
    //  YM2423
    //

    /** ym2413 */
    @Serdes
    public static class Ym2423 extends OpllBase implements YmFm.Chip {

        /** table below taken from https://github.com/plgDavid/misc/wiki/Copyright-free-OPLL(x)-ROM-patches */
        private static final int[] s_default_instruments = {
                // May 4-6 2016 Hubert Lamontagne
                // Doesn't seem to have any diff between opllx-x and opllx-y
                // Drums seem identical to regular opll
                0x61, 0x61, 0x1B, 0x07, 0x94, 0x5F, 0x10, 0x06, // 1	Strings	Saw wave with vibrato Violin
                0x93, 0xB1, 0x51, 0x04, 0xF3, 0xF2, 0x70, 0xFB, // 2	Guitar	Jazz GuitarPiano
                0x41, 0x21, 0x11, 0x85, 0xF2, 0xF2, 0x70, 0x75, // 3	Electric Guitar	Same as OPLL No.15 Synth
                0x93, 0xB2, 0x28, 0x07, 0xF3, 0xF2, 0x70, 0xB4, // 4	Electric Piano 2	Slow attack, tremoloDing-a-ling
                0x72, 0x31, 0x97, 0x05, 0x51, 0x6F, 0x60, 0x09, // 5 	Flute	Same as OPLL No.4Clarinet
                0x13, 0x30, 0x18, 0x06, 0xF7, 0xF4, 0x50, 0x85, // 6	Marimba 	Also be used as steel drumXyophone
                0x51, 0x31, 0x1C, 0x07, 0x51, 0x71, 0x20, 0x26, // 7	Trumpet 	Same as OPLL No.7Trumpet
                0x41, 0xF4, 0x1B, 0x07, 0x74, 0x34, 0x00, 0x06, // 8	Harmonica Harmonica synth
                0x50, 0x30, 0x4D, 0x03, 0x42, 0x65, 0x20, 0x06, // 9	Tuba Tuba
                0x40, 0x20, 0x10, 0x85, 0xF3, 0xF5, 0x20, 0x04, // 10 	Synth Brass 2 Synth sweep
                0x61, 0x61, 0x1B, 0x07, 0xC5, 0x96, 0xF3, 0xF6, // 11 	Short Saw	Saw wave with short envelopeSynth hit
                0xF9, 0xF1, 0xDC, 0x00, 0xF5, 0xF3, 0x77, 0xF2, // 12 	Vibraphone	Bright vibraphoneVibes
                0x60, 0xA2, 0x91, 0x03, 0x94, 0xC1, 0xF7, 0xF7, // 13 	Electric Guitar 2	Clean guitar with feedbackHarmonic bass
                0x30, 0x30, 0x17, 0x06, 0xF3, 0xF1, 0xB7, 0xFC, // 14 	Synth Bass 2Snappy bass
                0x31, 0x36, 0x0D, 0x05, 0xF2, 0xF4, 0x27, 0x9C, // 15 	Sitar	Also be used as ShamisenBanjo
                0x01, 0x01, 0x18, 0x0F, 0xDF, 0xF8, 0x6A, 0x6D, // rhythm 1
                0x01, 0x01, 0x00, 0x00, 0xC8, 0xD8, 0xA7, 0x48, // rhythm 2
                0x05, 0x01, 0x00, 0x00, 0xF8, 0xAA, 0x59, 0x55  // rhythm 3
        };

        public Ym2423(YmFm.Interface intf) {
            this(intf, null);
        }

        /**
         * Constructor.
         */
        public Ym2423(YmFm.Interface intf, int[] instrument_data /*= null */) {
            super(intf, (instrument_data != null) ? instrument_data : s_default_instruments);
        }
    }

    //
    // YMF281
    //

    /** ymf281 */
    @Serdes
    public static class Ymf281 extends OpllBase implements YmFm.Chip {

        /** table below taken from https://github.com/plgDavid/misc/wiki/Copyright-free-OPLL(x)-ROM-patches */
        private static final int[] s_default_instruments = {
                // May 14th 2015 Hubert Lamontagne
                0x72, 0x21, 0x1A, 0x07, 0xF6, 0x64, 0x01, 0x16, // Clarinet ~~ Electric String 	Square wave with vibrato
                0x00, 0x10, 0x45, 0x00, 0xF6, 0x83, 0x73, 0x63, // Synth Bass ~~ Bow wow 	Triangular wave
                0x13, 0x01, 0x96, 0x00, 0xF1, 0xF4, 0x31, 0x23, // Piano ~~ Electric Guitar 	Despite of its name, same as Piano of YM2413.
                0x71, 0x21, 0x0B, 0x0F, 0xF9, 0x64, 0x70, 0x17, // Flute ~~ Organ 	Sine wave
                0x02, 0x21, 0x1E, 0x06, 0xF9, 0x76, 0x00, 0x28, // Square Wave ~~ Clarinet 	Same as ones of YM2413.
                0x00, 0x61, 0x82, 0x0E, 0xF9, 0x61, 0x20, 0x27, // Space Oboe ~~ Saxophone 	Saw wave with vibrato
                0x21, 0x61, 0x1B, 0x07, 0x84, 0x8F, 0x10, 0x07, // Trumpet ~~ Trumpet 	Same as ones of YM2413.
                0x37, 0x32, 0xCA, 0x02, 0x66, 0x64, 0x47, 0x29, // Wow Bell ~~ Street Organ 	Calliope
                0x41, 0x41, 0x07, 0x03, 0xF5, 0x70, 0x51, 0xF5, // Electric Guitar ~~ Synth Brass 	Same as Synthesizer of YM2413.
                0x36, 0x01, 0x5E, 0x07, 0xF2, 0xF3, 0xF7, 0xF7, // Vibes ~~ Electric Piano 	Simulate of Rhodes Piano
                0x00, 0x00, 0x18, 0x06, 0xC5, 0xF3, 0x20, 0xF2, // Bass ~~ Bass 	Electric bass
                0x17, 0x81, 0x25, 0x07, 0xF7, 0xF3, 0x21, 0xF7, // Vibraphone ~~ Vibraphone	Same as ones of YM2413.
                0x35, 0x64, 0x00, 0x00, 0xFF, 0xF3, 0x77, 0xF5, // Vibrato Bell ~~ Chime 	Bell
                0x11, 0x31, 0x00, 0x07, 0xDD, 0xF3, 0xFF, 0xFB, // Click Sine ~~ Tom Tom II 	Tom
                0x3A, 0x21, 0x00, 0x07, 0x95, 0x84, 0x0F, 0xF5, // Noise and Tone ~~ Noise 	for S.E.
                0x01, 0x01, 0x18, 0x0F, 0xDF, 0xF8, 0x6A, 0x6D, // rhythm 1
                0x01, 0x01, 0x00, 0x00, 0xC8, 0xD8, 0xA7, 0x48, // rhythm 2
                0x05, 0x01, 0x00, 0x00, 0xF8, 0xAA, 0x59, 0x55  // rhythm 3
        };

        public Ymf281(YmFm.Interface intf) {
            this(intf, null);
        }

        /**
         * Constructor.
         */
        public Ymf281(YmFm.Interface intf, int[] instrument_data /* = null */) {
            super(intf, (instrument_data != null) ? instrument_data : s_default_instruments);
        }
    }

    //
    // DS1001
    //

    /** Ds1001 */
    @Serdes
    public static class Ds1001 extends OpllBase implements YmFm.Chip {

        /** table below taken from https://github.com/plgDavid/misc/wiki/Copyright-free-OPLL(x)-ROM-patches */
        private static final int[] s_default_instruments = {
                // May 15th 2015 Hubert Lamontagne & David Viens
                0x03, 0x21, 0x05, 0x06, 0xC8, 0x81, 0x42, 0x27, // Buzzy Bell
                0x13, 0x41, 0x14, 0x0D, 0xF8, 0xF7, 0x23, 0x12, // Guitar
                0x31, 0x11, 0x08, 0x08, 0xFA, 0xC2, 0x28, 0x22, // Wurly
                0x31, 0x61, 0x0C, 0x07, 0xF8, 0x64, 0x60, 0x27, // Flute
                0x22, 0x21, 0x1E, 0x06, 0xFF, 0x76, 0x00, 0x28, // Clarinet
                0x02, 0x01, 0x05, 0x00, 0xAC, 0xF2, 0x03, 0x02, // Synth
                0x21, 0x61, 0x1D, 0x07, 0x82, 0x8F, 0x10, 0x07, // Trumpet
                0x23, 0x21, 0x22, 0x17, 0xFF, 0x73, 0x00, 0x17, // Organ
                0x15, 0x11, 0x25, 0x00, 0x41, 0x71, 0x00, 0xF1, // Bells
                0x95, 0x01, 0x10, 0x0F, 0xB8, 0xAA, 0x50, 0x02, // Vibes
                0x17, 0xC1, 0x5E, 0x07, 0xFA, 0xF8, 0x22, 0x12, // Vibraphone
                0x71, 0x23, 0x11, 0x06, 0x65, 0x74, 0x10, 0x16, // Tutti
                0x01, 0x02, 0xD3, 0x05, 0xF3, 0x92, 0x83, 0xF2, // Fretless
                0x61, 0x63, 0x0C, 0x00, 0xA4, 0xFF, 0x30, 0x06, // Synth Bass
                0x21, 0x62, 0x0D, 0x00, 0xA1, 0xFF, 0x50, 0x08, // Sweep
                0x01, 0x01, 0x18, 0x0F, 0xDF, 0xF8, 0x6A, 0x6D, // rhythm 1
                0x01, 0x01, 0x00, 0x00, 0xC8, 0xD8, 0xA7, 0x48, // rhythm 2
                0x05, 0x01, 0x00, 0x00, 0xF8, 0xAA, 0x59, 0x55  // rhythm 3
        };

        public Ds1001(YmFm.Interface intf) {
            this(intf, null);
        }

        /** Constructor */
        public Ds1001(YmFm.Interface intf, int[] instrument_data /*= null */) {
            super(intf, (instrument_data != null) ? instrument_data : s_default_instruments);
        }
    }

    //
    // EXPLICIT INSTANTIATION
    //

    //template class OplRegistersBase<4>;
    //template class EngineBase<OplRegistersBase<4>>;
}
