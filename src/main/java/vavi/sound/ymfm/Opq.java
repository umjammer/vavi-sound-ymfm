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

import vavi.sound.ymfm.Fm.EngineBase;
import vavi.sound.ymfm.Fm.RegistersBase;
import vavi.sound.ymfm.Fm.OpDataCache;
import vavi.sound.ymfm.YmFm.Chip;
import vavi.sound.ymfm.YmFm.EnvelopeState;
import vavi.sound.ymfm.YmFm.Interface;
import vavi.sound.ymfm.YmFm.Output;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static vavi.sound.ymfm.Opz.TEMPORARY_DEBUG_PRINTS;
import static vavi.sound.ymfm.YmFm.abs_sin_attenuation;
import static vavi.sound.ymfm.YmFm.bitfield;
import static vavi.sound.ymfm.YmFm.Debug.log_unexpected_read_write;
import static vavi.sound.ymfm.YmFm.detune_adjustment;
import static vavi.sound.ymfm.YmFm.opn_lfo_pm_phase_adjustment;


//
// OPQ (aka YM3806/YM3533)
//
// This chip is not officially documented as far as I know. What I have
// comes from Jari Kangas' work on reverse engineering the PSR70:
//
//    https://github.com/JKN0/PSR70-reverse
//
// OPQ appears be bsaically a mixture of OPM and OPN.
//
public abstract class Opq {

    private Opq() {}

    //*********************************************************
    //  REGISTER CLASSES
    //*********************************************************

    // ======================> opq_registers

    //*********************************************************
    //  OPQ SPECIFICS
    //*********************************************************

    //
    // OPQ register map:
    //
    //      System-wide registers:
    //           03 xxxxxxxx Timer control (unknown; 0x71 causes interrupts at ~10ms)
    //           04 ----x--- LFO disable
    //              -----xxx LFO frequency (0=~4Hz, 6=~10Hz, 7=~47Hz)
    //           05 -x------ Key on/off operator 4
    //              --x----- Key on/off operator 3
    //              ---x---- Key on/off operator 2
    //              ----x--- Key on/off operator 1
    //              -----xxx Channel select
    //
    //     Per-channel registers (channel in address bits 0-2)
    //        10-17 x------- Pan right
    //              -x------ Pan left
    //              --xxx--- Feedback level for operator 1 (0-7)
    //              -----xxx Operator connection algorithm (0-7)
    //        18-1F x------- Reverb
    //              -xxx---- PM sensitivity
    //              ------xx AM shift
    //        20-27 -xxx---- Block (0-7), Operator 2 & 4
    //              ----xxxx Frequency number upper 4 bits, Operator 2 & 4
    //        28-2F -xxx---- Block (0-7), Operator 1 & 3
    //              ----xxxx Frequency number upper 4 bits, Operator 1 & 3
    //        30-37 xxxxxxxx Frequency number lower 8 bits, Operator 2 & 4
    //        38-3F xxxxxxxx Frequency number lower 8 bits, Operator 1 & 3
    //
    //     Per-operator registers (channel in address bits 0-2, operator in bits 3-4)
    //        40-5F 0-xxxxxx Detune value (0-63)
    //              1---xxxx Multiple value (0-15)
    //        60-7F -xxxxxxx Total level (0-127)
    //        80-9F xx------ Key scale rate (0-3)
    //              ---xxxxx Attack rate (0-31)
    //        A0-BF x------- LFO AM enable, retrigger disable
    //               x------ Waveform select
    //              ---xxxxx Decay rate (0-31)
    //        C0-DF ---xxxxx Sustain rate (0-31)
    //        E0-FF xxxx---- Sustain level (0-15)
    //              ----xxxx Release rate (0-15)
    //
    // Diffs from OPM:
    //  - 2 frequencies/channel
    //  - retrigger disable
    //  - 2 waveforms
    //  - uses FNUM
    //  - reverb behavior
    //  - larger detune range
    //
    // Questions:
    //  - timer information is pretty light
    //  - how does echo work?
    //  -
    @Serdes
    static class Registers extends RegistersBase {

