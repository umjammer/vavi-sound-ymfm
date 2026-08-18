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
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static vavi.sound.ymfm.YmFm.AccessClass.IO;
import static vavi.sound.ymfm.YmFm.Debug.log_unexpected_read_write;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_ATTACK;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_DECAY;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_RELEASE;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_REVERB;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_SUSTAIN;
import static vavi.sound.ymfm.YmFm.abs_sin_attenuation;
import static vavi.sound.ymfm.YmFm.bitfield;
import static vavi.sound.ymfm.YmFm.detune_adjustment;
import static vavi.sound.ymfm.YmFm.opm_key_code_to_phase_step;


//
// OPZ (aka YM2414)
//
// This chip is not officially documented as far as I know. What I have
// comes from this site:
//
//    http://sr4.sakura.ne.jp/fmsound/opz.html
//
// and from reading the TX81Z operator manual, which describes how a number
// of these new features work.
//
// OPZ appears be bsaically OPM with a bunch of extra features.
//
// For starters, there are two LFO generators. I have presumed that they
// operate identically since identical parameters are offered for each. I
// have also presumed the effects are additive between them. The LFOs on
// the OPZ have an extra "sync" option which apparently causes the LFO to
// reset whenever a key on is received.
//
// At the channel level, there is an additional 8-bit volume control. This
// might work as an addition to total level, or some other way. Completely
// unknown, and unimplemented.
//
// At the operator level, there are a number of extra features. First, there
// are 8 different waveforms to choose from. These are different than the
// waveforms introduced in the OPL2 and later chips.
//
// Second, there is an additional "reverb" stage added to the envelope
// generator, which kicks in when the envelope reaches -18dB. It specifies
// a slower decay rate to produce a sort of faux reverb effect.
//
// The envelope generator also supports a 2-bit shift value, which can be
// used to reduce the effect of the envelope attenuation.
//
// OPZ supports a "fixed frequency" mode for each operator, with a 3-bit
// range and 4-bit frequency value, plus a 1-bit enable. Not sure how that
// works at all, so it's not implemented.
//
// There are also several mystery fields in the operators which I have no
// clue about: "fine" (4 bits), "eg_shift" (2 bits), and "rev" (3 bits).
// eg_shift is some kind of envelope generator effect, but how it works is
// unknown.
//
// Also, according to the site above, the panning controls are changed from
// OPM, with a "mono" bit and only one control bit for the right channel.
// Current implementation is just a guess.
//
public abstract class Opz {

    private Opz() {}

    public static final int TEMPORARY_DEBUG_PRINTS = 0;

    //
    // REGISTER CLASSES
    //

    //
    // OPZ REGISTERS
    //

    //
    // OPZ register map:
    //
    //      System-wide registers:
    //           08 -----xxx Load preset (not sure how it gets saved)
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
    //           16 xxxxxxxx LFO #2 frequency
    //           17 0xxxxxxx AM LFO #2 depth
    //              1xxxxxxx PM LFO #2 depth
    //           18 xxxxxxxx LFO frequency
    //           19 0xxxxxxx AM LFO depth
    //              1xxxxxxx PM LFO depth
    //           1B xx------ CT (2 output data lines)
    //              --x----- LFO #2 sync
    //              ---x---- LFO sync
    //              ----xx-- LFO #2 waveform
    //              ------xx LFO waveform
    //
    //     Per-channel registers (channel in address bits 0-2)
    //        00-07 xxxxxxxx Channel volume
    //        20-27 x------- Pan right
    //              -x------ Key on (0)/off(1)
    //              --xxx--- Feedback level for operator 1 (0-7)
    //              -----xxx Operator connection algorithm (0-7)
    //        28-2F -xxxxxxx Key code
    //        30-37 xxxxxx-- Key fraction
    //              -------x Mono? mode
    //        38-3F 0xxx---- LFO PM sensitivity
    //              -----0xx LFO AM shift
    //              1xxx---- LFO #2 PM sensitivity
    //              -----1xx LFO #2 AM shift
    //
    //     Per-operator registers (channel in address bits 0-2, operator in bits 3-4)
    //        40-5F 0xxx---- Detune value (0-7)
    //              0---xxxx Multiple value (0-15)
    //              0xxx---- Fix range (0-15)
    //              0---xxxx Fix frequency (0-15)
    //              1xxx---- Oscillator waveform (0-7)
    //              1---xxxx Fine? (0-15)
    //        60-7F -xxxxxxx Total level (0-127)
    //        80-9F xx------ Key scale rate (0-3)
    //              --x----- Fix frequency mode
    //              ---xxxxx Attack rate (0-31)
    //        A0-BF x------- LFO AM enable
    //              ---xxxxx Decay rate (0-31)
    //        C0-DF xx0----- Detune 2 value (0-3)
    //              --0xxxxx Sustain rate (0-31)
    //              xx1----- Envelope generator shift? (0-3)
    //              --1--xxx Rev? (0-7)
    //        E0-FF xxxx---- Sustain level (0-15)
    //              ----xxxx Release rate (0-15)
    //
    //     Internal (fake) registers:
    //      100-11F -xxx---- Oscillator waveform (0-7)
    //              ----xxxx Fine? (0-15)
    //      120-13F xx------ Envelope generator shift (0-3)
    //              -----xxx Reverb rate (0-7)
    //      140-15F xxxx---- Preset sustain level (0-15)
    //              ----xxxx Preset release rate (0-15)
    //      160-17F xx------ Envelope generator shift (0-3)
    //              -----xxx Reverb rate (0-7)
    //      180-187 -xxx---- LFO #2 PM sensitivity
    //              ---- xxx LFO #2 AM shift
    //          188 -xxxxxxx LFO #2 PM depth
    //          189 -xxxxxxx LFO PM depth
    //

