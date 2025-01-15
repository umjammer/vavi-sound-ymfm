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

import vavi.sound.ymfm.YmFm.Debug;
import vavi.sound.ymfm.YmFm.EnvelopeState;
import vavi.sound.ymfm.YmFm.Interface;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static vavi.sound.ymfm.YmFm.AccessClass.PCM;
import static vavi.sound.ymfm.YmFm.Debug.log_keyon;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_ATTACK;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_DECAY;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_RELEASE;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_REVERB;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_STATES;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_SUSTAIN;
import static vavi.sound.ymfm.YmFm.attenuation_increment;
import static vavi.sound.ymfm.YmFm.attenuation_to_volume;
import static vavi.sound.ymfm.YmFm.bitfield;
import static vavi.sound.ymfm.YmFm.clamp;


/*
Note to self: Sega "Multi-PCM" is almost identical to this

28 channels

Writes:
00 = data reg, causes write
01 = target slot = data - (data / 8)
02 = address (clamped to 7)

Slot data (registers with ADSR/KSR seem to be inaccessible):
0: xxxx---- panpot
1: xxxxxxxx wavetable low
2: xxxxxx-- pitch low
   -------x wavetable high
3: xxxx---- octave
   ----xxxx pitch hi
4: x------- key on
5: xxxxxxx- total level
   -------x level direct (0=interpolate)
6: --xxx--- LFO frequency
   -----xxx PM sensitivity
7: -----xxx AM sensitivity

Sample data:
+00: start hi
+01: start mid
+02: start low
+03: loop hi
+04: loop low
+05: -end hi
+06: -end low
+07: vibrato (reg 6)
+08: attack/decay
+09: sustain level/rate
+0A: ksr/release
+0B: LFO amplitude (reg 7)

*/
abstract class Pcm {

    private Pcm() {
    }

    //
    // INTERFACE CLASSES
    //

    /**
     * pcm_cache
     * <p>
     * this class holds data that is computed once at the start of clocking
     * and remains static during subsequent sound generation
     */
    public static class Cache {

        /** sample position step, as a .16 value */
        int step;
        /** target total level, as a .10 value */
        int total_level;
        /** left panning attenuation */
        int pan_left;
        /** right panning attenuation */
        int pan_right;
        /** sustain level, shifted up to envelope values */
        int eg_sustain;
        /** envelope rate, including KSR */
        final int[] eg_rate = new int[EG_STATES.ordinal()];
        /** stepping value for LFO */
        int lfo_step;
        /** scale value for AM LFO */
        int am_depth;
        /** scale value for PM LFO */
        int pm_depth;
    }

    //
    // PCM REGISTERS
    //

    //
    // PCM register map:
    //
    //      System-wide registers:
    //        00-01 xxxxxxxx LSI Test
    //           02 -------x Memory access mode (0=sound gen, 1=read/write)
    //              ------x- Memory type (0=ROM, 1=ROM+SRAM)
    //              ---xxx-- Wave table header
    //              xxx----- Device ID (=1 for YMF278B)
    //           03 --xxxxxx Memory address high
    //           04 xxxxxxxx Memory address mid
    //           05 xxxxxxxx Memory address low
    //           06 xxxxxxxx Memory data
    //           F8 --xxx--- Mix control (FM_R)
    //              -----xxx Mix control (FM_L)
    //           F9 --xxx--- Mix control (PCM_R)
    //              -----xxx Mix control (PCM_L)
    //
    //      Channel-specific registers:
    //        08-1F xxxxxxxx Wave table number low
    //        20-37 -------x Wave table number high
    //              xxxxxxx- F-number low
    //        38-4F -----xxx F-number high
    //              ----x--- Pseudo-reverb
    //              xxxx---- Octave
    //        50-67 xxxxxxx- Total level
    //              -------x Level direct
    //        68-7F x------- Key on
    //              -x------ Damp
    //              --x----- LFO reset
    //              ---x---- Output channel
    //              ----xxxx Panpot
    //        80-97 --xxx--- LFO speed
    //              -----xxx Vibrato
    //        98-AF xxxx---- Attack rate
    //              ----xxxx Decay rate
    //        B0-C7 xxxx---- Sustain level
    //              ----xxxx Sustain rate
    //        C8-DF xxxx---- Rate correction
    //              ----xxxx Release rate
    //        E0-F7 -----xxx AM depth

