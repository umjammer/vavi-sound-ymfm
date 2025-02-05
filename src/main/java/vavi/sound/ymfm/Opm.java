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
import vavi.sound.ymfm.Fm.OpDataCache;
import vavi.sound.ymfm.Fm.RegistersBase;
import vavi.sound.ymfm.YmFm.EnvelopeState;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static vavi.sound.ymfm.YmFm.AccessClass.IO;
import static vavi.sound.ymfm.YmFm.Debug.log_unexpected_read_write;
import static vavi.sound.ymfm.YmFm.abs_sin_attenuation;
import static vavi.sound.ymfm.YmFm.bitfield;
import static vavi.sound.ymfm.YmFm.detune_adjustment;
import static vavi.sound.ymfm.YmFm.opm_key_code_to_phase_step;


public abstract class Opm {

    private Opm() {}

    //
    // REGISTER CLASSES
    //

    //
    // OPM REGISTERS
    //

    //
    // OPM register map:
    //
    //      System-wide registers:
    //           01 xxxxxx-x Test register
    //              ------x- LFO reset
    //           08 -x------ Key on/off operator 4
    //              --x----- Key on/off operator 3
    //              ---x---- Key on/off operator 2
    //              ----x--- Key on/off operator 1
    //              -----xxx Channel select
    //           0F x------- Noise enable
    //              ---xxxxx Noise frequency
    //           10 xxxxxxxx Timer A value (upper 8 bits)
    //           11 ------xx Timer A value (lower 2 bits)
    //           12 xxxxxxxx Timer B value
    //           14 x------- CSM mode
    //              --x----- Reset timer B
    //              ---x---- Reset timer A
    //              ----x--- Enable timer B
    //              -----x-- Enable timer A
    //              ------x- Load timer B
    //              -------x Load timer A
    //           18 xxxxxxxx LFO frequency
    //           19 0xxxxxxx AM LFO depth
    //              1xxxxxxx PM LFO depth
    //           1B xx------ CT (2 output data lines)
    //              ------xx LFO waveform
    //
    //     Per-channel registers (channel in address bits 0-2)
    //        20-27 x------- Pan right
    //              -x------ Pan left
    //              --xxx--- Feedback level for operator 1 (0-7)
    //              -----xxx Operator connection algorithm (0-7)
    //        28-2F -xxxxxxx Key code
    //        30-37 xxxxxx-- Key fraction
    //        38-3F -xxx---- LFO PM sensitivity
    //              ------xx LFO AM shift
    //
    //     Per-operator registers (channel in address bits 0-2, operator in bits 3-4)
    //        40-5F -xxx---- Detune value (0-7)
    //              ----xxxx Multiple value (0-15)
    //        60-7F -xxxxxxx Total level (0-127)
    //        80-9F xx------ Key scale rate (0-3)
    //              ---xxxxx Attack rate (0-31)
    //        A0-BF x------- LFO AM enable
    //              ---xxxxx Decay rate (0-31)
    //        C0-DF xx------ Detune 2 value (0-3)
    //              ---xxxxx Sustain rate (0-31)
    //        E0-FF xxxx---- Sustain level (0-15)
    //              ----xxxx Release rate (0-15)
    //
    //     Internal (fake) registers:
    //           1A -xxxxxxx PM depth
    //

    /** opm_registers */
    @Serdes
    protected static class Registers extends RegistersBase {

        // LFO waveforms are 256 entries long
        protected static final int LFO_WAVEFORM_LENGTH = 256;

        // constants
        protected static final int OUTPUTS = 2;
        protected static final int CHANNELS = 8;
        protected static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
        protected static final int OPERATORS = CHANNELS * 4;
        protected static final int WAVEFORMS = 1;
        protected static final int REGISTERS = 0x100;
        protected static final int DEFAULT_PRESCALE = 2;
        protected static final int EG_CLOCK_DIVIDER = 3;
        protected static final int CSM_TRIGGER_MASK = ALL_CHANNELS;
        protected static final int REG_MODE = 0x14;
        protected static final int STATUS_TIMERA = 0x01;
        protected static final int STATUS_TIMERB = 0x02;
        protected static final int STATUS_BUSY = 0x80;
        protected static final int STATUS_IRQ = 0;

