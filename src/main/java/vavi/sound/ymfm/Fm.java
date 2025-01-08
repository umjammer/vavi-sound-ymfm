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
import java.util.HashMap;
import java.util.Map;

import vavi.sound.ymfm.YmFm.EngineCallbacks;
import vavi.sound.ymfm.YmFm.EnvelopeState;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static vavi.sound.ymfm.YmFm.Debug.GLOBAL_FM_CHANNEL_MASK;
import static vavi.sound.ymfm.YmFm.Debug.log_fm_write;
import static vavi.sound.ymfm.YmFm.Debug.log_keyon;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_ATTACK;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_DECAY;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_DEPRESS;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_RELEASE;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_REVERB;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_STATES;
import static vavi.sound.ymfm.YmFm.EnvelopeState.EG_SUSTAIN;
import static vavi.sound.ymfm.YmFm.attenuation_increment;
import static vavi.sound.ymfm.YmFm.attenuation_to_volume;
import static vavi.sound.ymfm.YmFm.bitfield;
import static vavi.sound.ymfm.YmFm.clamp;


abstract class Fm {

    //*********************************************************
    //  GLOBAL ENUMERATORS
    //*********************************************************

    protected enum KeyOnType {
        NORMAL,
        RHYTHM,
        CSM
    }

    //*********************************************************
    //  CORE IMPLEMENTATION
    //*********************************************************

    // ======================> OpDataCache

    // this class holds data that is computed once at the start of clocking
    // and remains static during subsequent sound generation
    protected static class OpDataCache {

        // set phase_step to this value to recalculate it each sample; needed
        // in the case of PM LFO changes
        static final int PHASE_STEP_DYNAMIC = 1;

        int[] waveform;         // base of sine table
        int phase_step;              // phase step, or PHASE_STEP_DYNAMIC if PM is active
        int total_level;             // total level * 8 + KSL
        int block_freq;              // raw block frequency value (used to compute phase_step)
        int detune;                   // detuning value (used to compute phase_step)
        int multiple;                // multiple value (x.1, used to compute phase_step)
        int eg_sustain;              // sustain level, shifted up to envelope values
        int[] eg_rate = new int[EG_STATES.ordinal()];       // envelope rate, including KSR
        int eg_shift = 0;             // envelope shift amount
    }

    // ======================> RegistersBase

    // base class for family-specific register classes; this provides a few
    // constants, common defaults, and helpers, but mostly each derived class is
    // responsible for defining all commonly-called methods
    abstract static class RegistersBase {

        private final Map<String, Object> params = new HashMap<>();

        public Map<String, Object> getParams() {
            return params;
        }

//#region vavi

        public abstract int ch_feedback(int choffs);

        public abstract int ch_output_any(int choffs);

        public abstract int ch_algorithm(int choffs);

        public abstract int ch_output_0(int choffs);

        public abstract int ch_output_1(int choffs);

        public abstract int ch_output_2(int choffs);

        public abstract int ch_output_3(int choffs);

        public abstract void save(OutputStream os) throws IOException;

        public abstract void restore(InputStream is) throws IOException;

        public abstract void reset();

        public abstract int enable_timer_a();

        public abstract int enable_timer_b();

        public abstract int csm();

        public abstract int timer_a_value();

        public abstract int timer_b_value();

        public abstract int load_timer_b();

        public abstract int load_timer_a();

        public abstract int reset_timer_b();

        public abstract int reset_timer_a();

        public abstract boolean write(int regMode, int data, int[] channel, int[] opmask);

        public abstract int clock_noise_and_lfo();

        public abstract int channel_offset(int chnum);

        public abstract int operator_offset(int opnum);

        public abstract void cache_operator_data(int mChoffs, int mOpoffs, OpDataCache mCache);

        public abstract int noise_state();

        public abstract int compute_phase_step(int mChoffs, int mOpoffs, OpDataCache mCache, int lfoRawPm);

        public abstract int op_lfo_am_enable(int mOpoffs);

        public abstract Object log_keyon(int mChoffs, int opoffs);

        public abstract int lfo_am_offset(int mChoffs);

        public abstract void operator_map(int[][] map);

//#endregion

        // this value is returned from the write() function for rhythm channels
        public static final int RHYTHM_CHANNEL = 0xff;

        // this is the size of a full sin waveform
        public static final int WAVEFORM_LENGTH = 0x400;

        //
        // the following constants need to be defined per family:
        //          int OUTPUTS: The number of outputs exposed (1-4)
        //         int CHANNELS: The number of channels on the chip
        //     int ALL_CHANNELS: A bitmask of all channels
        //        int OPERATORS: The number of operators on the chip
        //        int WAVEFORMS: The number of waveforms offered
        //        int REGISTERS: The number of 8-bit registers allocated
        // int DEFAULT_PRESCALE: The starting clock prescale
        // int EG_CLOCK_DIVIDER: The clock divider of the envelope generator
        // int CSM_TRIGGER_MASK: Mask of channels to trigger in CSM mode
        //         int REG_MODE: The address of the "mode" register controlling timers
        //     byte STATUS_TIMERA: Status bit to set when timer A fires
        //     byte STATUS_TIMERB: Status bit to set when tiemr B fires
        //       byte STATUS_BUSY: Status bit to set when the chip is busy
        //        byte STATUS_IRQ: Status bit to set when an IRQ is signalled
        //
        // the following constants are uncommon:
        //          boolean DYNAMIC_OPS: True if ops/channel can be changed at runtime (OPL3+)
        //       boolean EG_HAS_DEPRESS: True if the chip has a DP ("depress"?) envelope stage (OPLL)
        //        boolean EG_HAS_REVERB: True if the chip has a faux reverb envelope stage (OPQ/OPZ)
        //           boolean EG_HAS_SSG: True if the chip has SSG envelope support (OPN)
        //      boolean MODULATOR_DELAY: True if the modulator is delayed by 1 sample (OPL pre-OPL3)
        //
        protected RegistersBase() {
            params.put("DYNAMIC_OPS", false);
            params.put("EG_HAS_DEPRESS", false);
            params.put("EG_HAS_REVERB", false);
            params.put("EG_HAS_SSG", false);
            params.put("MODULATOR_DELAY", false);
        }

        // system-wide register defaults
        public int status_mask() {
            return 0;
        } // OPL only

        public int irq_reset() {
            return 0;
        } // OPL only

        public int noise_enable() {
            return 0;
        } // OPM only

        public int rhythm_enable() {
            return 0;
        } // OPL only

        // per-operator register defaults
        public int op_ssg_eg_enable(int opoffs) {
            return 0;
        } // OPN(A) only

        public int op_ssg_eg_mode(int opoffs) {
            return 0;
        } // OPN(A) only

        public static int operator_list() {
            return operator_list(0xff, 0xff, 0xff, 0xff);
        }

        public static int operator_list(int o1 /* = 0xff */, int o2 /* = 0xff */) {
            return operator_list(o1, o2, 0xff, 0xff);
        }

        // helper to encode four operator numbers into a 32-bit value in the
        // operator maps for each register class
        public static int operator_list(int o1 /* = 0xff */, int o2 /* = 0xff */, int o3 /* = 0xff */, int o4 /* = 0xff */) {
            return o1 | (o2 << 8) | (o3 << 16) | (o4 << 24);
        }