    /** pcm_registers */
    @Serdes
    public static class Registers {

        // constants
        protected static final int OUTPUTS = 4;
        protected static final int CHANNELS = 24;
        protected static final int REGISTERS = 0x100;
        protected static final int ALL_CHANNELS = (1 << CHANNELS) - 1;

        /** Constructor */
        public Registers() {
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
         * Resets the register state.
         */
        public void reset() {
            Arrays.fill(m_regdata, 0, REGISTERS, (byte) 0);
            m_regdata[0xf8] = 0x1b;
        }

        /**
         * determine the LFO stepping value; this how much to add to a running
         * x.18 value for the LFO; steps were derived from frequencies in the
         * manual and come out very close with these values
         */
        private static final int[] s_lfo_steps = {1, 12, 19, 25, 31, 35, 37, 42};

        /**
         * AM LFO depth values, derived from the manual; note each has at most
         * 2 bits to make the "multiply" easy in hardware
         */
        private static final int[] s_am_depth = {0, 0x14, 0x20, 0x28, 0x30, 0x40, 0x50, 0x80};

        /**
         * PM LFO depth values; these are converted from the manual's cents values
         * into f-numbers; the computations come out quite cleanly so pretty sure
         * these are correct
         */
        private static final int[] s_pm_depth = {0, 2, 3, 4, 6, 12, 24, 48};

        /**
         * Updates the cache with data from the registers.
         */
        public void cache_channel_data(int choffs, Cache cache) {
            // compute step from octave and fnumber; the math here implies
            // a .18 fraction but .16 should be perfectly fine
            int octave = (ch_octave(choffs) << 4) >> 4;
            int fnum = ch_fnumber(choffs);
            cache.step = ((0x400 | fnum) << (octave + 7)) >> 2;

            // total level is computed as a .10 value for interpolation
            cache.total_level = ch_total_level(choffs) << 10;

            // compute panning values in terms of envelope attenuation
            int panpot = (ch_panpot(choffs) << 4) >> 4;
            if (panpot >= 0) {
                cache.pan_left = (panpot == 7) ? 0x3ff : 0x20 * panpot;
                cache.pan_right = 0;
            } else if (panpot >= -7) {
                cache.pan_left = 0;
                cache.pan_right = (panpot == -7) ? 0x3ff : -0x20 * panpot;
            } else
                cache.pan_left = cache.pan_right = 0x3ff;

            cache.lfo_step = s_lfo_steps[ch_lfo_speed(choffs)];

            cache.am_depth = s_am_depth[ch_am_depth(choffs)];

            cache.pm_depth = s_pm_depth[ch_vibrato(choffs)];

            // 4-bit sustain level, but 15 means 31 so effectively 5 bits
            cache.eg_sustain = ch_sustain_level(choffs);
            cache.eg_sustain |= (cache.eg_sustain + 1) & 0x10;
            cache.eg_sustain <<= 5;

            // compute the key scaling correction factor; 15 means don't do any correction
            int correction = ch_rate_correction(choffs);
            if (correction == 15)
                correction = 0;
            else
                correction = (octave + correction) * 2 + bitfield(fnum, 9);

            // compute the envelope generator rates
            cache.eg_rate[EG_ATTACK.ordinal()] = effective_rate(ch_attack_rate(choffs), correction);
            cache.eg_rate[EG_DECAY.ordinal()] = effective_rate(ch_decay_rate(choffs), correction);
            cache.eg_rate[EG_SUSTAIN.ordinal()] = effective_rate(ch_sustain_rate(choffs), correction);
            cache.eg_rate[EG_RELEASE.ordinal()] = effective_rate(ch_release_rate(choffs), correction);
            cache.eg_rate[EG_REVERB.ordinal()] = 5;

            // if damping is on, override some things; essentially decay at a hardcoded
            // rate of 48 until -12db (0x80), then at maximum rate for the rest
            if (ch_damp(choffs) != 0) {
                cache.eg_rate[EG_DECAY.ordinal()] = 48;
                cache.eg_rate[EG_SUSTAIN.ordinal()] = 63;
                cache.eg_rate[EG_RELEASE.ordinal()] = 63;
                cache.eg_sustain = 0x80;
            }
        }

        // direct read/write access

        public int read(int index) {
            return m_regdata[index];
        }

        public void write(int index, int data) {
            m_regdata[index] = data;
        }

        // system-wide registers

        public final int memory_access_mode() {
            return bitfield(m_regdata[0x02], 0);
        }

        public final int memory_type() {
            return bitfield(m_regdata[0x02], 1);
        }

        public final int wave_table_header() {
            return bitfield(m_regdata[0x02], 2, 3);
        }

        public final int device_id() {
            return bitfield(m_regdata[0x02], 5, 3);
        }

        public final int memory_address() {
            return (bitfield(m_regdata[0x03], 0, 6) << 16) | (m_regdata[0x04] << 8) | m_regdata[0x05];
        }

        public final int memory_data() {
            return m_regdata[0x06];
        }

        public final int mix_fm_r() {
            return bitfield(m_regdata[0xf8], 3, 3);
        }

        public final int mix_fm_l() {
            return bitfield(m_regdata[0xf8], 0, 3);
        }

        public final int mix_pcm_r() {
            return bitfield(m_regdata[0xf9], 3, 3);
        }

        public final int mix_pcm_l() {
            return bitfield(m_regdata[0xf9], 0, 3);
        }

        // per-channel registers

        public final int ch_wave_table_num(int choffs) {
            return m_regdata[choffs + 0x08] | (bitfield(m_regdata[choffs + 0x20], 0) << 8);
        }

        public final int ch_fnumber(int choffs) {
            return bitfield(m_regdata[choffs + 0x20], 1, 7) | (bitfield(m_regdata[choffs + 0x38], 0, 3) << 7);
        }

        public final int ch_pseudo_reverb(int choffs) {
            return bitfield(m_regdata[choffs + 0x38], 3);
        }

        public final int ch_octave(int choffs) {
            return bitfield(m_regdata[choffs + 0x38], 4, 4);
        }

        public final int ch_total_level(int choffs) {
            return bitfield(m_regdata[choffs + 0x50], 1, 7);
        }

        public final int ch_level_direct(int choffs) {
            return bitfield(m_regdata[choffs + 0x50], 0);
        }

        public final int ch_keyon(int choffs) {
            return bitfield(m_regdata[choffs + 0x68], 7);
        }

        public final int ch_damp(int choffs) {
            return bitfield(m_regdata[choffs + 0x68], 6);
        }

        public final int ch_lfo_reset(int choffs) {
            return bitfield(m_regdata[choffs + 0x68], 5);
        }

        public final int ch_output_channel(int choffs) {
            return bitfield(m_regdata[choffs + 0x68], 4);
        }

        public final int ch_panpot(int choffs) {
            return bitfield(m_regdata[choffs + 0x68], 0, 4);
        }

        public final int ch_lfo_speed(int choffs) {
            return bitfield(m_regdata[choffs + 0x80], 3, 3);
        }

        public final int ch_vibrato(int choffs) {
            return bitfield(m_regdata[choffs + 0x80], 0, 3);
        }

        public final int ch_attack_rate(int choffs) {
            return bitfield(m_regdata[choffs + 0x98], 4, 4);
        }

        public final int ch_decay_rate(int choffs) {
            return bitfield(m_regdata[choffs + 0x98], 0, 4);
        }

        public final int ch_sustain_level(int choffs) {
            return bitfield(m_regdata[choffs + 0xb0], 4, 4);
        }

        public final int ch_sustain_rate(int choffs) {
            return bitfield(m_regdata[choffs + 0xb0], 0, 4);
        }

        public final int ch_rate_correction(int choffs) {
            return bitfield(m_regdata[choffs + 0xc8], 4, 4);
        }

        public final int ch_release_rate(int choffs) {
            return bitfield(m_regdata[choffs + 0xc8], 0, 4);
        }

        public final int ch_am_depth(int choffs) {
            return bitfield(m_regdata[choffs + 0xe0], 0, 3);
        }

        /** Returns the memory address and increment it */
        public int memory_address_autoinc() {
            int result = memory_address();
            int newval = result + 1;
            m_regdata[0x05] = newval >> 0;
            m_regdata[0x04] = newval >> 8;
            m_regdata[0x03] = (newval >> 16) & 0x3f;
            return result;
        }

        /**
         * Returns the effective rate, clamping and applying corrections as needed.
         */
        private static int effective_rate(int raw, int correction) {
            // raw rates of 0 and 15 just pin to min/max
            if (raw == 0)
                return 0;
            if (raw == 15)
                return 63;

            // otherwise add the correction and clamp to range
            return clamp(raw * 4 + correction, 0, 63);
        }

        // internal state

        /** register data */
        @Element
        private final int[] m_regdata = new int[REGISTERS];
    }