        /**
         * Constructor.
         */
        protected Registers() {
            m_lfo_counter = 0;
            m_noise_lfsr = 1;
            m_noise_counter = 0;
            m_noise_state = 0;
            m_noise_lfo = 0;
            m_lfo_am = 0;

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

            // create the waveforms
            for (int index = 0; index < WAVEFORM_LENGTH; index++)
                m_waveform[0][index] = abs_sin_attenuation(index) | (bitfield(index, 9) << 15);

            // create the LFO waveforms; AM in the low 8 bits, PM in the upper 8
            // waveforms are adjusted to match the pictures in the application manual
            for (int index = 0; index < LFO_WAVEFORM_LENGTH; index++) {
                // waveform 0 is a sawtooth
                int am = index ^ 0xff;
                int pm = index;
                m_lfo_waveform[0][index] = am | (pm << 8);

                // waveform 1 is a square wave
                am = bitfield(index, 7) != 0 ? 0 : 0xff;
                pm = am ^ 0x80;
                m_lfo_waveform[1][index] = am | (pm << 8);

                // waveform 2 is a triangle wave
                am = bitfield(index, 7) != 0 ? (index << 1) : ((index ^ 0xff) << 1);
                pm = bitfield(index, 6) != 0 ? am : ~am;
                m_lfo_waveform[2][index] = am | (pm << 8);

                // waveform 3 is noise; it is filled in dynamically
                m_lfo_waveform[3][index] = 0;
            }
        }