        // helper to apply KSR to the raw ADSR rate, ignoring ksr if the
        // raw value is 0, and clamping to 63
        public static int effective_rate(int rawrate, int ksr) {
            return (rawrate == 0) ? 0 : Math.min(rawrate + ksr, 63);
        }
    }

    // ======================> Operator

    //*********************************************************
    //  FM OPERATOR
    //*********************************************************
    // Operator represents an FM operator (or "slot" in FM parlance), which
    // produces an output sine wave modulated by an envelope
    //template<class RegisterType>
    protected static class Operator<RegisterType extends RegistersBase> {

        // "quiet" value, used to optimize when we can skip doing work
        static final int EG_QUIET = 0x380;

        /**
         * Operator - constructor
         */
        protected Operator(Fm.EngineBase<RegisterType> owner, int opoffs) {
            m_choffs = 0;
            m_opoffs = opoffs;
            m_phase = 0;
            m_env_attenuation = 0x3ff;
            m_env_state = EG_RELEASE;
            m_ssg_inverted = false;
            m_key_state = 0;
            m_keyon_live = 0;
            m_regs = (RegisterType) owner.regs();
            m_owner = owner;
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
         * reset - reset the channel state
         */
        public void reset() {
            // reset our data
            m_phase = 0;
            m_env_attenuation = 0x3ff;
            m_env_state = EG_RELEASE;
            m_ssg_inverted = false;
            m_key_state = 0;
            m_keyon_live = 0;
        }

        // return the operator/channel offset
        public final int opoffs() {
            return m_opoffs;
        }

        public final int choffs() {
            return m_choffs;
        }

        // set the current channel
        public void set_choffs(int choffs) {
            m_choffs = choffs;
        }

        /**
         * prepare - prepare for clocking
         */
        public boolean prepare() {
            // cache the data
            m_regs.cache_operator_data(m_choffs, m_opoffs, m_cache);

            // clock the key state
            clock_keystate(m_keyon_live != 0 ? 1 : 0);
            m_keyon_live &= ~(1 << KeyOnType.CSM.ordinal());

            // we're active until we're quiet after the release
            return (m_env_state != ((boolean) m_regs.getParams().get("EG_HAS_REVERB") ? EG_REVERB : EG_RELEASE) || m_env_attenuation < EG_QUIET);
        }

        /**
         * clock - master clocking function
         */
        public void clock(int env_counter, int lfo_raw_pm) {
            // clock the SSG-EG state (OPN/OPNA)
            if (m_regs.op_ssg_eg_enable(m_opoffs) != 0)
                clock_ssg_eg_state();
            else
                m_ssg_inverted = false;

            // clock the envelope if on an envelope cycle; env_counter is a x.2 value
            if (bitfield(env_counter, 0, 2) == 0)
                clock_envelope(env_counter >> 2);

            // clock the phase
            clock_phase(lfo_raw_pm);
        }

        // return the current phase value
        public final int phase() {
            return m_phase >> 10;
        }

        /**
         * compute_volume - compute the 14-bit signed
         * volume of this operator, given a phase
         * modulation and an AM LFO offset
         */
        public final int compute_volume(int phase, int am_offset) {
            // the low 10 bits of phase represents a full 2*PI period over
            // the full sin wave

            // early out if the envelope is effectively off
            if (m_env_attenuation > EG_QUIET)
                return 0;

            // get the absolute value of the sin, as attenuation, as a 4.8 fixed point value
            int sin_attenuation = m_cache.waveform[phase & (RegistersBase.WAVEFORM_LENGTH - 1)];

            // get the attenuation from the evelope generator as a 4.6 value, shifted up to 4.8
            int env_attenuation = envelope_attenuation(am_offset) << 2;

            // combine into a 5.8 value, then convert from attenuation to 13-bit linear volume
            int result = attenuation_to_volume((sin_attenuation & 0x7fff) + env_attenuation);

            // negate if in the negative part of the sin wave (sign bit gives 14 bits)
            return bitfield(sin_attenuation, 15) != 0 ? -result : result;
        }

        /**
         * compute_noise_volume - compute the 14-bit
         * signed noise volume of this operator, given a
         * noise input value and an AM offset
         */
        public final int compute_noise_volume(int am_offset) {
            // application manual says the logarithmic transform is not applied here, so we
            // just use the raw envelope attenuation, inverted (since 0 attenuation should be
            // maximum), and shift it up from a 10-bit value to an 11-bit value
            int result = (envelope_attenuation(am_offset) ^ 0x3ff) << 1;

            // QUESTION: is AM applied still?

            // negate based on the noise state
            return bitfield(m_regs.noise_state(), 0) != 0 ? -result : result;
        }

        /**
         * keyonoff - signal a key on/off event
         */
        public void keyonoff(int on, KeyOnType type) {
            m_keyon_live = (m_keyon_live & ~(1 << type.ordinal())) | (bitfield(on, 0) << (int) (type.ordinal()));
        }

        // return a reference to our registers
        public final RegisterType regs() {
            return m_regs;
        }

        // simple getters for debugging
        public final EnvelopeState debug_eg_state() {
            return m_env_state;
        }

        public final int debug_eg_attenuation() {
            return m_env_attenuation;
        }

        public final boolean debug_ssg_inverted() {
            return m_ssg_inverted;
        }

        public OpDataCache debug_cache() {
            return m_cache;
        }

        /**
         * start_attack - start the attack phase; called
         * when a keyon happens or when an SSG-EG cycle
         * is complete and restarts
         */
        private void start_attack(boolean is_restart /* = false */) {
            // don't change anything if already in attack state
            if (m_env_state == EG_ATTACK)
                return;
            m_env_state = EG_ATTACK;

            // generally not inverted at start, except if SSG-EG is enabled and
            // one of the inverted modes is specified; leave this alone on a
            // restart, as it is managed by the clock_ssg_eg_state() code
            if ((boolean) m_regs.getParams().get("EG_HAS_SSG") && !is_restart)
                m_ssg_inverted = (m_regs.op_ssg_eg_enable(m_opoffs) & bitfield(m_regs.op_ssg_eg_mode(m_opoffs), 2)) != 0;

            // reset the phase when we start an attack due to a key on
            // (but not when due to an SSG-EG restart except in certain cases
            // managed directly by the SSG-EG code)
            if (!is_restart)
                m_phase = 0;

            // if the attack rate >= 62 then immediately go to max attenuation
            if (m_cache.eg_rate[EG_ATTACK.ordinal()] >= 62)
                m_env_attenuation = 0;
        }

        /**
         * start_release - start the release phase;
         * called when a keyoff happens
         */
        private void start_release() {
            // don't change anything if already in release state
            if (m_env_state.ordinal() >= EG_RELEASE.ordinal())
                return;
            m_env_state = EG_RELEASE;

            // if attenuation if inverted due to SSG-EG, snap the inverted attenuation
            // as the starting point
            if ((boolean) m_regs.getParams().get("EG_HAS_SSG") && m_ssg_inverted) {
                m_env_attenuation = (0x200 - m_env_attenuation) & 0x3ff;
                m_ssg_inverted = false;
            }
        }

        /**
         * clock_keystate - clock the keystate to match
         * the incoming keystate
         */
        private void clock_keystate(int keystate) {
            assert (keystate == 0 || keystate == 1);

            // has the key changed?
            if ((keystate ^ m_key_state) != 0) {
                m_key_state = keystate;

                // if the key has turned on, start the attack
                if (keystate != 0) {
                    // OPLL has a DP ("depress"?) state to bring the volume
                    // down before starting the attack
                    if ((boolean) m_regs.getParams().get("EG_HAS_DEPRESS") && m_env_attenuation < 0x200)
                        m_env_state = EG_DEPRESS;
                    else
                        start_attack(false);
                }

                // otherwise, start the release
                else
                    start_release();
            }
        }

        /**
         * clock_ssg_eg_state - clock the SSG-EG state;
         * should only be called if SSG-EG is enabled
         */
        private void clock_ssg_eg_state() {
            // work only happens once the attenuation crosses above 0x200
            if (bitfield(m_env_attenuation, 9) == 0)
                return;

            // 8 SSG-EG modes:
            //    000: repeat normally
            //    001: run once, hold low
            //    010: repeat, alternating between inverted/non-inverted
            //    011: run once, hold high
            //    100: inverted repeat normally
            //    101: inverted run once, hold low
            //    110: inverted repeat, alternating between inverted/non-inverted
            //    111: inverted run once, hold high
            int mode = m_regs.op_ssg_eg_mode(m_opoffs);

            // hold modes (1/3/5/7)
            if (bitfield(mode, 0) != 0) {
                // set the inverted flag to the end state (0 for modes 1/7, 1 for modes 3/5)
                m_ssg_inverted = (bitfield(mode, 2) ^ bitfield(mode, 1)) != 0;

                // if holding, force the attenuation to the expected value once we're
                // past the attack phase
                if (m_env_state != EG_ATTACK)
                    m_env_attenuation = m_ssg_inverted ? 0x200 : 0x3ff;
            }

            // continuous modes (0/2/4/6)
            else {
                // toggle invert in alternating mode (even in attack state)
                m_ssg_inverted ^= bitfield(mode, 1) != 0;

                // restart attack if in decay/sustain states
                if (m_env_state == EG_DECAY || m_env_state == EG_SUSTAIN)
                    start_attack(true);

                // phase is reset to 0 in modes 0/4
                if (bitfield(mode, 1) == 0)
                    m_phase = 0;
            }

            // in all modes, once we hit release state, attenuation is forced to maximum
            if (m_env_state == EG_RELEASE)
                m_env_attenuation = 0x3ff;
        }

        /**
         * clock_envelope - clock the envelope state
         * according to the given count
         */
        private void clock_envelope(int env_counter) {
            // handle attack->decay transitions
            if (m_env_state == EG_ATTACK && m_env_attenuation == 0)
                m_env_state = EG_DECAY;

            // handle decay->sustain transitions; it is important to do this immediately
            // after the attack->decay transition above in the event that the sustain level
            // is set to 0 (in which case we will skip right to sustain without doing any
            // decay); as an example where this can be heard, check the cymbals sound
            // in channel 0 of shinobi's test mode sound #5
            if (m_env_state == EG_DECAY && m_env_attenuation >= m_cache.eg_sustain)
                m_env_state = EG_SUSTAIN;

            // fetch the appropriate 6-bit rate value from the cache
            int rate = m_cache.eg_rate[m_env_state.ordinal()];

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
            if (m_env_state == EG_ATTACK) {
                // glitch means that attack rates of 62/63 don't increment if
                // changed after the initial key on (where they are handled
                // specially); nukeykt confirms this happens on OPM, OPN, OPL/OPLL
                // at least so assuming it is true for everyone
                if (rate < 62)
                    m_env_attenuation += (~m_env_attenuation * increment) >> 4;
            }

            // all other cases are similar
            else {
                // non-SSG-EG cases just apply the increment
                if (m_regs.op_ssg_eg_enable(m_opoffs) == 0)
                    m_env_attenuation += increment;

                    // SSG-EG only applies if less than mid-point, and then at 4x
                else if (m_env_attenuation < 0x200)
                    m_env_attenuation += 4 * increment;

                // clamp the final attenuation
                if (m_env_attenuation >= 0x400)
                    m_env_attenuation = 0x3ff;

                // transition from depress to attack
                if ((boolean) m_regs.getParams().get("EG_HAS_DEPRESS") && m_env_state == EG_DEPRESS && m_env_attenuation >= 0x200)
                    start_attack(false);

                // transition from release to reverb, should switch at -18dB
                if ((boolean) m_regs.getParams().get("EG_HAS_REVERB") && m_env_state == EG_RELEASE && m_env_attenuation >= 0xc0)
                    m_env_state = EG_REVERB;
            }
        }

        /**
         * clock_phase - clock the 10.10 phase value; the
         * OPN version of the logic has been verified
         * against the Nuked phase generator
         */
        private void clock_phase(int lfo_raw_pm) {
            // read from the cache, or recalculate if PM active
            int phase_step = m_cache.phase_step;
            if (phase_step == OpDataCache.PHASE_STEP_DYNAMIC)
                phase_step = m_regs.compute_phase_step(m_choffs, m_opoffs, m_cache, lfo_raw_pm);

            // finally apply the step to the current phase value
            m_phase += phase_step;
        }

        /**
         * envelope_attenuation - return the effective
         * attenuation of the envelope
         */
        private final int envelope_attenuation(int am_offset) {
            int result = m_env_attenuation >> m_cache.eg_shift;

            // invert if necessary due to SSG-EG
            if ((boolean) m_regs.getParams().get("EG_HAS_SSG") && m_ssg_inverted)
                result = (0x200 - result) & 0x3ff;

            // add in LFO AM modulation
            if (m_regs.op_lfo_am_enable(m_opoffs) != 0)
                result += am_offset;

            // add in total level and KSL from the cache
            result += m_cache.total_level;

            // clamp to max, apply shift, and return
            return Math.min(result, 0x3ff);
        }

        // internal state
        private int m_choffs;                     // channel offset in registers
        private int m_opoffs;                     // operator offset in registers
        @Element(sequence = 0)
        private int m_phase;                      // current phase value (10.10 format)
        @Element(sequence = 1)
        private int m_env_attenuation;            // computed envelope attenuation (4.6 format)
        @Element(sequence = 2)
        private EnvelopeState m_env_state;            // current envelope state
        @Element(sequence = 3)
        private boolean m_ssg_inverted;                // non-zero if the output should be inverted (bit 0)
        @Element(sequence = 4)
        private int m_key_state;                   // current key state: on or off (bit 0)
        @Element(sequence = 5)
        private int m_keyon_live;                  // live key on state (bit 0 = direct, bit 1 = rhythm, bit 2 = CSM)
        private OpDataCache m_cache = new OpDataCache();                  // cached values for performance
        private RegisterType m_regs;                  // direct reference to registers
        private EngineBase<RegisterType> m_owner; // reference to the owning engine
    }