    //
    // PCM CHANNEL
    //

    /** pcm_channel */
    static class Channel {

        static final int KEY_ON = 0x01;
        static final int KEY_PENDING_ON = 0x02;
        static final int KEY_PENDING = 0x04;

        // "quiet" value, used to optimize when we can skip doing working
        static final int EG_QUIET = 0x200;

        //using output_data = Output<pcm_registers.OUTPUTS>;

        /**
         * Constructor.
         */
        public Channel(Engine owner, int choffs) {
            m_choffs = choffs;
            m_baseaddr = 0;
            m_endpos = 0;
            m_looppos = 0;
            m_curpos = 0;
            m_nextpos = 0;
            m_lfo_counter = 0;
            m_eg_state = EG_RELEASE;
            m_env_attenuation = 0x3ff;
            m_total_level = 0x7f << 10;
            m_format = 0;
            m_key_state = 0;
            m_regs = owner.regs();
            m_owner = owner;
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
         * Resets the channel state.
         */
        public void reset() {
            m_baseaddr = 0;
            m_endpos = 0;
            m_looppos = 0;
            m_curpos = 0;
            m_nextpos = 0;
            m_lfo_counter = 0;
            m_eg_state = EG_RELEASE;
            m_env_attenuation = 0x3ff;
            m_total_level = 0x7f << 10;
            m_format = 0;
            m_key_state = 0;
        }

        /** Returns the channel offset */
        public final int choffs() {
            return m_choffs;
        }

        /**
         * Prepares for clocking.
         */
        public boolean prepare() {
            // cache the data
            m_regs.cache_channel_data(m_choffs, m_cache);

            // clock the key state
            if ((m_key_state & KEY_PENDING) != 0) {
                int oldstate = m_key_state;
                m_key_state = (m_key_state >> 1) & KEY_ON;
                if (((oldstate ^ m_key_state) & KEY_ON) != 0) {
                    if ((m_key_state & KEY_ON) != 0)
                        start_attack();
                    else
                        start_release();
                }
            }

            // set the total level directly if not interpolating
            if (m_regs.ch_level_direct(m_choffs) != 0)
                m_total_level = m_cache.total_level;

            // we're active until we're quiet after the release
            return m_eg_state.ordinal() < EG_RELEASE.ordinal() || m_env_attenuation < EG_QUIET;
        }

        /**
         * Master clocking function.
         */
        public void clock(int env_counter) {
            // clock the LFO, which is an x.18 value incremented based on the
            // LFO speed value
            m_lfo_counter += m_cache.lfo_step;

            // clock the envelope
            clock_envelope(env_counter);

            // determine the step after applying vibrato
            int step = m_cache.step;
            if (m_cache.pm_depth != 0) {
                // shift the LFO by 1/4 cycle for PM so that it starts at 0
                int lfo_shifted = m_lfo_counter + (1 << 16);
                int lfo_value = bitfield(lfo_shifted, 10, 7);
                if (bitfield(lfo_shifted, 17) != 0)
                    lfo_value ^= 0x7f;
                lfo_value -= 0x40;
                step += (lfo_value * m_cache.pm_depth) >> 7;
            }

            // advance the sample step and loop as needed
            m_curpos = m_nextpos;
            m_nextpos = m_curpos + step;
            if (m_nextpos >= m_endpos)
                m_nextpos += m_looppos - m_endpos;

            // interpolate total level if needed
            if (m_total_level != m_cache.total_level) {
                // max->min volume takes 156.4ms, or pretty close to 19/1024 per 44.1kHz sample
                // min->max volume is half that, so advance by 38/1024 per sample
                if (m_total_level < m_cache.total_level)
                    m_total_level = Math.min(m_total_level + 19, m_cache.total_level);
                else
                    m_total_level = Math.max(m_total_level - 38, m_cache.total_level);
            }
        }

        /**
         * Returns the computed output value, with panning applied.
         */
        public final void output(YmFm.Output output) {
            // early out if the envelope is effectively off
            int envelope = m_env_attenuation;
            if (envelope > EG_QUIET)
                return;

            // add in LFO AM modulation
            if (m_cache.am_depth != 0) {
                int lfo_value = bitfield(m_lfo_counter, 10, 7);
                if (bitfield(m_lfo_counter, 17) != 0)
                    lfo_value ^= 0x7f;
                envelope += (lfo_value * m_cache.am_depth) >> 7;
            }

            // add in the current interpolated total level value, which is a .10
            // value shifted left by 2
            envelope += m_total_level >> 8;

            // add in panning effect and clamp
            int lenv = Math.min(envelope + m_cache.pan_left, 0x3ff);
            int renv = Math.min(envelope + m_cache.pan_right, 0x3ff);

            // convert to volume as a .11 fraction
            int lvol = attenuation_to_volume(lenv << 2);
            int rvol = attenuation_to_volume(renv << 2);

            // fetch current sample and add
            int sample = fetch_sample();
            int outnum = m_regs.ch_output_channel(m_choffs) * 2;
            output.data[outnum + 0] += (lvol * sample) >> 15;
            output.data[outnum + 1] += (rvol * sample) >> 15;
        }

        /**
         * Signals key on/off.
         */
        public void keyOnOff(boolean on) {
            // mark the key state as pending
            m_key_state |= KEY_PENDING | (on ? KEY_PENDING_ON : 0);

            // don't log masked channels
            if ((m_key_state & (KEY_PENDING_ON | KEY_ON)) == KEY_PENDING_ON && ((Debug.GLOBAL_PCM_CHANNEL_MASK >> m_choffs) & 1) != 0) {
                log_keyon.log(Level.DEBUG, "KeyOn PCM-%02d: num=%3d oct=%2d fnum=%03X level=%02X%c ADSR=%X/%X/%X/%X SL=%X",
                        m_choffs,
                        m_regs.ch_wave_table_num(m_choffs),
                        (byte) (m_regs.ch_octave(m_choffs) << 4) >> 4,
                        m_regs.ch_fnumber(m_choffs),
                        m_regs.ch_total_level(m_choffs),
                        m_regs.ch_level_direct(m_choffs) != 0 ? '!' : '/',
                        m_regs.ch_attack_rate(m_choffs),
                        m_regs.ch_decay_rate(m_choffs),
                        m_regs.ch_sustain_rate(m_choffs),
                        m_regs.ch_release_rate(m_choffs),
                        m_regs.ch_sustain_level(m_choffs));

                if (m_regs.ch_rate_correction(m_choffs) != 15)
                    log_keyon.log(Level.DEBUG, " RC=%X", m_regs.ch_rate_correction(m_choffs));

                if (m_regs.ch_pseudo_reverb(m_choffs) != 0)
                    log_keyon.log(Level.DEBUG, " %s", "REV");
                if (m_regs.ch_damp(m_choffs) != 0)
                    log_keyon.log(Level.DEBUG, " %s", "DAMP");

                if (m_regs.ch_vibrato(m_choffs) != 0 || m_regs.ch_am_depth(m_choffs) != 0) {
                    if (m_regs.ch_vibrato(m_choffs) != 0)
                        log_keyon.log(Level.DEBUG, " VIB=%d", m_regs.ch_vibrato(m_choffs));
                    if (m_regs.ch_am_depth(m_choffs) != 0)
                        log_keyon.log(Level.DEBUG, " AM=%d", m_regs.ch_am_depth(m_choffs));
                    log_keyon.log(Level.DEBUG, " LFO=%d", m_regs.ch_lfo_speed(m_choffs));
                }
                log_keyon.log(Level.DEBUG, "%s", "\n");
            }
        }

        /**
         * Loads a waveTable by fetching its data from external memory.
         */
        public void load_wavetable() {
            // determine the address of the wave table header
            int wavnum = m_regs.ch_wave_table_num(m_choffs);
            int wavheader = 12 * wavnum;

            // above 384 it may be in a different bank
            if (wavnum >= 384) {
                int bank = m_regs.wave_table_header();
                if (bank != 0)
                    wavheader = 512 * 1024 * bank + (wavnum - 384) * 12;
            }

            // fetch the 22-bit base address and 2-bit format
            int byte_ = read_pcm(wavheader + 0);
            m_format = bitfield(byte_, 6, 2);
            m_baseaddr = bitfield(byte_, 0, 6) << 16;
            m_baseaddr |= read_pcm(wavheader + 1) << 8;
            m_baseaddr |= read_pcm(wavheader + 2) << 0;

            // fetch the 16-bit loop position
            m_looppos = read_pcm(wavheader + 3) << 8;
            m_looppos |= read_pcm(wavheader + 4);
            m_looppos <<= 16;

            // fetch the 16-bit end position, which is stored as a negative value
            // for some reason that is unclear
            m_endpos = read_pcm(wavheader + 5) << 8;
            m_endpos |= read_pcm(wavheader + 6);
            m_endpos = -m_endpos << 16;

            // remaining data values set registers
            m_owner.write(0x80 + m_choffs, read_pcm(wavheader + 7));
            m_owner.write(0x98 + m_choffs, read_pcm(wavheader + 8));
            m_owner.write(0xb0 + m_choffs, read_pcm(wavheader + 9));
            m_owner.write(0xc8 + m_choffs, read_pcm(wavheader + 10));
            m_owner.write(0xe0 + m_choffs, read_pcm(wavheader + 11));

            // reset the envelope so we don't continue playing mid-sample from previous key ons
            m_env_attenuation = 0x3ff;
        }

        /**
         * Starts the attack phase.
         */
        private void start_attack() {
            // don't change anything if already in attack state
            if (m_eg_state == EG_ATTACK)
                return;
            m_eg_state = EG_ATTACK;

            // reset the LFO if requested
            if (m_regs.ch_lfo_reset(m_choffs) != 0)
                m_lfo_counter = 0;

            // if the attack rate == 63 then immediately go to max attenuation
            if (m_cache.eg_rate[EG_ATTACK.ordinal()] == 63)
                m_env_attenuation = 0;

            // reset the positions
            m_curpos = m_nextpos = 0;
        }

        /**
         * Start the release phase.
         */
        private void start_release() {
            // don't change anything if already in release or reverb state
            if (m_eg_state.ordinal() >= EG_RELEASE.ordinal())
                return;
            m_eg_state = EG_RELEASE;
        }

        /**
         * Clocks the envelope generator.
         */
        private void clock_envelope(int env_counter) {
            // handle attack->decay transitions
            if (m_eg_state == EG_ATTACK && m_env_attenuation == 0)
                m_eg_state = EG_DECAY;

            // handle decay->sustain transitions
            if (m_eg_state == EG_DECAY && m_env_attenuation >= m_cache.eg_sustain)
                m_eg_state = EG_SUSTAIN;

            // fetch the appropriate 6-bit rate value from the cache
            int rate = m_cache.eg_rate[m_eg_state.ordinal()];

            // compute the rate shift value; this is the shift needed to
            // apply to the env_counter such that it becomes a 5.11 fixed
            // point number
            int rate_shift = rate >> 2;
            env_counter <<= rate_shift;

            // see if the fractional part is 0; if not, it's not time to clock
            if (bitfield(env_counter, 0, 11) != 0)
                return;

            // determine the increment based on the non-fractional part of env_counter
            int relevant_bits = bitfield(env_counter, (rate_shift <= 11) ? 11 : rate_shift, 3);
            int increment = attenuation_increment(rate, relevant_bits);

            // attack is the only one that increases
            if (m_eg_state == EG_ATTACK)
                m_env_attenuation += (~m_env_attenuation * increment) >> 4;

                // all other cases are similar
            else {
                // apply the increment
                m_env_attenuation += increment;

                // clamp the final attenuation
                if (m_env_attenuation >= 0x400)
                    m_env_attenuation = 0x3ff;

                // transition to reverb at -18dB if enabled
                if (m_env_attenuation >= 0xc0 && m_eg_state.ordinal() < EG_REVERB.ordinal() && m_regs.ch_pseudo_reverb(m_choffs) != 0)
                    m_eg_state = EG_REVERB;
            }
        }

        /**
         * Fetches a sample at the current position.
         */
        private final int fetch_sample() {
            int addr = m_baseaddr;
            int pos = m_curpos >> 16;

            // 8-bit PCM: shift up by 8
            if (m_format == 0)
                return read_pcm(addr + pos) << 8;

            // 16-bit PCM: assemble from 2 halves
            if (m_format == 2) {
                addr += pos * 2;
                return (read_pcm(addr) << 8) | read_pcm(addr + 1);
            }

            // 12-bit PCM: assemble out of half of 3 bytes
            addr += (pos / 2) * 3;
            if ((pos & 1) == 0)
                return (read_pcm(addr + 0) << 8) | ((read_pcm(addr + 1) << 4) & 0xf0);
            else
                return (read_pcm(addr + 2) << 8) | ((read_pcm(addr + 1) << 0) & 0xf0);
        }

        /**
         * Reads a byte from the external PCM memory interface.
         */
        private int read_pcm(int address) {
            return m_owner.intf().ymfm_external_read(PCM, address);
        }

        // internal state

        /** channel offset */
        private final int m_choffs;
        /** base address */
        @Element(sequence = 0)
        private int m_baseaddr;
        /** ending position */
        @Element(sequence = 1)
        private int m_endpos;
        /** loop position */
        @Element(sequence = 2)
        private int m_looppos;
        /** current position */
        @Element(sequence = 3)
        private int m_curpos;
        /** next position */
        @Element(sequence = 4)
        private int m_nextpos;
        /** LFO counter */
        @Element(sequence = 5)
        private int m_lfo_counter;
        /** envelope state */
        @Element(sequence = 6)
        private EnvelopeState m_eg_state;
        /** computed envelope attenuation */
        @Element(sequence = 7)
        private int m_env_attenuation;
        /** total level with as 7.10 for interp */
        @Element(sequence = 8)
        private int m_total_level;
        /** sample format */
        @Element(sequence = 9)
        private int m_format;
        /** current key state */
        @Element(sequence = 10)
        private int m_key_state;
        /** cached data */
        private final Pcm.Cache m_cache = new Pcm.Cache();
        /** reference to registers */
        private final Pcm.Registers m_regs;
        /** reference to our owner */
        private final Pcm.Engine m_owner;
    }

