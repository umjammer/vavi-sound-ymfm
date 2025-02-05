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
import java.util.function.BiConsumer;

import vavi.sound.ymfm.Adpcm.ChannelB;
import vavi.sound.ymfm.Fm.EngineBase;
import vavi.sound.ymfm.Fm.OpDataCache;
import vavi.sound.ymfm.YmFm.EnvelopeState;
import vavi.sound.ymfm.YmFm.Output;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static vavi.sound.ymfm.Opn.Fidelity.MAX;
import static vavi.sound.ymfm.YmFm.Debug.log_unexpected_read_write;
import static vavi.sound.ymfm.YmFm.abs_sin_attenuation;
import static vavi.sound.ymfm.YmFm.bitfield;
import static vavi.sound.ymfm.YmFm.clamp;
import static vavi.sound.ymfm.YmFm.detune_adjustment;
import static vavi.sound.ymfm.YmFm.opn_lfo_pm_phase_adjustment;


public abstract class Opn {

    //
    // REGISTER CLASSES
    //

    //
    // OPN/OPNA REGISTERS
    //

    //
    // OPN register map:
    //
    //      System-wide registers:
    //           21 xxxxxxxx Test register
    //           22 ----x--- LFO enable [OPNA+ only]
    //              -----xxx LFO rate [OPNA+ only]
    //           24 xxxxxxxx Timer A value (upper 8 bits)
    //           25 ------xx Timer A value (lower 2 bits)
    //           26 xxxxxxxx Timer B value
    //           27 xx------ CSM/Multi-frequency mode for channel #2
    //              --x----- Reset timer B
    //              ---x---- Reset timer A
    //              ----x--- Enable timer B
    //              -----x-- Enable timer A
    //              ------x- Load timer B
    //              -------x Load timer A
    //           28 x------- Key on/off operator 4
    //              -x------ Key on/off operator 3
    //              --x----- Key on/off operator 2
    //              ---x---- Key on/off operator 1
    //              ------xx Channel select
    //
    //     Per-channel registers (channel in address bits 0-1)
    //     Note that all these apply to address+100 as well on OPNA+
    //        A0-A3 xxxxxxxx Frequency number lower 8 bits
    //        A4-A7 --xxx--- Block (0-7)
    //              -----xxx Frequency number upper 3 bits
    //        B0-B3 --xxx--- Feedback level for operator 1 (0-7)
    //              -----xxx Operator connection algorithm (0-7)
    //        B4-B7 x------- Pan left [OPNA]
    //              -x------ Pan right [OPNA]
    //              --xx---- LFO AM shift (0-3) [OPNA+ only]
    //              -----xxx LFO PM depth (0-7) [OPNA+ only]
    //
    //     Per-operator registers (channel in address bits 0-1, operator in bits 2-3)
    //     Note that all these apply to address+100 as well on OPNA+
    //        30-3F -xxx---- Detune value (0-7)
    //              ----xxxx Multiple value (0-15)
    //        40-4F -xxxxxxx Total level (0-127)
    //        50-5F xx------ Key scale rate (0-3)
    //              ---xxxxx Attack rate (0-31)
    //        60-6F x------- LFO AM enable [OPNA]
    //              ---xxxxx Decay rate (0-31)
    //        70-7F ---xxxxx Sustain rate (0-31)
    //        80-8F xxxx---- Sustain level (0-15)
    //              ----xxxx Release rate (0-15)
    //        90-9F ----x--- SSG-EG enable
    //              -----xxx SSG-EG envelope (0-7)
    //
    //     Special multi-frequency registers (channel implicitly #2; operator in address bits 0-1)
    //        A8-AB xxxxxxxx Frequency number lower 8 bits
    //        AC-AF --xxx--- Block (0-7)
    //              -----xxx Frequency number upper 3 bits
    //
    //     Internal (fake) registers:
    //        B8-BB --xxxxxx Latched frequency number upper bits (from A4-A7)
    //        BC-BF --xxxxxx Latched frequency number upper bits (from AC-AF)
    //

    /** opn_registers_base */
    @Serdes
    abstract static class RegistersBase extends Fm.RegistersBase {

        //template<boolean IsOpnA>
        protected final boolean IsOpnA;

        // constants
        private final int OUTPUTS;
        private final int CHANNELS;
        private final int ALL_CHANNELS;
        private final int OPERATORS;
        private static final int WAVEFORMS = 1;
        private final int REGISTERS;
        private static final int REG_MODE = 0x27;
        private static final int DEFAULT_PRESCALE = 6;
        private static final int EG_CLOCK_DIVIDER = 3;
        private static final boolean EG_HAS_SSG = true;
        private static final boolean MODULATOR_DELAY = false;
        private static final int CSM_TRIGGER_MASK = 1 << 2;
        protected static final int STATUS_TIMERA = 0x01;
        protected static final int STATUS_TIMERB = 0x02;
        protected static final int STATUS_BUSY = 0x80;
        protected static final int STATUS_IRQ = 0;

        /**
         * Constructor.
         */
        protected RegistersBase(boolean IsOpnA) {
            this.IsOpnA = IsOpnA;

            m_lfo_counter = 0;
            m_lfo_am = 0;

            OUTPUTS = IsOpnA ? 2 : 1;
            CHANNELS = IsOpnA ? 6 : 3;
            ALL_CHANNELS = (1 << CHANNELS) - 1;
            OPERATORS = CHANNELS * 4;
            REGISTERS = IsOpnA ? 0x200 : 0x100;

            getParams().put("OUTPUTS", OUTPUTS);
            getParams().put("CHANNELS", CHANNELS);
            getParams().put("ALL_CHANNELS", ALL_CHANNELS);
            getParams().put("OPERATORS", OPERATORS);
            getParams().put("WAVEFORMS", WAVEFORMS);
            getParams().put("REGISTERS", REGISTERS);
            getParams().put("REG_MODE", REG_MODE);
            getParams().put("DEFAULT_PRESCALE", DEFAULT_PRESCALE);
            getParams().put("EG_CLOCK_DIVIDER", EG_CLOCK_DIVIDER);
            getParams().put("EG_HAS_SSG", EG_HAS_SSG);
            getParams().put("MODULATOR_DELAY", MODULATOR_DELAY);
            getParams().put("CSM_TRIGGER_MASK", CSM_TRIGGER_MASK);
            getParams().put("STATUS_TIMERA", STATUS_TIMERA);
            getParams().put("STATUS_TIMERB", STATUS_TIMERB);
            getParams().put("STATUS_BUSY", STATUS_BUSY);
            getParams().put("STATUS_IRQ", STATUS_IRQ);

            m_regdata = new int[REGISTERS];

            // create the waveforms
            for (int index = 0; index < WAVEFORM_LENGTH; index++)
                m_waveform[0][index] = abs_sin_attenuation(index) | (bitfield(index, 9) << 15);
        }