        /**
         * Resets to initial state.
         */
        @Override
        public void reset() {
            Arrays.fill(m_regdata, 0, REGISTERS, 0);

            // enable output on both channels by default
            m_regdata[0x20] = m_regdata[0x21] = m_regdata[0x22] = m_regdata[0x23] = 0xc0;
            m_regdata[0x24] = m_regdata[0x25] = m_regdata[0x26] = m_regdata[0x27] = 0xc0;
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

        /** map channel number to register offset */
        @Override
        public int channel_offset(int chNum) {
            assert (chNum < CHANNELS);
            return chNum;
        }

        /** map operator number to register offset */
        @Override
        public int operator_offset(int opNum) {
            assert (opNum < OPERATORS);
            return opNum;
        }

        // return an array of operator indices for each channel
        // Note that the channel index order is 0,2,1,3, so we bitswap the index.
        //
        // This is because the order in the map is:
        //    carrier 1, carrier 2, modulator 1, modulator 2
        //
        // But when wiring up the connections, the more natural order is:
        //    carrier 1, modulator 1, carrier 2, modulator 2
        private static final int[] s_fixed_map = {
                operator_list(0, 16, 8, 24),  // Channel 0 operators
                operator_list(1, 17, 9, 25),  // Channel 1 operators
                operator_list(2, 18, 10, 26),  // Channel 2 operators
                operator_list(3, 19, 11, 27),  // Channel 3 operators
                operator_list(4, 20, 12, 28),  // Channel 4 operators
                operator_list(5, 21, 13, 29),  // Channel 5 operators
                operator_list(6, 22, 14, 30),  // Channel 6 operators
                operator_list(7, 23, 15, 31),  // Channel 7 operators
        };

        /**
         * Returns an array of operator indices for each channel; for OPM this is fixed.
         */
        @Override
        public final void operator_map(int[][] dest) {
            dest[0] = s_fixed_map;
        }

        /**
         * Handles writes to the register array.
         */
        @Override
        public boolean write(int index, int data, int[] channel, int[] opMask) {
            assert (index < REGISTERS);

            // LFO AM/PM depth are written to the same register (0x19);
            // redirect the PM depth to an unused neighbor (0x1a)
            if (index == 0x19)
                m_regdata[index + bitfield(data, 7)] = data;
            else if (index != 0x1a)
                m_regdata[index] = data;

            // handle writes to the key on index
            if (index == 0x08) {
                channel[0] = bitfield(data, 0, 3);
                opMask[0] = bitfield(data, 3, 4);
                return true;
            }
            return false;
        }

        /**
         * Clocks the noise and LFO, handling clock division, depth, and waveform computations.
         */
        @Override
        public int clock_noise_and_lfo() {
            // base noise frequency is measured at 2x 1/2 FM frequency; this
            // means each tick counts as two steps against the noise counter
            int freq = noise_frequency();
            for (int rep = 0; rep < 2; rep++) {
                // evidence seems to suggest the LFSR is clocked continually and just
                // sampled at the noise frequency for output purposes; note that the
                // low 8 bits are the most recent 8 bits of history while bits 8-24
                // contain the 17 bit LFSR state
                m_noise_lfsr <<= 1;
                m_noise_lfsr |= bitfield(m_noise_lfsr, 17) ^ bitfield(m_noise_lfsr, 14) ^ 1;

                // compare against the frequency and latch when we exceed it
                if (m_noise_counter++ >= freq) {
                    m_noise_counter = 0;
                    m_noise_state = bitfield(m_noise_lfsr, 17);
                }
            }

            // treat the rate as a 4.4 floating-point step value with implied
            // leading 1; this matches exactly the frequencies in the application
            // manual, though it might not be implemented exactly this way on chip
            int rate = lfo_rate();
            m_lfo_counter += (0x10 | bitfield(rate, 0, 4)) << bitfield(rate, 4, 4);

            // bit 1 of the test register is officially undocumented but has been
            // discovered to hold the LFO in reset while active
            if (lfo_reset() != 0)
                m_lfo_counter = 0;

            // now pull out the non-fractional LFO value
            int lfo = bitfield(m_lfo_counter, 22, 8);

            // fill in the noise entry 1 ahead of our current position; this
            // ensures the current value remains stable for a full LFO clock
            // and effectively latches the running value when the LFO advances
            int lfo_noise = bitfield(m_noise_lfsr, 17, 8);
            m_lfo_waveform[3][(lfo + 1) & 0xff] = lfo_noise | (lfo_noise << 8);

            // fetch the AM/PM values based on the waveform; AM is unsigned and
            // encoded in the low 8 bits, while PM signed and encoded in the upper
            // 8 bits
            int ampm = m_lfo_waveform[lfo_waveform()][lfo];

            // apply depth to the AM value and store for later
            m_lfo_am = ((ampm & 0xff) * lfo_am_depth()) >> 7;

            // apply depth to the PM value and return it
            return ((ampm >> 8) * lfo_pm_depth()) >> 7;
        }

        /**
         * Returns the AM offset from LFO for the given channel.
         */
        @Override
        public final int lfo_am_offset(int chOffs) {
            // OPM maps AM quite differently from OPN

            // shift value for AM sensitivity is [*, 0, 1, 2],
            // mapping to values of [0, 23.9, 47.8, and 95.6dB]
            int am_sensitivity = ch_lfo_am_sens(chOffs);
            if (am_sensitivity == 0)
                return 0;

            // QUESTION: see OPN note below for the dB range mapping; it applies
            // here as well

            // raw LFO AM value on OPM is 0-FF, which is already a factor of 2
            // larger than the OPN below, putting our staring point at 2x theirs;
            // this works out since our minimum is 2x their maximum
            return m_lfo_am << (am_sensitivity - 1);
        }

        /** return the current noise state, gated by the noise clock */
        @Override
        public final int noise_state() {
            return m_noise_state;
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

            // compute the keycode: block_freq is:
            //
            //     BBBCCCCFFFFFF
            //     ^^^^^
            //
            // the 5-bit keycode is just the top 5 bits (block + top 2 bits
            // of the key code)
            int keycode = bitfield(block_freq, 8, 5);

            // detune adjustment
            cache.detune = detune_adjustment(op_detune(opOffs), keycode);

            // multiple value, as an x.1 value (0 means 0.5)
            cache.multiple = op_multiple(opOffs) * 2;
            if (cache.multiple == 0)
                cache.multiple = 1;

            // phase step, or PHASE_STEP_DYNAMIC if PM is active; this depends on
            // block_freq, detune, and multiple, so compute it after we've done those
            if (lfo_pm_depth() == 0 || ch_lfo_pm_sens(chOffs) == 0)
                cache.phase_step = compute_phase_step(chOffs, opOffs, cache, 0);
            else
                cache.phase_step = OpDataCache.PHASE_STEP_DYNAMIC;

            // total level, scaled by 8
            cache.total_level = op_total_level(opOffs) << 3;

            // 4-bit sustain level, but 15 means 31 so effectively 5 bits
            cache.eg_sustain = op_sustain_level(opOffs);
            cache.eg_sustain |= (cache.eg_sustain + 1) & 0x10;
            cache.eg_sustain <<= 5;

            // determine KSR adjustment for envelope rates
            int ksrVal = keycode >> (op_ksr(opOffs) ^ 3);
            cache.eg_rate[EnvelopeState.EG_ATTACK.ordinal()] = effective_rate(op_attack_rate(opOffs) * 2, ksrVal);
            cache.eg_rate[EnvelopeState.EG_DECAY.ordinal()] = effective_rate(op_decay_rate(opOffs) * 2, ksrVal);
            cache.eg_rate[EnvelopeState.EG_SUSTAIN.ordinal()] = effective_rate(op_sustain_rate(opOffs) * 2, ksrVal);
            cache.eg_rate[EnvelopeState.EG_RELEASE.ordinal()] = effective_rate(op_release_rate(opOffs) * 4 + 2, ksrVal);
        }

        private static final int[] s_detune2_delta = {
                0, (600 * 64 + 50) / 100, (781 * 64 + 50) / 100, (950 * 64 + 50) / 100
        };

        /**
         * Computes the phase step.
         */
        @Override
        public int compute_phase_step(int chOffs, int opOffs, OpDataCache cache, int lfo_raw_pm) {
            // OPM logic is rather unique here, due to extra detune
            // and the use of key codes (not to be confused with keycode)

            // start with coarse detune delta; table uses cents value from
            // manual, converted into 1/64ths
            int delta = s_detune2_delta[op_detune2(opOffs)];

            // add in the PM delta
            int pm_sensitivity = ch_lfo_pm_sens(chOffs);
            if (pm_sensitivity != 0) {
                // raw PM value is -127..128 which is +/- 200 cents
                // manual gives these magnitudes in cents:
                //    0, +/-5, +/-10, +/-20, +/-50, +/-100, +/-400, +/-700
                // this roughly corresponds to shifting the 200-cent value:
                //    0  >> 5,  >> 4,  >> 3,  >> 2,  >> 1,   << 1,   << 2
                if (pm_sensitivity < 6)
                    delta += lfo_raw_pm >> (6 - pm_sensitivity);
                else
                    delta += lfo_raw_pm << (pm_sensitivity - 5);
            }

            // apply delta and convert to a frequency number
            int phase_step = opm_key_code_to_phase_step(cache.block_freq, delta);

            // apply detune based on the keycode
            phase_step += cache.detune;

            // apply frequency multiplier (which is cached as an x.1 value)
            return (phase_step * cache.multiple) >> 1;
        }

        /**
         * Logs a key-on event.
         */
        @Override
        public String log_keyOn(int chOffs, int opOffs) {
            int chNum = chOffs;
            int opNum = opOffs;

            StringBuilder buffer = new StringBuilder();

            buffer.append("%d.%02d freq=%04X dt2=%d dt=%d fb=%d alg=%X mul=%X tl=%02X ksr=%d adsr=%02X/%02X/%02X/%X sl=%X out=%c%c".formatted(
                    chNum, opNum,
                    ch_block_freq(chOffs),
                    op_detune2(opOffs),
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
                    op_sustain_level(opOffs),
                    ch_output_0(chOffs) != 0 ? 'L' : '-',
                    ch_output_1(chOffs) != 0 ? 'R' : '-'));

            boolean am = (lfo_am_depth() != 0 && ch_lfo_am_sens(chOffs) != 0 && op_lfo_am_enable(opOffs) != 0);
            if (am)
                buffer.append(" am=%d/%02X".formatted(ch_lfo_am_sens(chOffs), lfo_am_depth()));
            boolean pm = (lfo_pm_depth() != 0 && ch_lfo_pm_sens(chOffs) != 0);
            if (pm)
                buffer.append(" pm=%d/%02X".formatted(ch_lfo_pm_sens(chOffs), lfo_pm_depth()));
            if (am || pm)
                buffer.append(" lfo=%02X/%c", lfo_rate(), "WQTN".charAt(lfo_waveform()));
            if (noise_enable() != 0 && opOffs == 31)
                buffer.append(" noise=1");

            return buffer.toString();
        }

        // system-wide registers

        public final int test() {
            return byte_(0x01, 0, 8);
        }

        public final int lfo_reset() {
            return byte_(0x01, 1, 1);
        }

        public final int noise_frequency() {
            return byte_(0x0f, 0, 5) ^ 0x1f;
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

        public final int lfo_rate() {
            return byte_(0x18, 0, 8);
        }

        public final int lfo_am_depth() {
            return byte_(0x19, 0, 7);
        }

        public final int lfo_pm_depth() {
            return byte_(0x1a, 0, 7);
        }

        public final int output_bits() {
            return byte_(0x1b, 6, 2);
        }

        public final int lfo_waveform() {
            return byte_(0x1b, 0, 2);
        }

        // per-channel registers

        @Override
        public final int ch_output_any(int chOffs) {
            return byte_(0x20, 6, 2, chOffs);
        }

        @Override
        public final int ch_output_0(int chOffs) {
            return byte_(0x20, 6, 1, chOffs);
        }

        @Override
        public final int ch_output_1(int chOffs) {
            return byte_(0x20, 7, 1, chOffs);
        }

        @Override
        public final int ch_output_2(int chOffs) {
            return 0;
        }

        @Override
        public final int ch_output_3(int chOffs) {
            return 0;
        }

        @Override
        public final int ch_feedback(int chOffs) {
            return byte_(0x20, 3, 3, chOffs);
        }

        @Override
        public final int ch_algorithm(int chOffs) {
            return byte_(0x20, 0, 3, chOffs);
        }

        public final int ch_block_freq(int chOffs) {
            return word(0x28, 0, 7, 0x30, 2, 6, chOffs);
        }

        public final int ch_lfo_pm_sens(int chOffs) {
            return byte_(0x38, 4, 3, chOffs);
        }

        public final int ch_lfo_am_sens(int chOffs) {
            return byte_(0x38, 0, 2, chOffs);
        }

        // per-operator registers

        public final int op_detune(int opOffs) {
            return byte_(0x40, 4, 3, opOffs);
        }

        public final int op_multiple(int opOffs) {
            return byte_(0x40, 0, 4, opOffs);
        }

        public final int op_total_level(int opOffs) {
            return byte_(0x60, 0, 7, opOffs);
        }

        public final int op_ksr(int opOffs) {
            return byte_(0x80, 6, 2, opOffs);
        }

        public final int op_attack_rate(int opOffs) {
            return byte_(0x80, 0, 5, opOffs);
        }

        @Override
        public final int op_lfo_am_enable(int opOffs) {
            return byte_(0xa0, 7, 1, opOffs);
        }

        public final int op_decay_rate(int opOffs) {
            return byte_(0xa0, 0, 5, opOffs);
        }

        public final int op_detune2(int opOffs) {
            return byte_(0xc0, 6, 2, opOffs);
        }

        public final int op_sustain_rate(int opOffs) {
            return byte_(0xc0, 0, 5, opOffs);
        }

        public final int op_sustain_level(int opOffs) {
            return byte_(0xe0, 4, 4, opOffs);
        }

        public final int op_release_rate(int opOffs) {
            return byte_(0xe0, 0, 4, opOffs);
        }

        /** Returns a bitfield extracted from a byte. */
        protected final int byte_(int offset, int start, int count) {
            return byte_(offset, start, count, 0);
        }

        /** Returns a bitfield extracted from a byte. */
        protected final int byte_(int offset, int start, int count, int extra_offset /* = 0 */) {
            return bitfield(m_regdata[offset + extra_offset], start, count);
        }

        /** Returns a bitfield extracted from a pair of bytes, MSBs listed first. */
        protected final int word(int offset1, int start1, int count1, int offset2, int start2, int count2) {
            return word(offset1, start1, count1, offset2, start2, count2, 0);
        }

        /** Returns a bitfield extracted from a pair of bytes, MSBs listed first. */
        protected final int word(int offset1, int start1, int count1, int offset2, int start2, int count2, int extra_offset /* = 0 */) {
            return (byte_(offset1, start1, count1, extra_offset) << count2) | byte_(offset2, start2, count2, extra_offset);
        }

        // internal state

        /** LFO counter */
        @Element(sequence = 1)
        protected int m_lfo_counter;
        /** noise LFSR state */
        @Element(sequence = 3)
        protected int m_noise_lfsr;
        /** noise counter */
        @Element(sequence = 4)
        protected int m_noise_counter;
        /** latched noise state */
        @Element(sequence = 5)
        protected int m_noise_state;
        /** latched LFO noise value */
        @Element(sequence = 6)
        protected final int m_noise_lfo;
        /** current LFO AM value */
        @Element(sequence = 2)
        protected int m_lfo_am;
        /** register data */
        @Element(sequence = 7)
        protected final int[] m_regdata = new int[REGISTERS];
        /** LFO waveforms; AM in low 8, PM in upper 8 */
        protected final int[][] m_lfo_waveform = new int[4][LFO_WAVEFORM_LENGTH];
        /** waveforms */
        protected final int[][] m_waveform = new int[WAVEFORMS][WAVEFORM_LENGTH];
    }

    //
    // OPM IMPLEMENTATION CLASSES
    //

    /** opm variants */
    protected enum Variant {
        YM2151,
        YM2164
    }

    //
    // YM2151
    //

    /** ym2151 */
    public static class Ym2151 implements YmFm.Chip {

        protected static class FmEngine extends EngineBase<Opm.Registers> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, Opm.Registers.class);
            }
        }