    //
    // PCM ENGINE
    //

    /** pcm_engine */
    @Serdes
    protected static class Engine {

        public static final int OUTPUTS = Pcm.Registers.OUTPUTS;
        public static final int CHANNELS = Pcm.Registers.CHANNELS;
        static final int ALL_CHANNELS = Pcm.Registers.ALL_CHANNELS;

        //using output_data = pcm_channel.output_data;

        /**
         * Constructor.
         */
        public Engine(YmFm.Interface intf) {
            m_intf = intf;
            m_env_counter = 0;
            m_modified_channels = ALL_CHANNELS;
            m_active_channels = ALL_CHANNELS;
            // create the channels
            for (int chnum = 0; chnum < CHANNELS; chnum++)
                m_channel[chnum] = new Channel(this, chnum);
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
            // save our data
            Serdes.Util.serialize(this, os);

            // save channel state
            for (int chnum = 0; chnum < CHANNELS; chnum++)
                m_channel[chnum].save(os);
        }

        /**
         * Restores the data.
         */
        public void restore(InputStream is) throws IOException {
            // save our data
            Serdes.Util.deserialize(is, this);

            // save channel state
            for (int chnum = 0; chnum < CHANNELS; chnum++)
                m_channel[chnum].restore(is);
        }

        /**
         * Master clocking function.
         */
        public void clock(int chanmask) {
            // if something was modified, prepare
            // also prepare every 4k samples to catch ending notes
            if (m_modified_channels != 0 || m_prepare_count++ >= 4096) {
                // call each channel to prepare
                m_active_channels = 0;
                for (int chnum = 0; chnum < CHANNELS; chnum++)
                    if (bitfield(chanmask, chnum) != 0)
                        if (m_channel[chnum].prepare())
                            m_active_channels |= 1 << chnum;

                // reset the modified channels and prepare count
                m_modified_channels = m_prepare_count = 0;
            }

            // increment the envelope counter; the envelope generator
            // only clocks every other sample in order to make the PCM
            // envelopes line up with the FM envelopes (after taking into
            // account the different FM sampling rate)
            m_env_counter++;

            // now update the state of all the channels and operators
            for (int chnum = 0; chnum < CHANNELS; chnum++)
                if (bitfield(chanmask, chnum) != 0)
                    m_channel[chnum].clock(m_env_counter >> 1);
        }