    /** opz_registers */
    @Serdes
    static class Registers extends RegistersBase {

        // LFO waveforms are 256 entries long
        static final int LFO_WAVEFORM_LENGTH = 256;

        // constants
        protected static final int OUTPUTS = 2;
        protected static final int CHANNELS = 8;
        protected static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
        protected static final int OPERATORS = CHANNELS * 4;
        protected static final int WAVEFORMS = 8;
        protected static final int REGISTERS = 0x190;
        protected static final int DEFAULT_PRESCALE = 2;
        protected static final int EG_CLOCK_DIVIDER = 3;
        protected static final boolean EG_HAS_REVERB = true;
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
            getParams().put("EG_HAS_REVERB", EG_HAS_REVERB);
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

//			m_lfo_counter = {0, 0};
            m_noise_lfsr = 1;
            m_noise_counter = 0;
            m_noise_state = 0;
            m_noise_lfo = 0;
//			m_lfo_am = {0, 0};

            // create the waveforms
            for (int index = 0; index < WAVEFORM_LENGTH; index++)
                m_waveform[0][index] = abs_sin_attenuation(index) | (bitfield(index, 9) << 15);

            // we only have the diagrams to judge from, but suspecting waveform 1 (and
            // derived waveforms) are sin^2, based on OPX description of similar wave-
            // forms; since our sin table is logarithmic, this ends up just being
            // 2*existing value
            int zeroval = m_waveform[0][0];
            for (int index = 0; index < WAVEFORM_LENGTH; index++)
                m_waveform[1][index] = Math.min(2 * (m_waveform[0][index] & 0x7fff), zeroval) | (bitfield(index, 9) << 15);

            // remaining waveforms are just derivations of the 2 main ones
            for (int index = 0; index < WAVEFORM_LENGTH; index++) {
                m_waveform[2][index] = bitfield(index, 9) != 0 ? zeroval : m_waveform[0][index];
                m_waveform[3][index] = bitfield(index, 9) != 0 ? zeroval : m_waveform[1][index];
                m_waveform[4][index] = bitfield(index, 9) != 0 ? zeroval : m_waveform[0][index * 2];
                m_waveform[5][index] = bitfield(index, 9) != 0 ? zeroval : m_waveform[1][index * 2];
                m_waveform[6][index] = bitfield(index, 9) != 0 ? zeroval : m_waveform[0][(index * 2) & 0x1ff];
                m_waveform[7][index] = bitfield(index, 9) != 0 ? zeroval : m_waveform[1][(index * 2) & 0x1ff];
            }

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
            Arrays.fill(m_phase_substep, 0, OPERATORS, 0);

            // enable output on both channels by default
            m_regdata[0x30] = m_regdata[0x31] = m_regdata[0x32] = m_regdata[0x33] = 0x01;
            m_regdata[0x34] = m_regdata[0x35] = m_regdata[0x36] = m_regdata[0x37] = 0x01;
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
            return chNum;
        }

