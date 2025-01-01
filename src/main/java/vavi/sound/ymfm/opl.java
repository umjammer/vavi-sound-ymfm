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

import java.lang.System.Logger.Level;
import java.util.Arrays;

import vavi.sound.ymfm.adpcm.adpcm_b_channel;
import vavi.sound.ymfm.adpcm.adpcm_b_engine;
import vavi.sound.ymfm.fm.fm_engine_base;
import vavi.sound.ymfm.fm.fm_engine_base.output_data;
import vavi.sound.ymfm.fm.fm_registers_base;
import vavi.sound.ymfm.fm.opdata_cache;
import vavi.sound.ymfm.pcm.pcm_engine;
import vavi.sound.ymfm.ymfm.ymfm_interface;
import vavi.sound.ymfm.ymfm.ymfm_saved_state;

import static vavi.sound.ymfm.opl.opl_registers_base.opl_compute_phase_step;
import static vavi.sound.ymfm.ymfm.abs_sin_attenuation;
import static vavi.sound.ymfm.ymfm.access_class.ACCESS_IO;
import static vavi.sound.ymfm.ymfm.bitfield;
import static vavi.sound.ymfm.ymfm.debug.log_unexpected_read_write;
import static vavi.sound.ymfm.ymfm.envelope_state.EG_ATTACK;
import static vavi.sound.ymfm.ymfm.envelope_state.EG_DECAY;
import static vavi.sound.ymfm.ymfm.envelope_state.EG_DEPRESS;
import static vavi.sound.ymfm.ymfm.envelope_state.EG_RELEASE;
import static vavi.sound.ymfm.ymfm.envelope_state.EG_SUSTAIN;


class opl {

	//-------------------------------------------------
	//  opl_key_scale_atten - converts an
	//  OPL concatenated block (3 bits) and fnum
	//  (10 bits) into an attenuation offset; values
	//  here are for 6dB/octave, in 0.75dB units
	//  (matching total level LSB)
	//-------------------------------------------------
	static final byte[] fnum_to_atten = {0, 24, 32, 37, 40, 43, 45, 47, 48, 50, 51, 52, 53, 54, 55, 56};

	static int opl_key_scale_atten(int block, int fnum_4msb) {
		// this table uses the top 4 bits of FNUM and are the maximal values
		// (for when block == 7). Values for other blocks can be computed by
		// subtracting 8 for each block below 7.
		int result = fnum_to_atten[fnum_4msb] - 8 * (block ^ 7);
		return Math.max(0, result);
	}

	//*********************************************************
	//  REGISTER CLASSES
	//*********************************************************

	// ======================> opl_registers_base

	//*********************************************************
	//  OPL REGISTERS
	//*********************************************************

	//
	// OPL/OPL2/OPL3/OPL4 register map:
	//
	//      System-wide registers:
	//           01 xxxxxxxx Test register
	//              --x----- Enable OPL compatibility mode [OPL2 only] (1 = enable)
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
	static abstract class opl_registers_base extends fm_registers_base {

		abstract int getRevision();

		final boolean IsOpl2 = (getRevision() == 2);
		final boolean IsOpl2Plus = (getRevision() >= 2);
		final boolean IsOpl3Plus = (getRevision() >= 3);
		final boolean IsOpl4Plus = (getRevision() >= 4);

		// constants
		public final int OUTPUTS = IsOpl3Plus ? 4 : 1;
		public final int CHANNELS = IsOpl3Plus ? 18 : 9;
		public final int ALL_CHANNELS = (1 << CHANNELS) - 1;
		public final int OPERATORS = CHANNELS * 2;
		public final int WAVEFORMS = IsOpl3Plus ? 8 : (IsOpl2Plus ? 4 : 1);
		public final int REGISTERS = IsOpl3Plus ? 0x200 : 0x100;
		public static final int REG_MODE = 0x04;
		public final int DEFAULT_PRESCALE = IsOpl4Plus ? 19 : (IsOpl3Plus ? 8 : 4);
		public static final int EG_CLOCK_DIVIDER = 1;
		public final int CSM_TRIGGER_MASK = ALL_CHANNELS;
		public final boolean DYNAMIC_OPS = IsOpl3Plus;
		public final boolean MODULATOR_DELAY = !IsOpl3Plus;
		public static final int STATUS_TIMERA = 0x40;
		public static final int STATUS_TIMERB = 0x20;
		public static final int STATUS_BUSY = 0;
		public static final int STATUS_IRQ = 0x80;

		//-------------------------------------------------
		//  opl_registers_base - constructor
		//-------------------------------------------------
		public opl_registers_base() {
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
				int zeroval = wf0[0];
				for (int index = 0; index < WAVEFORM_LENGTH; index++) {
					wf1[index] = bitfield(index, 9) != 0 ? zeroval : wf0[index];
					wf2[index] = wf0[index] & 0x7fff;
					wf3[index] = bitfield(index, 8) != 0 ? zeroval : (wf0[index] & 0x7fff);
					if (WAVEFORMS >= 8) {
						wf4[index] = bitfield(index, 9) != 0 ? zeroval : wf0[index * 2];
						wf5[index] = bitfield(index, 9) != 0 ? zeroval : wf0[(index * 2) & 0x1ff];
						wf6[index] = bitfield(index, 9) << 15;
						wf7[index] = (bitfield(index, 9) != 0 ? (index ^ 0x13ff) : index) << 3;
					}
				}
			}

			// OPL3/OPL4 have dynamic operators, so initialize the fourop_enable value here
			// since operator_map() is called right away, prior to reset()
			if (getRevision() > 2)
				m_regdata[0x104 % REGISTERS] = 0;
		}

		//-------------------------------------------------
		//  reset - reset to initial state
		//-------------------------------------------------
		public void reset() {
			Arrays.fill(m_regdata, 0, REGISTERS, 0);
		}

		//-------------------------------------------------
		//  save_restore - save or restore the data
		//-------------------------------------------------
		public void save_restore(ymfm_saved_state state) {
			state.save_restore(m_lfo_am_counter);
			state.save_restore(m_lfo_pm_counter);
			state.save_restore(m_lfo_am);
			state.save_restore(m_noise_lfsr);
			state.save_restore(m_regdata);
		}

		// map channel number to register offset
		protected int channel_offset(int chnum) {
			assert (chnum < CHANNELS);
			if (!IsOpl3Plus)
				return chnum;
			else
				return (chnum % 9) + 0x100 * (chnum / 9);
		}

		// map operator number to register offset
		protected int operator_offset(int opnum) {
			assert (opnum < OPERATORS);
			if (!IsOpl3Plus)
				return opnum + 2 * (opnum / 6);
			else
				return (opnum % 18) + 2 * ((opnum % 18) / 6) + 0x100 * (opnum / 18);
		}

		// return an array of operator indices for each channel
		protected class operator_mapping {

			int[] chan = new int[CHANNELS];
		}