        /**
         * Master update function.
         */
        public void output(YmFm.Output output, int chanmask) {
            // mask out some channels for debug purposes
            chanmask &= Debug.GLOBAL_PCM_CHANNEL_MASK;

            // compute the output of each channel
            for (int chnum = 0; chnum < CHANNELS; chnum++)
                if (bitfield(chanmask, chnum) != 0)
                    m_channel[chnum].output(output);
        }

        /**
         * Handles reads from the PCM registers.
         */
        public int read(int regnum) {
            // handle reads from the data register
            if (regnum == 0x06 && m_regs.memory_access_mode() != 0)
                return m_intf.ymfm_external_read(PCM, m_regs.memory_address_autoinc());

            return m_regs.read(regnum);
        }

        /**
         * Handles writes to the PCM registers.
         */
        public void write(int regnum, int data) {
            // handle reads to the data register
            if (regnum == 0x06 && m_regs.memory_access_mode() != 0) {
                m_intf.ymfm_external_write(PCM, m_regs.memory_address_autoinc(), data);
                return;
            }

            // for now just mark all channels as modified
            m_modified_channels = ALL_CHANNELS;

            // most writes are passive, consumed only when needed
            m_regs.write(regnum, data);

            // however, process keyons immediately
            if (regnum >= 0x68 && regnum <= 0x7f)
                m_channel[regnum - 0x68].keyOnOff(bitfield(data, 7) != 0);

                // and also wavetable writes
            else if (regnum >= 0x08 && regnum <= 0x1f)
                m_channel[regnum - 0x08].load_wavetable();
        }

        /** Returns a reference to our interface */
        public Interface intf() {
            return m_intf;
        }

        /** Returns a reference to our registers */
        public Registers regs() {
            return m_regs;
        }

        // internal state

        /** reference to the interface */
        private final Interface m_intf;
        /** envelope counter */
        @Element
        private int m_env_counter;
        /** bitmask of modified channels */
        private int m_modified_channels;
        /** bitmask of active channels */
        private int m_active_channels;
        /** counter to do periodic prepare sweeps */
        private int m_prepare_count;
        /** array of channels */
        private final Pcm.Channel[] m_channel = new Pcm.Channel[CHANNELS];
        /** registers */
        private final Pcm.Registers m_regs = new Pcm.Registers();
    }
}