        /** Maps operator number to register offset */
        @Override
        public int operator_offset(int opNum) {
            assert (opNum < OPERATORS);
            return opNum;
        }

        /**
         * return an array of operator indices for each channel
         * Note that the channel index order is 0,2,1,3, so we bitswap the index.
         * <pre>
         * This is because the order in the map is:
         *    carrier 1, carrier 2, modulator 1, modulator 2
         *
         * But when wiring up the connections, the more natural order is:
         *    carrier 1, modulator 1, carrier 2, modulator 2
         * </pre>
         */
        static final int[] s_fixed_map = {
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
         * Returns an array of operator indices for each channel;
         * for OPZ this is fixed
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

            // special mappings:
            //   0x16 -> 0x188 if bit 7 is set
            //   0x19 -> 0x189 if bit 7 is set
            //   0x38..0x3F -> 0x180..0x187 if bit 7 is set
            //   0x40..0x5F -> 0x100..0x11F if bit 7 is set
            //   0xC0..0xDF -> 0x120..0x13F if bit 5 is set
            if (index == 0x17 && bitfield(data, 7) != 0)
                m_regdata[0x188] = data;
            else if (index == 0x19 && bitfield(data, 7) != 0)
                m_regdata[0x189] = data;
            else if ((index & 0xf8) == 0x38 && bitfield(data, 7) != 0)
                m_regdata[0x180 + (index & 7)] = data;
            else if ((index & 0xe0) == 0x40 && bitfield(data, 7) != 0)
                m_regdata[0x100 + (index & 0x1f)] = data;
            else if ((index & 0xe0) == 0xc0 && bitfield(data, 5) != 0)
                m_regdata[0x120 + (index & 0x1f)] = data;
            else if (index < 0x100)
                m_regdata[index] = data;

            // preset writes restore some values from a preset memory; not sure
            // how this really works but the TX81Z will overwrite the sustain level/
            // release rate register and the envelope shift/reverb rate register to
            // dampen sound, then write the preset number to register 8 to restore them
            if (index == 0x08) {
                int chan = bitfield(data, 0, 3);
                if (TEMPORARY_DEBUG_PRINTS != 0)
                    System.out.printf("Loading preset %d%n", chan);
                m_regdata[0xe0 + chan + 0] = m_regdata[0x140 + chan + 0];
                m_regdata[0xe0 + chan + 8] = m_regdata[0x140 + chan + 8];
                m_regdata[0xe0 + chan + 16] = m_regdata[0x140 + chan + 16];
                m_regdata[0xe0 + chan + 24] = m_regdata[0x140 + chan + 24];
                m_regdata[0x120 + chan + 0] = m_regdata[0x160 + chan + 0];
                m_regdata[0x120 + chan + 8] = m_regdata[0x160 + chan + 8];
                m_regdata[0x120 + chan + 16] = m_regdata[0x160 + chan + 16];
                m_regdata[0x120 + chan + 24] = m_regdata[0x160 + chan + 24];
            }

            // store the presets under some unknown condition; the pattern of writes
            // when setting a new preset is:
            //
            //   08 (0-7), 80-9F, A0-BF, C0-DF, C0-DF (alt), 20-27, 40-5F, 40-5F (alt),
            //   C0-DF (alt -- again?), 38-3F, 1B, 18, E0-FF
            //
            // So it writes 0-7 to 08 to either reset all presets or to indicate
            // that we're going to be loading them. Immediately after all the writes
            // above, the very next write will be temporary values to blow away the
            // values loaded into E0-FF, so somehow it also knows that anything after
            // that point is not part of the preset.
            //
            // For now, try using the 40-5F (alt) writes as flags that presets are
            // being loaded until the E0-FF writes happen.
            boolean is_setting_preset = (bitfield(m_regdata[0x100 + (index & 0x1f)], 7) != 0);
            if (is_setting_preset) {
                if ((index & 0xe0) == 0xe0) {
                    m_regdata[0x140 + (index & 0x1f)] = data;
                    m_regdata[0x100 + (index & 0x1f)] &= 0x7f;
                } else if ((index & 0xe0) == 0xc0 && bitfield(data, 5) != 0)
                    m_regdata[0x160 + (index & 0x1f)] = data;
            }

            // handle writes to the key on index
            if ((index & 0xf8) == 0x20 && bitfield(index, 0, 3) == bitfield(m_regdata[0x08], 0, 3)) {
                channel[0] = bitfield(index, 0, 3);
                opMask[0] = ch_key_on(channel[0]) != 0 ? 0xf : 0;

                // according to the TX81Z manual, the sync option causes the LFOs
                // to reset at each note on
                if (opMask[0] != 0) {
                    if (lfo_sync() != 0)
                        m_lfo_counter[0] = 0;
                    if (lfo2_sync() != 0)
                        m_lfo_counter[1] = 0;
                }
                return true;
            }
            return false;
        }