		// OPL/OPL2 has a fixed map, all 2 operators
		protected final operator_mapping s_fixed_map = new operator_mapping() {{
			chan = new int[] {
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
		}};

		//-------------------------------------------------
		//  operator_map - return an array of operator
		//  indices for each channel; for OPL this is fixed
		//-------------------------------------------------
		public final void operator_map(operator_mapping dest) {
			if (getRevision() <= 2) {
				dest = s_fixed_map;
			} else {
				// OPL3/OPL4 can be configured for 2 or 4 operators
				int fourop = fourop_enable();

				dest.chan[0] = bitfield(fourop, 0) != 0 ? operator_list(0, 3, 6, 9) : operator_list(0, 3);
				dest.chan[1] = bitfield(fourop, 1) != 0 ? operator_list(1, 4, 7, 10) : operator_list(1, 4);
				dest.chan[2] = bitfield(fourop, 2) != 0 ? operator_list(2, 5, 8, 11) : operator_list(2, 5);
				dest.chan[3] = bitfield(fourop, 0) != 0 ? operator_list() : operator_list(6, 9);
				dest.chan[4] = bitfield(fourop, 1) != 0 ? operator_list() : operator_list(7, 10);
				dest.chan[5] = bitfield(fourop, 2) != 0 ? operator_list() : operator_list(8, 11);
				dest.chan[6] = operator_list(12, 15);
				dest.chan[7] = operator_list(13, 16);
				dest.chan[8] = operator_list(14, 17);

				dest.chan[9] = bitfield(fourop, 3) != 0 ? operator_list(18, 21, 24, 27) : operator_list(18, 21);
				dest.chan[10] = bitfield(fourop, 4) != 0 ? operator_list(19, 22, 25, 28) : operator_list(19, 22);
				dest.chan[11] = bitfield(fourop, 5) != 0 ? operator_list(20, 23, 26, 29) : operator_list(20, 23);
				dest.chan[12] = bitfield(fourop, 3) != 0 ? operator_list() : operator_list(24, 27);
				dest.chan[13] = bitfield(fourop, 4) != 0 ? operator_list() : operator_list(25, 28);
				dest.chan[14] = bitfield(fourop, 5) != 0 ? operator_list() : operator_list(26, 29);
				dest.chan[15] = operator_list(30, 33);
				dest.chan[16] = operator_list(31, 34);
				dest.chan[17] = operator_list(32, 35);
			}
		}

		// OPL4 apparently can read back FM registers?
		public final int read(int index) {
			return m_regdata[index];
		}

		//-------------------------------------------------
		//  write - handle writes to the register array
		//-------------------------------------------------
		public boolean write(int index, byte data, int[] channel, int[] opmask) {
			assert (index < REGISTERS);

			// writes to the mode register with high bit set ignore the low bits
			if (index == REG_MODE && bitfield(data, 7) != 0)
				m_regdata[index] |= 0x80;
			else
				m_regdata[index] = data;

			// handle writes to the rhythm keyons
			if (index == 0xbd) {
				channel[0] = RHYTHM_CHANNEL;
				opmask[0] = bitfield(data, 5) != 0 ? bitfield(data, 0, 5) : 0;
				return true;
			}

			// handle writes to the channel keyons
			if ((index & 0xf0) == 0xb0) {
				channel[0] = index & 0x0f;
				if (channel[0] < 9) {
					if (IsOpl3Plus)
						channel[0] += 9 * bitfield(index, 8);
					opmask[0] = bitfield(data, 5) != 0 ? 15 : 0;
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
			lfo_am[0] = ((am_counter < 105 * 64) ? am_counter : (210 * 64 + 63 - am_counter)) >> shift;

			// the PM LFO has 8192 steps, or a nominal period of 6.1Hz
			int pm_counter = lfo_pm_counter[0]++;

			// PM LFO is broken into 8 chunks, each lasting 1024 steps; the PM value
			// depends on the upper bits of FNUM, so this value is a fraction and
			// sign to apply to that value, as a 1.3 value
			return pm_scale[bitfield(pm_counter, 10, 3)] >> (pm_depth ^ 1);
		}

		//-------------------------------------------------
		//  clock_noise_and_lfo - clock the noise and LFO,
		//  handling clock division, depth, and waveform
		//  computations
		//-------------------------------------------------
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

		// reset the LFO
		private void reset_lfo() {
			m_lfo_am_counter = m_lfo_pm_counter = 0;
		}

		// return the AM offset from LFO for the given channel
		// on OPL this is just a fixed value
		private final int lfo_am_offset(int choffs) {
			return m_lfo_am;
		}

		// return LFO/noise states
		private final int noise_state() {
			return m_noise_lfsr >> 23;
		}

		//-------------------------------------------------
		//  cache_operator_data - fill the operator cache
		//  with prefetched data; note that this code is
		//  also used by ymopna_registers, so it must
		//  handle upper channels cleanly
		//-------------------------------------------------
		public void cache_operator_data(int choffs, int opoffs, opdata_cache cache) {
			// set up the easy stuff
			cache.waveform = m_waveform[op_waveform(opoffs) % WAVEFORMS];

			// get frequency from the channel
			int block_freq = cache.block_freq = ch_block_freq(choffs);

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
			int multiple = op_multiple(opoffs);
			cache.multiple = ((multiple & 0xe) | bitfield(0xc2aa, multiple)) * 2;
			if (cache.multiple == 0)
				cache.multiple = 1;

			// phase step, or PHASE_STEP_DYNAMIC if PM is active; this depends on block_freq, detune,
			// and multiple, so compute it after we've done those
			if (op_lfo_pm_enable(opoffs) == 0)
				cache.phase_step = compute_phase_step(choffs, opoffs, cache, 0);
			else
				cache.phase_step = opdata_cache.PHASE_STEP_DYNAMIC;

			// total level, scaled by 8
			cache.total_level = op_total_level(opoffs) << 3;

			// pre-add key scale level
			int ksl = op_ksl(opoffs);
			if (ksl != 0)
				cache.total_level += opl_key_scale_atten(bitfield(block_freq, 10, 3), bitfield(block_freq, 6, 4)) << ksl;

			// 4-bit sustain level, but 15 means 31 so effectively 5 bits
			cache.eg_sustain = op_sustain_level(opoffs);
			cache.eg_sustain |= (cache.eg_sustain + 1) & 0x10;
			cache.eg_sustain <<= 5;

			// determine KSR adjustment for enevlope rates
			int ksrval = keycode >> (2 * (op_ksr(opoffs) ^ 1));
			cache.eg_rate[EG_ATTACK.ordinal()] = effective_rate(op_attack_rate(opoffs) * 4, ksrval);
			cache.eg_rate[EG_DECAY.ordinal()] = effective_rate(op_decay_rate(opoffs) * 4, ksrval);
			cache.eg_rate[EG_SUSTAIN.ordinal()] = op_eg_sustain(opoffs) != 0 ? 0 : effective_rate(op_release_rate(opoffs) * 4, ksrval);
			cache.eg_rate[EG_RELEASE.ordinal()] = effective_rate(op_release_rate(opoffs) * 4, ksrval);
			cache.eg_rate[EG_DEPRESS.ordinal()] = 0x3f;
		}

		static int opl_compute_phase_step(int block_freq, int multiple, int lfo_raw_pm) {
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

		//-------------------------------------------------
		//  compute_phase_step - compute the phase step
		//-------------------------------------------------
		public int compute_phase_step(int choffs, int opoffs, final opdata_cache cache, int lfo_raw_pm) {
			return opl_compute_phase_step(cache.block_freq, cache.multiple, op_lfo_pm_enable(opoffs) != 0 ? lfo_raw_pm : 0);
		}

		//-------------------------------------------------
		//  log_keyon - log a key-on event
		//-------------------------------------------------
		public String log_keyon(int choffs, int opoffs) {
			int chnum = (choffs & 15) + 9 * bitfield(choffs, 8);
			int opnum = (opoffs & 31) - 2 * ((opoffs & 31) / 8) + 18 * bitfield(opoffs, 8);

			StringBuilder buffer = new StringBuilder();

			buffer.append("%2d.%02d freq=%04X fb=%d alg=%X mul=%X tl=%02X ksr=%d ns=%d ksl=%d adr=%X/%X/%X sl=%X sus=%d".formatted(
				chnum, opnum,
				ch_block_freq(choffs),
				ch_feedback(choffs),
				ch_algorithm(choffs),
				op_multiple(opoffs),
				op_total_level(opoffs),
				op_ksr(opoffs),
				note_select(),
				op_ksl(opoffs),
				op_attack_rate(opoffs),
				op_decay_rate(opoffs),
				op_release_rate(opoffs),
				op_sustain_level(opoffs),
				op_eg_sustain(opoffs)));

			if (OUTPUTS > 1)
				buffer.append(" out=%c%c%c%c".formatted(
					ch_output_0(choffs) != 0 ? 'L' : '-',
					ch_output_1(choffs) != 0 ? 'R' : '-',
					ch_output_2(choffs) != 0 ? '0' : '-',
					ch_output_3(choffs) != 0 ? '1' : '-'));
			if (op_lfo_am_enable(opoffs) != 0)
				buffer.append(" am=%d".formatted(lfo_am_depth()));
			if (op_lfo_pm_enable(opoffs) != 0)
				buffer.append(" pm=%d".formatted(lfo_pm_depth()));
			if (waveform_enable() != 0 && op_waveform(opoffs) != 0)
				buffer.append(" wf=%d".formatted(op_waveform(opoffs)));
			if (is_rhythm(choffs))
				buffer.append(" rhy=1");
			if (DYNAMIC_OPS) {
				operator_mapping map = new operator_mapping();
				operator_map(map);
				if (bitfield(map.chan[chnum], 16, 8) != 0xff)
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

		public final int timer_a_value() {
			return byte_(0x02, 0, 8) * 4;
		} // 8->10 bits

		public final int timer_b_value() {
			return byte_(0x03, 0, 8);
		}

		public final int status_mask() {
			return byte_(0x04, 0, 8) & 0x78;
		}

		public final int irq_reset() {
			return byte_(0x04, 7, 1);
		}

		public final int reset_timer_b() {
			return byte_(0x04, 7, 1) | byte_(0x04, 5, 1);
		}

		public final int reset_timer_a() {
			return byte_(0x04, 7, 1) | byte_(0x04, 6, 1);
		}

		public final int enable_timer_b() {
			return 1;
		}

		public final int enable_timer_a() {
			return 1;
		}

		public final int load_timer_b() {
			return byte_(0x04, 1, 1);
		}

		public final int load_timer_a() {
			return byte_(0x04, 0, 1);
		}

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

		public final int rhythm_enable() {
			return byte_(0xbd, 5, 1);
		}

		public final int rhythm_keyon() {
			return byte_(0xbd, 4, 0);
		}

		public final int newflag() {
			return IsOpl3Plus ? byte_(0x105, 0, 1) : 0;
		}

		public final int new2flag() {
			return IsOpl4Plus ? byte_(0x105, 1, 1) : 0;
		}

		public final int fourop_enable() {
			return IsOpl3Plus ? byte_(0x104, 0, 6) : 0;
		}

		// per-channel registers
		final int ch_block_freq(int choffs) {
			return word(0xb0, 0, 5, 0xa0, 0, 8, choffs);
		}

		final int ch_feedback(int choffs) {
			return byte_(0xc0, 1, 3, choffs);
		}

		final int ch_algorithm(int choffs) {
			return byte_(0xc0, 0, 1, choffs) | (IsOpl3Plus ? (8 | (byte_(0xc3, 0, 1, choffs) << 1)) : 0);
		}

		final int ch_output_any(int choffs) {
			return newflag() != 0 ? byte_(0xc0 + choffs, 4, 4) : 1;
		}

		final int ch_output_0(int choffs) {
			return newflag() != 0 ? byte_(0xc0 + choffs, 4, 1) : 1;
		}

		final int ch_output_1(int choffs) {
			return newflag() != 0 ? byte_(0xc0 + choffs, 5, 1) : (IsOpl3Plus ? 1 : 0);
		}

		final int ch_output_2(int choffs) {
			return newflag() != 0 ? byte_(0xc0 + choffs, 6, 1) : 0;
		}

		final int ch_output_3(int choffs) {
			return newflag() != 0 ? byte_(0xc0 + choffs, 7, 1) : 0;
		}

		// per-operator registers
		final int op_lfo_am_enable(int opoffs) {
			return byte_(0x20, 7, 1, opoffs);
		}

		final int op_lfo_pm_enable(int opoffs) {
			return byte_(0x20, 6, 1, opoffs);
		}

		final int op_eg_sustain(int opoffs) {
			return byte_(0x20, 5, 1, opoffs);
		}

		final int op_ksr(int opoffs) {
			return byte_(0x20, 4, 1, opoffs);
		}

		final int op_multiple(int opoffs) {
			return byte_(0x20, 0, 4, opoffs);
		}

		final int op_ksl(int opoffs) {
			int temp = byte_(0x40, 6, 2, opoffs);
			return bitfield(temp, 1) | (bitfield(temp, 0) << 1);
		}

		final int op_total_level(int opoffs) {
			return byte_(0x40, 0, 6, opoffs);
		}

		final int op_attack_rate(int opoffs) {
			return byte_(0x60, 4, 4, opoffs);
		}

		final int op_decay_rate(int opoffs) {
			return byte_(0x60, 0, 4, opoffs);
		}

		final int op_sustain_level(int opoffs) {
			return byte_(0x80, 4, 4, opoffs);
		}

		final int op_release_rate(int opoffs) {
			return byte_(0x80, 0, 4, opoffs);
		}

		final int op_waveform(int opoffs) {
			return IsOpl2Plus ? byte_(0xe0, 0, newflag() != 0 ? 3 : 2, opoffs) : 0;
		}

		protected final int byte_(int offset, int start, int count) {
			return byte_(offset, start, count, 0);
		}

		// return a bitfield extracted from a byte
		protected final int byte_(int offset, int start, int count, int extra_offset /* = 0 */) {
			return bitfield(m_regdata[offset + extra_offset], start, count);
		}

		// return a bitfield extracted from a pair of bytes, MSBs listed first
		protected final int word(int offset1, int start1, int count1, int offset2, int start2, int count2, int extra_offset /* = 0 */) {
			return (byte_(offset1, start1, count1, extra_offset) << count2) | byte_(offset2, start2, count2, extra_offset);
		}

		// helper to determine if the this channel is an active rhythm channel
		protected final boolean is_rhythm(int choffs) {
			return rhythm_enable() != 0 && (choffs >= 6 && choffs <= 8);
		}

		// internal state
		protected int m_lfo_am_counter;            // LFO AM counter
		protected int m_lfo_pm_counter;            // LFO PM counter
		protected int m_noise_lfsr;                // noise LFSR state
		protected int m_lfo_am;                     // current LFO AM value
		protected int[] m_regdata = new int[REGISTERS];         // register data
		protected int[][] m_waveform = new int[WAVEFORMS][WAVEFORM_LENGTH]; // waveforms
	}

	static class opl_registers extends opl_registers_base {

		@Override int getRevision() {
			return 1;
		}
	}

	static class opl2_registers extends opl_registers_base {

		@Override int getRevision() {
			return 2;
		}
	}

	static class opl3_registers extends opl_registers_base {

		@Override int getRevision() {
			return 3;
		}
	}

	static class opl4_registers extends opl_registers_base {

		@Override int getRevision() {
			return 4;
		}
	}

	// ======================> opll_registers

	//*********************************************************
	//  OPLL SPECIFICS
	//*********************************************************

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
	static class opll_registers extends fm_registers_base {

		public static final int OUTPUTS = 2;
		public static final int CHANNELS = 9;
		public static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
		public static final int OPERATORS = CHANNELS * 2;
		public static final int WAVEFORMS = 2;
		public static final int REGISTERS = 0x40;
		public static final int REG_MODE = 0x3f;
		public static final int DEFAULT_PRESCALE = 4;
		public static final int EG_CLOCK_DIVIDER = 1;
		public static final int CSM_TRIGGER_MASK = 0;
		public static final boolean EG_HAS_DEPRESS = true;
		public static final boolean MODULATOR_DELAY = true;
		public static final byte STATUS_TIMERA = 0;
		public static final byte STATUS_TIMERB = 0;
		public static final byte STATUS_BUSY = 0;
		public static final byte STATUS_IRQ = 0;

		// OPLL-specific constants
		public static final int INSTDATA_SIZE = 0x90;

		//-------------------------------------------------
		//  opll_registers - constructor
		//-------------------------------------------------
		public opll_registers() {
			m_lfo_am_counter = 0;
			m_lfo_pm_counter = 0;
			m_noise_lfsr = 1;
			m_lfo_am = 0;

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

		//-------------------------------------------------
		//  reset - reset to initial state
		//-------------------------------------------------
		public void reset() {
			Arrays.fill(m_regdata, 0, REGISTERS, 0);
		}

		//-------------------------------------------------
		//  save_restore - save or restore the data
		//-------------------------------------------------
		public void save_restore(ymfm_saved_state state) {
			state.save_restore(m_lfo_am_counter);
			state.save_restore(m_lfo_pm_counter);
			state.save_restore(m_lfo_am);
			state.save_restore(m_noise_lfsr);
			state.save_restore(m_regdata);
		}

		// map channel number to register offset
		protected int channel_offset(int chnum) {
			assert (chnum < CHANNELS);
			return chnum;
		}

		// map operator number to register offset
		protected int operator_offset(int opnum) {
			assert (opnum < OPERATORS);
			return opnum;
		}

		// return an array of operator indices for each channel
		public static class operator_mapping {

			int[] chan = new int[CHANNELS];
		}

		protected static final operator_mapping s_fixed_map = new operator_mapping() {{
			chan = new int[] {
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
		}};

		//-------------------------------------------------
		//  operator_map - return an array of operator
		//  indices for each channel; for OPLL this is fixed
		//-------------------------------------------------
		public final void operator_map(operator_mapping dest) {
			dest = s_fixed_map;
		}

		// read a register value
		protected final int read(int index) {
			return m_regdata[index];
		}

		//-------------------------------------------------
		//  write - handle writes to the register array;
		//  note that this code is also used by
		//  ymopl3_registers, so it must handle upper
		//  channels cleanly
		//-------------------------------------------------
		public boolean write(int index, byte data, int[] channel, int[] opmask) {
			// unclear the address is masked down to 6 bits or if writes above
			// the register top are ignored; assuming the latter for now
			if (index >= REGISTERS)
				return false;

			// write the new data
			m_regdata[index] = data;

			// handle writes to the rhythm keyons
			if (index == 0x0e) {
				channel[0] = RHYTHM_CHANNEL;
				opmask[0] = bitfield(data, 5) != 0 ? bitfield(data, 0, 5) : 0;
				return true;
			}

			// handle writes to the channel keyons
			if ((index & 0xf0) == 0x20) {
				channel[0] = index & 0x0f;
				if (channel[0] < CHANNELS) {
					opmask[0] = bitfield(data, 4) != 0 ? 3 : 0;
					return true;
				}
			}
			return false;
		}

		//-------------------------------------------------
		//  clock_noise_and_lfo - clock the noise and LFO,
		//  handling clock division, depth, and waveform
		//  computations
		//-------------------------------------------------
		public int clock_noise_and_lfo() {
			// implementation is the same as OPL with fixed depths
			int[] a1 = new int[1];
			int[] a2 = new int[1];
			int[] a3 = new int[1];
			int[] a4 = new int[1];
			int r = opl_registers_base.opl_clock_noise_and_lfo(a1, a2, a3, a4, 1, 1);
			m_noise_lfsr = a1[0];
			m_lfo_am_counter = a2[0];
			m_lfo_pm_counter = a3[0];
			m_lfo_am = a4[0];
			return r;
		}

		// reset the LFO
		protected void reset_lfo() {
			m_lfo_am_counter = m_lfo_pm_counter = 0;
		}

		// return the AM offset from LFO for the given channel
		// on OPL this is just a fixed value
		protected final int lfo_am_offset(int choffs) {
			return m_lfo_am;
		}

		// return LFO/noise states
		protected final int noise_state() {
			return m_noise_lfsr >> 23;
		}

		//-------------------------------------------------
		//  cache_operator_data - fill the operator cache
		//  with prefetched data; note that this code is
		//  also used by ymopna_registers, so it must
		//  handle upper channels cleanly
		//-------------------------------------------------
		public void cache_operator_data(int choffs, int opoffs, opdata_cache cache) {
			// first set up the instrument data
			int instrument = ch_instrument(choffs);
			if (rhythm_enable() != 0 && choffs >= 6)
				m_chinst[choffs] = Arrays.copyOfRange(m_instdata, 8 * (15 + (choffs - 6)), m_instdata.length);
			else
				m_chinst[choffs] = (instrument == 0) ? m_regdata : Arrays.copyOfRange(m_instdata, 8 * (instrument - 1), m_instdata.length);
			m_opinst[opoffs] = Arrays.copyOfRange(m_chinst[choffs], bitfield(opoffs, 0), m_instdata.length);

			// set up the easy stuff
			cache.waveform = m_waveform[op_waveform(opoffs) % WAVEFORMS];

			// get frequency from the channel
			int block_freq = cache.block_freq = ch_block_freq(choffs);

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
			int multiple = op_multiple(opoffs);
			cache.multiple = ((multiple & 0xe) | bitfield(0xc2aa, multiple)) * 2;
			if (cache.multiple == 0)
				cache.multiple = 1;

			// phase step, or PHASE_STEP_DYNAMIC if PM is active; this depends on
			// block_freq, detune, and multiple, so compute it after we've done those
			if (op_lfo_pm_enable(opoffs) == 0)
				cache.phase_step = compute_phase_step(choffs, opoffs, cache, 0);
			else
				cache.phase_step = opdata_cache.PHASE_STEP_DYNAMIC;

			// total level, scaled by 8; for non-rhythm operator 0, this is the total
			// level from the instrument data; for other operators it is 4*volume
			if (bitfield(opoffs, 0) == 1 || (rhythm_enable() != 0 && choffs >= 7))
				cache.total_level = op_volume(opoffs) * 4;
			else
				cache.total_level = ch_total_level(choffs);
			cache.total_level <<= 3;

			// pre-add key scale level
			int ksl = op_ksl(opoffs);
			if (ksl != 0)
				cache.total_level += opl_key_scale_atten(bitfield(block_freq, 9, 3), bitfield(block_freq, 5, 4)) << ksl;

			// 4-bit sustain level, but 15 means 31 so effectively 5 bits
			cache.eg_sustain = op_sustain_level(opoffs);
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
			final byte DP = 12 * 4;
			final byte RR = 7 * 4;
			final byte RS = 5 * 4;

			// determine KSR adjustment for envelope rates
			int ksrval = keycode >> (2 * (op_ksr(opoffs) ^ 1));
			cache.eg_rate[EG_DEPRESS.ordinal()] = DP;
			cache.eg_rate[EG_ATTACK.ordinal()] = effective_rate(op_attack_rate(opoffs) * 4, ksrval);
			cache.eg_rate[EG_DECAY.ordinal()] = effective_rate(op_decay_rate(opoffs) * 4, ksrval);
			if (op_eg_sustain(opoffs) != 0) {
				cache.eg_rate[EG_SUSTAIN.ordinal()] = 0;
				cache.eg_rate[EG_RELEASE.ordinal()] = ch_sustain(choffs) != 0 ? RS : effective_rate(op_release_rate(opoffs) * 4, ksrval);
			} else {
				cache.eg_rate[EG_SUSTAIN.ordinal()] = effective_rate(op_release_rate(opoffs) * 4, ksrval);
				cache.eg_rate[EG_RELEASE.ordinal()] = ch_sustain(choffs) != 0 ? RS : RR;
			}
		}

		//-------------------------------------------------
		//  compute_phase_step - compute the phase step
		//-------------------------------------------------
		public int compute_phase_step(int choffs, int opoffs, final opdata_cache cache, int lfo_raw_pm) {
			// phase step computation is the same as OPL but the block_freq has one
			// more bit, which we shift in
			return opl_compute_phase_step(cache.block_freq << 1, cache.multiple, op_lfo_pm_enable(opoffs) != 0 ? lfo_raw_pm : 0);
		}

		//-------------------------------------------------
		//  log_keyon - log a key-on event
		//-------------------------------------------------
		public String log_keyon(int choffs, int opoffs) {
			int chnum = choffs;
			int opnum = opoffs;

			StringBuilder buffer = new StringBuilder();

			buffer.append("%d.%02d freq=%04X inst=%X fb=%d mul=%X".formatted(
				chnum, opnum,
				ch_block_freq(choffs),
				ch_instrument(choffs),
				ch_feedback(choffs),
				op_multiple(opoffs)));

			if (bitfield(opoffs, 0) == 1 || (is_rhythm(choffs) && choffs >= 6))
				buffer.append(" vol=%X".formatted(op_volume(opoffs)));
			else
				buffer.append(" tl=%02X".formatted(ch_total_level(choffs)));

			buffer.append(" ksr=%d ksl=%d adr=%X/%X/%X sl=%X sus=%d/%d".formatted(
				op_ksr(opoffs),
				op_ksl(opoffs),
				op_attack_rate(opoffs),
				op_decay_rate(opoffs),
				op_release_rate(opoffs),
				op_sustain_level(opoffs),
				op_eg_sustain(opoffs),
				ch_sustain(choffs)));

			if (op_lfo_am_enable(opoffs) != 0)
				buffer.append(" am=1");
			if (op_lfo_pm_enable(opoffs) != 0)
				buffer.append(" pm=1");
			if (op_waveform(opoffs) != 0)
				buffer.append(" wf=1");
			if (is_rhythm(choffs))
				buffer.append(" rhy=1");

			return buffer.toString();
		}

		// set the instrument data
		public void set_instrument_data(final byte[] data) {
			System.arraycopy(data, 0, m_instdata, 0, INSTDATA_SIZE);
		}

		// system-wide registers
		public final int rhythm_enable() {
			return byte_(0x0e, 5, 1);
		}

		public final int rhythm_keyon() {
			return byte_(0x0e, 4, 0);
		}

		public final int test() {
			return byte_(0x0f, 0, 8);
		}

		public final int waveform_enable() {
			return 1;
		}

		public final int timer_a_value() {
			return 0;
		}

		public final int timer_b_value() {
			return 0;
		}

		public final int status_mask() {
			return 0;
		}

		public final int irq_reset() {
			return 0;
		}

		public final int reset_timer_b() {
			return 0;
		}

		public final int reset_timer_a() {
			return 0;
		}

		public final int enable_timer_b() {
			return 0;
		}

		public final int enable_timer_a() {
			return 0;
		}

		public final int load_timer_b() {
			return 0;
		}

		public final int load_timer_a() {
			return 0;
		}

		public final int csm() {
			return 0;
		}

		// per-channel registers
		public final int ch_block_freq(int choffs) {
			return word(0x20, 0, 4, 0x10, 0, 8, choffs);
		}

		public final int ch_sustain(int choffs) {
			return byte_(0x20, 5, 1, choffs);
		}

		public final int ch_total_level(int choffs) {
			return instchbyte_(0x02, 0, 6, choffs);
		}

		public final int ch_feedback(int choffs) {
			return instchbyte_(0x03, 0, 3, choffs);
		}

		public final int ch_algorithm(int choffs) {
			return 0;
		}

		public final int ch_instrument(int choffs) {
			return byte_(0x30, 4, 4, choffs);
		}

		public final int ch_output_any(int choffs) {
			return 1;
		}

		public final int ch_output_0(int choffs) {
			return !is_rhythm(choffs) ? 1 : 0;
		}

		public final int ch_output_1(int choffs) {
			return is_rhythm(choffs) ? 1 : 0;
		}

		public final int ch_output_2(int choffs) {
			return 0;
		}

		public final int ch_output_3(int choffs) {
			return 0;
		}

		// per-operator registers
		public final int op_lfo_am_enable(int opoffs) {
			return instopbyte_(0x00, 7, 1, opoffs);
		}

		public final int op_lfo_pm_enable(int opoffs) {
			return instopbyte_(0x00, 6, 1, opoffs);
		}

		public final int op_eg_sustain(int opoffs) {
			return instopbyte_(0x00, 5, 1, opoffs);
		}

		public final int op_ksr(int opoffs) {
			return instopbyte_(0x00, 4, 1, opoffs);
		}

		public final int op_multiple(int opoffs) {
			return instopbyte_(0x00, 0, 4, opoffs);
		}

		public final int op_ksl(int opoffs) {
			return instopbyte_(0x02, 6, 2, opoffs);
		}

		public final int op_waveform(int opoffs) {
			return instchbyte_(0x03, 3 + bitfield(opoffs, 0), 1, opoffs >> 1);
		}

		public final int op_attack_rate(int opoffs) {
			return instopbyte_(0x04, 4, 4, opoffs);
		}

		public final int op_decay_rate(int opoffs) {
			return instopbyte_(0x04, 0, 4, opoffs);
		}

		public final int op_sustain_level(int opoffs) {
			return instopbyte_(0x06, 4, 4, opoffs);
		}

		public final int op_release_rate(int opoffs) {
			return instopbyte_(0x06, 0, 4, opoffs);
		}

		public final int op_volume(int opoffs) {
			return byte_(0x30, 4 * bitfield(~opoffs, 0), 4, opoffs >> 1);
		}

		private int byte_(int offset, int start, int count) {
			return byte_(offset, start, count, 0);
		}

		// return a bitfield extracted from a byte
		private int byte_(int offset, int start, int count, int extra_offset /* = 0 */) {
			return bitfield(m_regdata[offset + extra_offset], start, count);
		}

		// return a bitfield extracted from a pair of bytes, MSBs listed first
		private final int word(int offset1, int start1, int count1, int offset2, int start2, int count2, int extra_offset /* = 0 */) {
			return (byte_(offset1, start1, count1, extra_offset) << count2) | byte_(offset2, start2, count2, extra_offset);
		}

		// helpers to read from instrument channel/operator data
		private final int instchbyte_(int offset, int start, int count, int choffs) {
			return bitfield(m_chinst[choffs][offset], start, count);
		}

		private final int instopbyte_(int offset, int start, int count, int opoffs) {
			return bitfield(m_opinst[opoffs][offset], start, count);
		}

		// helper to determine if the this channel is an active rhythm channel
		private final boolean is_rhythm(int choffs) {
			return rhythm_enable() != 0 && choffs >= 6;
		}

		// internal state
		private int m_lfo_am_counter;            // LFO AM counter
		private int m_lfo_pm_counter;            // LFO PM counter
		private int m_noise_lfsr;                // noise LFSR state
		private int m_lfo_am;                     // current LFO AM value
		private final int[][] m_chinst = new int[CHANNELS][];    // pointer to instrument data for each channel
		private final int[][] m_opinst = new int[OPERATORS][];   // pointer to instrument data for each operator
		private int[] m_regdata = new int[REGISTERS];         // register data
		private int[] m_instdata = new int[INSTDATA_SIZE];    // instrument data
		private int[][] m_waveform = new int[WAVEFORMS][WAVEFORM_LENGTH]; // waveforms
	}

	//*********************************************************
	//  OPL IMPLEMENTATION CLASSES
	//*********************************************************

	// ======================> ym3526

	//*********************************************************
	//  YM3526
	//*********************************************************
	static class ym3526 {

		//	using fm_engine = fm_engine_base<opl_registers>;
        //	using output_data = fm_engine.output_data;
		public static final int OUTPUTS = opl_registers.OUTPUTS;

		//-------------------------------------------------
		//  ym3526 - constructor
		//-------------------------------------------------
		public ym3526(ymfm_interface intf) {
			m_address = 0;
			m_fm = (opl_registers) intf;
		}

		//-------------------------------------------------
		//  reset - reset the system
		//-------------------------------------------------
		public void reset() {
			// reset the engines
			m_fm.reset();
		}

		//-------------------------------------------------
		//  save_restore - save or restore the data
		//-------------------------------------------------
		public void save_restore(ymfm_saved_state state) {
			state.save_restore(m_address);
			m_fm.save_restore(state);
		}

		// pass-through helpers
		public final int sample_rate(int input_clock) {
			return m_fm.sample_rate(input_clock);
		}

		public void invalidate_caches() {
			m_fm.invalidate_caches();
		}

		//-------------------------------------------------
		//  read_status - read the status register
		//-------------------------------------------------
		public int read_status() {
			return m_fm.status() | 0x06;
		}

		//-------------------------------------------------
		//  read - handle a read from the device
		//-------------------------------------------------
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

		//-------------------------------------------------
		//  write_address - handle a write to the address
		//  register
		//-------------------------------------------------
		public void write_address(byte data) {
			// YM3526 doesn't expose a busy signal, and the datasheets don't indicate
			// delays, but all other OPL chips need 12 cycles for address writes
			m_fm.intf().ymfm_set_busy_end(12 * m_fm.clock_prescale());

			// just set the address
			m_address = data;
		}

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		public void write_data(byte data) {
			switch ((offset & 1) != 0) {
				case 0: // address port
					write_address(data);
					break;

				case 1: // data port
					write_data(data);
					break;
			}
		}

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		public void write(int offset, byte data) {
			// YM3526 doesn't expose a busy signal, and the datasheets don't indicate
			// delays, but all other OPL chips need 84 cycles for data writes
			m_fm.intf().ymfm_set_busy_end(84 * m_fm.clock_prescale());

			// write to FM
			m_fm.write(m_address, data);
		}

		//-------------------------------------------------
		//  generate - generate samples of sound
		//-------------------------------------------------
		public void generate(fm_engine_base.output_data output, int numsamples /* = 1 */) {
			for (int samp = 0; samp < numsamples; samp++, output++) {
				// clock the system
				m_fm.clock(opl_registers.ALL_CHANNELS);

				// update the FM content; mixing details for YM3526 need verification
				m_fm.output(output.clear(), 1, 32767, opl_registers.ALL_CHANNELS);

				// YM3526 uses an external DAC (YM3014) with mantissa/exponent format
				// convert to 10.3 floating point value and back to simulate truncation
				output.roundtrip_fp();
			}
		}

		// internal state
		protected byte m_address;               // address register
		protected opl_registers m_fm;                  // core FM engine
	}

	// ======================> y8950

	//*********************************************************
	//  Y8950
	//*********************************************************
	static class y8950 {

		//	using fm_engine = opl_registers;
		//	using output_data = fm_engine.output_data;
		public static final int OUTPUTS = opl_registers.OUTPUTS;

		public static final byte STATUS_ADPCM_B_PLAYING = 0x01;
		public static final byte STATUS_ADPCM_B_BRDY = 0x08;
		public static final byte STATUS_ADPCM_B_EOS = 0x10;
		public static final byte ALL_IRQS = STATUS_ADPCM_B_BRDY | STATUS_ADPCM_B_EOS | opl_registers.STATUS_TIMERA | opl_registers.STATUS_TIMERB;

		//-------------------------------------------------
		//  y8950 - constructor
		//-------------------------------------------------
		public y8950(ymfm_interface intf) {
			m_address = 0;
			m_io_ddr = 0;
			m_fm = intf;
			m_adpcm_b = intf;
		}

		//-------------------------------------------------
		//  reset - reset the system
		//-------------------------------------------------
		public void reset() {
			// reset the engines
			m_fm.reset();
			m_adpcm_b.reset();
		}

		//-------------------------------------------------
		//  save_restore - save or restore the data
		//-------------------------------------------------
		public void save_restore(ymfm_saved_state state) {
			state.save_restore(m_address);
			state.save_restore(m_io_ddr);
			m_fm.save_restore(state);
		}

		// pass-through helpers
		public final int sample_rate(int input_clock) {
			return m_fm.sample_rate(input_clock);
		}

		public void invalidate_caches() {
			m_fm.invalidate_caches();
		}

		//-------------------------------------------------
		//  read_status - read the status register
		//-------------------------------------------------
		public byte read_status() {
			// start with current FM status, masking out bits we might set
			int status = m_fm.status() & ~(STATUS_ADPCM_B_EOS | STATUS_ADPCM_B_BRDY | STATUS_ADPCM_B_PLAYING);

			// insert the live ADPCM status bits
			int adpcm_status = m_adpcm_b.status();
			if ((adpcm_status & adpcm_b_channel.STATUS_EOS) != 0)
				status |= STATUS_ADPCM_B_EOS;
			if ((adpcm_status & adpcm_b_channel.STATUS_BRDY) != 0)
				status |= STATUS_ADPCM_B_BRDY;
			if ((adpcm_status & adpcm_b_channel.STATUS_PLAYING) != 0)
				status |= STATUS_ADPCM_B_PLAYING;

			// run it through the FM engine to handle interrupts for us
			return m_fm.set_reset_status(status, ~status);
		}

		//-------------------------------------------------
		//  read_data - read the data port
		//-------------------------------------------------
		public int read_data() {
			int result = 0xff;
			switch (m_address) {
				case 0x05:  // keyboard in
					result = m_fm.intf().ymfm_external_read(ACCESS_IO, 1);
					break;

				case 0x09:  // ADPCM data
				case 0x1a:
					result = m_adpcm_b.read(m_address - 0x07);
					break;

				case 0x19:  // I/O data
					result = m_fm.intf().ymfm_external_read(ACCESS_IO, 0);
					break;

				default:
					log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from Y8950 data port %02X\n", m_address);
					break;
			}
			return result;
		}

		//-------------------------------------------------
		//  read - handle a read from the device
		//-------------------------------------------------
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

		//-------------------------------------------------
		//  write_address - handle a write to the address
		//  register
		//-------------------------------------------------
		public void write_address(byte data) {
			// Y8950 doesn't expose a busy signal, but it does indicate that
			// address writes should be no faster than every 12 clocks
			m_fm.intf().ymfm_set_busy_end(12 * m_fm.clock_prescale());

			// just set the address
			m_address = data;
		}

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		public void write_data(byte data) {
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
					m_fm.intf().ymfm_external_write(ACCESS_IO, 1, data);
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
					m_fm.intf().ymfm_external_write(ACCESS_IO, 0, data & m_io_ddr);
					break;

				default:    // everything else to FM
					m_fm.write(m_address, data);
					break;
			}
		}

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		public void write(int offset, byte data) {
			switch (offset & 1) {
				case 0: // address port
					write_address(data);
					break;

				case 1: // data port
					write_data(data);
					break;
			}
		}

		//-------------------------------------------------
		//  generate - generate samples of sound
		//-------------------------------------------------
		public void generate(output_data output, int numsamples /* = 1 */) {
			for (int samp = 0; samp < numsamples; samp++, output++) {
				// clock the system
				m_fm.clock(fm_engine.ALL_CHANNELS);
				m_adpcm_b.clock();

				// update the FM content; clipping need verification
				m_fm.output(output.clear(), 1, 32767, fm_engine.ALL_CHANNELS);

				// mix in the ADPCM; ADPCM-B is stereo, but only one channel
				// not sure how it's wired up internally
				m_adpcm_b.output( * output, 3);

				// Y8950 uses an external DAC (YM3014) with mantissa/exponent format
				// convert to 10.3 floating point value and back to simulate truncation
				output.roundtrip_fp();
			}
		}

		// internal state
		protected int m_address;               // address register
		protected int m_io_ddr;                // data direction register for I/O
		protected opl_registers m_fm;                  // core FM engine
		protected adpcm_b_engine m_adpcm_b;        // ADPCM-B engine
	}

	//*********************************************************
	//  OPL2 IMPLEMENTATION CLASSES
	//*********************************************************

	// ======================> ym3812

	//*********************************************************
	//  YM3812
	//*********************************************************
	static class ym3812 {

		//	using fm_engine = fm_engine_base<>;
//	using output_data = fm_engine.output_data;
		public static final int OUTPUTS = opl2_registers.OUTPUTS;

		//-------------------------------------------------
		//  ym3812 - constructor
		//-------------------------------------------------
		public ym3812(ymfm_interface intf) {
			m_address = 0;
			m_fm = intf;
		}

		//-------------------------------------------------
		//  reset - reset the system
		//-------------------------------------------------
		public void reset() {
			// reset the engines
			m_fm.reset();
		}

		//-------------------------------------------------
		//  save_restore - save or restore the data
		//-------------------------------------------------
		public void save_restore(ymfm_saved_state state) {
			state.save_restore(m_address);
			m_fm.save_restore(state);
		}

		// pass-through helpers
		public final int sample_rate(int input_clock) {
			return m_fm.sample_rate(input_clock);
		}

		public void invalidate_caches() {
			m_fm.invalidate_caches();
		}

		//-------------------------------------------------
		//  read_status - read the status register
		//-------------------------------------------------
		public int read_status() {
			return m_fm.status() | 0x06;
		}

		//-------------------------------------------------
		//  read - handle a read from the device
		//-------------------------------------------------
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

		//-------------------------------------------------
		//  write_address - handle a write to the address
		//  register
		//-------------------------------------------------
		public void write_address(byte data) {
			// YM3812 doesn't expose a busy signal, but it does indicate that
			// address writes should be no faster than every 12 clocks
			m_fm.intf().ymfm_set_busy_end(12 * m_fm.clock_prescale());

			// just set the address
			m_address = data;
		}

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		public void write_data(byte data) {
			// YM3812 doesn't expose a busy signal, but it does indicate that
			// data writes should be no faster than every 84 clocks
			m_fm.intf().ymfm_set_busy_end(84 * m_fm.clock_prescale());

			// write to FM
			m_fm.write(m_address, data);
		}

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		public void write(int offset, byte data) {
			switch (offset & 1) {
				case 0: // address port
					write_address(data);
					break;

				case 1: // data port
					write_data(data);
					break;
			}
		}

		//-------------------------------------------------
		//  generate - generate samples of sound
		//-------------------------------------------------
		public void generate(output_data output, int numsamples /* = 1 */) {
			for (int samp = 0; samp < numsamples; samp++, output++) {
				// clock the system
				m_fm.clock(fm_engine.ALL_CHANNELS);

				// update the FM content; mixing details for YM3812 need verification
				m_fm.output(output.clear(), 1, 32767, fm_engine.ALL_CHANNELS);

				// YM3812 uses an external DAC (YM3014) with mantissa/exponent format
				// convert to 10.3 floating point value and back to simulate truncation
				output.roundtrip_fp();
			}
		}

		// internal state
		protected byte m_address;               // address register
		protected opl2_registers m_fm;                  // core FM engine
	}

	//*********************************************************
	//  OPL3 IMPLEMENTATION CLASSES
	//*********************************************************

	// ======================> ymf262

	//*********************************************************
	//  YMF262
	//*********************************************************
	static class ymf262<fm_engine extends fm_engine_base> {

		//	using fm_engine = fm_engine_base<>;
		//	using output_data = fm_engine.output_data;
		public static final int OUTPUTS = opl3_registers.OUTPUTS;

		//-------------------------------------------------
		//  ymf262 - constructor
		//-------------------------------------------------
		public ymf262(ymfm_interface intf) {
			m_address = 0;
			m_fm = intf;
		}

		//-------------------------------------------------
		//  reset - reset the system
		//-------------------------------------------------
		public void reset() {
			// reset the engines
			m_fm.reset();
		}

		//-------------------------------------------------
		//  save_restore - save or restore the data
		//-------------------------------------------------
		public void save_restore(ymfm_saved_state state) {
			state.save_restore(m_address);
			m_fm.save_restore(state);
		}

		// pass-through helpers
		public final int sample_rate(int input_clock) {
			return m_fm.sample_rate(input_clock);
		}

		public void invalidate_caches() {
			m_fm.invalidate_caches();
		}

		//-------------------------------------------------
		//  read_status - read the status register
		//-------------------------------------------------
		public byte read_status() {
			return m_fm.status();
		}

		//-------------------------------------------------
		//  read - handle a read from the device
		//-------------------------------------------------
		public int read(int offset) {
			int result = 0xff;
			switch (offset & 3) {
				case 0: // status port
					result = read_status();
					break;

				case 1:
				case 2:
				case 3:
					log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from YMF262 offset %d\n", offset & 3);
					break;
			}
			return result;
		}

		//-------------------------------------------------
		//  write_address - handle a write to the address
		//  register
		//-------------------------------------------------
		public void write_address(byte data) {
			// YMF262 doesn't expose a busy signal, but it does indicate that
			// address writes should be no faster than every 32 clocks
			m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());

			// just set the address
			m_address = data;
		}

		//-------------------------------------------------
		//  write_data - handle a write to the data
		//  register
		//-------------------------------------------------
		public void write_data(byte data) {
			// YMF262 doesn't expose a busy signal, but it does indicate that
			// data writes should be no faster than every 32 clocks
			m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());

			// write to FM
			m_fm.write(m_address, data);
		}

		//-------------------------------------------------
		//  write_address_hi - handle a write to the upper
		//  address register
		//-------------------------------------------------
		void write_address_hi(byte data) {
			// YMF262 doesn't expose a busy signal, but it does indicate that
			// address writes should be no faster than every 32 clocks
			m_fm.intf().ymfm_set_busy_end(32 * m_fm.clock_prescale());

			// just set the address
			m_address = data | 0x100;

			// tests reveal that in compatibility mode, upper bit is masked
			// except for register 0x105
			if (m_fm.regs().newflag() == 0 && m_address != 0x105)
				m_address &= 0xff;
		}

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		void write(int offset, byte data) {
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

		//-------------------------------------------------
		//  generate - generate samples of sound
		//-------------------------------------------------
		void generate(output_data output, int numsamples /* = 1 */) {
			for (int samp = 0; samp < numsamples; samp++, output++) {
				// clock the system
				m_fm.clock(fm_engine.ALL_CHANNELS);

				// update the FM content; mixing details for YMF262 need verification
				m_fm.output(output.clear(), 0, 32767, fm_engine.ALL_CHANNELS);

				// YMF262 output is 16-bit offset serial via YAC512 DAC
				output.clamp16();
			}
		}

		// internal state
		protected int m_address;              // address register
		protected opl3_registers m_fm;                  // core FM engine
	}

	// ======================> ymf289b

	//*********************************************************
	//  YMF289B
	//*********************************************************

	// YMF289B is a YMF262 with the following changes:
	//   * "Power down" mode added
	//   * Bulk register clear added
	//   * Busy flag added to the status register
	//   * Shorter busy times
	//   * All registers can be read
	//   * Only 2 outputs exposed
	static class ymf289b<fm_engine extends fm_engine_base> {

		static final byte STATUS_BUSY_FLAGS = 0x05;

		//	using fm_engine = fm_engine_base<opl3_registers>;
//	using output_data = fm_engine.output_data;
		public static final int OUTPUTS = 2;

		//-------------------------------------------------
		//  ymf289b - constructor
		//-------------------------------------------------
		public ymf289b(ymfm_interface intf) {
			m_address = 0;
			m_fm = intf;
		}

		//-------------------------------------------------
		//  reset - reset the system
		//-------------------------------------------------
		public void reset() {
			// reset the engines
			m_fm.reset();
		}

		//-------------------------------------------------
		//  save_restore - save or restore the data
		//-------------------------------------------------
		public void save_restore(ymfm_saved_state state) {
			state.save_restore(m_address);
			m_fm.save_restore(state);
		}

		// pass-through helpers
		public final int sample_rate(int input_clock) {
			return m_fm.sample_rate(input_clock);
		}

		public void invalidate_caches() {
			m_fm.invalidate_caches();
		}

		//-------------------------------------------------
		//  read_status - read the status register
		//-------------------------------------------------
		public byte read_status() {
			byte result = m_fm.status();

			// YMF289B adds a busy flag
			if (ymf289b_mode() && m_fm.intf().ymfm_is_busy())
				result |= STATUS_BUSY_FLAGS;
			return result;
		}

		//-------------------------------------------------
		//  read_data - read the data register
		//-------------------------------------------------
		public int read_data() {
			int result = 0xff;

			// YMF289B can read register data back
			if (ymf289b_mode())
				result = m_fm.regs().read(m_address);
			return result;
		}

		//-------------------------------------------------
		//  read - handle a read from the device
		//-------------------------------------------------
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
					log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from YMF289B offset %d\n", offset & 3);
					break;
			}
			return result;
		}

		//-------------------------------------------------
		//  write_address - handle a write to the address
		//  register
		//-------------------------------------------------
		public void write_address(byte data) {
			m_address = data;

			// count busy time
			m_fm.intf().ymfm_set_busy_end(56);
		}

		//-------------------------------------------------
		//  write_data - handle a write to the data
		//  register
		//-------------------------------------------------
		public void write_data(byte data) {
			// write to FM
			m_fm.write(m_address, data);

			// writes to 0x108 with the CLR flag set clear the registers
			if (m_address == 0x108 && bitfield(data, 2) != 0)
				m_fm.regs().reset();

			// count busy time
			m_fm.intf().ymfm_set_busy_end(56);
		}

		//-------------------------------------------------
		//  write_address_hi - handle a write to the upper
		//  address register
		//-------------------------------------------------
		public void write_address_hi(byte data) {
			// just set the address
			m_address = data | 0x100;

			// tests reveal that in compatibility mode, upper bit is masked
			// except for register 0x105
			if (m_fm.regs().newflag() == 0 && m_address != 0x105)
				m_address &= 0xff;

			// count busy time
			m_fm.intf().ymfm_set_busy_end(56);
		}

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		public void write(int offset, byte data) {
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

		//-------------------------------------------------
		//  generate - generate samples of sound
		//-------------------------------------------------
		public void generate(output_data output, int numsamples /* = 1 */) {
			for (int samp = 0; samp < numsamples; samp++, output++) {
				// clock the system
				m_fm.clock(fm_engine.ALL_CHANNELS);

				// update the FM content; mixing details for YMF262 need verification
				fm_engine.output_data full;
				m_fm.output(full.clear(), 0, 32767, fm_engine.ALL_CHANNELS);

				// YMF278B output is 16-bit offset serial via YAC512 DAC, but
				// only 2 of the 4 outputs are exposed
				output.data[0] = full.data[0];
				output.data[1] = full.data[1];
				output.clamp16();
			}
		}

		// internal helpers
		protected boolean ymf289b_mode() {
			return ((m_fm.regs().read(0x105) & 0x04) != 0);
		}

		// internal state
		protected int m_address;              // address register
		protected opl3_registers m_fm;                  // core FM engine
	}

	//*********************************************************
	//  OPL4 IMPLEMENTATION CLASSES
	//*********************************************************

	// ======================> ymf278b

	//*********************************************************
	//  YMF278B
	//*********************************************************
	static class ymf278b {

		// Using the nominal datasheet frequency of 33.868MHz, the output of the
		// chip will be clock/768 = 44.1kHz. However, the FM engine is clocked
		// internally at clock/(19*36), or 49.515kHz, so the FM output needs to
		// be downsampled. We treat this as needing to clock the FM engine an
		// extra tick every few samples. The exact ratio is 768/(19*36) or
		// 768/684 = 192/171. So if we always clock the FM once, we'll have
		// 192/171 - 1 = 21/171 left. Thus we count 21 for each sample and when
		// it gets above 171, we tick an extra time.
		static final int FM_EXTRA_SAMPLE_THRESH = 171;
		static final int FM_EXTRA_SAMPLE_STEP = 192 - FM_EXTRA_SAMPLE_THRESH;

		//	using fm_engine = fm_engine_base<opl4_registers>;
		public static final int OUTPUTS = 6;
//	using output_data = ymfm_output<OUTPUTS>;

		public static final byte STATUS_BUSY = 0x01;
		public static final byte STATUS_LD = 0x02;

		//-------------------------------------------------
		//  ymf278b - constructor
		//-------------------------------------------------
		public ymf278b(ymfm_interface intf) {
			m_address = 0;
			m_fm_pos = 0;
			m_load_remaining = 0;
			m_next_status_id = false;
			m_fm = intf;
			m_pcm = intf;
		}

		//-------------------------------------------------
		//  reset - reset the system
		//-------------------------------------------------
		public void reset() {
			// reset the engines
			m_fm.reset();
			m_pcm.reset();

			// next status read will return ID
			m_next_status_id = true;
		}

		//-------------------------------------------------
		//  save_restore - save or restore the data
		//-------------------------------------------------
		public void save_restore(ymfm_saved_state state) {
			state.save_restore(m_address);
			state.save_restore(m_fm_pos);
			state.save_restore(m_load_remaining);
			state.save_restore(m_next_status_id);
			m_fm.save_restore(state);
			m_pcm.save_restore(state);
		}

		// pass-through helpers
		public final int sample_rate(int input_clock) {
			return input_clock / 768;
		}

		public void invalidate_caches() {
			m_fm.invalidate_caches();
		}

		//-------------------------------------------------
		//  read_status - read the status register
		//-------------------------------------------------
		public byte read_status() {
			byte result;

			// first status read after initialization returns a chip ID, which
			// varies based on the "new" flags, indicating the mode
			if (m_next_status_id) {
				if (m_fm.regs().new2flag())
					result = 0x02;
				else if (m_fm.regs().newflag())
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
				if (!m_fm.regs().new2flag())
					result &= ~(STATUS_BUSY | STATUS_LD);
			}
			return result;
		}

		//-------------------------------------------------
		//  write_data_pcm - handle a write to the PCM data
		//  register
		//-------------------------------------------------
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

		//-------------------------------------------------
		//  read - handle a read from the device
		//-------------------------------------------------
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
					log_unexpected_read_write.log(Level.DEBUG, "Unexpected read from ymf278b offset %d\n", offset & 3);
					break;
			}
			return result;
		}

		//-------------------------------------------------
		//  write_address - handle a write to the address
		//  register
		//-------------------------------------------------
		public void write_address(byte data) {
			// just set the address
			m_address = data;
		}

		//-------------------------------------------------
		//  write_data - handle a write to the data
		//  register
		//-------------------------------------------------
		public void write_data(byte data) {
			// write to FM
			if (bitfield(m_address, 9) == 0) {
				byte old = m_fm.regs().new2flag();
				m_fm.write(m_address, data);

				// changing NEW2 from 0->1 causes the next status read to
				// return the chip ID
				if (old == 0 && m_fm.regs().new2flag() != 0)
					m_next_status_id = true;
			}

			// BUSY goes for 56 clocks on FM writes
			m_fm.intf().ymfm_set_busy_end(56);
		}

		//-------------------------------------------------
		//  write_address_hi - handle a write to the upper
		//  address register
		//-------------------------------------------------
		public void write_address_hi(byte data) {
			// just set the address
			m_address = data | 0x100;

			// YMF262, in compatibility mode, treats the upper bit as masked
			// except for register 0x105; assuming YMF278B works the same way?
			if (m_fm.regs().newflag() == 0 && m_address != 0x105)
				m_address &= 0xff;
		}

		//-------------------------------------------------
		//  write_address_pcm - handle a write to the upper
		//  address register
		//-------------------------------------------------
		public void write_address_pcm(byte data) {
			// just set the address
			m_address = data | 0x200;
		}

		//-------------------------------------------------
		//  write_data_pcm - handle a write to the PCM data
		//  register
		//-------------------------------------------------
		public void write_data_pcm(byte data) {
			// ignore data writes if new2 is not yet set
			if (m_fm.regs().new2flag() == 0)
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

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		public void write(int offset, byte data) {
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
					log_unexpected_read_write.log(Level.DEBUG, "Unexpected write to ymf278b offset %d\n", offset & 7);
					break;
			}
		}

		static final int[] s_mix_scale = {0x7fa, 0x5a4, 0x3fd, 0x2d2, 0x1fe, 0x169, 0xff, 0};
		final int pcm_l = s_mix_scale[m_pcm.regs().mix_pcm_l()];
		final int pcm_r = s_mix_scale[m_pcm.regs().mix_pcm_r()];
		final int fm_l = s_mix_scale[m_pcm.regs().mix_fm_l()];
		final int fm_r = s_mix_scale[m_pcm.regs().mix_fm_r()];

		//-------------------------------------------------
		//  generate - generate one sample of sound
		//-------------------------------------------------
		public void generate(output_data output, int numsamples /*= 1*/) {
			for (int samp = 0; samp < numsamples; samp++, output++) {
				// clock the system
				m_fm_pos += FM_EXTRA_SAMPLE_STEP;
				if (m_fm_pos >= FM_EXTRA_SAMPLE_THRESH) {
					m_fm.clock(fm_engine.ALL_CHANNELS);
					m_fm_pos -= FM_EXTRA_SAMPLE_THRESH;
				}
				m_fm.clock(fm_engine.ALL_CHANNELS);
				m_pcm.clock(pcm_engine.ALL_CHANNELS);

				// update the FM content; mixing details for YMF278B need verification
				fm_engine.output_data fmout;
				m_fm.output(fmout.clear(), 0, 32767, fm_engine.ALL_CHANNELS);

				// update the PCM content
				pcm_engine.output_data pcmout;
				m_pcm.output(pcmout.clear(), pcm_engine.ALL_CHANNELS);

				// DO0 output: FM channels 2+3 only
				output.data[0] = fmout.data[2];
				output.data[1] = fmout.data[3];

				// DO1 output: wavetable channels 2+3 only
				output.data[2] = pcmout.data[2];
				output.data[3] = pcmout.data[3];

				// DO2 output: mixed FM channels 0+1 and wavetable channels 0+1
				output.data[4] = (fmout.data[0] * fm_l + pcmout.data[0] * pcm_l) >> 11;
				output.data[5] = (fmout.data[1] * fm_r + pcmout.data[1] * pcm_r) >> 11;

				// YMF278B output is 16-bit 2s complement serial
				output.clamp16();
			}

			// decrement the load waiting count
			if (m_load_remaining > 0)
				m_load_remaining -= Math.min(m_load_remaining, numsamples);
		}

		// internal state
		protected int m_address;              // address register
		protected int m_fm_pos;               // FM resampling position
		protected int m_load_remaining;       // how many more samples until LD flag clears
		protected boolean m_next_status_id;           // flag to track which status ID to return
		protected opl4_registers m_fm;                  // core FM engine
		protected pcm_engine m_pcm;                // core PCM engine
	}

	//*********************************************************
	//  OPLL IMPLEMENTATION CLASSES
	//*********************************************************

	// ======================> opll_base

	//*********************************************************
	//  OPLL BASE
	//*********************************************************
	static class opll_base {

		//	using fm_engine = fm_engine_base<opll_registers>;
//	using output_data = fm_engine.output_data;
		public static final int OUTPUTS = opll_registers.OUTPUTS;

		//-------------------------------------------------
		//  opll_base - constructor
		//-------------------------------------------------
		public opll_base(ymfm_interface intf, final byte[] data) {
			m_address = 0;
			m_fm = (opll_registers) intf;

			m_fm.regs().set_instrument_data(instrument_data);
		}

		// configuration
		public void set_instrument_data(final byte data) {
			m_fm.regs().set_instrument_data(data);
		}

		//-------------------------------------------------
		//  reset - reset the system
		//-------------------------------------------------
		public void reset() {
			// reset the engines
			m_fm.reset();
		}

		//-------------------------------------------------
		//  save_restore - save or restore the data
		//-------------------------------------------------
		public void save_restore(ymfm_saved_state state) {
			state.save_restore(m_address);
			m_fm.save_restore(state);
		}

		// pass-through helpers
		private final int sample_rate(int input_clock) {
			return m_fm.sample_rate(input_clock);
		}

		public void invalidate_caches() {
			m_fm.invalidate_caches();
		}

		// read access -- doesn't really have any, but provide these for consistency
		private byte read_status() {
			return 0x00;
		}

		private byte read(int offset) {
			return 0x00;
		}

		//-------------------------------------------------
		//  write_address - handle a write to the address
		//  register
		//-------------------------------------------------
		public void write_address(byte data) {
			// OPLL doesn't expose a busy signal, but datasheets are pretty consistent
			// in indicating that address writes should be no faster than every 12 clocks
			m_fm.intf().ymfm_set_busy_end(12);

			// just set the address
			m_address = data;
		}

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		public void write_data(byte data) {
			// OPLL doesn't expose a busy signal, but datasheets are pretty consistent
			// in indicating that address writes should be no faster than every 84 clocks
			m_fm.intf().ymfm_set_busy_end(84);

			// write to FM
			m_fm.write(m_address, data);
		}

		//-------------------------------------------------
		//  write - handle a write to the register
		//  interface
		//-------------------------------------------------
		public void write(int offset, byte data) {
			switch (offset & 1) {
				case 0: // address port
					write_address(data);
					break;

				case 1: // data port
					write_data(data);
					break;
			}
		}

		//-------------------------------------------------
		//  generate - generate one sample of sound
		//-------------------------------------------------
		public void generate(output_data output, int numsamples /* = 1 */) {
			for (int samp = 0; samp < numsamples; samp++, output++) {
				// clock the system
				m_fm.clock(opll_registers.ALL_CHANNELS);

				// update the FM content; OPLL has a built-in 9-bit DAC
				m_fm.output(output.clear(), 5, 256, opll_registers.ALL_CHANNELS);

				// final output is multiplexed; we don't simulate that here except
				// to average over everything
				output.data[0] = (output.data[0] * 128) / 9;
				output.data[1] = (output.data[1] * 128) / 9;
			}
		}

		// internal state
		protected byte m_address;               // address register
		protected opll_registers m_fm;                  // core FM engine
	}

	// ======================> ym2413

	//*********************************************************
	//  YM2413
	//*********************************************************

	static class ym2413 extends opll_base {

		// table below taken from https://github.com/plgDavid/misc/wiki/Copyright-free-OPLL(x)-ROM-patches
		private static final byte[] s_default_instruments = {
			//April 2015 David Viens, tweaked May 19-21th 2015 Hubert Lamontagne
			0x71, 0x61, 0x1E, 0x17, (byte) 0xEF, 0x7F, 0x00, 0x17, //Violin
			0x13, 0x41, 0x1A, 0x0D, (byte) 0xF8, (byte) 0xF7, 0x23, 0x13, //Guitar
			0x13, 0x01, (byte) 0x99, 0x00, (byte) 0xF2, (byte) 0xC4, 0x11, 0x23, //Piano
			0x31, 0x61, 0x0E, 0x07, (byte) 0x98, 0x64, 0x70, 0x27, //Flute
			0x22, 0x21, 0x1E, 0x06, (byte) 0xBF, 0x76, 0x00, 0x28, //Clarinet
			0x31, 0x22, 0x16, 0x05, (byte) 0xE0, 0x71, 0x0F, 0x18, //Oboe
			0x21, 0x61, 0x1D, 0x07, (byte) 0x82, (byte) 0x8F, 0x10, 0x07, //Trumpet
			0x23, 0x21, 0x2D, 0x14, (byte) 0xFF, 0x7F, 0x00, 0x07, //Organ
			0x41, 0x61, 0x1B, 0x06, 0x64, 0x65, 0x10, 0x17, //Horn
			0x61, 0x61, 0x0B, 0x18, (byte) 0x85, (byte) 0xFF, (byte) 0x81, 0x07, //Synthesizer
			0x13, 0x01, (byte) 0x83, 0x11, (byte) 0xFA, (byte) 0xE4, 0x10, 0x04, //Harpsichord
			0x17, (byte) 0x81, 0x23, 0x07, (byte) 0xF8, (byte) 0xF8, 0x22, 0x12, //Vibraphone
			0x61, 0x50, 0x0C, 0x05, (byte) 0xF2, (byte) 0xF5, 0x29, 0x42, //Synthesizer Bass
			0x01, 0x01, 0x54, 0x03, (byte) 0xC3, (byte) 0x92, 0x03, 0x02, //Acoustic Bass
			0x41, 0x41, (byte) 0x89, 0x03, (byte) 0xF1, (byte) 0xE5, 0x11, 0x13, //Electric Guitar
			0x01, 0x01, 0x18, 0x0F, (byte) 0xDF, (byte) 0xF8, 0x6A, 0x6D, //rhythm 1
			0x01, 0x01, 0x00, 0x00, (byte) 0xC8, (byte) 0xD8, (byte) 0xA7, 0x48, //rhythm 2
			0x05, 0x01, 0x00, 0x00, (byte) 0xF8, (byte) 0xAA, 0x59, 0x55  //rhythm 3
		};

		//-------------------------------------------------
		//  ym2413 - constructor
		//-------------------------------------------------
		public ym2413(ymfm_interface intf, final byte[] instrument_data /* = null */) {
			super(intf, (instrument_data != null) ? instrument_data : s_default_instruments);
		}
	}

	// ======================> ym2413

	//*********************************************************
	//  YM2423
	//*********************************************************
	static class ym2423 extends opll_base {

		// table below taken from https://github.com/plgDavid/misc/wiki/Copyright-free-OPLL(x)-ROM-patches
		private static final byte[] s_default_instruments = {
			// May 4-6 2016 Hubert Lamontagne
			// Doesn't seem to have any diff between opllx-x and opllx-y
			// Drums seem identical to regular opll
			0x61, 0x61, 0x1B, 0x07, (byte) 0x94, 0x5F, 0x10, 0x06, //1	Strings	Saw wave with vibrato Violin
			(byte) 0x93, (byte) 0xB1, 0x51, 0x04, (byte) 0xF3, (byte) 0xF2, 0x70, (byte) 0xFB, //2	Guitar	Jazz GuitarPiano
			0x41, 0x21, 0x11, (byte) 0x85, (byte) 0xF2, (byte) 0xF2, 0x70, 0x75, //3	Electric Guitar	Same as OPLL No.15 Synth
			(byte) 0x93, (byte) 0xB2, 0x28, 0x07, (byte) 0xF3, (byte) 0xF2, 0x70, (byte) 0xB4, //4	Electric Piano 2	Slow attack, tremoloDing-a-ling
			0x72, 0x31, (byte) 0x97, 0x05, 0x51, 0x6F, 0x60, 0x09, //5 	Flute	Same as OPLL No.4Clarinet
			0x13, 0x30, 0x18, 0x06, (byte) 0xF7, (byte) 0xF4, 0x50, (byte) 0x85, //6	Marimba 	Also be used as steel drumXyophone
			0x51, 0x31, 0x1C, 0x07, 0x51, 0x71, 0x20, 0x26, //7	Trumpet 	Same as OPLL No.7Trumpet
			0x41, (byte) 0xF4, 0x1B, 0x07, 0x74, 0x34, 0x00, 0x06, //8	Harmonica Harmonica synth
			0x50, 0x30, 0x4D, 0x03, 0x42, 0x65, 0x20, 0x06, //9	Tuba Tuba
			0x40, 0x20, 0x10, (byte) 0x85, (byte) 0xF3, (byte) 0xF5, 0x20, 0x04, //10 	Synth Brass 2 Synth sweep
			0x61, 0x61, 0x1B, 0x07, (byte) 0xC5, (byte) 0x96, (byte) 0xF3, (byte) 0xF6, //11 	Short Saw	Saw wave with short envelopeSynth hit
			(byte) 0xF9, (byte) 0xF1, (byte) 0xDC, 0x00, (byte) 0xF5, (byte) 0xF3, 0x77, (byte) 0xF2, //12 	Vibraphone	Bright vibraphoneVibes
			0x60, (byte) 0xA2, (byte) 0x91, 0x03, (byte) 0x94, (byte) 0xC1, (byte) 0xF7, (byte) 0xF7, //13 	Electric Guitar 2	Clean guitar with feedbackHarmonic bass
			0x30, 0x30, 0x17, 0x06, (byte) 0xF3, (byte) 0xF1, (byte) 0xB7, (byte) 0xFC, //14 	Synth Bass 2Snappy bass
			0x31, 0x36, 0x0D, 0x05, (byte) 0xF2, (byte) 0xF4, 0x27, (byte) 0x9C, //15 	Sitar	Also be used as ShamisenBanjo
			0x01, 0x01, 0x18, 0x0F, (byte) 0xDF, (byte) 0xF8, 0x6A, 0x6D, //rhythm 1
			0x01, 0x01, 0x00, 0x00, (byte) 0xC8, (byte) 0xD8, (byte) 0xA7, 0x48, //rhythm 2
			0x05, 0x01, 0x00, 0x00, (byte) 0xF8, (byte) 0xAA, 0x59, 0x55  //rhythm 3
		};

		//-------------------------------------------------
		//  ym2423 - constructor
		//-------------------------------------------------
		public ym2423(ymfm_interface intf, final byte[] instrument_data /*= null */) {
			super(intf, (instrument_data != null) ? instrument_data : s_default_instruments);
		}
	}

	// ======================> ymf281

	//*********************************************************
	//  YMF281
	//*********************************************************
	static class ymf281 extends opll_base {

		// table below taken from https://github.com/plgDavid/misc/wiki/Copyright-free-OPLL(x)-ROM-patches
		private static final byte[] s_default_instruments = {
			// May 14th 2015 Hubert Lamontagne
			0x72, 0x21, 0x1A, 0x07, (byte) 0xF6, 0x64, 0x01, 0x16, // Clarinet ~~ Electric String 	Square wave with vibrato
			0x00, 0x10, 0x45, 0x00, (byte) 0xF6, (byte) 0x83, 0x73, 0x63, // Synth Bass ~~ Bow wow 	Triangular wave
			0x13, 0x01, (byte) 0x96, 0x00, (byte) 0xF1, (byte) 0xF4, 0x31, 0x23, // Piano ~~ Electric Guitar 	Despite of its name, same as Piano of YM2413.
			0x71, 0x21, 0x0B, 0x0F, (byte) 0xF9, 0x64, 0x70, 0x17, // Flute ~~ Organ 	Sine wave
			0x02, 0x21, 0x1E, 0x06, (byte) 0xF9, 0x76, 0x00, 0x28, // Square Wave ~~ Clarinet 	Same as ones of YM2413.
			0x00, 0x61, (byte) 0x82, 0x0E, (byte) 0xF9, 0x61, 0x20, 0x27, // Space Oboe ~~ Saxophone 	Saw wave with vibrato
			0x21, 0x61, 0x1B, 0x07, (byte) 0x84, (byte) 0x8F, 0x10, 0x07, // Trumpet ~~ Trumpet 	Same as ones of YM2413.
			0x37, 0x32, (byte) 0xCA, 0x02, 0x66, 0x64, 0x47, 0x29, // Wow Bell ~~ Street Organ 	Calliope
			0x41, 0x41, 0x07, 0x03, (byte) 0xF5, 0x70, 0x51, (byte) 0xF5, // Electric Guitar ~~ Synth Brass 	Same as Synthesizer of YM2413.
			0x36, 0x01, 0x5E, 0x07, (byte) 0xF2, (byte) 0xF3, (byte) 0xF7, (byte) 0xF7, // Vibes ~~ Electric Piano 	Simulate of Rhodes Piano
			0x00, 0x00, 0x18, 0x06, (byte) 0xC5, (byte) 0xF3, 0x20, (byte) 0xF2, // Bass ~~ Bass 	Electric bass
			0x17, (byte) 0x81, 0x25, 0x07, (byte) 0xF7, (byte) 0xF3, 0x21, (byte) 0xF7, // Vibraphone ~~ Vibraphone	Same as ones of YM2413.
			0x35, 0x64, 0x00, 0x00, (byte) 0xFF, (byte) 0xF3, 0x77, (byte) 0xF5, // Vibrato Bell ~~ Chime 	Bell
			0x11, 0x31, 0x00, 0x07, (byte) 0xDD, (byte) 0xF3, (byte) 0xFF, (byte) 0xFB, // Click Sine ~~ Tom Tom II 	Tom
			0x3A, 0x21, 0x00, 0x07, (byte) 0x95, (byte) 0x84, 0x0F, (byte) 0xF5, // Noise and Tone ~~ Noise 	for S.E.
			0x01, 0x01, 0x18, 0x0F, (byte) 0xDF, (byte) 0xF8, 0x6A, 0x6D, //rhythm 1
			0x01, 0x01, 0x00, 0x00, (byte) 0xC8, (byte) 0xD8, (byte) 0xA7, 0x48, //rhythm 2
			0x05, 0x01, 0x00, 0x00, (byte) 0xF8, (byte) 0xAA, 0x59, 0x55  //rhythm 3
		};

		//-------------------------------------------------
		//  ymf281 - constructor
		//-------------------------------------------------
		public ymf281(ymfm_interface intf, final byte[] instrument_data /* = null */) {
			super(intf, (instrument_data != null) ? instrument_data : s_default_instruments);
		}
	}

	// ======================> ds1001

	//*********************************************************
	//  DS1001
	//*********************************************************
	static class ds1001 extends opll_base {

		// table below taken from https://github.com/plgDavid/misc/wiki/Copyright-free-OPLL(x)-ROM-patches
		private static final byte[] s_default_instruments = {
			// May 15th 2015 Hubert Lamontagne & David Viens
			0x03, 0x21, 0x05, 0x06, (byte) 0xC8, (byte) 0x81, 0x42, 0x27, // Buzzy Bell
				0x13, 0x41, 0x14, 0x0D, (byte) 0xF8, (byte) 0xF7, 0x23, 0x12, // Guitar
				0x31, 0x11, 0x08, 0x08, (byte) 0xFA, (byte) 0xC2, 0x28, 0x22, // Wurly
				0x31, 0x61, 0x0C, 0x07, (byte) 0xF8, 0x64, 0x60, 0x27, // Flute
				0x22, 0x21, 0x1E, 0x06, (byte) 0xFF, 0x76, 0x00, 0x28, // Clarinet
				0x02, 0x01, 0x05, 0x00, (byte) 0xAC, (byte) 0xF2, 0x03, 0x02, // Synth
				0x21, 0x61, 0x1D, 0x07, (byte) 0x82, (byte) 0x8F, 0x10, 0x07, // Trumpet
				0x23, 0x21, 0x22, 0x17, (byte) 0xFF, 0x73, 0x00, 0x17, // Organ
				0x15, 0x11, 0x25, 0x00, 0x41, 0x71, 0x00, (byte) 0xF1, // Bells
				(byte) 0x95, 0x01, 0x10, 0x0F, (byte) 0xB8, (byte) 0xAA, 0x50, 0x02, // Vibes
				0x17, (byte) 0xC1, 0x5E, 0x07, (byte) 0xFA, (byte) 0xF8, 0x22, 0x12, // Vibraphone
				0x71, 0x23, 0x11, 0x06, 0x65, 0x74, 0x10, 0x16, // Tutti
				0x01, 0x02, (byte) 0xD3, 0x05, (byte) 0xF3, (byte) 0x92, (byte) 0x83, (byte) 0xF2, // Fretless
				0x61, 0x63, 0x0C, 0x00, (byte) 0xA4, (byte) 0xFF, 0x30, 0x06, // Synth Bass
				0x21, 0x62, 0x0D, 0x00, (byte) 0xA1, (byte) 0xFF, 0x50, 0x08, // Sweep
				0x01, 0x01, 0x18, 0x0F, (byte) 0xDF, (byte) 0xF8, 0x6A, 0x6D, //rhythm 1
				0x01, 0x01, 0x00, 0x00, (byte) 0xC8, (byte) 0xD8, (byte) 0xA7, 0x48, //rhythm 2
				0x05, 0x01, 0x00, 0x00, (byte) 0xF8, (byte) 0xAA, 0x59, 0x55  //rhythm 3
		};

		// constructor
		public ds1001(ymfm_interface intf, final byte[] instrument_data /*= null */) {
			super(intf, (instrument_data != null) ? instrument_data : s_default_instruments);
		}
	}

//*********************************************************
//  EXPLICIT INSTANTIATION
//*********************************************************

//	template class opl_registers_base<4>;
//	template class fm_engine_base<opl_registers_base<4>>;
}