        //using output_data = fm_engine.output_data;
        @Override
        public YmFm.Output outputFactory() {
            return m_fm.outputFactory();
        }

        private static final int OUTPUTS = Opm.Registers.OUTPUTS;

        @Override
        public final int getOutputs(){
            return OUTPUTS;
        }

        public Ym2151(YmFm.Interface intf) {
            this(intf, Opm.Variant.YM2151);
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
        public final int sample_rate(int input_clock) {
            return m_fm.sample_rate(input_clock);
        }

        void invalidate_caches() {
            m_fm.invalidate_caches();
        }

        /**
         * Reads the status register.
         */
        int read_status() {
            int result = m_fm.status();
            if (m_fm.intf().ymfm_is_busy())
                result |= Registers.STATUS_BUSY;
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
                    log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from YM2151 offset %d".formatted(offset & 3));
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
        void write_address(int data) {
            // just set the address
            m_address = data;
        }

        /**
         * Handles a write to the register interface.
         */
        void write_data(int data) {
            // write the FM register
            m_fm.write(m_address, data);

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
                m_fm.clock(Registers.ALL_CHANNELS);

                // update the FM content; OPM is full 14-bit with no intermediate clipping
                m_fm.output(output[samp].clear(), 0, 32767, Registers.ALL_CHANNELS);

                // YM2151 uses an external DAC (YM3012) with mantissa/exponent format
                // convert to 10.3 floating point value and back to simulate truncation
                output[samp].roundtrip_fp();
            }
        }

        /**
         * Constructor.
         */
        protected Ym2151(YmFm.Interface intf, Opm.Variant variant) {
            m_variant = variant;
            m_address = 0;
            m_fm = new FmEngine(intf);
        }

        // internal state

        /** chip variant */
        protected final Opm.Variant m_variant;
        /** address register */
        protected int m_address;
        /** core FM engine */
        protected final FmEngine m_fm;
    }

    //
    // OPP IMPLEMENTATION CLASSES
    //

    /**
     * ym2164
     *
     * the YM2164 is almost 100% functionally identical to the YM2151, except
     * it apparently has some mystery registers in the 00-07 range, and timer
     * B's frequency is half that of the 2151
     */
    public static class Ym2164 extends Ym2151 implements YmFm.Chip {

        /** Constructor. */
        public Ym2164(YmFm.Interface intf) {
            super(intf, Opm.Variant.YM2164);
        }
    }
}