        /**
         * Resets to initial state.
         */
        @Override
        public void reset() {
            Arrays.fill(m_regdata, 0, REGISTERS, 0);
            if (IsOpnA) {
                // enable output on both channels by default
                m_regdata[0xb4] = m_regdata[0xb5] = m_regdata[0xb6] = 0xc0;
                m_regdata[0x1b4] = m_regdata[0x1b5] = m_regdata[0x1b6] = 0xc0;
            }
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
        public final int channel_offset(int chNum) {
            assert (chNum < CHANNELS);
            if (!IsOpnA)
                return chNum;
            else
                return (chNum % 3) + 0x100 * (chNum / 3);
        }

        /** Maps operator number to register offset. */
        @Override
        public final int operator_offset(int opNum) {
            assert (opNum < OPERATORS);
            if (!IsOpnA)
                return opNum + opNum / 3;
            else
                return (opNum % 12) + ((opNum % 12) / 3) + 0x100 * (opNum / 12);
        }

        /** Returns an array of operator indices for each channel */
        protected static int[] s_fixed_map;

        /** read a register value */
        public final int read(int address) {
            return m_regdata[address];
        }

        /**
         * Handles writes to the register array.
         */
        @Override
        public boolean write(int index, int data, int[] channel, int[] opMask) {
            assert (index < REGISTERS);

            // writes in the 0xa0-af/0x1a0-af region are handled as latched pairs
            // borrow unused registers 0xb8-bf/0x1b8-bf as temporary holding locations
            if ((index & 0xf0) == 0xa0) {
                if (bitfield(index, 0, 2) == 3)
                    return false;

                int latchindex = 0xb8 | bitfield(index, 3);
                if (IsOpnA)
                    latchindex |= index & 0x100;

                // writes to the upper half just latch (only low 6 bits matter)
                if (bitfield(index, 2) != 0)
                    m_regdata[latchindex] = data | 0x80;

                    // writes to the lower half only commit if the latch is there
                else if (bitfield(m_regdata[latchindex], 7) != 0) {
                    m_regdata[index] = data;
                    m_regdata[index | 4] = m_regdata[latchindex] & 0x3f;
                    m_regdata[latchindex] = 0;
                }
                return false;
            } else if ((index & 0xf8) == 0xb8) {
                // registers 0xb8-0xbf are used internally
                return false;
            }

            // everything else is normal
            m_regdata[index] = data;

            // handle writes to the key on index
            if (index == 0x28) {
                channel[0] = bitfield(data, 0, 2);
                if (channel[0] == 3)
                    return false;
                if (IsOpnA)
                    channel[0] += bitfield(data, 2, 1) * 3;
                opMask[0] = bitfield(data, 4, 4);
                return true;
            }
            return false;
        }

        /**
         * Clocks the noise and LFO, handling clock division, depth, and waveform
         * computations.
         */
        @Override
        public int clock_noise_and_lfo() {
            // OPN has no noise generation

            // if LFO not enabled (not present on OPN), quick exit with 0s
            if (!IsOpnA || lfo_enable() == 0) {
                m_lfo_counter = 0;

                // special case: if LFO is disabled on OPNA, it basically just keeps the counter
                // at 0; since position 0 gives an AM value of 0x3f, it is important to reflect
                // that here; for example, MegaDrive Venom plays some notes with LFO globally
                // disabled but enabling LFO on the operators, and it expects this added attenutation
                m_lfo_am = IsOpnA ? 0x3f : 0x00;
                return 0;
            }

            // this table is based on converting the frequencies in the applications
            // manual to clock dividers, based on the assumption of a 7-bit LFO value
            /*static final*/
            int[] lfo_max_count = {109, 78, 72, 68, 63, 45, 9, 6};
            int subCount = m_lfo_counter++;

            // when we cross the divider count, add enough to zero it and cause an
            // increment at bit 8; the 7-bit value lives from bits 8-14
            if (subCount >= lfo_max_count[lfo_rate()]) {
                // note: to match the published values this should be 0x100 - subCount;
                // however, tests on the hardware and nuked bear out an off-by-one
                // error exists that causes the max LFO rate to be faster than published
                m_lfo_counter += 0x101 - subCount;
            }

            // AM value is 7 bits, staring at bit 8; grab the low 6 directly
            m_lfo_am = bitfield(m_lfo_counter, 8, 6);

            // first half of the AM period (bit 6 == 0) is inverted
            if (bitfield(m_lfo_counter, 8 + 6) == 0)
                m_lfo_am ^= 0x3f;

            // PM value is 5 bits, starting at bit 10; grab the low 3 directly
            int pm = bitfield(m_lfo_counter, 10, 3);

            // PM is reflected based on bit 3
            if (bitfield(m_lfo_counter, 10 + 3) != 0)
                pm ^= 7;

            // PM is negated based on bit 4
            return bitfield(m_lfo_counter, 10 + 4) != 0 ? -pm : pm;
        }

        /** reset the LFO */
        public void reset_lfo() {
            m_lfo_counter = 0;
        }

        /**
         * Returns the AM offset from LFO for the given channel.
         */
        @Override
        public final int lfo_am_offset(int chOffs) {
            // shift value for AM sensitivity is [7, 3, 1, 0],
            // mapping to values of [0, 1.4, 5.9, and 11.8dB]
            int am_shift = (1 << (ch_lfo_am_sens(chOffs) ^ 3)) - 1;

            // QUESTION: max sensitivity should give 11.8dB range, but this value
            // is directly added to an x.8 attenuation value, which will only give
            // 126/256 or ~4.9dB range -- what am I missing? The calculation below
            // matches several other emulators, including the Nuked implemenation.

            // raw LFO AM value on OPN is 0-3F, scale that up by a factor of 2
            // (giving 7 bits) before applying the final shift
            return (m_lfo_am << 1) >> am_shift;
        }

        /** return LFO/noise states */
        @Override
        public final int noise_state() {
            return 0;
        }

        /**
         * Fills the operator cache with prefetched data.
         */
        @Override
        public void cache_operator_data(int chOffs, int opOffs, OpDataCache cache) {
            // set up the easy stuff
            cache.waveform = m_waveform[0];

            // get frequency from the channel
            int block_freq = cache.block_freq = ch_block_freq(chOffs);

            // if multi-frequency mode is enabled and this is channel 2,
            // fetch one of the special frequencies
            if (multi_freq() != 0 && chOffs == 2) {
                if (opOffs == 2)
                    block_freq = cache.block_freq = multi_block_freq(1);
                else if (opOffs == 10)
                    block_freq = cache.block_freq = multi_block_freq(2);
                else if (opOffs == 6)
                    block_freq = cache.block_freq = multi_block_freq(0);
            }

            // compute the keycode: block_freq is:
            //
            //     BBBFFFFFFFFFFF
            //     ^^^^???
            //
            // the 5-bit keycode uses the top 4 bits plus a magic formula
            // for the final bit
            int keycode = bitfield(block_freq, 10, 4) << 1;

            // lowest bit is determined by a mix of next lower FNUM bits
            // according to this equation from the YM2608 manual:
            //
            //   (F11 & (F10 | F9 | F8)) | (!F11 & F10 & F9 & F8)
            //
            // for speed, we just look it up in a 16-bit constant
            keycode |= bitfield(0xfe80, bitfield(block_freq, 7, 4));

            // detune adjustment
            cache.detune = detune_adjustment(op_detune(opOffs), keycode);

            // multiple value, as an x.1 value (0 means 0.5)
            cache.multiple = op_multiple(opOffs) * 2;
            if (cache.multiple == 0)
                cache.multiple = 1;

            // phase step, or PHASE_STEP_DYNAMIC if PM is active; this depends on
            // block_freq, detune, and multiple, so compute it after we've done those
            if (!IsOpnA || lfo_enable() == 0 || ch_lfo_pm_sens(chOffs) == 0)
                cache.phase_step = compute_phase_step(chOffs, opOffs, cache, 0);
            else
                cache.phase_step = OpDataCache.PHASE_STEP_DYNAMIC;

            // total level, scaled by 8
            cache.total_level = op_total_level(opOffs) << 3;

            // 4-bit sustain level, but 15 means 31 so effectively 5 bits
            cache.eg_sustain = op_sustain_level(opOffs);
            cache.eg_sustain |= (cache.eg_sustain + 1) & 0x10;
            cache.eg_sustain <<= 5;

            // determine KSR adjustment for enevlope rates
            int ksrval = keycode >> (op_ksr(opOffs) ^ 3);
            cache.eg_rate[EnvelopeState.EG_ATTACK.ordinal()] = effective_rate(op_attack_rate(opOffs) * 2, ksrval);
            cache.eg_rate[EnvelopeState.EG_DECAY.ordinal()] = effective_rate(op_decay_rate(opOffs) * 2, ksrval);
            cache.eg_rate[EnvelopeState.EG_SUSTAIN.ordinal()] = effective_rate(op_sustain_rate(opOffs) * 2, ksrval);
            cache.eg_rate[EnvelopeState.EG_RELEASE.ordinal()] = effective_rate(op_release_rate(opOffs) * 4 + 2, ksrval);
        }

        /**
         * Computes the phase step.
         */
        @Override
        public int compute_phase_step(int chOffs, int opOffs, OpDataCache cache, int lfo_raw_pm) {
            // OPN phase calculation has only a single detune parameter
            // and uses FNUMs instead of keycodes

            // extract frequency number (low 11 bits of block_freq)
            int fnum = bitfield(cache.block_freq, 0, 11) << 1;

            // if there's a non-zero PM sensitivity, compute the adjustment
            int pm_sensitivity = ch_lfo_pm_sens(chOffs);
            if (pm_sensitivity != 0) {
                // apply the phase adjustment based on the upper 7 bits
                // of FNUM and the PM depth parameters
                fnum += opn_lfo_pm_phase_adjustment(bitfield(cache.block_freq, 4, 7), pm_sensitivity, lfo_raw_pm);

                // keep fnum to 12 bits
                fnum &= 0xfff;
            }

            // apply block shift to compute phase step
            int block = bitfield(cache.block_freq, 11, 3);
            int phase_step = (fnum << block) >> 2;

            // apply detune based on the keycode
            phase_step += cache.detune;

            // clamp to 17 bits in case detune overflows
            // QUESTION: is this specific to the YM2612/3438?
            phase_step &= 0x1ffff;

            // apply frequency multiplier (which is cached as an x.1 value)
            return (phase_step * cache.multiple) >> 1;
        }

        /**
         * Logs a key-on event.
         */
        @Override
        public String log_keyOn(int chOffs, int opOffs) {
            int chnum = (chOffs & 3) + 3 * bitfield(chOffs, 8);
            int opnum = (opOffs & 15) - ((opOffs & 15) / 4) + 12 * bitfield(opOffs, 8);

            int block_freq = ch_block_freq(chOffs);
            if (multi_freq() != 0 && chOffs == 2) {
                if (opOffs == 2)
                    block_freq = multi_block_freq(1);
                else if (opOffs == 10)
                    block_freq = multi_block_freq(2);
                else if (opOffs == 6)
                    block_freq = multi_block_freq(0);
            }

            StringBuilder buffer = new StringBuilder();

            buffer.append("%d.%02d freq=%04X dt=%d fb=%d alg=%X mul=%X tl=%02X ksr=%d adsr=%02X/%02X/%02X/%X sl=%X".formatted(
                    chnum, opnum,
                    block_freq,
                    op_detune(opOffs),
                    ch_feedback(chOffs),
                    ch_algorithm(chOffs),
                    op_multiple(opOffs),
                    op_total_level(opOffs),
                    op_ksr(opOffs),
                    op_attack_rate(opOffs),
                    op_decay_rate(opOffs),
                    op_sustain_rate(opOffs),
                    op_release_rate(opOffs),
                    op_sustain_level(opOffs)));

            if (OUTPUTS > 1)
                buffer.append(" out=%c%c".formatted(
                        ch_output_0(chOffs) != 0 ? 'L' : '-',
                        ch_output_1(chOffs) != 0 ? 'R' : '-'));
            if (op_ssg_eg_enable(opOffs) != 0)
                buffer.append(" ssg=%X".formatted(op_ssg_eg_mode(opOffs)));
            boolean am = (op_lfo_am_enable(opOffs) != 0 && ch_lfo_am_sens(chOffs) != 0);
            if (am)
                buffer.append(" am=%d".formatted(ch_lfo_am_sens(chOffs)));
            boolean pm = (ch_lfo_pm_sens(chOffs) != 0);
            if (pm)
                buffer.append(" pm=%d".formatted(ch_lfo_pm_sens(chOffs)));
            if (am || pm)
                buffer.append(" lfo=%02X".formatted(lfo_rate()));
            if (multi_freq() != 0 && chOffs == 2)
                buffer.append(" multi=1");

            return buffer.toString();
        }

        // system-wide registers

        public final int test() {
            return byte_(0x21, 0, 8);
        }

        public final int lfo_enable() {
            return IsOpnA ? byte_(0x22, 3, 1) : 0;
        }

        public final int lfo_rate() {
            return IsOpnA ? byte_(0x22, 0, 3) : 0;
        }

        @Override
        public final int timer_a_value() {
            return word(0x24, 0, 8, 0x25, 0, 2);
        }

        @Override
        public final int timer_b_value() {
            return byte_(0x26, 0, 8);
        }

        @Override
        public final int csm() {
            return (byte_(0x27, 6, 2) == 2) ? 1 : 0;
        }

        public final int multi_freq() {
            return (byte_(0x27, 6, 2) != 0) ? 1 : 0;
        }

        @Override
        public final int reset_timer_b() {
            return byte_(0x27, 5, 1);
        }

        @Override
        public final int reset_timer_a() {
            return byte_(0x27, 4, 1);
        }

        @Override
        public final int enable_timer_b() {
            return byte_(0x27, 3, 1);
        }

        @Override
        public final int enable_timer_a() {
            return byte_(0x27, 2, 1);
        }

        @Override
        public final int load_timer_b() {
            return byte_(0x27, 1, 1);
        }

        @Override
        public final int load_timer_a() {
            return byte_(0x27, 0, 1);
        }

        public final int multi_block_freq(int num) {
            return word(0xac, 0, 6, 0xa8, 0, 8, num);
        }

        // per-channel registers

        public final int ch_block_freq(int choffs) {
            return word(0xa4, 0, 6, 0xa0, 0, 8, choffs);
        }

        @Override
        public final int ch_feedback(int chOffs) {
            return byte_(0xb0, 3, 3, chOffs);
        }

        @Override
        public final int ch_algorithm(int chOffs) {
            return byte_(0xb0, 0, 3, chOffs);
        }

        @Override
        public final int ch_output_any(int chOffs) {
            return IsOpnA ? byte_(0xb4, 6, 2, chOffs) : 1;
        }

        @Override
        public final int ch_output_0(int chOffs) {
            return IsOpnA ? byte_(0xb4, 7, 1, chOffs) : 1;
        }

        @Override
        public final int ch_output_1(int chOffs) {
            return IsOpnA ? byte_(0xb4, 6, 1, chOffs) : 0;
        }

        @Override
        public final int ch_output_2(int chOffs) {
            return 0;
        }

        @Override
        public final int ch_output_3(int chOffs) {
            return 0;
        }

        public final int ch_lfo_am_sens(int choffs) {
            return IsOpnA ? byte_(0xb4, 4, 2, choffs) : 0;
        }

        public final int ch_lfo_pm_sens(int choffs) {
            return IsOpnA ? byte_(0xb4, 0, 3, choffs) : 0;
        }

        // per-operator registers

        public final int op_detune(int opoffs) {
            return byte_(0x30, 4, 3, opoffs);
        }

        public final int op_multiple(int opoffs) {
            return byte_(0x30, 0, 4, opoffs);
        }

        public final int op_total_level(int opoffs) {
            return byte_(0x40, 0, 7, opoffs);
        }

        public final int op_ksr(int opoffs) {
            return byte_(0x50, 6, 2, opoffs);
        }

        public final int op_attack_rate(int opoffs) {
            return byte_(0x50, 0, 5, opoffs);
        }

        public final int op_decay_rate(int opoffs) {
            return byte_(0x60, 0, 5, opoffs);
        }

        @Override
        public final int op_lfo_am_enable(int opOffs) {
            return IsOpnA ? byte_(0x60, 7, 1, opOffs) : 0;
        }

        public final int op_sustain_rate(int opoffs) {
            return byte_(0x70, 0, 5, opoffs);
        }

        public final int op_sustain_level(int opoffs) {
            return byte_(0x80, 4, 4, opoffs);
        }

        public final int op_release_rate(int opoffs) {
            return byte_(0x80, 0, 4, opoffs);
        }

        @Override
        public final int op_ssg_eg_enable(int opOffs) {
            return byte_(0x90, 3, 1, opOffs);
        }

        @Override
        public final int op_ssg_eg_mode(int opOffs) {
            return byte_(0x90, 0, 3, opOffs);
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
        protected final int word(int offset1, int start1, int count1, int offset2, int start2, int count2) {
            return word(offset1, start1, count1, offset2, start2, count2, 0);
        }

        /** Returns a bitfield extracted from a pair of bytes, MSBs listed first */
        protected final int word(int offset1, int start1, int count1, int offset2, int start2, int count2, int extra_offset /* = 0 */) {
            return (byte_(offset1, start1, count1, extra_offset) << count2) | byte_(offset2, start2, count2, extra_offset);
        }

        // internal state

        // for serdes (DON'T REMOVE)
        @SuppressWarnings("unused")
        boolean isOpnA(int seq) {
            return IsOpnA;
        }

        /** LFO counter */
        @Element(sequence = 1, condition = "isOpnA")
        protected int m_lfo_counter;
        /** current LFO AM value */
        @Element(sequence = 2, condition = "isOpnA")
        protected int m_lfo_am;
        /** register data */
        @Element(sequence = 3)
        protected final int[] m_regdata;
        /** waveforms */
        protected final int[][] m_waveform = new int[WAVEFORMS][WAVEFORM_LENGTH];
    }

    //using opn_registers = opn_registers_base<false>;
    static class OpnRegisters extends RegistersBase {

        OpnRegisters() {
            super(false);
        }

        static {
            // false
            s_fixed_map = new int[] {
                    operator_list(0, 6, 3, 9),  // Channel 0 operators
                    operator_list(1, 7, 4, 10),  // Channel 1 operators
                    operator_list(2, 8, 5, 11),  // Channel 2 operators
            };
        }

        /**
         * Returns an array of operator
         * indices for each channel; for OPN this is fixed.
         */
        //template<>opn_registers_base<false>.
        @Override
        public final void operator_map(int[][] dest) {
            // Note that the channel index order is 0,2,1,3, so we bitswap the index.
            //
            // This is because the order in the map is:
            //    carrier 1, carrier 2, modulator 1, modulator 2
            //
            // But when wiring up the connections, the more natural order is:
            //    carrier 1, modulator 1, carrier 2, modulator 2
            dest[0] = s_fixed_map;
        }
    }

    //using opna_registers = opn_registers_base<true>;
    static class OpnaRegisters extends RegistersBase {

        OpnaRegisters() {
            super(true);
        }

        static {
            // true
            s_fixed_map = new int[] {
                    operator_list(0, 6, 3, 9),  // Channel 0 operators
                    operator_list(1, 7, 4, 10),  // Channel 1 operators
                    operator_list(2, 8, 5, 11),  // Channel 2 operators
                    operator_list(12, 18, 15, 21),  // Channel 3 operators
                    operator_list(13, 19, 16, 22),  // Channel 4 operators
                    operator_list(14, 20, 17, 23),  // Channel 5 operators
            };
        }

        /**
         * Returns an array of operator
         * indices for each channel; for OPN this is fixed
         */
        //template<>opn_registers_base<true>.
        @Override
        public final void operator_map(int[][] dest) {
            // Note that the channel index order is 0,2,1,3, so we bitswap the index.
            //
            // This is because the order in the map is:
            //    carrier 1, carrier 2, modulator 1, modulator 2
            //
            // But when wiring up the connections, the more natural order is:
            //    carrier 1, modulator 1, carrier 2, modulator 2
            dest[0] = s_fixed_map;
        }
    }

    //
    // OPN IMPLEMENTATION CLASSES
    //

    // A note about prescaling and sample rates.
    //
    // YM2203, YM2608, and YM2610 contain an onboard SSG (basically, a YM2149).
    // In order to properly generate sound at fully fidelity, the output sample
    // rate of the YM2149 must be input_clock / 8. This is much higher than the
    // FM needs, but in the interest of keeping things simple, the OPN generate
    // functions will output at the higher rate and just replicate the last FM
    // sample as many times as needed.
    //
    // To make things even more complicated, the YM2203 and YM2608 allow for
    // software-controlled prescaling, which affects the FM and SSG clocks in
    // different ways. There are three settings: divide by 6/4 (FM/SSG); divide
    // by 3/2; and divide by 2/1.
    //
    // Thus, the minimum output sample rate needed by each part of the chip
    // varies with the prescale as follows:
    //
    //             ---- YM2203 -----    ---- YM2608 -----    ---- YM2610 -----
    // Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
    //     6         /72      /16         /144     /32          /144    /32
    //     3         /36      /8          /72      /16
    //     2         /24      /4          /48      /8
    //
    // If we standardized on the fastest SSG rate, we'd end up with the following
    // (ratios are output_samples:source_samples):
    //
    //             ---- YM2203 -----    ---- YM2608 -----    ---- YM2610 -----
    //              rate = clock/4       rate = clock/8       rate = clock/16
    // Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
    //     6         18:1     4:1         18:1     4:1          9:1    2:1
    //     3          9:1     2:1          9:1     2:1
    //     2          6:1     1:1          6:1     1:1
    //
    // However, that's a pretty big performance hit for minimal gain. Going to
    // the other extreme, we could standardize on the fastest FM rate, but then
    // at least one prescale case (3) requires the FM to be smeared across two
    // output samples:
    //
    //             ---- YM2203 -----    ---- YM2608 -----    ---- YM2610 -----
    //              rate = clock/24      rate = clock/48      rate = clock/144
    // Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
    //     6          3:1     2:3          3:1     2:3          1:1    2:9
    //     3        1.5:1     1:3        1.5:1     1:3
    //     2          1:1     1:6          1:1     1:6
    //
    // Stepping back one factor of 2 addresses that issue:
    //
    //             ---- YM2203 -----    ---- YM2608 -----    ---- YM2610 -----
    //              rate = clock/12      rate = clock/24      rate = clock/144
    // Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
    //     6          6:1     4:3          6:1     4:3          1:1    2:9
    //     3          3:1     2:3          3:1     2:3
    //     2          2:1     1:3          2:1     1:3
    //
    // This gives us three levels of output fidelity:
    //    OPN_FIEDLITY_MAX -- highest sample rate, using fastest SSG rate
    //    OPN_FIEDLITY_MIN -- lowest sample rate, using fastest FM rate
    //    OPN_FIEDLITY_MED -- medium sample rate such that FM is never smeared
    //
    // At the maximum clocks for YM2203/YM2608 (4Mhz/8MHz), these rates will
    // end up as:
    //    OPN_FIEDLITY_MAX = 1000kHz
    //    OPN_FIEDLITY_MIN =  166kHz
    //    OPN_FIEDLITY_MED =  333kHz

    /** opn_fidelity */
    public enum Fidelity {
        MAX,
        MIN,
        MED;
        static final int DEFAULT = MAX.ordinal();
    }

    //
    // SSG RESAMPLER
    //

    /** ssg_resampler */
    @Serdes
    protected abstract static class SsgResampler {

        //template<typename OutputType, int FirstOutput, boolean MixTo1>

        abstract int getOutput();

        abstract int getFirstOutput();

        abstract boolean isMixTo1();

        /**
         * Helper to add the last computed
         * value to the sums, applying the given scale
         */
        private void add_last(int[] sum0, int[] sum1, int[] sum2, int scale /* = 1 */) {
            sum0[0] += m_last.data[0] * scale;
            sum1[0] += m_last.data[1] * scale;
            sum2[0] += m_last.data[2] * scale;
        }

        /**
         * Constructor.
         */
        //template<typename OutputType, int FirstOutput, boolean MixTo1>
        protected SsgResampler(Ssg.Engine ssg) {
            m_ssg = ssg;
            m_sampleIndex = 0;
            m_resampler = this::resample_nop;

            m_last = m_ssg.outputFactory();
            m_last.clear();
        }

        /**
         * Helper to clock a new value and then add it to the sums,
         * applying the given scale
         */
        //template<typename OutputType, int FirstOutput, boolean MixTo1>
        private void clock_and_add(int[] sum0, int[] sum1, int[] sum2, int scale /* = 1 */) {
            m_ssg.clock();
            m_ssg.output(m_last);
            add_last(sum0, sum1, sum2, scale);
        }

        /**
         * helper to write the sums to the appropriate outputs,
         * applying the given divisor to the final result
         */
        //template<typename OutputType, int FirstOutput, boolean MixTo1>
        private void write_to_output(YmFm.Output output, int sum0, int sum1, int sum2, int divisor /* = 1 */) {
            assert output.getNumOutputs() == getOutput(); // TODO vavi how to adjust?
            if (isMixTo1()) {
                // mixing to one, apply a 2/3 factor to prevent overflow
                output.data[getFirstOutput()] = (sum0 + sum1 + sum2) * 2 / (3 * divisor);
            } else {
                // write three outputs in a row
                output.data[getFirstOutput() + 0] = sum0 / divisor;
                output.data[getFirstOutput() + 1] = sum1 / divisor;
                output.data[getFirstOutput() + 2] = sum2 / divisor;
            }

            // track the sample index here
            m_sampleIndex++;
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

        /** get the current sample index */
        public final int sampleIndex() {
            return m_sampleIndex;
        }

        /**
         * Configures a new ratio.
         */
        public void configure(int outSamples, int srcSamples) {
            switch (outSamples * 10 + srcSamples) {
                case 4 * 10 + 1:    // 4:1
                    m_resampler = this::_resample_4_1 /* <4> */;
                    break;
                case 2 * 10 + 1:    // 2:1
                    m_resampler = this::_resample_2_1 /* <2> */;
                    break;
                case 4 * 10 + 3:    // 4:3
                    m_resampler = this::resample_4_3;
                    break;
                case 1 * 10 + 1:    // 1:1
                    m_resampler = this::_resample_1_1 /* <1> */;
                    break;
                case 2 * 10 + 3:    // 2:3
                    m_resampler = this::resample_2_3;
                    break;
                case 1 * 10 + 3:    // 1:3
                    m_resampler = this::_resample_1_3 /* <3> */;
                    break;
                case 2 * 10 + 9:    // 2:9
                    m_resampler = this::resample_2_9;
                    break;
                case 1 * 10 + 6:    // 1:6
                    m_resampler = this::_resample_1_6 /* <6> */;
                    break;
                case 0 * 10 + 0:    // 0:0
                    m_resampler = this::resample_nop;
                    break;
                default:
                    assert (false);
                    break;
            }
        }

        /** resample */
        public final void resample(YmFm.Output[] output, int numSamples) {
            this.m_resampler.accept(output, numSamples);
        }

        /**
         * Resamples SSG output to the
         * target at a rate of 1 SSG sample to every
         * n output sample
         *
         * @param multiplier used as template in c++
         */
        private void resample_n_1(YmFm.Output[] output, int numSamples, int multiplier) {
            for (int samp = 0; samp < numSamples; samp++) {
                if (m_sampleIndex % multiplier == 0) {
                    m_ssg.clock();
                    m_ssg.output(m_last);
                }
                write_to_output(output[samp], m_last.data[0], m_last.data[1], m_last.data[2], 1);
            }
        }

        private void _resample_4_1(YmFm.Output[] output, int numSamples) {
            resample_n_1(output, numSamples, 4);
        }

        private void _resample_2_1(YmFm.Output[] output, int numSamples) {
            resample_n_1(output, numSamples, 2);
        }

        private void _resample_1_1(YmFm.Output[] output, int numSamples) {
            resample_n_1(output, numSamples, 1);
        }

        /**
         * Resample SSG output to the
         * target at a rate of n SSG samples to every
         * 1 output sample
         *
         * @param divisor used as template in c++
         */
        private void resample_1_n(YmFm.Output[] output, int numSamples, int divisor) {
            for (int samp = 0; samp < numSamples; samp++) {
                int[] sum0 = new int[1], sum1 = new int[1], sum2 = new int[1];
                for (int rep = 0; rep < divisor; rep++)
                    clock_and_add(sum0, sum1, sum2, 1);
                write_to_output(output[samp], sum0[0], sum1[0], sum2[0], divisor);
            }
        }

        /**
         * resample SSG output to the target at a rate of 3 SSG samples
         * to every 1 output sample
         */
        private void _resample_1_3(YmFm.Output[] output, int numSamples) {
            resample_1_n(output, numSamples, 3);
        }

        private void _resample_1_6(YmFm.Output[] output, int numSamples) {
            resample_1_n(output, numSamples, 6);
        }

        /**
         * Resamples SSG output to the
         * target at a rate of 9 SSG samples to every
         * 2 output samples
         */
        private void resample_2_9(YmFm.Output[] output, int numSamples) {
            for (int samp = 0; samp < numSamples; samp++) {
                int[] sum0 = new int[1], sum1 = new int[1], sum2 = new int[1];
                if (bitfield(m_sampleIndex, 0) != 0)
                    add_last(sum0, sum1, sum2, 1);
                clock_and_add(sum0, sum1, sum2, 2);
                clock_and_add(sum0, sum1, sum2, 2);
                clock_and_add(sum0, sum1, sum2, 2);
                clock_and_add(sum0, sum1, sum2, 2);
                if (bitfield(m_sampleIndex, 0) == 0)
                    clock_and_add(sum0, sum1, sum2, 1);
                write_to_output(output[samp], sum0[0], sum1[0], sum2[0], 9);
            }
        }

        /**
         * Resamples SSG output to the
         * target at a rate of 3 SSG samples to every
         * 2 output samples
         */
        private void resample_2_3(YmFm.Output[] output, int numSamples) {
            for (int samp = 0; samp < numSamples; samp++) {
                int[] sum0 = new int[1], sum1 = new int[1], sum2 = new int[1];
                if (bitfield(m_sampleIndex, 0) == 0) {
                    clock_and_add(sum0, sum1, sum2, 2);
                    clock_and_add(sum0, sum1, sum2, 1);
                } else {
                    add_last(sum0, sum1, sum2, 1);
                    clock_and_add(sum0, sum1, sum2, 2);
                }
                write_to_output(output[samp], sum0[0], sum1[0], sum2[0], 3);
            }
        }

        /**
         * Resamples SSG output to the
         * target at a rate of 3 SSG samples to every
         * 4 output samples
         */
        private void resample_4_3(YmFm.Output[] output, int numSamples) {
            for (int samp = 0; samp < numSamples; samp++) {
                int[] sum0 = new int[1], sum1 = new int[1], sum2 = new int[1];
                int step = bitfield(m_sampleIndex, 0, 2);
                add_last(sum0, sum1, sum2, step);
                if (step != 3)
                    clock_and_add(sum0, sum1, sum2, 3 - step);
                write_to_output(output[samp], sum0[0], sum1[0], sum2[0], 3);
            }
        }

        /**
         * no-op resampler.
         */
        private void resample_nop(YmFm.Output[] output, int numSamples) {
            // nothing to do except increment the sample index
            m_sampleIndex += numSamples;
        }

        // define a pointer type
        //using resample_func = void (ssg_resampler)(OutputType output, int numSamples);

        // internal state

        private final Ssg.Engine m_ssg;
        @Element(sequence = 1)
        private int m_sampleIndex;
        // resample_func
        private BiConsumer<Output[], Integer> m_resampler;
        @Element(sequence = 2)
        private final YmFm.Output m_last;
    }

    //
    // YM2203
    //

    /** ym2203 */
    @Serdes
    public static class Ym2203 implements YmFm.Chip {

        protected static class FmEngine extends EngineBase<OpnRegisters> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, OpnRegisters.class);
            }
        }