        // constants
        public static final int OUTPUTS = 2;
        public static final int CHANNELS = 8;
        public static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
        public static final int OPERATORS = CHANNELS * 4;
        public static final int WAVEFORMS = 2;
        public static final int REGISTERS = 0x120;
        public static final int REG_MODE = 0x03;
        public static final int DEFAULT_PRESCALE = 2;
        public static final int EG_CLOCK_DIVIDER = 3;
        public static final boolean EG_HAS_REVERB = true;
        public static final boolean MODULATOR_DELAY = false;
        public static final int CSM_TRIGGER_MASK = ALL_CHANNELS;
        public static final int STATUS_TIMERA = 0;
        public static final int STATUS_TIMERB = 0x04;
        public static final int STATUS_BUSY = 0x80;
        public static final int STATUS_IRQ = 0;

        {
            getParams().put("OUTPUTS", OUTPUTS);
            getParams().put("CHANNELS", CHANNELS);
            getParams().put("ALL_CHANNELS", ALL_CHANNELS);
            getParams().put("OPERATORS", OPERATORS);
            getParams().put("WAVEFORMS", WAVEFORMS);
            getParams().put("REGISTERS", REGISTERS);
            getParams().put("REG_MODE", REG_MODE);
            getParams().put("DEFAULT_PRESCALE", DEFAULT_PRESCALE);
            getParams().put("EG_CLOCK_DIVIDER", EG_CLOCK_DIVIDER);
            getParams().put("EG_HAS_REVERB", EG_HAS_REVERB);
            getParams().put("MODULATOR_DELAY", MODULATOR_DELAY);
            getParams().put("CSM_TRIGGER_MASK", CSM_TRIGGER_MASK);
            getParams().put("STATUS_TIMERA", STATUS_TIMERA);
            getParams().put("STATUS_TIMERB", STATUS_TIMERB);
            getParams().put("STATUS_BUSY", STATUS_BUSY);
            getParams().put("STATUS_IRQ", STATUS_IRQ);
        }

        /**
         * opq_registers - constructor
         */
        public Registers() {
            m_lfo_counter = 0;
            m_lfo_am = 0;

            // create the waveforms
            for (int index = 0; index < WAVEFORM_LENGTH; index++)
                m_waveform[0][index] = abs_sin_attenuation(index) | (bitfield(index, 9) << 15);

            int zeroval = m_waveform[0][0];
            for (int index = 0; index < WAVEFORM_LENGTH; index++)
                m_waveform[1][index] = bitfield(index, 9) != 0 ? zeroval : m_waveform[0][index];
        }

        /**
         * reset - reset to initial state
         */
        public void reset() {
            Arrays.fill(m_regdata, 0, REGISTERS, 0);

            // enable output on both channels by default
            m_regdata[0x10] = m_regdata[0x11] = m_regdata[0x12] = m_regdata[0x13] = 0xc0;
            m_regdata[0x14] = m_regdata[0x15] = m_regdata[0x16] = m_regdata[0x17] = 0xc0;
        }

        /**
         * save_restore - save or restore the data
         */
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
        }