    // ======================> Channel

    //*********************************************************
    //  FM CHANNEL
    //*********************************************************
    //  represents an FM channel which combines the output of 2 or 4
    // operators into a final result
    //template<class RegisterType>
    @Serdes
    protected static class Channel<RegisterType extends RegistersBase> {

        final int OUTPUTS;

        //	using output_data = Output<RegisterType.OUTPUTS>;

        /**
         * Channel - constructor
         */
        public Channel(EngineBase owner, int choffs) {

            m_choffs = choffs;
//			m_feedback = {0, 0};
            m_feedback_in = 0;
//			m_op = {null, null, null, null};
            m_regs = (RegisterType) owner.regs();
            m_owner = owner;

            OUTPUTS = (int) m_regs.getParams().get("OUTPUTS");
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
         * reset - reset the channel state
         */
        public void reset() {
            // reset our data
            m_feedback[0] = m_feedback[1] = 0;
            m_feedback_in = 0;
        }

        // return the channel offset
        public final int choffs() {
            return m_choffs;
        }

        // assign operators
        public void assign(int index, Operator op) {
            assert (index < m_op.length);
            m_op[index] = op;
            if (op != null)
                op.set_choffs(m_choffs);
        }

        /**
         * keyonoff - signal key on/off to our operators
         */
        public void keyonoff(int states, KeyOnType type, int chnum) {
            for (int opnum = 0; opnum < m_op.length; opnum++)
                if (m_op[opnum] != null)
                    m_op[opnum].keyonoff(bitfield(states, opnum), type);

            if (log_keyon.isLoggable(Level.DEBUG) && ((GLOBAL_FM_CHANNEL_MASK >> chnum) & 1) != 0)
                for (int opnum = 0; opnum < m_op.length; opnum++)
                    if (m_op[opnum] != null)
                        log_keyon.log(Level.DEBUG, "%c%s\n", bitfield(states, opnum) != 0 ? '+' : '-', m_regs.log_keyon(m_choffs, m_op[opnum].opoffs()));
        }

        /**
         * prepare - prepare for clocking
         */
        public boolean prepare() {
            int active_mask = 0;

            // prepare all operators and determine if they are active
            for (int opnum = 0; opnum < m_op.length; opnum++)
                if (m_op[opnum] != null)
                    if (m_op[opnum].prepare())
                        active_mask |= 1 << opnum;

            return (active_mask != 0);
        }

        /**
         * clock - master clock of all operators
         */
        public void clock(int env_counter, int lfo_raw_pm) {
            // clock the feedback through
            m_feedback[0] = m_feedback[1];
            m_feedback[1] = m_feedback_in;

            for (int opnum = 0; opnum < m_op.length; opnum++)
                if (m_op[opnum] != null)
                    m_op[opnum].clock(env_counter, lfo_raw_pm);

//			// useful temporary code for envelope debugging
//			if (m_choffs == 0x101) {
//				for (uint opnum = 0; opnum < m_op.size(); opnum++) {
//					var op = * m_op[((opnum & 1) << 1) | ((opnum >> 1) & 1)];
//					printf(" %c%03X%c%c ",
//						"PADSRV"[op.debug_eg_state()],
//						op.debug_eg_attenuation(),
//						op.debug_ssg_inverted() ? '-' : '+',
//						m_regs.op_ssg_eg_enable(op.opoffs()) ? '0' + m_regs.op_ssg_eg_mode(op.opoffs()) : ' ');
//				}
//				printf(" -- ");
//			}
        }

        private static int ALGORITHM(int op2in, int op3in, int op4in, int op1out, int op2out, int op3out) {
            return op2in | (op3in << 1) | (op4in << 4) | (op1out << 7) | (op2out << 8) | (op3out << 9);
        }

        // OPM/OPN offer 8 different connection algorithms for 4 operators,
        // and OPL3 offers 4 more, which we designate here as 8-11.
        //
        // The operators are computed in order, with the inputs pulled from
        // an array of values (opout) that is populated as we go:
        //    0 = 0
        //    1 = O1
        //    2 = O2
        //    3 = O3
        //    4 = (O4)
        //    5 = O1+O2
        //    6 = O1+O3
        //    7 = O2+O3
        //
        // The s_algorithm_ops table describes the inputs and outputs of each
        // algorithm as follows:
        //
        //      ---------x use opout[x] as operator 2 input
        //      ------xxx- use opout[x] as operator 3 input
        //      ---xxx---- use opout[x] as operator 4 input
        //      --x------- include opout[1] in final sum
        //      -x-------- include opout[2] in final sum
        //      x--------- include opout[3] in final sum
        static final int[] s_algorithm_ops = {
                ALGORITHM(1, 2, 3, 0, 0, 0),    //  0: O1 -> O2 -> O3 -> O4 -> out (O4)
                ALGORITHM(0, 5, 3, 0, 0, 0),    //  1: (O1 + O2) -> O3 -> O4 -> out (O4)
                ALGORITHM(0, 2, 6, 0, 0, 0),    //  2: (O1 + (O2 -> O3)) -> O4 -> out (O4)
                ALGORITHM(1, 0, 7, 0, 0, 0),    //  3: ((O1 -> O2) + O3) -> O4 -> out (O4)
                ALGORITHM(1, 0, 3, 0, 1, 0),    //  4: ((O1 -> O2) + (O3 -> O4)) -> out (O2+O4)
                ALGORITHM(1, 1, 1, 0, 1, 1),    //  5: ((O1 -> O2) + (O1 -> O3) + (O1 -> O4)) -> out (O2+O3+O4)
                ALGORITHM(1, 0, 0, 0, 1, 1),    //  6: ((O1 -> O2) + O3 + O4) -> out (O2+O3+O4)
                ALGORITHM(0, 0, 0, 1, 1, 1),    //  7: (O1 + O2 + O3 + O4) -> out (O1+O2+O3+O4)
                ALGORITHM(1, 2, 3, 0, 0, 0),    //  8: O1 -> O2 -> O3 -> O4 -> out (O4)         [same as 0]
                ALGORITHM(0, 2, 3, 1, 0, 0),    //  9: (O1 + (O2 -> O3 -> O4)) -> out (O1+O4)   [unique]
                ALGORITHM(1, 0, 3, 0, 1, 0),    // 10: ((O1 -> O2) + (O3 -> O4)) -> out (O2+O4) [same as 4]
                ALGORITHM(0, 2, 0, 1, 0, 1)     // 11: (O1 + (O2 -> O3) + O4) -> out (O1+O3+O4) [unique]
        };

        /**
         * output_2op - combine 4 operators according to
         * the specified algorithm, returning a sum
         * according to the rshift and clipmax parameters,
         * which vary between different implementations
         */
        public final void output_2op(YmFm.Output output, int rshift, int clipmax) {
            // The first 2 operators should be populated
            assert (m_op[0] != null);
            assert (m_op[1] != null);

            // AM amount is the same across all operators; compute it once
            int am_offset = m_regs.lfo_am_offset(m_choffs);

            // operator 1 has optional self-feedback
            int opmod = 0;
            int feedback = m_regs.ch_feedback(m_choffs);
            if (feedback != 0)
                opmod = (m_feedback[0] + m_feedback[1]) >> (10 - feedback);

            // compute the 14-bit volume/value of operator 1 and update the feedback
            int op1value = m_feedback_in = m_op[0].compute_volume(m_op[0].phase() + opmod, am_offset);

            // now that the feedback has been computed, skip the rest if all volumes
            // are clear; no need to do all this work for nothing
            if (m_regs.ch_output_any(m_choffs) == 0)
                return;

            // Algorithms for two-operator case:
            //    0: O1 -> O2 -> out
            //    1: (O1 + O2) -> out
            int result;
            if (bitfield(m_regs.ch_algorithm(m_choffs), 0) == 0) {
                // some OPL chips use the previous sample for modulation instead of
                // the current sample
                opmod = ((boolean) m_regs.getParams().get("MODULATOR_DELAY") ? m_feedback[1] : op1value) >> 1;
                result = m_op[1].compute_volume(m_op[1].phase() + opmod, am_offset) >> rshift;
            } else {
                result = ((boolean) m_regs.getParams().get("MODULATOR_DELAY") ? m_feedback[1] : op1value) >> rshift;
                result += m_op[1].compute_volume(m_op[1].phase(), am_offset) >> rshift;
                int clipmin = -clipmax - 1;
                result = clamp(result, clipmin, clipmax);
            }

            // add to the output
            add_to_output(m_choffs, output, result);
        }

        /**
         * output_4op - combine 4 operators according to
         * the specified algorithm, returning a sum
         * according to the rshift and clipmax parameters,
         * which vary between different implementations
         */
        public final void output_4op(YmFm.Output output, int rshift, int clipmax) {
            // all 4 operators should be populated
            assert (m_op[0] != null);
            assert (m_op[1] != null);
            assert (m_op[2] != null);
            assert (m_op[3] != null);

            // AM amount is the same across all operators; compute it once
            int am_offset = m_regs.lfo_am_offset(m_choffs);

            // operator 1 has optional self-feedback
            int opmod = 0;
            int feedback = m_regs.ch_feedback(m_choffs);
            if (feedback != 0)
                opmod = (m_feedback[0] + m_feedback[1]) >> (10 - feedback);

            // compute the 14-bit volume/value of operator 1 and update the feedback
            int op1value = m_feedback_in = m_op[0].compute_volume(m_op[0].phase() + opmod, am_offset);

            // now that the feedback has been computed, skip the rest if all volumes
            // are clear; no need to do all this work for nothing
            if (m_regs.ch_output_any(m_choffs) == 0)
                return;

            int algorithm_ops = s_algorithm_ops[m_regs.ch_algorithm(m_choffs)];

            // populate the opout table
            int[] opout = new int[8];
            opout[0] = 0;
            opout[1] = op1value;

            // compute the 14-bit volume/value of operator 2
            opmod = opout[bitfield(algorithm_ops, 0, 1)] >> 1;
            opout[2] = m_op[1].compute_volume(m_op[1].phase() + opmod, am_offset);
            opout[5] = opout[1] + opout[2];

            // compute the 14-bit volume/value of operator 3
            opmod = opout[bitfield(algorithm_ops, 1, 3)] >> 1;
            opout[3] = m_op[2].compute_volume(m_op[2].phase() + opmod, am_offset);
            opout[6] = opout[1] + opout[3];
            opout[7] = opout[2] + opout[3];

            // compute the 14-bit volume/value of operator 4; this could be a noise
            // value on the OPM; all algorithms consume OP4 output at a minimum
            int result;
            if (m_regs.noise_enable() != 0 && m_choffs == 7)
                result = m_op[3].compute_noise_volume(am_offset);
            else {
                opmod = opout[bitfield(algorithm_ops, 4, 3)] >> 1;
                result = m_op[3].compute_volume(m_op[3].phase() + opmod, am_offset);
            }
            result >>= rshift;

            // optionally add OP1, OP2, OP3
            int clipmin = -clipmax - 1;
            if (bitfield(algorithm_ops, 7) != 0)
                result = clamp(result + (opout[1] >> rshift), clipmin, clipmax);
            if (bitfield(algorithm_ops, 8) != 0)
                result = clamp(result + (opout[2] >> rshift), clipmin, clipmax);
            if (bitfield(algorithm_ops, 9) != 0)
                result = clamp(result + (opout[3] >> rshift), clipmin, clipmax);

            // add to the output
            add_to_output(m_choffs, output, result);
        }

        /**
         * output_rhythm_ch6 - special case output
         * computation for OPL channel 6 in rhythm mode,
         * which outputs a Bass Drum instrument
         */
        public final void output_rhythm_ch6(YmFm.Output output, int rshift, int clipmax) {
            // AM amount is the same across all operators; compute it once
            int am_offset = m_regs.lfo_am_offset(m_choffs);

            // Bass Drum: this uses operators 12 and 15 (i.e., channel 6)
            // in an almost-normal way, except that if the algorithm is 1,
            // the first operator is ignored instead of added in

            // operator 1 has optional self-feedback
            int opmod = 0;
            int feedback = m_regs.ch_feedback(m_choffs);
            if (feedback != 0)
                opmod = (m_feedback[0] + m_feedback[1]) >> (10 - feedback);

            // compute the 14-bit volume/value of operator 1 and update the feedback
            int opout1 = m_feedback_in = m_op[0].compute_volume(m_op[0].phase() + opmod, am_offset);

            // compute the 14-bit volume/value of operator 2, which is the result
            opmod = bitfield(m_regs.ch_algorithm(m_choffs), 0) != 0 ? 0 : (opout1 >> 1);
            int result = m_op[1].compute_volume(m_op[1].phase() + opmod, am_offset) >> rshift;

            // add to the output
            add_to_output(m_choffs, output, result * 2);
        }

        /**
         * output_rhythm_ch7 - special case output
         * computation for OPL channel 7 in rhythm mode,
         * which outputs High Hat and Snare Drum
         * instruments
         */
        public final void output_rhythm_ch7(int phase_select, YmFm.Output output, int rshift, int clipmax) {
            // AM amount is the same across all operators; compute it once
            int am_offset = m_regs.lfo_am_offset(m_choffs);
            int noise_state = bitfield(m_regs.noise_state(), 0);

            // High Hat: this uses the envelope from operator 13 (channel 7),
            // and a combination of noise and the operator 13/17 phase select
            // to compute the phase
            int phase = (phase_select << 9) | (0xd0 >> (2 * (noise_state ^ phase_select)));
            int result = m_op[0].compute_volume(phase, am_offset) >> rshift;

            // Snare Drum: this uses the envelope from operator 16 (channel 7),
            // and a combination of noise and operator 13 phase to pick a phase
            int op13phase = m_op[0].phase();
            phase = (0x100 << bitfield(op13phase, 8)) ^ (noise_state << 8);
            result += m_op[1].compute_volume(phase, am_offset) >> rshift;
            result = clamp(result, -clipmax - 1, clipmax);

            // add to the output
            add_to_output(m_choffs, output, result * 2);
        }

        /**
         * output_rhythm_ch8 - special case output
         * computation for OPL channel 8 in rhythm mode,
         * which outputs Tom Tom and Top Cymbal instruments
         */
        public final void output_rhythm_ch8(int phase_select, YmFm.Output output, int rshift, int clipmax) {
            // AM amount is the same across all operators; compute it once
            int am_offset = m_regs.lfo_am_offset(m_choffs);

            // Tom Tom: this is just a single operator processed normally
            int result = m_op[0].compute_volume(m_op[0].phase(), am_offset) >> rshift;

            // Top Cymbal: this uses the envelope from operator 17 (channel 8),
            // and the operator 13/17 phase select to compute the phase
            int phase = 0x100 | (phase_select << 9);
            result += m_op[1].compute_volume(phase, am_offset) >> rshift;
            result = clamp(result, -clipmax - 1, clipmax);

            // add to the output
            add_to_output(m_choffs, output, result * 2);
        }

        // are we a 4-operator channel or a 2-operator one?
        public final boolean is4op() {
            if ((boolean) m_regs.getParams().get("DYNAMIC_OPS"))
                return (m_op[2] != null);
            return ((int) m_regs.getParams().get("OPERATORS") / (int) m_regs.getParams().get("CHANNELS")) == 4;
        }

        // return a reference to our registers
        public final RegisterType regs() {
            return m_regs;
        }

        // simple getters for debugging
        public final Fm.Operator<RegisterType> debug_operator(int index) {
            return m_op[index];
        }

        // helper to add values to the outputs based on channel enables
        private final void add_to_output(int choffs, YmFm.Output output, int value) {
            // create these constants to appease overzealous compilers checking array
            // bounds in unreachable code (looking at you, clang)
            final int out0_index = 0;
            final int out1_index = 1 % OUTPUTS;
            final int out2_index = 2 % OUTPUTS;
            final int out3_index = 3 % OUTPUTS;

            if (OUTPUTS == 1 || m_regs.ch_output_0(choffs) != 0)
                output.data[out0_index] += value;
            if (OUTPUTS >= 2 && m_regs.ch_output_1(choffs) != 0)
                output.data[out1_index] += value;
            if (OUTPUTS >= 3 && m_regs.ch_output_2(choffs) != 0)
                output.data[out2_index] += value;
            if (OUTPUTS >= 4 && m_regs.ch_output_3(choffs) != 0)
                output.data[out3_index] += value;
        }

        // internal state
        private int m_choffs;                     // channel offset in registers
        @Element(sequence = 0)
        private int[] m_feedback = new int[2];                 // feedback memory for operator 1
        @Element(sequence = 1)
        private int m_feedback_in;         // next input value for op 1 feedback (set in output)
        private Fm.Operator<RegisterType>[] m_op = new Fm.Operator[4]; // up to 4 operators
        private RegisterType m_regs;                  // direct reference to registers
        private EngineBase<RegisterType> m_owner; // reference to the owning engine
    }