        public final int FM_OUTPUTS;
        private static final int SSG_OUTPUTS = Ssg.Engine.OUTPUTS;
        private final int OUTPUTS;

        //using output_data = ymfm_output<OUTPUTS>;
        @Override
        public YmFm.Output outputFactory() {
            return new YmFm.Output(OUTPUTS);
        }

        @Override
        public final int getOutputs(){
            return OUTPUTS;
        }

        /**
         * Constructor.
         */
        public Ym2203(YmFm.Interface intf) {
            m_fidelity = MAX;
            m_address = 0;
            m_fm = new FmEngine(intf);
            m_ssg = new Ssg.Engine(intf);
            m_ssg_resampler = new SsgResampler(m_ssg) {
                @Override int getOutput() {
                    return OUTPUTS;
                }
                @Override int getFirstOutput() {
                    return 1;
                }
                @Override boolean isMixTo1() {
                    return false;
                }
            };

            FM_OUTPUTS = (int) m_fm.getRegisterType().getParams().get("OUTPUTS");
            OUTPUTS = FM_OUTPUTS + SSG_OUTPUTS;

            m_last_fm = m_fm.outputFactory();
            m_last_fm.clear();
            update_prescale(m_fm.clock_prescale());
        }

        // configuration

        public void ssg_override(Ssg.Override intf) {
            m_ssg.override(intf);
        }