        /**
         * Clocks the noise and LFO, handling clock division,
         * depth, and waveform computations
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
            int rate0 = lfo_rate();
            int rate1 = lfo2_rate();
            m_lfo_counter[0] += (0x10 | bitfield(rate0, 0, 4)) << bitfield(rate0, 4, 4);
            m_lfo_counter[1] += (0x10 | bitfield(rate1, 0, 4)) << bitfield(rate1, 4, 4);
            int lfo0 = bitfield(m_lfo_counter[0], 22, 8);
            int lfo1 = bitfield(m_lfo_counter[1], 22, 8);

            // fill in the noise entry 1 ahead of our current position; this
            // ensures the current value remains stable for a full LFO clock
            // and effectively latches the running value when the LFO advances
            int lfo_noise = bitfield(m_noise_lfsr, 17, 8);
            m_lfo_waveform[3][(lfo0 + 1) & 0xff] = lfo_noise | (lfo_noise << 8);
            m_lfo_waveform[3][(lfo1 + 1) & 0xff] = lfo_noise | (lfo_noise << 8);

            // fetch the AM/PM values based on the waveform; AM is unsigned and
            // encoded in the low 8 bits, while PM signed and encoded in the upper
            // 8 bits
            int ampm0 = m_lfo_waveform[lfo_waveform()][lfo0];
            int ampm1 = m_lfo_waveform[lfo2_waveform()][lfo1];

            // apply depth to the AM values and store for later
            m_lfo_am[0] = ((ampm0 & 0xff) * lfo_am_depth()) >> 7;
            m_lfo_am[1] = ((ampm1 & 0xff) * lfo2_am_depth()) >> 7;

            // apply depth to the PM values and return them combined into two
            int pm0 = ((ampm0 >> 8) * lfo_pm_depth()) >> 7;
            int pm1 = ((ampm1 >> 8) * lfo2_pm_depth()) >> 7;
            return (pm0 & 0xff) | (pm1 << 8);
        }

        /**
         * Returns the AM offset from LFO for the given channel.
         */
        @Override
        public final int lfo_am_offset(int chOffs) {
            // not sure how this works for real, but just adding the two
            // AM LFOs together
            int result = 0;

            // shift value for AM sensitivity is [*, 0, 1, 2],
            // mapping to values of [0, 23.9, 47.8, and 95.6dB]
            int am_sensitivity = ch_lfo_am_sens(chOffs);
            if (am_sensitivity != 0)
                result = m_lfo_am[0] << (am_sensitivity - 1);

            // QUESTION: see OPN note below for the dB range mapping; it applies
            // here as well

            // raw LFO AM value on OPZ is 0-FF, which is already a factor of 2
            // larger than the OPN below, putting our staring point at 2x theirs;
            // this works out since our minimum is 2x their maximum
            int am_sensitivity2 = ch_lfo2_am_sens(chOffs);
            if (am_sensitivity2 != 0)
                result += m_lfo_am[1] << (am_sensitivity2 - 1);

            return result;
        }

        /** Returns the current noise state, gated by the noise clock */
        @Override
        public final int noise_state() {
            return m_noise_state;
        }