    //*********************************************************
    //  CORE ENGINE CLASSES
    //*********************************************************

    // forward declarations
    //template<class RegisterType> class EngineBase;

    // ======================> EngineBase

    //*********************************************************
    //  FM ENGINE BASE
    //*********************************************************

    // EngineBase represents a set of operators and channels which together
    // form a Yamaha FM core; chips that implement other engines (ADPCM, wavetable,
    // etc) take this output and combine it with the others externally
    //template<class RegisterType>
    @Serdes
    protected abstract static class EngineBase<RegisterType extends RegistersBase> implements EngineCallbacks {

        // expose some constants from the registers
        public final int OUTPUTS;
        public final int CHANNELS;
        public final int ALL_CHANNELS;
        public final int OPERATORS;

        // also expose status flags for consumers that inject additional bits
        public final int STATUS_TIMERA;
        public final int STATUS_TIMERB;
        public final int STATUS_BUSY;
        public final int STATUS_IRQ;

        protected RegisterType getRegisterType() {
            return m_regs;
        }

        // expose the correct output class
        //using output_data = Output<OUTPUTS>;
        protected YmFm.Output outputFactory() {
            return new YmFm.Output(OUTPUTS);
        }

        /**
         * EngineBase - constructor
         */
        protected EngineBase(YmFm.Interface intf, Class<RegisterType> c) {
            m_intf = intf;
            try {
                m_regs = c.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            OUTPUTS = (int) getRegisterType().getParams().get("OUTPUTS");
            CHANNELS = (int) getRegisterType().getParams().get("CHANNELS");
            ALL_CHANNELS = (int) getRegisterType().getParams().get("ALL_CHANNELS");
            OPERATORS = (int) getRegisterType().getParams().get("OPERATORS");

            // also expose status flags for consumers that inject additional bits
            STATUS_TIMERA = (int) getRegisterType().getParams().get("STATUS_TIMERA");
            STATUS_TIMERB = (int) getRegisterType().getParams().get("STATUS_TIMERB");
            STATUS_BUSY = (int) getRegisterType().getParams().get("STATUS_BUSY");
            STATUS_IRQ = (int) getRegisterType().getParams().get("STATUS_IRQ");

            m_env_counter = 0;
            m_status = 0;
            m_clock_prescale = (int) m_regs.getParams().get("DEFAULT_PRESCALE");
            m_irq_mask = STATUS_TIMERA | STATUS_TIMERB;
            m_irq_state = 0;
//			m_timer_running = {0, 0};
            m_total_clocks = 0;
            m_active_channels = ALL_CHANNELS;
            m_modified_channels = ALL_CHANNELS;
            m_prepare_count = 0;
            // inform the interface of their engine
			m_intf.m_engine = this;

            m_channel = new Fm.Channel[CHANNELS]; // channel pointers
            m_operator = new Fm.Operator[OPERATORS]; // operator pointers

            // create the channels
            for (int chnum = 0; chnum < CHANNELS; chnum++)
                m_channel[chnum] = new Fm.Channel<>(this, getRegisterType().channel_offset(chnum));

            // create the operators
            for (int opnum = 0; opnum < OPERATORS; opnum++)
                m_operator[opnum] = new Fm.Operator<>(this, getRegisterType().operator_offset(opnum));

//#if (YMFM_DEBUG_LOG_WAVFILES)
//			for (int chnum = 0; chnum < CHANNELS; chnum++)
//				m_wavfile[chnum].set_index(chnum);
//#endif

            // do the initial operator assignment
            assign_operators();
        }

        EngineCallbacks callBacks() {
            return this;
        }

        /**
         * save_restore - save or restore the data
         */
        public void save(OutputStream os) throws IOException {
            // save our data
            Serdes.Util.serialize(this, os);

            // save the register/family data
            m_regs.save(os);

            // save channel data
            for (int chnum = 0; chnum < CHANNELS; chnum++)
                m_channel[chnum].save(os);

            // save operator data
            for (int opnum = 0; opnum < OPERATORS; opnum++)
                m_operator[opnum].save(os);

            // invalidate any caches
            invalidate_caches();
        }

        /**
         * save_restore - save or restore the data
         */
        public void restore(InputStream is) throws IOException {
            // save our data
            Serdes.Util.deserialize(is, this);

            // save the register/family data
            m_regs.restore(is);

            // save channel data
            for (int chnum = 0; chnum < CHANNELS; chnum++)
                m_channel[chnum].restore(is);

            // save operator data
            for (int opnum = 0; opnum < OPERATORS; opnum++)
                m_operator[opnum].restore(is);

            // invalidate any caches
            invalidate_caches();
        }

        /**
         * reset - reset the overall state
         */
        public void reset() {
            // reset all status bits
            set_reset_status(0, 0xff);

            // register type-specific initialization
            m_regs.reset();

            // explicitly write to the mode register since it has side-effects
            // QUESTION: old cores initialize this to 0x30 -- who is right?
            write((int) m_regs.getParams().get("REG_MODE"), 0);

            // reset the channels
            for (var chan : m_channel)
                chan.reset();

            // reset the operators
            for (var op : m_operator)
                op.reset();
        }

        /**
         * clock - iterate over all channels, clocking
         * them forward one step
         */
        public int clock(int chanmask) {
            // update the clock counter
            m_total_clocks++;

            // if something was modified, prepare
            // also prepare every 4k samples to catch ending notes
            if (m_modified_channels != 0 || m_prepare_count++ >= 4096) {
                // reassign operators to channels if dynamic
                if ((boolean) m_regs.getParams().get("DYNAMIC_OPS"))
                    assign_operators();

                // call each channel to prepare
                m_active_channels = 0;
                for (int chnum = 0; chnum < CHANNELS; chnum++)
                    if (bitfield(chanmask, chnum) != 0)
                        if (m_channel[chnum].prepare())
                            m_active_channels |= 1 << chnum;

                // reset the modified channels and prepare count
                m_modified_channels = m_prepare_count = 0;
            }

            // if the envelope clock divider is 1, just increment by 4;
            // otherwise, increment by 1 and manually wrap when we reach the divide count
            if ((int) m_regs.getParams().get("EG_CLOCK_DIVIDER") == 1)
                m_env_counter += 4;
            else if (bitfield(++m_env_counter, 0, 2) == (int) m_regs.getParams().get("EG_CLOCK_DIVIDER"))
                m_env_counter += 4 - (int) m_regs.getParams().get("EG_CLOCK_DIVIDER");

            // clock the noise generator
            int lfo_raw_pm = m_regs.clock_noise_and_lfo();

            // now update the state of all the channels and operators
            for (int chnum = 0; chnum < CHANNELS; chnum++)
                if (bitfield(chanmask, chnum) != 0)
                    m_channel[chnum].clock(m_env_counter, lfo_raw_pm);

            // return the envelope counter as it is used to clock ADPCM-A
            return m_env_counter;
        }

        /**
         * output - compute a sum over the relevant
         * channels
         */
        public final void output(YmFm.Output output, int rshift, int clipmax, int chanmask) {
            // mask out some channels for debug purposes
            chanmask &= GLOBAL_FM_CHANNEL_MASK;

            // mask out inactive channels
            if (true /* !YMFM_DEBUG_LOG_WAVFILES */)
                chanmask &= m_active_channels;

            // handle the rhythm case, where some of the operators are dedicated
            // to percussion (this is an OPL-specific feature)
            if (m_regs.rhythm_enable() != 0) {
                // we don't support the OPM noise channel here; ensure it is off
                assert m_regs.noise_enable() == 0;

                // precompute the operator 13+17 phase selection value
                int op13phase = m_operator[13].phase();
                int op17phase = m_operator[17].phase();
                int phase_select = (bitfield(op13phase, 2) ^ bitfield(op13phase, 7)) | bitfield(op13phase, 3) | (bitfield(op17phase, 5) ^ bitfield(op17phase, 3));

                // sum over all the desired channels
                for (int chnum = 0; chnum < CHANNELS; chnum++)
                    if (bitfield(chanmask, chnum) != 0) {
//#if (YMFM_DEBUG_LOG_WAVFILES)
//						var reference = output;
//#endif
                        if (chnum == 6)
                            m_channel[chnum].output_rhythm_ch6(output, rshift, clipmax);
                        else if (chnum == 7)
                            m_channel[chnum].output_rhythm_ch7(phase_select, output, rshift, clipmax);
                        else if (chnum == 8)
                            m_channel[chnum].output_rhythm_ch8(phase_select, output, rshift, clipmax);
                        else if (m_channel[chnum].is4op())
                            m_channel[chnum].output_4op(output, rshift, clipmax);
                        else
                            m_channel[chnum].output_2op(output, rshift, clipmax);
//#if (YMFM_DEBUG_LOG_WAVFILES)
//						m_wavfile[chnum].add(output, reference);
//#endif
                    }
            } else {
                // sum over all the desired channels
                for (int chnum = 0; chnum < CHANNELS; chnum++)
                    if (bitfield(chanmask, chnum) != 0) {
//#if (YMFM_DEBUG_LOG_WAVFILES)
//						var reference = output;
//#endif
                        if (m_channel[chnum].is4op())
                            m_channel[chnum].output_4op(output, rshift, clipmax);
                        else
                            m_channel[chnum].output_2op(output, rshift, clipmax);
//#if (YMFM_DEBUG_LOG_WAVFILES)
//						m_wavfile[chnum].add(output, reference);
//#endif
                    }
            }
        }

        /**
         * write - handle writes to the OPN registers
         */
        public void write(int regnum, int data) {
            log_fm_write.log(Level.DEBUG, "%03X = %02X".formatted(regnum, data));

            // special case: writes to the mode register can impact IRQs;
            // schedule these writes to ensure ordering with timers
            if (regnum == (int) m_regs.getParams().get("REG_MODE")) {
                m_intf.ymfm_sync_mode_write(data);
                return;
            }

            // for now just mark all channels as modified
            m_modified_channels = ALL_CHANNELS;

            // most writes are passive, consumed only when needed
            int[] keyon_channel = new int[1];
            int[] keyon_opmask = new int[1];
            if (m_regs.write(regnum, data, keyon_channel, keyon_opmask)) {
                // handle writes to the keyon register(s)
                if (keyon_channel[0] < CHANNELS) {
                    // normal channel on/off
                    m_channel[keyon_channel[0]].keyonoff(keyon_opmask[0], KeyOnType.NORMAL, keyon_channel[0]);
                } else if (CHANNELS >= 9 && keyon_channel[0] == RegistersBase.RHYTHM_CHANNEL) {
                    // special case for the OPL rhythm channels
                    m_channel[6].keyonoff(bitfield(keyon_opmask[0], 4) != 0 ? 3 : 0, KeyOnType.RHYTHM, 6);
                    m_channel[7].keyonoff(bitfield(keyon_opmask[0], 0) | (bitfield(keyon_opmask[0], 3) << 1), KeyOnType.RHYTHM, 7);
                    m_channel[8].keyonoff(bitfield(keyon_opmask[0], 2) | (bitfield(keyon_opmask[0], 1) << 1), KeyOnType.RHYTHM, 8);
                }
            }
        }

        /**
         * status - return the current state of the
         * status flags
         */
        public final int status() {
            return m_status & ~STATUS_BUSY & ~m_regs.status_mask();
        }

        // set/reset bits in the status register, updating the IRQ status
        public int set_reset_status(int set, int reset) {
            m_status = (m_status | set) & ~(reset | STATUS_BUSY);
            m_intf.ymfm_sync_check_interrupts();
            return m_status & ~m_regs.status_mask();
        }

        // set the IRQ mask
        public void set_irq_mask(int mask) {
            m_irq_mask = mask;
            m_intf.ymfm_sync_check_interrupts();
        }

        // return the current clock prescale
        public final int clock_prescale() {
            return m_clock_prescale;
        }

        // set prescale factor (2/3/6)
        public void set_clock_prescale(int prescale) {
            m_clock_prescale = prescale;
        }

        // compute sample rate
        public final int sample_rate(int baseclock) {
//#if (YMFM_DEBUG_LOG_WAVFILES)
//		for (int chnum = 0; chnum < CHANNELS; chnum++)
//			m_wavfile[chnum].set_samplerate(baseclock / (m_clock_prescale * OPERATORS));
//#endif
            return baseclock / (m_clock_prescale * OPERATORS);
        }

        // return the owning device
        public final YmFm.Interface intf() {
            return m_intf;
        }

        // return a reference to our registers
        public RegisterType regs() {
            return m_regs;
        }

        // invalidate any caches
        public void invalidate_caches() {
            m_modified_channels = ALL_CHANNELS;
        }

        // simple getters for debugging
        public final Channel<RegisterType> debug_channel(int index) {
            return m_channel[index];
        }

        public final Operator<RegisterType> debug_operator(int index) {
            return m_operator[index];
        }

        /**
         * engine_timer_expired - timer has expired - signal
         * status and possibly IRQs
         */
        @Override
        public void engine_timer_expired(int tnum) {
            assert (tnum == 0 || tnum == 1);

            // update status
            if (tnum == 0 && m_regs.enable_timer_a() != 0)
                set_reset_status(STATUS_TIMERA, 0);
            else if (tnum == 1 && m_regs.enable_timer_b() != 0)
                set_reset_status(STATUS_TIMERB, 0);

            // if timer A fired in CSM mode, trigger CSM on all relevant channels
            if (tnum == 0 && m_regs.csm() != 0)
                for (int chnum = 0; chnum < CHANNELS; chnum++)
                    if (bitfield((int) m_regs.getParams().get("CSM_TRIGGER_MASK"), chnum) != 0) {
                        m_channel[chnum].keyonoff(0xf, KeyOnType.CSM, chnum);
                        m_modified_channels |= 1 << chnum;
                    }

            // reset
            m_timer_running[tnum] = 0;
            update_timer(tnum, 1, 0);
        }

        /**
         * check_interrupts - check the interrupt sources
         * for interrupts
         */
        @Override
        public void engine_check_interrupts() {
            // update the state
            int old_state = m_irq_state;
            m_irq_state = ((m_status & m_irq_mask & ~m_regs.status_mask()) != 0) ? 1 : 0;

            // set the IRQ status bit
            if (m_irq_state != 0)
                m_status |= STATUS_IRQ;
            else
                m_status &= ~STATUS_IRQ;

            // if changed, signal the new state
            if (old_state != m_irq_state)
                m_intf.ymfm_update_irq(m_irq_state != 0);
        }

        /**
         * engine_mode_write - handle a mode register write
         * via timer callback
         */
        @Override
        public void engine_mode_write(int data) {
            // mark all channels as modified
            m_modified_channels = ALL_CHANNELS;

            // actually write the mode register now
            int[] dummy1 = new int[1], dummy2 = new int[1];
            m_regs.write((int) m_regs.getParams().get("REG_MODE"), data, dummy1, dummy2);

            // reset IRQ status -- when written, all other bits are ignored
            // QUESTION: should this maybe just reset the IRQ bit and not all the bits?
            //   That is, check_interrupts would only set, this would only clear?
            if (m_regs.irq_reset() != 0)
                set_reset_status(0, 0x78);
            else {
                // reset timer status

                int reset_mask = 0;
                if (m_regs.reset_timer_b() != 0)
                    reset_mask |= (int) m_regs.getParams().get("STATUS_TIMERB");
                if (m_regs.reset_timer_a() != 0)
                    reset_mask |= (int) m_regs.getParams().get("STATUS_TIMERA");
                set_reset_status(0, reset_mask);

                // load timers; note that timer B gets a small negative adjustment because
                // the *16 multiplier is free-running, so the first tick of the clock
                // is a bit shorter
                update_timer(1, m_regs.load_timer_b(), -(m_total_clocks & 15));
                update_timer(0, m_regs.load_timer_a(), 0);
            }
        }

        /**
         * assign_operators - get the current mapping of
         * operators to channels and assign them all
         */
        protected void assign_operators() {
            int[][] map = new int[1][CHANNELS];
            m_regs.operator_map(map);

assert map[0].length == CHANNELS;
            for (int chnum = 0; chnum < CHANNELS; chnum++)
                for (int index = 0; index < 4; index++) {
                    int opnum = bitfield(map[0][chnum], 8 * index, 8);
                    m_channel[chnum].assign(index, (opnum == 0xff) ? null : m_operator[opnum]);
                }
        }

        /**
         * update_timer - update the state of the given
         * timer
         */
        protected void update_timer(int tnum, int enable, int delta_clocks) {
            // if the timer is live, but not currently enabled, set the timer
            if (enable != 0 && m_timer_running[tnum] == 0) {
                // period comes from the registers, and is different for each
                int period = (tnum == 0) ? (1024 - m_regs.timer_a_value()) : 16 * (256 - m_regs.timer_b_value());

                // caller can also specify a delta to account for other effects
                period += delta_clocks;

                // reset it
                m_intf.ymfm_set_timer(tnum, period * OPERATORS * m_clock_prescale);
                m_timer_running[tnum] = 1;
            }

            // if the timer is not live, ensure it is not enabled
            else if (enable == 0) {
                m_intf.ymfm_set_timer(tnum, -1);
                m_timer_running[tnum] = 0;
            }
        }

        // internal state
        protected YmFm.Interface m_intf;          // reference to the system interface
        @Element(sequence = 0)
        protected int m_env_counter;          // envelope counter; low 2 bits are sub-counter
        @Element(sequence = 1)
        protected int m_status;                // current status register
        @Element(sequence = 2)
        protected int m_clock_prescale;        // prescale factor (2/3/6)
        @Element(sequence = 3)
        protected int m_irq_mask;              // mask of which bits signal IRQs
        @Element(sequence = 4)
        protected int m_irq_state;             // current IRQ state
        @Element(sequence = 5)
        protected int[] m_timer_running = new int[2];      // current timer running state
        @Element(sequence = 6)
        protected int m_total_clocks;          // low 8 bits of the total number of clocks processed
        protected int m_active_channels;      // mask of active channels (computed by prepare)
        protected int m_modified_channels;    // mask of channels that have been modified
        protected int m_prepare_count;        // counter to do periodic prepare sweeps
        protected RegisterType m_regs;             // register accessor
        protected Channel<RegisterType>[] m_channel; // channel pointers
        protected Operator<RegisterType>[] m_operator; // operator pointers

//#if (YMFM_DEBUG_LOG_WAVFILES)
//	mutable WavFile<1> m_wavfile[CHANNELS]; // for debugging
//#endif
    }
}