        public void set_fidelity(Fidelity fidelity) {
            m_fidelity = fidelity;
            update_prescale(m_fm.clock_prescale());
        }

        /**
         * Resets the system.
         */
        @Override
        public void reset() {
            // reset the engines
            m_fm.reset();
            m_ssg.reset();
        }

        /**
         * Saves the data.
         */
        @Override
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);

            m_fm.save(os);
            m_ssg.save(os);
            m_ssg_resampler.save(os);

            update_prescale(m_fm.clock_prescale());
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);

            m_fm.restore(is);
            m_ssg.restore(is);
            m_ssg_resampler.restore(is);

            update_prescale(m_fm.clock_prescale());
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
            switch (m_fidelity) {
                case MIN:
                    return input_clock / 24;
                case MED:
                    return input_clock / 12;
                default:
                case MAX:
                    return input_clock / 4;
            }
        }

        public final int ssg_effective_clock(int input_clock) {
            int scale = m_fm.clock_prescale() * 2 / 3;
            return input_clock * 2 / scale;
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
                result |= OpnRegisters.STATUS_BUSY;
            return result;
        }

        /**
         * Reads the data register.
         */
        public int read_data() {
            int result = 0;
            if (m_address < 0x10) {
                // 00-0F: Read from SSG
                result = m_ssg.read(m_address & 0x0f);
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

                case 1: // data port (only SSG)
                    result = read_data();
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

            // special case: update the prescale
            if (m_address >= 0x2d && m_address <= 0x2f) {
                // 2D-2F: prescaler select
                if (m_address == 0x2d)
                    update_prescale(6);
                else if (m_address == 0x2e && m_fm.clock_prescale() == 6)
                    update_prescale(3);
                else if (m_address == 0x2f)
                    update_prescale(2);
            }
        }

        /**
         * Handles a write to the register interface.
         */
        public void write_data(int data) {
            if (m_address < 0x10) {
                // 00-0F: write to SSG
                m_ssg.write(m_address & 0x0f, data);
            } else {
                // 10-FF: write to FM
                m_fm.write(m_address, data);
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
            // FM output is just repeated the prescale number of times; note that
            // 0 is a special 1.5 case
            if (m_fm_samples_per_output != 0) {
                for (int samp = 0; samp < numSamples; samp++) {
                    if ((m_ssg_resampler.sampleIndex() + samp) % m_fm_samples_per_output == 0)
                        clock_fm();
                    output[samp].data[0] = m_last_fm.data[0];
                }
            } else {
                for (int samp = 0; samp < numSamples; samp++) {
                    int step = (m_ssg_resampler.sampleIndex() + samp) % 3;
                    if (step == 0)
                        clock_fm();
                    output[samp].data[0] = m_last_fm.data[0];
                    if (step == 1) {
                        clock_fm();
                        output[samp].data[0] = (output[samp].data[0] + m_last_fm.data[0]) / 2;
                    }
                }
            }

            // resample the SSG as configured
            m_ssg_resampler.resample(output, numSamples);
        }

        /**
         * Updates the prescale value, recomputing derived values.
         */
        protected void update_prescale(int prescale) {
            // tell the FM engine
            m_fm.set_clock_prescale(prescale);
            m_ssg.prescale_changed();

            // Fidelity:   ---- minimum ----    ---- medium -----    ---- maximum-----
            //              rate = clock/24      rate = clock/12      rate = clock/4
            // Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
            //     6          3:1     2:3          6:1     4:3         18:1     4:1
            //     3        1.5:1     1:3          3:1     2:3          9:1     2:1
            //     2          1:1     1:6          2:1     1:3          6:1     1:1

            // compute the number of FM samples per output sample, and select the
            // resampler function
            if (m_fidelity == Fidelity.MIN) {
                switch (prescale) {
                    default:
                    case 6:
                        m_fm_samples_per_output = 3;
                        m_ssg_resampler.configure(2, 3);
                        break;
                    case 3:
                        m_fm_samples_per_output = 0;
                        m_ssg_resampler.configure(1, 3);
                        break;
                    case 2:
                        m_fm_samples_per_output = 1;
                        m_ssg_resampler.configure(1, 6);
                        break;
                }
            } else if (m_fidelity == Fidelity.MED) {
                switch (prescale) {
                    default:
                    case 6:
                        m_fm_samples_per_output = 6;
                        m_ssg_resampler.configure(4, 3);
                        break;
                    case 3:
                        m_fm_samples_per_output = 3;
                        m_ssg_resampler.configure(2, 3);
                        break;
                    case 2:
                        m_fm_samples_per_output = 2;
                        m_ssg_resampler.configure(1, 3);
                        break;
                }
            } else {
                switch (prescale) {
                    default:
                    case 6:
                        m_fm_samples_per_output = 18;
                        m_ssg_resampler.configure(4, 1);
                        break;
                    case 3:
                        m_fm_samples_per_output = 9;
                        m_ssg_resampler.configure(2, 1);
                        break;
                    case 2:
                        m_fm_samples_per_output = 6;
                        m_ssg_resampler.configure(1, 1);
                        break;
                }
            }

            // if overriding the SSG, override the configuration with the nop
            // resampler to at least keep the sample index moving forward
            if (m_ssg.overridden())
                m_ssg_resampler.configure(0, 0);
        }

        /**
         * Clocks FM state.
         */
        protected void clock_fm() {
            // clock the system
            m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

            // update the FM content; OPN is full 14-bit with no intermediate clipping
            m_fm.output(m_last_fm.clear(), 0, 32767, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

            // convert to 10.3 floating point value for the DAC and back
            m_last_fm.roundtrip_fp();
        }

        // internal state

        /** configured fidelity */
        protected Fidelity m_fidelity;
        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** how many samples to repeat */
        protected int m_fm_samples_per_output;
        /** last FM output */
        @Element(sequence = 2)
        protected final YmFm.Output m_last_fm;
        /** core FM engine */
        protected final FmEngine m_fm;
        /** SSG engine */
        protected final Ssg.Engine m_ssg;
        /** SSG resampler helper */
        protected final SsgResampler /* <output_data, 1, false> */ m_ssg_resampler;
    }

    //
    // OPNA IMPLEMENTATION CLASSES
    //

    //
    // YM2608
    //

    /** ym2608 */
    @Serdes
    public static class Ym2608 implements YmFm.Chip {

        protected static final int STATUS_ADPCM_B_EOS = 0x04;
        protected static final int STATUS_ADPCM_B_BRDY = 0x08;
        protected static final int STATUS_ADPCM_B_ZERO = 0x10;
        protected static final int STATUS_ADPCM_B_PLAYING = 0x20;

        protected static class FmEngine extends EngineBase<OpnaRegisters> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, OpnaRegisters.class);
            }
        }

        protected final int FM_OUTPUTS;
        protected static final int SSG_OUTPUTS = 1;
        private final int OUTPUTS;

        //using output_data = Output<OUTPUTS>;
        @Override
        public YmFm.Output outputFactory() {
            return new YmFm.Output(OUTPUTS);
        }

        @Override
        public final int getOutputs(){
            return OUTPUTS;
        }

        /**
         * Constructor.
         */
        public Ym2608(YmFm.Interface intf) {
            m_fidelity = MAX;
            m_address = 0;
            m_irq_enable = 0x1f;
            m_flag_control = 0x1c;
            m_fm = new FmEngine(intf);
            m_ssg = new Ssg.Engine(intf);
            m_ssg_resampler = new SsgResampler(m_ssg) {
                @Override int getOutput() {
                    return OUTPUTS;
                }
                @Override int getFirstOutput() {
                    return 2;
                }
                @Override boolean isMixTo1() {
                    return true;
                }
            };
            m_adpcm_a = new Adpcm.EngineA(intf, 0);
            m_adpcm_b = new Adpcm.EngineB(intf, 0);

            FM_OUTPUTS = (int) m_fm.getRegisterType().getParams().get("OUTPUTS");
            OUTPUTS = FM_OUTPUTS + SSG_OUTPUTS;

            m_last_fm = m_fm.outputFactory();
            m_last_fm.clear();
            update_prescale(m_fm.clock_prescale());
        }

        // configuration

        public void ssg_override(Ssg.Override intf) {
            m_ssg.override(intf);
        }

        public void set_fidelity(Opn.Fidelity fidelity) {
            m_fidelity = fidelity;
            update_prescale(m_fm.clock_prescale());
        }

        /**
         * Resets the system.
         */
        @Override
        public void reset() {
            // reset the engines
            m_fm.reset();
            m_ssg.reset();
            m_adpcm_a.reset();
            m_adpcm_b.reset();

            // configure ADPCM percussion sounds; these are present in an embedded ROM
            m_adpcm_a.set_start_end(0, 0x0000, 0x01bf); // bass drum
            m_adpcm_a.set_start_end(1, 0x01c0, 0x043f); // snare drum
            m_adpcm_a.set_start_end(2, 0x0440, 0x1b7f); // top cymbal
            m_adpcm_a.set_start_end(3, 0x1b80, 0x1cff); // high hat
            m_adpcm_a.set_start_end(4, 0x1d00, 0x1f7f); // tom tom
            m_adpcm_a.set_start_end(5, 0x1f80, 0x1fff); // rim shot

            // initialize our special interrupt states, then read the upper status
            // register, which updates the IRQs
            m_irq_enable = 0x1f;
            m_flag_control = 0x1c;
            read_status_hi();
        }

        /**
         * Saves the data.
         */
        @Override
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);

            m_fm.save(os);
            m_ssg.save(os);
            m_ssg_resampler.save(os);
            m_adpcm_a.save(os);
            m_adpcm_b.save(os);
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);

            m_fm.restore(is);
            m_ssg.restore(is);
            m_ssg_resampler.restore(is);
            m_adpcm_a.restore(is);
            m_adpcm_b.restore(is);
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
            switch (m_fidelity) {
                case MIN:
                    return input_clock / 48;
                case MED:
                    return input_clock / 24;
                default:
                case MAX:
                    return input_clock / 8;
            }
        }

        public final int ssg_effective_clock(int input_clock) {
            int scale = m_fm.clock_prescale() * 2 / 3;
            return input_clock / scale;
        }

        public void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /**
         * Reads the status register.
         */
        public int read_status() {
            int result = m_fm.status() & (OpnaRegisters.STATUS_TIMERA | OpnaRegisters.STATUS_TIMERB);
            if (m_fm.intf().ymfm_is_busy())
                result |= OpnaRegisters.STATUS_BUSY;
            return result;
        }

        /**
         * Reads the data register.
         */
        public int read_data() {
            int result = 0;
            if (m_address < 0x10) {
                // 00-0F: Read from SSG
                result = m_ssg.read(m_address & 0x0f);
            } else if (m_address == 0xff) {
                // FF: ID code
                result = 1;
            }
            return result;
        }

        /**
         * Reads the extended status register.
         */
        public int read_status_hi() {
            // fetch regular status
            int status = m_fm.status() & ~(STATUS_ADPCM_B_EOS | STATUS_ADPCM_B_BRDY | STATUS_ADPCM_B_PLAYING);

            // fetch ADPCM-B status, and merge in the bits
            int adpcm_status = m_adpcm_b.status();
            if ((adpcm_status & ChannelB.STATUS_EOS) != 0)
                status |= STATUS_ADPCM_B_EOS;
            if ((adpcm_status & ChannelB.STATUS_BRDY) != 0)
                status |= STATUS_ADPCM_B_BRDY;
            if ((adpcm_status & ChannelB.STATUS_PLAYING) != 0)
                status |= STATUS_ADPCM_B_PLAYING;

            // turn off any bits that have been requested to be masked
            status &= ~(m_flag_control & 0x1f);

            // update the status so that IRQs are propagated
            m_fm.set_reset_status(status, ~status);

            // merge in the busy flag
            if (m_fm.intf().ymfm_is_busy())
                status |= OpnaRegisters.STATUS_BUSY;
            return status;
        }

        /**
         * Reads the upper data register.
         */
        public int read_data_hi() {
            int result = 0;
            if ((m_address & 0xff) < 0x10) {
                // 00-0F: Read from ADPCM-B
                result = m_adpcm_b.read(m_address & 0x0f);
            }
            return result;
        }

        /**
         * Handles a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0;
            switch (offset & 3) {
                case 0: // status port, YM2203 compatible
                    result = read_status();
                    break;

                case 1: // data port (only SSG)
                    result = read_data();
                    break;

                case 2: // status port, extended
                    result = read_status_hi();
                    break;

                case 3: // ADPCM-B data
                    result = read_data_hi();
                    break;
            }
            return result;
        }

        /**
         * Handle a write to the address register.
         */
        public void write_address(int data) {
            // just set the address
            m_address = data;

            // special case: update the prescale
            if (m_address >= 0x2d && m_address <= 0x2f) {
                // 2D-2F: prescaler select
                if (m_address == 0x2d)
                    update_prescale(6);
                else if (m_address == 0x2e && m_fm.clock_prescale() == 6)
                    update_prescale(3);
                else if (m_address == 0x2f)
                    update_prescale(2);
            }
        }

        /**
         * Handles a write to the data register.
         */
        public void write_data(int data) {
            // ignore if paired with upper address
            if (bitfield(m_address, 8) != 0)
                return;

            if (m_address < 0x10) {
                // 00-0F: write to SSG
                m_ssg.write(m_address & 0x0f, data);
            } else if (m_address < 0x20) {
                // 10-1F: write to ADPCM-A
                m_adpcm_a.write(m_address & 0x0f, data);
            } else if (m_address == 0x29) {
                // 29: special IRQ mask register
                m_irq_enable = data;
                m_fm.set_irq_mask(m_irq_enable & ~m_flag_control & 0x1f);
            } else {
                // 20-28, 2A-FF: write to FM
                m_fm.write(m_address, data);
            }

            // mark busy for a bit
            m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());
        }

        /**
         * Handles a write to the upper address register.
         */
        public void write_address_hi(int data) {
            // just set the address
            m_address = 0x100 | data;
        }

        /**
         * Handles a write to the upper data register.
         */
        public void write_data_hi(int data) {
            // ignore if paired with upper address
            if (bitfield(m_address, 8) == 0)
                return;

            if (m_address < 0x110) {
                // 100-10F: write to ADPCM-B
                m_adpcm_b.write(m_address & 0x0f, data);
            } else if (m_address == 0x110) {
                // 110: IRQ flag control
                if (bitfield(data, 7) != 0)
                    m_fm.set_reset_status(0, 0xff);
                else {
                    m_flag_control = data;
                    m_fm.set_irq_mask(m_irq_enable & ~m_flag_control & 0x1f);
                }
            } else {
                // 111-1FF: write to FM
                m_fm.write(m_address, data);
            }

            // mark busy for a bit
            m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());
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

                case 2: // upper address port
                    write_address_hi(data);
                    break;

                case 3: // upper data port
                    write_data_hi(data);
                    break;
            }
        }

        /**
         * Generates one sample of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            // FM output is just repeated the prescale number of times; note that
            // 0 is a special 1.5 case
            if (m_fm_samples_per_output != 0) {
                for (int samp = 0; samp < numSamples; samp++) {
                    if ((m_ssg_resampler.sampleIndex() + samp) % m_fm_samples_per_output == 0)
                        clock_fm_and_adpcm();
                    output[samp].data[0] = m_last_fm.data[0];
                    output[samp].data[1] = m_last_fm.data[1];
                }
            } else {
                for (int samp = 0; samp < numSamples; samp++) {
                    int step = (m_ssg_resampler.sampleIndex() + samp) % 3;
                    if (step == 0)
                        clock_fm_and_adpcm();
                    output[samp].data[0] = m_last_fm.data[0];
                    output[samp].data[1] = m_last_fm.data[1];
                    if (step == 1) {
                        clock_fm_and_adpcm();
                        output[samp].data[0] = (output[samp].data[0] + m_last_fm.data[0]) / 2;
                        output[samp].data[1] = (output[samp].data[1] + m_last_fm.data[1]) / 2;
                    }
                }
            }

            // resample the SSG as configured
            m_ssg_resampler.resample(output, numSamples);
        }

        /**
         * Updates the prescale value, recomputing derived values.
         */
        protected void update_prescale(int prescale) {
            // tell the FM engine
            m_fm.set_clock_prescale(prescale);
            m_ssg.prescale_changed();

            // Fidelity:   ---- minimum ----    ---- medium -----    ---- maximum-----
            //              rate = clock/48      rate = clock/24      rate = clock/8
            // Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
            //     6          3:1     2:3          6:1     4:3         18:1     4:1
            //     3        1.5:1     1:3          3:1     2:3          9:1     2:1
            //     2          1:1     1:6          2:1     1:3          6:1     1:1

            // compute the number of FM samples per output sample, and select the
            // resampler function
            if (m_fidelity == Fidelity.MIN) {
                switch (prescale) {
                    default:
                    case 6:
                        m_fm_samples_per_output = 3;
                        m_ssg_resampler.configure(2, 3);
                        break;
                    case 3:
                        m_fm_samples_per_output = 0;
                        m_ssg_resampler.configure(1, 3);
                        break;
                    case 2:
                        m_fm_samples_per_output = 1;
                        m_ssg_resampler.configure(1, 6);
                        break;
                }
            } else if (m_fidelity == Fidelity.MED) {
                switch (prescale) {
                    default:
                    case 6:
                        m_fm_samples_per_output = 6;
                        m_ssg_resampler.configure(4, 3);
                        break;
                    case 3:
                        m_fm_samples_per_output = 3;
                        m_ssg_resampler.configure(2, 3);
                        break;
                    case 2:
                        m_fm_samples_per_output = 2;
                        m_ssg_resampler.configure(1, 3);
                        break;
                }
            } else {
                switch (prescale) {
                    default:
                    case 6:
                        m_fm_samples_per_output = 18;
                        m_ssg_resampler.configure(4, 1);
                        break;
                    case 3:
                        m_fm_samples_per_output = 9;
                        m_ssg_resampler.configure(2, 1);
                        break;
                    case 2:
                        m_fm_samples_per_output = 6;
                        m_ssg_resampler.configure(1, 1);
                        break;
                }
            }

            // if overriding the SSG, override the configuration with the nop
            // resampler to at least keep the sample index moving forward
            if (m_ssg.overridden())
                m_ssg_resampler.configure(0, 0);
        }

        /**
         * Clocks FM and ADPCM state.
         */
        protected void clock_fm_and_adpcm() {
            // top bit of the IRQ enable flags controls 3-channel vs 6-channel mode
            int fmMask = bitfield(m_irq_enable, 7) != 0 ? 0x3f : 0x07;

            // clock the system
            int env_counter = m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

            // clock the ADPCM-A engine on every envelope cycle
            // (channels 4 and 5 clock every 2 envelope clocks)
            if (bitfield(env_counter, 0, 2) == 0)
                m_adpcm_a.clock(bitfield(env_counter, 2) != 0 ? 0x0f : 0x3f);

            // clock the ADPCM-B engine every cycle
            m_adpcm_b.clock();

            // update the FM content; OPNA is 13-bit with no intermediate clipping
            m_fm.output(m_last_fm.clear(), 1, 32767, fmMask);

            // mix in the ADPCM and clamp
            m_adpcm_a.output(m_last_fm, 0x3f);
            m_adpcm_b.output(m_last_fm, 1);
            m_last_fm.clamp16();
        }

        // internal state

        /** configured fidelity */
        protected Fidelity m_fidelity;
        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** how many samples to repeat */
        protected int m_fm_samples_per_output;
        /** IRQ enable register */
        @Element(sequence = 2)
        protected int m_irq_enable;
        /** flag control register */
        @Element(sequence = 3)
        protected int m_flag_control;
        /** last FM output */
        @Element(sequence = 4)
        protected final YmFm.Output m_last_fm;
        /** core FM engine */
        protected final FmEngine m_fm;
        /** SSG engine */
        protected final Ssg.Engine m_ssg;
        /** SSG resampler helper */
        protected final SsgResampler /* <output_data, 2, true> */ m_ssg_resampler;
        /** ADPCM-A engine */
        protected final Adpcm.EngineA m_adpcm_a;
        /** ADPCM-B engine */
        protected final Adpcm.EngineB m_adpcm_b;
    }

    //
    // YMF288
    //

    /**
     * ymf288
     *
     * YMF288 is a YM2608 with the following changes:
     * <ul>
     *  <li>ADPCM-B part removed</li>
     *  <li>prescaler removed (fixed at 6)</li>
     *  <li>CSM removed</li>
     *  <li>Low power mode added</li>
     *  <li>SSG tone frequency is altered in some way? (explicitly DC for Tp 0-7, also double volume in some cases)</li>
     *  <li>I/O ports removed</li>
     *  <li>Shorter busy times</li>
     *  <li>All registers can be read</li>
     * </ul>
     */
    @Serdes
    public static class Ymf288 implements YmFm.Chip {

        protected static class FmEngine extends EngineBase<OpnaRegisters> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, OpnaRegisters.class);
            }
        }

        protected final int FM_OUTPUTS;
        protected static final int SSG_OUTPUTS = 1;
        private final int OUTPUTS;

        //using output_data = Output<OUTPUTS>;
        @Override
        public YmFm.Output outputFactory() {
            return new YmFm.Output(OUTPUTS);
        }

        @Override
        public final int getOutputs(){
            return OUTPUTS;
        }

        /**
         * ymf288 - constructor
         */
        public Ymf288(YmFm.Interface intf) {
            m_fidelity = MAX;
            m_address = 0;
            m_irq_enable = 0x03;
            m_flag_control = 0x03;
            m_fm = new FmEngine(intf);
            m_ssg = new Ssg.Engine(intf);
            m_ssg_resampler = new SsgResampler(m_ssg) {
                @Override int getOutput() {
                    return OUTPUTS;
                }
                @Override int getFirstOutput() {
                    return 2;
                }
                @Override boolean isMixTo1() {
                    return true;
                }
            };
            m_adpcm_a = new Adpcm.EngineA(intf, 0);

            FM_OUTPUTS = (int) m_fm.getRegisterType().getParams().get("OUTPUTS");
            OUTPUTS = FM_OUTPUTS + SSG_OUTPUTS;

            m_last_fm = m_fm.outputFactory();
            m_last_fm.clear();
            update_prescale();
        }

        // configuration

        public void ssg_override(Ssg.Override intf) {
            m_ssg.override(intf);
        }

        public void set_fidelity(Fidelity fidelity) {
            m_fidelity = fidelity;
            update_prescale();
        }

        /**
         * Resets the system.
         */
        @Override
        public void reset() {
            // reset the engines
            m_fm.reset();
            m_ssg.reset();
            m_adpcm_a.reset();

            // configure ADPCM percussion sounds; these are present in an embedded ROM
            m_adpcm_a.set_start_end(0, 0x0000, 0x01bf); // bass drum
            m_adpcm_a.set_start_end(1, 0x01c0, 0x043f); // snare drum
            m_adpcm_a.set_start_end(2, 0x0440, 0x1b7f); // top cymbal
            m_adpcm_a.set_start_end(3, 0x1b80, 0x1cff); // high hat
            m_adpcm_a.set_start_end(4, 0x1d00, 0x1f7f); // tom tom
            m_adpcm_a.set_start_end(5, 0x1f80, 0x1fff); // rim shot

            // initialize our special interrupt states, then read the upper status
            // register, which updates the IRQs
            m_irq_enable = 0x03;
            m_flag_control = 0x00;
            read_status_hi();
        }

        /**
         * Saves the data.
         */
        @Override
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);

            m_fm.save(os);
            m_ssg.save(os);
            m_ssg_resampler.save(os);
            m_adpcm_a.save(os);
        }

        /**
         * Restores the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);

            m_fm.restore(is);
            m_ssg.restore(is);
            m_ssg_resampler.restore(is);
            m_adpcm_a.restore(is);
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
            switch (m_fidelity) {
                case MIN:
                    return input_clock / 144;
                case MED:
                    return input_clock / 144;
                default:
                case MAX:
                    return input_clock / 16;
            }
        }

        public final int ssg_effective_clock(int input_clock) {
            return input_clock / 4;
        }

        public void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /**
         * Reads the status register.
         */
        public int read_status() {
            int result = m_fm.status() & (OpnaRegisters.STATUS_TIMERA | OpnaRegisters.STATUS_TIMERB);
            if (m_fm.intf().ymfm_is_busy())
                result |= OpnaRegisters.STATUS_BUSY;
            return result;
        }

        /**
         * Reads the data register.
         */
        public int read_data() {
            int result = 0;
            if (m_address < 0x0e) {
                // 00-0D: Read from SSG
                result = m_ssg.read(m_address & 0x0f);
            } else if (m_address < 0x10) {
                // 0E-0F: I/O ports not supported
                result = 0xff;
            } else if (m_address == 0xff) {
                // FF: ID code
                result = 2;
            } else if (ymf288_mode()) {
                // registers are readable in YMF288 mode
                result = m_fm.regs().read(m_address);
            }
            return result;
        }

        /**
         * Reads the extended status register.
         */
        public int read_status_hi() {
            // fetch regular status
            int status = m_fm.status() & (OpnaRegisters.STATUS_TIMERA | OpnaRegisters.STATUS_TIMERB);

            // turn off any bits that have been requested to be masked
            status &= ~(m_flag_control & 0x03);

            // update the status so that IRQs are propagated
            m_fm.set_reset_status(status, ~status);

            // merge in the busy flag
            if (m_fm.intf().ymfm_is_busy())
                status |= OpnaRegisters.STATUS_BUSY;
            return status;
        }

        /**
         * Handles a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0;
            switch (offset & 3) {
                case 0: // status port, YM2203 compatible
                    result = read_status();
                    break;

                case 1: // data port
                    result = read_data();
                    break;

                case 2: // status port, extended
                    result = read_status_hi();
                    break;

                case 3: // unmapped
                    log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from YMF288 offset %d".formatted(offset & 3));
                    break;
            }
            return result;
        }

        /**
         * Handle a write to the address register.
         */
        public void write_address(int data) {
            // just set the address
            m_address = data;

            // in YMF288 mode, busy is signaled after address writes too
            if (ymf288_mode())
                m_fm.intf().ymfm_set_busy_end(16);
        }

        /**
         * Handle a write to the data register.
         */
        public void write_data(int data) {
            // ignore if paired with upper address
            if (bitfield(m_address, 8) != 0)
                return;

            // wait times are shorter in YMF288 mode
            int busy_cycles = ymf288_mode() ? 16 : 32 * m_fm.clock_prescale();
            if (m_address < 0x0e) {
                // 00-0D: write to SSG
                m_ssg.write(m_address & 0x0f, data);
            } else if (m_address < 0x10) {
                // 0E-0F: I/O ports not supported
            } else if (m_address < 0x20) {
                // 10-1F: write to ADPCM-A
                m_adpcm_a.write(m_address & 0x0f, data);
                busy_cycles = 32 * m_fm.clock_prescale();
            } else if (m_address == 0x27) {
                // 27: mode register; CSM isn't supported so disable it
                data &= 0x7f;
                m_fm.write(m_address, data);
            } else if (m_address == 0x29) {
                // 29: special IRQ mask register
                m_irq_enable = data;
                m_fm.set_irq_mask(m_irq_enable & ~m_flag_control & 0x03);
            } else {
                // 20-27, 2A-FF: write to FM
                m_fm.write(m_address, data);
            }

            // mark busy for a bit
            m_fm.intf().ymfm_set_busy_end(busy_cycles);
        }

        /**
         * Handles a write to the upper address register.
         */
        public void write_address_hi(int data) {
            // just set the address
            m_address = 0x100 | data;

            // in YMF288 mode, busy is signaled after address writes too
            if (ymf288_mode())
                m_fm.intf().ymfm_set_busy_end(16);
        }

        /**
         * Handle a write to the upper data register.
         */
        public void write_data_hi(int data) {
            // ignore if paired with upper address
            if (bitfield(m_address, 8) == 0)
                return;

            // wait times are shorter in YMF288 mode
            int busy_cycles = ymf288_mode() ? 16 : 32 * m_fm.clock_prescale();
            if (m_address == 0x110) {
                // 110: IRQ flag control
                if (bitfield(data, 7) != 0)
                    m_fm.set_reset_status(0, 0xff);
                else {
                    m_flag_control = data;
                    m_fm.set_irq_mask(m_irq_enable & ~m_flag_control & 0x03);
                }
            } else {
                // 100-10F,111-1FF: write to FM
                m_fm.write(m_address, data);
            }

            // mark busy for a bit
            m_fm.intf().ymfm_set_busy_end(busy_cycles);
        }

        /**
         * Handle a write to the register interface.
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

                case 2: // upper address port
                    write_address_hi(data);
                    break;

                case 3: // upper data port
                    write_data_hi(data);
                    break;
            }
        }

        /**
         * Generates one sample of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            // FM output is just repeated the prescale number of times; note that
            // 0 is a special 1.5 case
            if (m_fm_samples_per_output != 0) {
                for (int samp = 0; samp < numSamples; samp++) {
                    if ((m_ssg_resampler.sampleIndex() + samp) % m_fm_samples_per_output == 0)
                        clock_fm_and_adpcm();
                    output[samp].data[0] = m_last_fm.data[0];
                    output[samp].data[1] = m_last_fm.data[1];
                }
            } else {
                for (int samp = 0; samp < numSamples; samp++) {
                    int step = (m_ssg_resampler.sampleIndex() + samp) % 3;
                    if (step == 0)
                        clock_fm_and_adpcm();
                    output[samp].data[0] = m_last_fm.data[0];
                    output[samp].data[1] = m_last_fm.data[1];
                    if (step == 1) {
                        clock_fm_and_adpcm();
                        output[samp].data[0] = (output[samp].data[0] + m_last_fm.data[0]) / 2;
                        output[samp].data[1] = (output[samp].data[1] + m_last_fm.data[1]) / 2;
                    }
                }
            }

            // resample the SSG as configured
            m_ssg_resampler.resample(output, numSamples);
        }

        // internal helpers

        protected boolean ymf288_mode() {
            return ((m_fm.regs().read(0x20) & 0x02) != 0);
        }

        /**
         * Updates the prescale value, recomputing derived values.
         */
        protected void update_prescale() {
            // Fidelity:   ---- minimum ----    ---- medium -----    ---- maximum-----
            //              rate = clock/144     rate = clock/144     rate = clock/16
            // Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
            //     6          1:1     2:9          1:1     2:9         9:1     2:1

            // compute the number of FM samples per output sample, and select the
            // resampler function
            if (m_fidelity == Fidelity.MIN || m_fidelity == Fidelity.MED) {
                m_fm_samples_per_output = 1;
                m_ssg_resampler.configure(2, 9);
            } else {
                m_fm_samples_per_output = 9;
                m_ssg_resampler.configure(2, 1);
            }

            // if overriding the SSG, override the configuration with the nop
            // resampler to at least keep the sample index moving forward
            if (m_ssg.overridden())
                m_ssg_resampler.configure(0, 0);
        }

        /**
         * Clocks FM and ADPCM state.
         */
        protected void clock_fm_and_adpcm() {
            // top bit of the IRQ enable flags controls 3-channel vs 6-channel mode
            int fmmask = bitfield(m_irq_enable, 7) != 0 ? 0x3f : 0x07;

            // clock the system
            int env_counter = m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

            // clock the ADPCM-A engine on every envelope cycle
            // (channels 4 and 5 clock every 2 envelope clocks)
            if (bitfield(env_counter, 0, 2) == 0)
                m_adpcm_a.clock(bitfield(env_counter, 2) != 0 ? 0x0f : 0x3f);

            // update the FM content; OPNA is 13-bit with no intermediate clipping
            m_fm.output(m_last_fm.clear(), 1, 32767, fmmask);

            // mix in the ADPCM
            m_adpcm_a.output(m_last_fm, 0x3f);
        }

        // internal state

        /** configured fidelity */
        protected Opn.Fidelity m_fidelity;
        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** how many samples to repeat */
        protected int m_fm_samples_per_output;
        /** IRQ enable register */
        @Element(sequence = 2)
        protected int m_irq_enable;
        /** flag control register */
        @Element(sequence = 3)
        protected int m_flag_control;
        /** last FM output */
        @Element(sequence = 4)
        protected final YmFm.Output m_last_fm;
        /** core FM engine */
        protected final FmEngine m_fm;
        /** SSG engine */
        protected final Ssg.Engine m_ssg;
        /** SSG resampler helper */
        protected final SsgResampler /* <output_data, 2,true> */ m_ssg_resampler;
        /** ADPCM-A engine */
        protected final Adpcm.EngineA m_adpcm_a;
    }

    //
    // YM2610
    //

    /** ym2610 */
    @Serdes
    public static class Ym2610 implements YmFm.Chip {

        protected static final int EOS_FLAGS_MASK = 0xbf;

        protected static class FmEngine extends EngineBase<OpnaRegisters> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, OpnaRegisters.class);
            }
        }

        protected final int FM_OUTPUTS;
        protected static final int SSG_OUTPUTS = 1;
        private final int OUTPUTS;

        //using output_data = Output<OUTPUTS>;
        @Override
        public YmFm.Output outputFactory() {
            return new YmFm.Output(OUTPUTS);
        }

        @Override
        public final int getOutputs(){
            return OUTPUTS;
        }

        public Ym2610(YmFm.Interface intf) {
            this(intf, 0x36);
        }

        /**
         * Constructor.
         */
        public Ym2610(YmFm.Interface intf, int channel_mask /* = 0x36 */) {
            m_fidelity = MAX;
            m_address = 0;
            m_fm_mask = channel_mask;
            m_eos_status = 0x00;
            m_flag_mask = EOS_FLAGS_MASK;
            m_fm = new FmEngine(intf);
            m_ssg = new Ssg.Engine(intf);
            m_ssg_resampler = new SsgResampler(m_ssg) {
                @Override int getOutput() {
                    return OUTPUTS;
                }
                @Override int getFirstOutput() {
                    return 2;
                }
                @Override boolean isMixTo1() {
                    return true;
                }
            };
            m_adpcm_a = new Adpcm.EngineA(intf, 8);
            m_adpcm_b = new Adpcm.EngineB(intf, 8);

            m_last_fm = m_fm.outputFactory();

            FM_OUTPUTS = (int) m_fm.getRegisterType().getParams().get("OUTPUTS");
            OUTPUTS = FM_OUTPUTS + SSG_OUTPUTS;

            update_prescale();
        }

        // configuration

        public void ssg_override(Ssg.Override intf) {
            m_ssg.override(intf);
        }

        public void set_fidelity(Fidelity fidelity) {
            m_fidelity = fidelity;
            update_prescale();
        }

        /**
         * Resets the system.
         */
        @Override
        public void reset() {
            // reset the engines
            m_fm.reset();
            m_ssg.reset();
            m_adpcm_a.reset();
            m_adpcm_b.reset();

            // initialize our special interrupt states
            m_eos_status = 0x00;
            m_flag_mask = EOS_FLAGS_MASK;
        }

        /**
         * Restores the data.
         */
        @Override
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);

            m_fm.save(os);
            m_ssg.save(os);
            m_ssg_resampler.save(os);
            m_adpcm_a.save(os);
            m_adpcm_b.save(os);
        }

        /**
         * Saves the data.
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);

            m_fm.restore(is);
            m_ssg.restore(is);
            m_ssg_resampler.restore(is);
            m_adpcm_a.restore(is);
            m_adpcm_b.restore(is);
        }

        // pass-through helpers

        @Override
        public final int sample_rate(int input_clock) {
            switch (m_fidelity) {
                case MIN:
                    return input_clock / 144;
                case MED:
                    return input_clock / 144;
                default:
                case MAX:
                    return input_clock / 16;
            }
        }

        public final int ssg_effective_clock(int input_clock) {
            return input_clock / 4;
        }

        public void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /**
         * Reads the status register.
         */
        int read_status() {
            int result = m_fm.status() & (OpnaRegisters.STATUS_TIMERA | OpnaRegisters.STATUS_TIMERB);
            if (m_fm.intf().ymfm_is_busy())
                result |= OpnaRegisters.STATUS_BUSY;
            return result;
        }

        /**
         * Reads the data register.
         */
        public int read_data() {
            int result = 0;
            if (m_address < 0x0e) {
                // 00-0D: Read from SSG
                result = m_ssg.read(m_address & 0x0f);
            } else if (m_address < 0x10) {
                // 0E-0F: I/O ports not supported
                result = 0xff;
            } else if (m_address == 0xff) {
                // FF: ID code
                result = 1;
            }
            return result;
        }

        /**
         * Reads the extended status register.
         */
        public int read_status_hi() {
            return m_eos_status & m_flag_mask;
        }

        /**
         * read_data_hi - read the upper data register
         */
        public int read_data_hi() {
            int result = 0;
            return result;
        }

        /**
         * Handles a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0;
            switch (offset & 3) {
                case 0: // status port, YM2203 compatible
                    result = read_status();
                    break;

                case 1: // data port (only SSG)
                    result = read_data();
                    break;

                case 2: // status port, extended
                    result = read_status_hi();
                    break;

                case 3: // ADPCM-B data
                    result = read_data_hi();
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
            // ignore if paired with upper address
            if (bitfield(m_address, 8) != 0)
                return;

            if (m_address < 0x0e) {
                // 00-0D: write to SSG
                m_ssg.write(m_address & 0x0f, data);
            } else if (m_address < 0x10) {
                // 0E-0F: I/O ports not supported
            } else if (m_address < 0x1c) {
                // 10-1B: write to ADPCM-B
                // YM2610 effectively forces external mode on, and disables recording
                if (m_address == 0x10)
                    data = (data | 0x20) & ~0x40;
                m_adpcm_b.write(m_address & 0x0f, data);
            } else if (m_address == 0x1c) {
                // 1C: EOS flag reset
                m_flag_mask = ~data & EOS_FLAGS_MASK;
                m_eos_status &= ~(data & EOS_FLAGS_MASK);
            } else {
                // 1D-FF: write to FM
                m_fm.write(m_address, data);
            }

            // mark busy for a bit
            m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());
        }

        /**
         * Handles a write to the upper address register.
         */
        public void write_address_hi(int data) {
            // just set the address
            m_address = 0x100 | data;
        }

        /**
         * Handle a write to the upper data register.
         */
        public void write_data_hi(int data) {
            // ignore if paired with upper address
            if (bitfield(m_address, 8) == 0)
                return;

            if (m_address < 0x130) {
                // 100-12F: write to ADPCM-A
                m_adpcm_a.write(m_address & 0x3f, data);
            } else {
                // 130-1FF: write to FM
                m_fm.write(m_address, data);
            }

            // mark busy for a bit
            m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());
        }

        /**
         * Handle a write to the register interface.
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

                case 2: // upper address port
                    write_address_hi(data);
                    break;

                case 3: // upper data port
                    write_data_hi(data);
                    break;
            }
        }

        /**
         * Generates one sample of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            // FM output is just repeated the prescale number of times
            for (int samp = 0; samp < numSamples; samp++) {
                if ((m_ssg_resampler.sampleIndex() + samp) % m_fm_samples_per_output == 0)
                    clock_fm_and_adpcm();
                output[samp].data[0] = m_last_fm.data[0];
                output[samp].data[1] = m_last_fm.data[1];
            }

            // resample the SSG as configured
            m_ssg_resampler.resample(output, numSamples);
        }

        /**
         * Updates the prescale value, recomputing derived values.
         */
        protected void update_prescale() {
            // Fidelity:   ---- minimum ----    ---- medium -----    ---- maximum-----
            //              rate = clock/144     rate = clock/144     rate = clock/16
            // Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
            //     6          1:1     2:9          1:1     2:9         9:1     2:1

            // compute the number of FM samples per output sample, and select the
            // resampler function
            if (m_fidelity == Fidelity.MIN || m_fidelity == Fidelity.MED) {
                m_fm_samples_per_output = 1;
                m_ssg_resampler.configure(2, 9);
            } else {
                m_fm_samples_per_output = 9;
                m_ssg_resampler.configure(2, 1);
            }

            // if overriding the SSG, override the configuration with the nop
            // resampler to at least keep the sample index moving forward
            if (m_ssg.overridden())
                m_ssg_resampler.configure(0, 0);
        }

        /**
         * Clocks FM and ADPCM state
         */
        protected void clock_fm_and_adpcm() {
            // clock the system
            int env_counter = m_fm.clock(m_fm_mask);

            // clock the ADPCM-A engine on every envelope cycle
            if (bitfield(env_counter, 0, 2) == 0)
                m_eos_status |= m_adpcm_a.clock(0x3f);

            // clock the ADPCM-B engine every cycle
            m_adpcm_b.clock();

            // we track the last ADPCM-B EOS value in bit 6 (which is hidden from callers);
            // if it changed since the last sample, update the visible EOS state in bit 7
            int live_eos = ((m_adpcm_b.status() & ChannelB.STATUS_EOS) != 0) ? 0x40 : 0x00;
            if (((live_eos ^ m_eos_status) & 0x40) != 0)
                m_eos_status = (m_eos_status & ~0xc0) | live_eos | (live_eos << 1);

            // update the FM content; OPNB is 13-bit with no intermediate clipping
            m_fm.output(m_last_fm.clear(), 1, 32767, m_fm_mask);

            // mix in the ADPCM and clamp
            m_adpcm_a.output(m_last_fm, 0x3f);
            m_adpcm_b.output(m_last_fm, 1);
            m_last_fm.clamp16();
        }

        // internal state

        /** configured fidelity */
        protected Fidelity m_fidelity;
        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** FM channel mask */
        protected final int m_fm_mask;
        /** how many samples to repeat */
        protected int m_fm_samples_per_output;
        /** end-of-sample signals */
        @Element(sequence = 2)
        protected int m_eos_status;
        /** flag mask control */
        @Element(sequence = 3)
        protected int m_flag_mask;
        /** last FM output */
        protected final YmFm.Output m_last_fm;
        /** core FM engine */
        protected final FmEngine m_fm;
        /** core FM engine */
        protected final Ssg.Engine m_ssg;
        /** SSG resampler helper */
        protected final SsgResampler /* <output_data, 2,true> */ m_ssg_resampler;
        /** ADPCM-A engine */
        protected final Adpcm.EngineA m_adpcm_a;
        /** ADPCM-B engine */
        protected final Adpcm.EngineB m_adpcm_b;
    }

    /** ym2610b */
    public static class Ym2610b extends Ym2610 implements YmFm.Chip {

        // constructor
        public Ym2610b(YmFm.Interface intf) {
            super(intf, 0x3f);
        }
    }

    //
    // YM2612
    //

    /** ym2612 */
    @Serdes
    public static class Ym2612 implements YmFm.Chip {

        protected static class FmEngine extends Fm.EngineBase<OpnaRegisters> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, OpnaRegisters.class);
            }
        }

        private final int OUTPUTS;

        //using output_data = fm_engine.output_data;
        @Override
        public YmFm.Output outputFactory() {
            return m_fm.outputFactory();
        }

        @Override
        public final int getOutputs(){
            return OUTPUTS;
        }

        /**
         * Constructor.
         */
        public Ym2612(YmFm.Interface intf) {
            m_address = 0;
            m_dac_data = 0;
            m_dac_enable = 0;
            m_fm = new FmEngine(intf);

            OUTPUTS = (int) m_fm.getRegisterType().getParams().get("OUTPUTS");
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
            if (m_fm.intf().ymfm_is_busy())
                result |= OpnaRegisters.STATUS_BUSY;
            return result;
        }

        /**
         * Handles a read from the device.
         */
        @Override
        public int read(int offset) {
            int result = 0;
            switch (offset & 3) {
                case 0: // status port, YM2203 compatible
                    result = read_status();
                    break;

                case 1: // data port (unused)
                case 2: // status port, extended
                case 3: // data port (unused)
                    log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from YM2612 offset %d".formatted(offset & 3));
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
         * Handles a write to the data register.
         */
        public void write_data(int data) {
            // ignore if paired with upper address
            if (bitfield(m_address, 8) != 0)
                return;

            if (m_address == 0x2a) {
                // 2A: DAC data (most significant 8 bits)
                m_dac_data = (m_dac_data & ~0x1fe) | ((data ^ 0x80) << 1);
            } else if (m_address == 0x2b) {
                // 2B: DAC enable (bit 7)
                m_dac_enable = bitfield(data, 7);
            } else if (m_address == 0x2c) {
                // 2C: test/low DAC bit
                m_dac_data = (m_dac_data & ~1) | bitfield(data, 3);
            } else {
                // 00-29, 2D-FF: write to FM
                m_fm.write(m_address, data);
            }

            // mark busy for a bit
            m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());
        }

        /**
         * Handles a write to the upper address register.
         */
        public void write_address_hi(int data) {
            // just set the address
            m_address = 0x100 | data;
        }

        /**
         * Handles a write to the upper data register.
         */
        public void write_data_hi(int data) {
            // ignore if paired with upper address
            if (bitfield(m_address, 8) == 0)
                return;

            // 100-1FF: write to FM
            m_fm.write(m_address, data);

            // mark busy for a bit
            m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());
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

                case 2: // upper address port
                    write_address_hi(data);
                    break;

                case 3: // upper data port
                    write_data_hi(data);
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
                m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // sum individual channels to apply DAC discontinuity on each
                output[samp].clear();
                YmFm.Output temp = new YmFm.Output(m_fm.OUTPUTS);

                // first do FM-only channels; OPN2 is 9-bit with intermediate clipping
                int last_fm_channel = m_dac_enable != 0 ? 5 : 6;
                for (int chan = 0; chan < last_fm_channel; chan++) {
                    m_fm.output(temp.clear(), 5, 256, 1 << chan);
                    output[samp].data[0] += dac_discontinuity(temp.data[0]);
                    output[samp].data[1] += dac_discontinuity(temp.data[1]);
                }

                // add in DAC
                if (m_dac_enable != 0) {
                    // DAC enabled: start with DAC value then add the first 5 channels only
                    int dacVal = dac_discontinuity((m_dac_data << 7) >> 7);
                    output[samp].data[0] += m_fm.regs().ch_output_0(0x102) != 0 ? dacVal : dac_discontinuity(0);
                    output[samp].data[1] += m_fm.regs().ch_output_1(0x102) != 0 ? dacVal : dac_discontinuity(0);
                }

                // output is technically multiplexed rather than mixed, but that requires
                // a better sound mixer than we usually have, so just average over the six
                // channels; also apply a 64/65 factor to account for the discontinuity
                // adjustment above
                output[samp].data[0] = (output[samp].data[0] * 128) * 64 / (6 * 65);
                output[samp].data[1] = (output[samp].data[1] * 128) * 64 / (6 * 65);
            }
        }

        /** simulate the DAC discontinuity */
        protected final int dac_discontinuity(int value) {
            return (value < 0) ? (value - 3) : (value + 4);
        }

        // internal state

        /** address register */
        @Element(sequence = 1)
        protected int m_address;
        /** 9-bit DAC data */
        @Element(sequence = 2)
        protected int m_dac_data;
        /** DAC enabled? */
        @Element(sequence = 3)
        protected int m_dac_enable;
        /** core FM engine */
        protected final FmEngine m_fm;
    }

    /** ym3438 */
    public static class Ym3438 extends Ym2612 implements YmFm.Chip {

        public Ym3438(YmFm.Interface intf) {
            super(intf);
        }

        /**
         * Generates one sample of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // first do FM-only channels; OPN2C is 9-bit with intermediate clipping
                if (m_dac_enable == 0) {
                    // DAC disabled: all 6 channels sum together
                    m_fm.output(output[samp].clear(), 5, 256, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));
                } else {
                    // DAC enabled: start with DAC value then add the first 5 channels only
                    int dacVal = (m_dac_data << 7) >> 7;
                    output[samp].data[0] = m_fm.regs().ch_output_0(0x102) != 0 ? dacVal : 0;
                    output[samp].data[1] = m_fm.regs().ch_output_1(0x102) != 0 ? dacVal : 0;
                    m_fm.output(output[samp], 5, 256, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS") ^ (1 << 5));
                }

                // YM3438 doesn't have the same DAC discontinuity, though its output is
                // multiplexed like the YM2612
                output[samp].data[0] = (output[samp].data[0] * 128) / 6;
                output[samp].data[1] = (output[samp].data[1] * 128) / 6;
            }
        }
    }

    /** ymf276 */
    public static class Ymf276 extends Ym2612 implements YmFm.Chip {

        public Ymf276(YmFm.Interface intf) {
            super(intf);
        }

        /**
         * Generate one sample of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm.clock((int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));

                // first do FM-only channels; OPN2L is 14-bit with intermediate clipping
                if (m_dac_enable == 0) {
                    // DAC disabled: all 6 channels sum together
                    m_fm.output(output[samp].clear(), 0, 8191, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS"));
                } else {
                    // DAC enabled: start with DAC value then add the first 5 channels only
                    int dacVal = (m_dac_data << 7) >> 7;
                    output[samp].data[0] = m_fm.regs().ch_output_0(0x102) != 0 ? dacVal : 0;
                    output[samp].data[1] = m_fm.regs().ch_output_1(0x102) != 0 ? dacVal : 0;
                    m_fm.output(output[samp], 0, 8191, (int) m_fm.getRegisterType().getParams().get("ALL_CHANNELS") ^ (1 << 5));
                }

                // YMF276 is properly mixed; it shifts down 1 bit before clamping
                output[samp].data[0] = clamp(output[samp].data[0] >> 1, -32768, 32767);
                output[samp].data[1] = clamp(output[samp].data[1] >> 1, -32768, 32767);
            }
        }
    }
}