        /**
         * Fills the operator cache with prefetched data.
         */
        @Override
        public void cache_operator_data(int chOffs, int opOffs, OpDataCache cache) {
            // TODO: how does fixed frequency mode work? appears to be enabled by
            // op_fix_mode(), and controlled by op_fix_range(), op_fix_frequency()

            // TODO: what is op_rev()?

            // set up the easy stuff
            cache.waveform = m_waveform[op_waveform(opOffs)];

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

            // multiple value, as an x.4 value (0 means 0.5)
            // the "fine" control provides the fractional bits
            cache.multiple = op_multiple(opOffs) << 4;
            if (cache.multiple == 0)
                cache.multiple = 0x08;
            cache.multiple |= op_fine(opOffs);

            // phase step, or PHASE_STEP_DYNAMIC if PM is active; this depends on
            // block_freq, detune, and multiple, so compute it after we've done those;
            // note that fix frequency mode is also treated as dynamic
            if (op_fix_mode(opOffs) == 0 && (lfo_pm_depth() == 0 || ch_lfo_pm_sens(chOffs) == 0) && (lfo2_pm_depth() == 0 || ch_lfo2_pm_sens(chOffs) == 0))
                cache.phase_step = compute_phase_step(chOffs, opOffs, cache, 0);
            else
                cache.phase_step = OpDataCache.PHASE_STEP_DYNAMIC;

            // total level, scaled by 8
            // TODO: how does ch_volume() fit into this?
            cache.total_level = op_total_level(opOffs) << 3;

            // 4-bit sustain level, but 15 means 31 so effectively 5 bits
            cache.eg_sustain = op_sustain_level(opOffs);
            cache.eg_sustain |= (cache.eg_sustain + 1) & 0x10;
            cache.eg_sustain <<= 5;

            // determine KSR adjustment for enevlope rates
            int ksrval = keycode >> (op_ksr(opOffs) ^ 3);
            cache.eg_rate[EG_ATTACK.ordinal()] = effective_rate(op_attack_rate(opOffs) * 2, ksrval);
            cache.eg_rate[EG_DECAY.ordinal()] = effective_rate(op_decay_rate(opOffs) * 2, ksrval);
            cache.eg_rate[EG_SUSTAIN.ordinal()] = effective_rate(op_sustain_rate(opOffs) * 2, ksrval);
            cache.eg_rate[EG_RELEASE.ordinal()] = effective_rate(op_release_rate(opOffs) * 4 + 2, ksrval);
            cache.eg_rate[EG_REVERB.ordinal()] = cache.eg_rate[EG_RELEASE.ordinal()];
            int reverb = op_reverb_rate(opOffs);
            if (reverb != 0)
                cache.eg_rate[EG_REVERB.ordinal()] = Math.min(effective_rate(reverb * 4 + 2, ksrval), cache.eg_rate[EG_REVERB.ordinal()]);

            // set the envelope shift; TX81Z manual says operator 1 shift is fixed at "off"
            cache.eg_shift = ((opOffs & 0x18) == 0) ? 0 : op_eg_shift(opOffs);
        }

        static final int[] s_detune2_delta = {
                0, (600 * 64 + 50) / 100, (781 * 64 + 50) / 100, (950 * 64 + 50) / 100
        };

        /**
         * Compute the phase step.
         */
        @Override
        public int compute_phase_step(int chOffs, int opOffs, OpDataCache cache, int lfo_raw_pm) {
            // OPZ has a fixed frequency mode; it is unclear whether the
            // detune and multiple parameters affect things

            int phase_step;
            if (op_fix_mode(opOffs) != 0) {
                // the baseline frequency in hz comes from the fix frequency and fine
                // registers, which can specify values 8-255Hz in 1Hz increments; that
                // value is then shifted up by the 3-bit range
                int freq = op_fix_frequency(opOffs) << 4;
                if (freq == 0)
                    freq = 8;
                freq |= op_fine(opOffs);
                freq <<= op_fix_range(opOffs);

                // there is not enough resolution in the plain phase step to track the
                // full range of frequencies, so we keep a per-operator sub step with an
                // additional 12 bits of resolution; this calculation gives us, for
                // example, a frequency of 8.0009Hz when 8Hz is requested
                int substep = m_phase_substep[opOffs];
                substep += 75 * freq;
                phase_step = substep >> 12;
                m_phase_substep[opOffs] = substep & 0xfff;

                // detune/multiple occupy the same space as fix_range/fix_frequency so
                // don't apply them in addition
                return phase_step;
            } else {
                // start with coarse detune delta; table uses cents value from
                // manual, converted into 1/64ths
                int delta = s_detune2_delta[op_detune2(opOffs)];

                // add in the PM deltas
                int pm_sensitivity = ch_lfo_pm_sens(chOffs);
                if (pm_sensitivity != 0) {
                    // raw PM value is -127..128 which is +/- 200 cents
                    // manual gives these magnitudes in cents:
                    //    0, +/-5, +/-10, +/-20, +/-50, +/-100, +/-400, +/-700
                    // this roughly corresponds to shifting the 200-cent value:
                    //    0  >> 5,  >> 4,  >> 3,  >> 2,  >> 1,   << 1,   << 2
                    if (pm_sensitivity < 6)
                        delta += lfo_raw_pm >>> (6 - pm_sensitivity);
                    else
                        delta += lfo_raw_pm << (pm_sensitivity - 5);
                }
                int pm_sensitivity2 = ch_lfo2_pm_sens(chOffs);
                if (pm_sensitivity2 != 0) {
                    // raw PM value is -127..128 which is +/- 200 cents
                    // manual gives these magnitudes in cents:
                    //    0, +/-5, +/-10, +/-20, +/-50, +/-100, +/-400, +/-700
                    // this roughly corresponds to shifting the 200-cent value:
                    //    0  >> 5,  >> 4,  >> 3,  >> 2,  >> 1,   << 1,   << 2
                    if (pm_sensitivity2 < 6)
                        delta += lfo_raw_pm >>> 8 >>> (6 - pm_sensitivity2);
                    else
                        delta += lfo_raw_pm >>> 8 << (pm_sensitivity2 - 5);
                }

                // apply delta and convert to a frequency number; this translation is
                // the same as OPM so just re-use that helper
                phase_step = opm_key_code_to_phase_step(cache.block_freq, delta);

                // apply detune based on the keycode
                phase_step += cache.detune;

                // apply frequency multiplier (which is cached as an x.4 value)
                return (phase_step * cache.multiple) >> 4;
            }
        }