        /**
         * save_restore - save or restore the data
         */
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
        }

        /**
         * save_restore - save or restore the data
         */
        public void save_restore(InputStream is) {
        }

        // map channel number to register offset
        @Override
        public final int channel_offset(int chnum) {
            assert (chnum < CHANNELS);
            return chnum;
        }

        // map operator number to register offset
        @Override
        public final int operator_offset(int opnum) {
            assert (opnum < OPERATORS);
            return opnum;
        }

        // return an array of operator indices for each channel
        static final int[] s_fixed_map = {
                operator_list(0, 8, 16, 24),  // Channel 0 operators
                operator_list(1, 9, 17, 25),  // Channel 1 operators
                operator_list(2, 10, 18, 26),  // Channel 2 operators
                operator_list(3, 11, 19, 27),  // Channel 3 operators
                operator_list(4, 12, 20, 28),  // Channel 4 operators
                operator_list(5, 13, 21, 29),  // Channel 5 operators
                operator_list(6, 14, 22, 30),  // Channel 6 operators
                operator_list(7, 15, 23, 31),  // Channel 7 operators
        };

        /**
         * operator_map - return an array of operator
         * indices for each channel; for OPM this is fixed
         */
        @Override
        public final void operator_map(int[][] dest) {
            // seems like the operators are not swizzled like they are on OPM/OPN?
            dest[0] = s_fixed_map;
        }

        /**
         * write - handle writes to the register array
         */
        @Override
        public boolean write(int index, int data, int[] channel, int[] opmask) {
            assert (index < REGISTERS);

            // detune/multiple share a register based on the MSB of what is written
            // remap the multiple values to 100-11F
            if ((index & 0xe0) == 0x40 && bitfield(data, 7) != 0)
                index += 0xc0;

            m_regdata[index] = data;

            // handle writes to the key on index
            if (index == 0x05) {
                channel[0] = bitfield(data, 0, 3);
                opmask[0] = bitfield(data, 3, 4);
                return true;
            }
            return false;
        }

        // this table is based on converting the frequencies in the applications
        // manual to clock dividers, based on the assumption of a 7-bit LFO value
        static final int[] lfo_max_count = {109, 78, 72, 68, 63, 45, 9, 6};

        /**
         * clock_noise_and_lfo - clock the noise and LFO,
         * handling clock division, depth, and waveform
         * computations
         */
        @Override
        public int clock_noise_and_lfo() {
            // OPQ LFO is not well-understood, but the enable and rate values
            // look a lot like OPN, so we'll crib from there as a starting point

            // if LFO not enabled (not present on OPN), quick exit with 0s
            if (lfo_enable() == 0) {
                m_lfo_counter = 0;
                m_lfo_am = 0;
                return 0;
            }

            int subcount = m_lfo_counter++;

            // when we cross the divider count, add enough to zero it and cause an
            // increment at bit 8; the 7-bit value lives from bits 8-14
            if (subcount >= lfo_max_count[lfo_rate()])
                m_lfo_counter += 0x101 - subcount;

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

        // reset the LFO
        void reset_lfo() {
            m_lfo_counter = 0;
        }

        /**
         * lfo_am_offset - return the AM offset from LFO
         * for the given channel
         */
        @Override
        public final int lfo_am_offset(int choffs) {
            // OPM maps AM quite differently from OPN

            // shift value for AM sensitivity is [*, 0, 1, 2],
            // mapping to values of [0, 23.9, 47.8, and 95.6dB]
            int am_sensitivity = ch_lfo_am_sens(choffs);
            if (am_sensitivity == 0)
                return 0;

            // QUESTION: see OPN note below for the dB range mapping; it applies
            // here as well

            // raw LFO AM value on OPM is 0-FF, which is already a factor of 2
            // larger than the OPN below, putting our staring point at 2x theirs;
            // this works out since our minimum is 2x their maximum
            return m_lfo_am << (am_sensitivity - 1);
        }

        // return the current noise state, gated by the noise clock
        @Override
        public final int noise_state() {
            return 0;
        }

        static final int[] s_multiple_map = {
                1, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 24, 30, 32, 34, 36
        };

        /**
         * cache_operator_data - fill the operator cache
         * with prefetched data
         */
        @Override
        public void cache_operator_data(int choffs, int opoffs, OpDataCache cache) {
            // set up the easy stuff
            cache.waveform = m_waveform[op_waveform(opoffs)];

            // get frequency from the appropriate registers
            int block_freq = cache.block_freq = (opoffs & 8) != 0 ? ch_block_freq_24(choffs) : ch_block_freq_13(choffs);

            // compute the keycode: block_freq is:
            //
            //     BBBFFFFFFFFFFFF
            //     ^^^^???
            //
            // keycode is not understood, so just guessing it is like OPN:
            // the 5-bit keycode uses the top 4 bits plus a magic formula
            // for the final bit
            int keycode = bitfield(block_freq, 11, 4) << 1;

            // lowest bit is determined by a mix of next lower FNUM bits
            // according to this equation from the YM2608 manual:
            //
            //   (F11 & (F10 | F9 | F8)) | (!F11 & F10 & F9 & F8)
            //
            // for speed, we just look it up in a 16-bit constant
            keycode |= bitfield(0xfe80, bitfield(block_freq, 8, 4));

            // detune adjustment: the detune values supported by the OPQ are
            // a much larger range (6 bits vs 3 bits) compared to any other
            // known FM chip; based on experiments, it seems that the extra
            // bits provide a bigger detune range rather than finer control,
            // so until we get true measurements just assemble a net detune
            // value by summing smaller detunes
            int detune = op_detune(opoffs) - 0x20;
            int abs_detune = Math.abs(detune);
            int adjust = (abs_detune / 3) * detune_adjustment(3, keycode) + detune_adjustment(abs_detune % 3, keycode);
            cache.detune = (detune >= 0) ? adjust : -adjust;

            // multiple value, as an x.1 value (0 means 0.5)
            cache.multiple = s_multiple_map[op_multiple(opoffs)];

            // phase step, or PHASE_STEP_DYNAMIC if PM is active; this depends on
            // block_freq, detune, and multiple, so compute it after we've done those
            if (lfo_enable() == 0 || ch_lfo_pm_sens(choffs) == 0)
                cache.phase_step = compute_phase_step(choffs, opoffs, cache, 0);
            else
                cache.phase_step = OpDataCache.PHASE_STEP_DYNAMIC;

            // total level, scaled by 8
            cache.total_level = op_total_level(opoffs) << 3;

            // 4-bit sustain level, but 15 means 31 so effectively 5 bits
            cache.eg_sustain = op_sustain_level(opoffs);
            cache.eg_sustain |= (cache.eg_sustain + 1) & 0x10;
            cache.eg_sustain <<= 5;

            // determine KSR adjustment for enevlope rates
            int ksrval = keycode >> (op_ksr(opoffs) ^ 3);
            cache.eg_rate[EnvelopeState.EG_ATTACK.ordinal()] = effective_rate(op_attack_rate(opoffs) * 2, ksrval);
            cache.eg_rate[EnvelopeState.EG_DECAY.ordinal()] = effective_rate(op_decay_rate(opoffs) * 2, ksrval);
            cache.eg_rate[EnvelopeState.EG_SUSTAIN.ordinal()] = effective_rate(op_sustain_rate(opoffs) * 2, ksrval);
            cache.eg_rate[EnvelopeState.EG_RELEASE.ordinal()] = effective_rate(op_release_rate(opoffs) * 4 + 2, ksrval);
            cache.eg_rate[EnvelopeState.EG_REVERB.ordinal()] = (ch_reverb(choffs) != 0) ? 5 * 4 : cache.eg_rate[EnvelopeState.EG_RELEASE.ordinal()];
            cache.eg_shift = 0;
        }

        /**
         * compute_phase_step - compute the phase step
         */
        @Override
        public int compute_phase_step(int choffs, int opoffs, final OpDataCache cache, int lfo_raw_pm) {
            // OPN phase calculation has only a single detune parameter
            // and uses FNUMs instead of keycodes

            // extract frequency number (low 12 bits of block_freq)
            int fnum = bitfield(cache.block_freq, 0, 12);

            // if there's a non-zero PM sensitivity, compute the adjustment
            int pm_sensitivity = ch_lfo_pm_sens(choffs);
            if (pm_sensitivity != 0) {
                // apply the phase adjustment based on the upper 7 bits
                // of FNUM and the PM depth parameters
                fnum += opn_lfo_pm_phase_adjustment(bitfield(cache.block_freq, 5, 7), pm_sensitivity, lfo_raw_pm);

                // keep fnum to 12 bits
                fnum &= 0xfff;
            }

            // apply block shift to compute phase step
            int block = bitfield(cache.block_freq, 12, 3);
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
         * log_keyon - log a key-on event
         */
        @Override
        public String log_keyon(int choffs, int opoffs) {
            int chnum = choffs;
            int opnum = opoffs;

            StringBuilder buffer = new StringBuilder();
            int end = 0;

            buffer.append("%d.%02d freq=%04X dt=%+2d fb=%d alg=%X mul=%X tl=%02X ksr=%d adsr=%02X/%02X/%02X/%X sl=%X out=%c%c".formatted(
                    chnum, opnum,
                    (opoffs & 1) != 0 ? ch_block_freq_24(choffs) : ch_block_freq_13(choffs),
                    op_detune(opoffs) - 0x20,
                    ch_feedback(choffs),
                    ch_algorithm(choffs),
                    op_multiple(opoffs),
                    op_total_level(opoffs),
                    op_ksr(opoffs),
                    op_attack_rate(opoffs),
                    op_decay_rate(opoffs),
                    op_sustain_rate(opoffs),
                    op_release_rate(opoffs),
                    op_sustain_level(opoffs),
                    ch_output_0(choffs) != 0 ? 'L' : '-',
                    ch_output_1(choffs) != 0 ? 'R' : '-'));

            boolean am = (lfo_enable() != 0 && op_lfo_am_enable(opoffs) != 0 && ch_lfo_am_sens(choffs) != 0);
            if (am)
                buffer.append(" am=%d".formatted(ch_lfo_am_sens(choffs)));
            boolean pm = (lfo_enable() != 0 && ch_lfo_pm_sens(choffs) != 0);
            if (pm)
                buffer.append(" pm=%d".formatted(ch_lfo_pm_sens(choffs)));
            if (am || pm)
                buffer.append(" lfo=%02X".formatted(lfo_rate()));
            if (ch_reverb(choffs) != 0)
                buffer.append(" reverb");

            return buffer.toString();
        }

        // system-wide registers
        @Override
        public final int timer_a_value() {
            return 0;
        }

        @Override
        public final int timer_b_value() {
            return byte_(0x03, 2, 6) | 0xc0;
        } // ???

        @Override
        public final int csm() {
            return 0;
        }

        @Override
        public final int reset_timer_b() {
            return byte_(0x03, 0, 1);
        } // ???

        @Override
        public final int reset_timer_a() {
            return 0;
        }

        @Override
        public final int enable_timer_b() {
            return byte_(0x03, 0, 1);
        } // ???

        @Override
        public final int enable_timer_a() {
            return 0;
        }

        @Override
        public final int load_timer_b() {
            return byte_(0x03, 0, 1);
        } // ???

        @Override
        public final int load_timer_a() {
            return 0;
        }

        public final int lfo_enable() {
            return byte_(0x04, 3, 1) ^ 1;
        }

        public final int lfo_rate() {
            return byte_(0x04, 0, 3);
        }

        // per-channel registers
        @Override
        public final int ch_output_any(int choffs) {
            return byte_(0x10, 6, 2, choffs);
        }

        @Override
        public final int ch_output_0(int choffs) {
            return byte_(0x10, 6, 1, choffs);
        }

        @Override
        public final int ch_output_1(int choffs) {
            return byte_(0x10, 7, 1, choffs);
        }

        @Override
        public final int ch_output_2(int choffs) {
            return 0;
        }

        @Override
        public final int ch_output_3(int choffs) {
            return 0;
        }

        @Override
        public final int ch_feedback(int choffs) {
            return byte_(0x10, 3, 3, choffs);
        }

        @Override
        public final int ch_algorithm(int choffs) {
            return byte_(0x10, 0, 3, choffs);
        }

        public final int ch_reverb(int choffs) {
            return byte_(0x18, 7, 1, choffs);
        }

        public final int ch_lfo_pm_sens(int choffs) {
            return byte_(0x18, 4, 3, choffs);
        }

        public final int ch_lfo_am_sens(int choffs) {
            return byte_(0x18, 0, 2, choffs);
        }

        public final int ch_block_freq_24(int choffs) {
            return word(0x20, 0, 7, 0x30, 0, 8, choffs);
        }

        public final int ch_block_freq_13(int choffs) {
            return word(0x28, 0, 7, 0x38, 0, 8, choffs);
        }

        // per-operator registers
        public final int op_detune(int opoffs) {
            return byte_(0x40, 0, 6, opoffs);
        }

        public final int op_multiple(int opoffs) {
            return byte_(0x100, 0, 4, opoffs);
        }

        public final int op_total_level(int opoffs) {
            return byte_(0x60, 0, 7, opoffs);
        }

        public final int op_ksr(int opoffs) {
            return byte_(0x80, 6, 2, opoffs);
        }

        public final int op_attack_rate(int opoffs) {
            return byte_(0x80, 0, 5, opoffs);
        }

        @Override
        public final int op_lfo_am_enable(int opoffs) {
            return byte_(0xa0, 7, 1, opoffs);
        }

        public final int op_waveform(int opoffs) {
            return byte_(0xa0, 6, 1, opoffs);
        }

        public final int op_decay_rate(int opoffs) {
            return byte_(0xa0, 0, 5, opoffs);
        }

        public final int op_sustain_rate(int opoffs) {
            return byte_(0xc0, 0, 5, opoffs);
        }

        public final int op_sustain_level(int opoffs) {
            return byte_(0xe0, 4, 4, opoffs);
        }

        public final int op_release_rate(int opoffs) {
            return byte_(0xe0, 0, 4, opoffs);
        }

        protected final int byte_(int offset, int start, int count) {
            return byte_(offset, start, count, 0);
        }

        // return a bitfield extracted from a byte
        protected final int byte_(int offset, int start, int count, int extra_offset/* = 0 */) {
            return bitfield(m_regdata[offset + extra_offset], start, count);
        }

        // return a bitfield extracted from a pair of bytes, MSBs listed first
        protected final int word(int offset1, int start1, int count1, int offset2, int start2, int count2, int extra_offset/* = 0 */) {
            return (byte_(offset1, start1, count1, extra_offset) << count2) | byte_(offset2, start2, count2, extra_offset);
        }

        // internal state
        @Element(sequence = 0)
        protected int m_lfo_counter;               // LFO counter
        @Element(sequence = 1)
        protected int m_lfo_am;                     // current LFO AM value
        @Element(sequence = 2)
        protected int[] m_regdata = new int[REGISTERS];         // register data
        protected int[][] m_waveform = new int[WAVEFORMS][WAVEFORM_LENGTH]; // waveforms
    }

    //*********************************************************
    //  IMPLEMENTATION CLASSES
    //*********************************************************

    // ======================> ym3806

    //*********************************************************
    //  YM3806
    //*********************************************************
    @Serdes
    public static class Ym3806 implements Chip {

        protected static class FmEngine extends EngineBase<Opq.Registers> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, Opq.Registers.class);
            }
        }

        private static final int OUTPUTS = Opq.Registers.OUTPUTS;
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
         * ym3806 - constructor
         */
        public Ym3806(YmFm.Interface intf) {
            m_fm = new FmEngine(intf);
        }

        /**
         * reset - reset the system
         */
        @Override
        public void reset() {
            // reset the engines
            m_fm.reset();
        }

        /**
         * save_restore - save or restore the data
         */
        @Override
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
        }

        /**
         * save_restore - save or restore the data
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
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
         * read_status - read the status register
         */
        public int read_status() {
            int result = m_fm.status();
            if (m_fm.intf().ymfm_is_busy())
                result |= Registers.STATUS_BUSY;
            return result;
        }

        /**
         * read - handle a read from the device
         */
        @Override
        public int read(int offset) {
            int result = 0xff;
            switch (offset) {
                case 0: // status port
                    result = read_status();
                    break;

                default: // unknown
                    log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from YM3806 offset %02X", offset);
                    break;
            }
            if (log_unexpected_read_write.isLoggable(Level.DEBUG) && offset != 0)
                System.out.printf("Read %02X = %02X%n", offset, result);
            return result;
        }

        // write access
        public void write_address(int data) { /* not supported; only direct writes */ }

        public void write_data(int data) { /* not supported; only direct writes */ }

        /**
         * write - handle a write to the register
         * interface
         */
        @Override
        public void write(int offset, int data) {
            if (TEMPORARY_DEBUG_PRINTS != 0 && (offset != 3 || data != 0x71))
                System.out.printf("Write %02X = %02X%n", offset, data);
            // write the FM register
            m_fm.write(offset, data);
        }

        /**
         * generate - generate one sample of sound
         */
        @Override
        public void generate(Output output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++, output.inc()) {
                // clock the system
                m_fm.clock(Registers.ALL_CHANNELS);

                // update the FM content; YM3806 is full 14-bit with no intermediate clipping
                m_fm.output(output.clear(), 0, 32767, Registers.ALL_CHANNELS);

                // YM3608 appears to go through a YM3012 DAC, which means we want to apply
                // the FP truncation logic to the outputs
                output.roundtrip_fp();
            }
        }

        // internal state
        @Element
        protected FmEngine m_fm;                  // core FM engine
    }

    // ======================> ym3533

    public static class Ym3533 extends Ym3806 implements Chip {

        // constructor
        public Ym3533(Interface intf) {
            super(intf);
        }
    }
}