        /**
         * Logs a key-on event.
         */
        @Override
        public String log_keyOn(int chOffs, int opOffs) {
            int chnum = chOffs;
            int opnum = opOffs;

            StringBuilder buffer = new StringBuilder();

            buffer.append("%d.%02d".formatted(chnum, opnum));

            if (op_fix_mode(opOffs) != 0)
                buffer.append(" fixfreq=%X fine=%X shift=%X".formatted(op_fix_frequency(opOffs), op_fine(opOffs), op_fix_range(opOffs)));
            else
                buffer.append(" freq=%04X dt2=%d fine=%X".formatted(ch_block_freq(chOffs), op_detune2(opOffs), op_fine(opOffs)));

            buffer.append(" dt=%d fb=%d alg=%X mul=%X tl=%02X ksr=%d adsr=%02X/%02X/%02X/%X sl=%X out=%c%c".formatted(
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

            if (op_eg_shift(opOffs) != 0)
                buffer.append(" egshift=%d".formatted(op_eg_shift(opOffs)));

            boolean am = (lfo_am_depth() != 0 && ch_lfo_am_sens(chOffs) != 0 && op_lfo_am_enable(opOffs) != 0);
            if (am)
                buffer.append(" am=%d/%02X".formatted(ch_lfo_am_sens(chOffs), lfo_am_depth()));
            boolean pm = (lfo_pm_depth() != 0 && ch_lfo_pm_sens(chOffs) != 0);
            if (pm)
                buffer.append(" pm=%d/%02X".formatted(ch_lfo_pm_sens(chOffs), lfo_pm_depth()));
            if (am || pm)
                buffer.append(" lfo=%02X/%c".formatted(lfo_rate(), "WQTN".charAt(lfo_waveform())));

            boolean am2 = (lfo2_am_depth() != 0 && ch_lfo2_am_sens(chOffs) != 0 && op_lfo_am_enable(opOffs) != 0);
            if (am2)
                buffer.append(" am2=%u/%02X", ch_lfo2_am_sens(chOffs), lfo2_am_depth());
            boolean pm2 = (lfo2_pm_depth() != 0 && ch_lfo2_pm_sens(chOffs) != 0);
            if (pm2)
                buffer.append(" pm2=%u/%02X", ch_lfo2_pm_sens(chOffs), lfo2_pm_depth());
            if (am2 || pm2)
                buffer.append(" lfo2=%02X/%c", lfo2_rate(), "WQTN".charAt(lfo2_waveform()));

            if (op_reverb_rate(opOffs) != 0)
                buffer.append(" rev=%d".formatted(op_reverb_rate(opOffs)));
            if (op_waveform(opOffs) != 0)
                buffer.append(" wf=%d".formatted(op_waveform(opOffs)));
            if (noise_enable() != 0 && opOffs == 31)
                buffer.append(" noise=1");

            return buffer.toString();
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

        public final int lfo2_pm_depth() {
            return byte_(0x188, 0, 7);
        } // fake

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

        public final int lfo_pm_depth() {
            return byte_(0x189, 0, 7);
        } // fake

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

        public final int ch_lfo2_pm_sens(int choffs) {
            return byte_(0x180, 4, 3, choffs);
        } // fake

        public final int ch_lfo2_am_sens(int choffs) {
            return byte_(0x180, 0, 2, choffs);
        } // fake

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

        public final int op_fix_frequency(int opoffs) {
            return byte_(0x40, 0, 4, opoffs);
        }

        public final int op_waveform(int opoffs) {
            return byte_(0x100, 4, 3, opoffs);
        } // fake

        public final int op_fine(int opoffs) {
            return byte_(0x100, 0, 4, opoffs);
        } // fake

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

        public final int op_eg_shift(int opoffs) {
            return byte_(0x120, 6, 2, opoffs);
        } // fake

        public final int op_reverb_rate(int opoffs) {
            return byte_(0x120, 0, 3, opoffs);
        } // fake

        public final int op_sustain_level(int opoffs) {
            return byte_(0xe0, 4, 4, opoffs);
        }

        public final int op_release_rate(int opoffs) {
            return byte_(0xe0, 0, 4, opoffs);
        }

        /** Returns a bitfield extracted from a byte */
        protected final int byte_(int offset, int start, int count) {
            return byte_(offset, start, count, 0);
        }

        /** Returns a bitfield extracted from a byte */
        protected final int byte_(int offset, int start, int count, int extra_offset/* = 0 */) {
            return bitfield(m_regdata[offset + extra_offset], start, count);
        }

        /** Return a bitfield extracted from a pair of bytes, MSBs listed first */
        protected final int word(int offset1, int start1, int count1, int offset2, int start2, int count2) {
            return word(offset1, start1, count1, offset2, start2, count2, 0);
        }

        /** Return a bitfield extracted from a pair of bytes, MSBs listed first */
        protected final int word(int offset1, int start1, int count1, int offset2, int start2, int count2, int extra_offset/* = 0*/) {
            return (byte_(offset1, start1, count1, extra_offset) << count2) | byte_(offset2, start2, count2, extra_offset);
        }

        // internal state

        /** LFO counter */
        @Element(sequence = 1)
        protected final int[] m_lfo_counter = new int[2];
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
        protected int m_noise_lfo;
        /** current LFO AM value */
        @Element(sequence = 2)
        protected final int[] m_lfo_am = new int[2];
        /** register data */
        @Element(sequence = 7)
        protected final int[] m_regdata = new int[REGISTERS];
        /** phase substep for fixed frequency */
        @Element(sequence = 8)
        protected final int[] m_phase_substep = new int[OPERATORS];
        /** LFO waveforms; AM in low 8, PM in upper 8 */
        protected final int[][] m_lfo_waveform = new int[4][LFO_WAVEFORM_LENGTH];
        /** waveforms */
        protected final int[][] m_waveform = new int[WAVEFORMS][WAVEFORM_LENGTH];
    }

    //
    // IMPLEMENTATION CLASSES
    //

    //
    // YM2414
    //

    /** ym2414 */
    @Serdes
    public static class Ym2414 implements YmFm.Chip {

        protected static class FmEngine extends EngineBase<Opz.Registers> {

            public FmEngine(YmFm.Interface intf) {
                super(intf, Opz.Registers.class);
            }
        }

        //using output_data = fm_engine.output_data;
        @Override
        public YmFm.Output outputFactory() {
            return m_fm.outputFactory();
        }

        private final int OUTPUTS;

        @Override
        public final int getOutputs(){
            return OUTPUTS;
        }

        /**
         * Constructor.
         */
        public Ym2414(YmFm.Interface intf) {
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
                result |= Registers.STATUS_BUSY;
            return result;
        }

        /**
         * Handle a read from the device.
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
         * Generate one sample of sound.
         */
        @Override
        public void generate(YmFm.Output[] output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++) {
                // clock the system
                m_fm.clock(Registers.ALL_CHANNELS);

                // update the FM content; YM2414 is full 14-bit with no intermediate clipping
                m_fm.output(output[samp].clear(), 0, 32767, Registers.ALL_CHANNELS);

                // unsure about YM2414 outputs; assume it is like YM2151
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
}
