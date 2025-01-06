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

import vavi.sound.ymfm.ymfm.debug;
import vavi.sound.ymfm.ymfm.ymfm_interface;
import vavi.sound.ymfm.ymfm.ymfm_output;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;

import static vavi.sound.ymfm.ymfm.access_class.ACCESS_ADPCM_A;
import static vavi.sound.ymfm.ymfm.access_class.ACCESS_ADPCM_B;
import static vavi.sound.ymfm.ymfm.bitfield;
import static vavi.sound.ymfm.ymfm.clamp;
import static vavi.sound.ymfm.ymfm.debug.log_keyon;


public class adpcm {

	//*********************************************************
	// INTERFACE CLASSES
	//*********************************************************

	//*********************************************************
	// ADPCM "A" REGISTERS
	//*********************************************************

	// ======================> adpcm_a_registers

	//
	// ADPCM-A register map:
	//
	//      System-wide registers:
	//           00 x------- Dump (disable=1) or keyon (0) control
	//              --xxxxxx Mask of channels to dump or keyon
	//           01 --xxxxxx Total level
	//           02 xxxxxxxx Test register
	//        08-0D x------- Pan left
	//              -x------ Pan right
	//              ---xxxxx Instrument level
	//        10-15 xxxxxxxx Start address (low)
	//        18-1D xxxxxxxx Start address (high)
	//        20-25 xxxxxxxx End address (low)
	//        28-2D xxxxxxxx End address (high)
	//
	@Serdes
	static class adpcm_a_registers {

		// constants
		public static final int OUTPUTS = 2;
		public static final int CHANNELS = 6;
		public static final int REGISTERS = 0x30;
		public static final int ALL_CHANNELS = (1 << CHANNELS) - 1;

		// constructor
		adpcm_a_registers() {
		}

		/**
		 * reset - reset the register state
		 */
		public void reset() {
			Arrays.fill(m_regdata, 0, REGISTERS, 0);

			// initialize the pans to on by default, and max instrument volume;
			// some neogeo homebrews (for example ffeast) rely on this
			m_regdata[0x08] = m_regdata[0x09] = m_regdata[0x0a] =
				m_regdata[0x0b] = m_regdata[0x0c] = m_regdata[0x0d] = (byte) 0xdf;
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

		// map channel number to register offset
		public static int channel_offset(int chnum) {
			assert (chnum < CHANNELS);
			return chnum;
		}

		// direct read/write access
		public void write(int index, int data) {
			m_regdata[index] = data;
		}

		// system-wide registers
		public final int dump() {
			return bitfield(m_regdata[0x00], 7);
		}

		public final int dump_mask() {
			return bitfield(m_regdata[0x00], 0, 6);
		}

		public final int total_level() {
			return bitfield(m_regdata[0x01], 0, 6);
		}

		public final int test() {
			return m_regdata[0x02];
		}

		// per-channel registers
		public final int ch_pan_left(int choffs) {
			return bitfield(m_regdata[choffs + 0x08], 7);
		}

		public final int ch_pan_right(int choffs) {
			return bitfield(m_regdata[choffs + 0x08], 6);
		}

		public final int ch_instrument_level(int choffs) {
			return bitfield(m_regdata[choffs + 0x08], 0, 5);
		}

		public final int ch_start(int choffs) {
			return m_regdata[choffs + 0x10] | (m_regdata[choffs + 0x18] << 8);
		}

		public final int ch_end(int choffs) {
			return m_regdata[choffs + 0x20] | (m_regdata[choffs + 0x28] << 8);
		}

		// per-channel writes
		public void write_start(int choffs, int address) {
			write(choffs + 0x10, address);
			write(choffs + 0x18, address >> 8);
		}

		public void write_end(int choffs, int address) {
			write(choffs + 0x20, address);
			write(choffs + 0x28, address >> 8);
		}

		// internal state
		@Element
		private int[] m_regdata = new int[REGISTERS];         // register data
	}

    // ======================> adpcm_a_channel

	//*********************************************************
	// ADPCM "A" CHANNEL
	//*********************************************************
	static class adpcm_a_channel {

		/**
		 * adpcm_a_channel - constructor
		 */
		public adpcm_a_channel(adpcm_a_engine owner, int choffs, int addrshift) {
			m_choffs = choffs;
			m_address_shift = addrshift;
			m_playing = false;
			m_curnibble = 0;
			m_curbyte = 0;
			m_curaddress = 0;
			m_accumulator = 0;
			m_step_index = 0;
			m_regs = owner.regs();
			m_owner = owner;
		}

		/**
		 * reset - reset the channel state
		 */
		public void reset() {
			m_playing = false;
			m_curnibble = 0;
			m_curbyte = 0;
			m_curaddress = 0;
			m_accumulator = 0;
			m_step_index = 0;
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
		 * keyonoff - signal key on/off
		 */
		public void keyonoff(boolean on) {
			// QUESTION: repeated key ons restart the sample?
			m_playing = on;
			if (m_playing) {
				m_curaddress = m_regs.ch_start(m_choffs) << m_address_shift;
				m_curnibble = 0;
				m_curbyte = 0;
				m_accumulator = 0;
				m_step_index = 0;

				// don't log masked channels
				if (((debug.GLOBAL_ADPCM_A_CHANNEL_MASK >> m_choffs) & 1) != 0)
					log_keyon.log(Level.DEBUG, "KeyOn ADPCM-A%d: pan=%d%d start=%04X end=%04X level=%02X\n",
						m_choffs,
						m_regs.ch_pan_left(m_choffs),
						m_regs.ch_pan_right(m_choffs),
						m_regs.ch_start(m_choffs),
						m_regs.ch_end(m_choffs),
						m_regs.ch_instrument_level(m_choffs));
			}
		}

		static final int[] s_steps = {
			16, 17, 19, 21, 23, 25, 28,
			31, 34, 37, 41, 45, 50, 55,
			60, 66, 73, 80, 88, 97, 107,
			118, 130, 143, 157, 173, 190, 209,
			230, 253, 279, 307, 337, 371, 408,
			449, 494, 544, 598, 658, 724, 796,
			876, 963, 1060, 1166, 1282, 1411, 1552
		};

		static final int[] s_step_inc = {-1, -1, -1, -1, 2, 5, 7, 9};

		/**
		 * clock - master clocking function
		 */
		public boolean clock() {
			// if not playing, just output 0
			if (!m_playing) {
				m_accumulator = 0;
				return false;
			}

			// if we're about to read nibble 0, fetch the data
			int data;
			if (m_curnibble == 0) {
				// stop when we hit the end address; apparently only low 20 bits are used for
				// comparison on the YM2610: this affects sample playback in some games, for
				// example twinspri character select screen music will skip some samples if
				// this is not correct
				//
				// note also: end address is inclusive, so wait until we are about to fetch
				// the sample just after the end before stopping; this is needed for nitd's
				// jump sound, for example
				int end = (m_regs.ch_end(m_choffs) + 1) << m_address_shift;
				if (((m_curaddress ^ end) & 0xf_ffff) == 0) {
					m_playing = false;
					m_accumulator = 0;
					return true;
				}

				m_curbyte = m_owner.intf().ymfm_external_read(ACCESS_ADPCM_A, m_curaddress++);
				data = m_curbyte >> 4;
				m_curnibble = 1;
			}

			// otherwise just extract from the previosuly-fetched byte
			else {
				data = m_curbyte & 0xf;
				m_curnibble = 0;
			}

			// compute the ADPCM delta
			int delta = (2 * bitfield(data, 0, 3) + 1) * s_steps[m_step_index] / 8;
			if (bitfield(data, 3) != 0)
				delta = -delta;

			// the 12-bit accumulator wraps on the ym2610 and ym2608 (like the msm5205)
			m_accumulator = (m_accumulator + delta) & 0xfff;

			// adjust ADPCM step
			m_step_index = clamp(m_step_index + s_step_inc[bitfield(data, 0, 3)], 0, 48);

			return false;
		}

		/**
		 * output - return the computed output value, with
		 * panning applied
		 */
		//	template<int NumOutputs>
		public final void output(ymfm_output output) {
			// volume combines instrument and total levels
			int vol = (m_regs.ch_instrument_level(m_choffs) ^ 0x1f) + (m_regs.total_level() ^ 0x3f);

			// if combined is maximum, don't add to outputs
			if (vol >= 63)
				return;

			// convert into a shift and a multiplier
			// QUESTION: verify this from other sources
			int mul = 15 - (vol & 7);
			int shift = 4 + 1 + (vol >> 3);

			// m_accumulator is a 12-bit value; shift up to sign-extend;
			// the downshift is incorporated into 'shift'
			int value = (((m_accumulator << 4) * mul) >>shift) &~3;

			// apply to left/right as appropriate
			if (output.getNumOutputs() == 1 || m_regs.ch_pan_left(m_choffs) != 0)
				output.data[0] += value;
			if (output.getNumOutputs() > 1 && m_regs.ch_pan_right(m_choffs) != 0)
				output.data[1] += value;
		}

		// internal state
		private final int m_choffs;              // channel offset
		private final int m_address_shift;       // address bits shift-left
		@Element(sequence = 0)
		private boolean m_playing;                   // currently playing?
		@Element(sequence = 1)
		private int m_curnibble;                 // index of the current nibble
		@Element(sequence = 2)
		private int m_curbyte;                   // current byte of data
		@Element(sequence = 3)
		private int m_curaddress;                // current address
		@Element(sequence = 4)
		private int m_accumulator;                // accumulator
		@Element(sequence = 5)
		private int m_step_index;                 // index in the stepping table
		private adpcm_a_registers m_regs;            // reference to registers
		private adpcm_a_engine m_owner;              // reference to our owner
	}

    // ======================> adpcm_a_engine

	//*********************************************************
	// ADPCM "A" ENGINE
	//*********************************************************
	static class adpcm_a_engine {

		static int NumOutput;

		public static final int CHANNELS = adpcm_a_registers.CHANNELS;

		/**
		 * adpcm_a_engine - constructor
		 */
		public adpcm_a_engine(ymfm_interface intf, int addrshift) {
			m_intf = intf;

			// create the channels
			for (int chnum = 0; chnum < CHANNELS; chnum++)
				m_channel[chnum] = new adpcm_a_channel(this, chnum, addrshift);
		}

		/**
		 * reset - reset the engine state
		 */
		public void reset() {
			// reset register state
			m_regs.reset();

			// reset each channel
			for (var chan : m_channel)
				chan.reset();
		}

		/**
		 * save_restore - save or restore the data
		 */
		public void save(OutputStream os) throws IOException {
			// save register state
			m_regs.save(os);

			// save channel state
			for (int chnum = 0; chnum < CHANNELS; chnum++)
				m_channel[chnum].save(os);
		}

		/**
		 * save_restore - save or restore the data
		 */
		public void restore(InputStream is) throws IOException {
			// save register state
			m_regs.restore(is);

			// save channel state
			for (int chnum = 0; chnum < CHANNELS; chnum++)
				m_channel[chnum].restore(is);
		}

		/**
		 * clock - master clocking function
		 */
		public int clock(int chanmask) {
			// clock each channel, setting a bit in result if it finished
			int result = 0;
			for (int chnum = 0; chnum < CHANNELS; chnum++)
				if (bitfield(chanmask, chnum) != 0)
					if (m_channel[chnum].clock())
						result |= 1 << chnum;

			// return the bitmask of completed samples
			return result;
		}

		/**
		 * update - master update function
		 */
		//	template<int NumOutputs>
		public void output(ymfm_output output, int chanmask) {
			// mask out some channels for debug purposes
			chanmask &= debug.GLOBAL_ADPCM_A_CHANNEL_MASK;

			// compute the output of each channel
			for (int chnum = 0; chnum < CHANNELS; chnum++)
				if (bitfield(chanmask, chnum) != 0)
					m_channel[chnum].output(output);
		}

//	template void adpcm_a_engine.output<1>(ymfm_output<1> output, int chanmask);
//	template void adpcm_a_engine.output<2>(ymfm_output<2> output, int chanmask);

		/**
		 * write - handle writes to the ADPCM-A registers
		 */
		public void write(int regnum, byte data) {
			// store the raw value to the register array;
			// most writes are passive, consumed only when needed
			m_regs.write(regnum, data);

			// actively handle writes to the control register
			if (regnum == 0x00)
				for (int chnum = 0; chnum < CHANNELS; chnum++)
					if (bitfield(data, chnum) != 0)
						m_channel[chnum].keyonoff(bitfield(~data, 7) != 0);
		}

		// set the start/end address for a channel (for hardcoded YM2608 percussion)
		public void set_start_end(int chnum, int start, int end) {
			int choffs = adpcm_a_registers.channel_offset(chnum);
			m_regs.write_start(choffs, start);
			m_regs.write_end(choffs, end);
		}

		// return a reference to our interface
		public ymfm_interface intf() {
			return m_intf;
		}

		// return a reference to our registers
		public adpcm_a_registers regs() {
			return m_regs;
		}

		// internal state
		private ymfm_interface m_intf;                                 // reference to the interface
		private adpcm_a_channel[] m_channel = new adpcm_a_channel[CHANNELS]; // array of channels
		private adpcm_a_registers m_regs;                             // registers
	}

	// ======================> adpcm_b_registers

	//*********************************************************
	// ADPCM "B" REGISTERS
	//*********************************************************

	//
	// ADPCM-B register map:
	//
	//      System-wide registers:
	//           00 x------- Start of synthesis/analysis
	//              -x------ Record
	//              --x----- External/manual driving
	//              ---x---- Repeat playback
	//              ----x--- Speaker off
	//              -------x Reset
	//           01 x------- Pan left
	//              -x------ Pan right
	//              ----x--- Start conversion
	//              -----x-- DAC enable
	//              ------x- DRAM access (1=8-bit granularity; 0=1-bit)
	//              -------x RAM/ROM (1=ROM, 0=RAM)
	//           02 xxxxxxxx Start address (low)
	//           03 xxxxxxxx Start address (high)
	//           04 xxxxxxxx End address (low)
	//           05 xxxxxxxx End address (high)
	//           06 xxxxxxxx Prescale value (low)
	//           07 -----xxx Prescale value (high)
	//           08 xxxxxxxx CPU data/buffer
	//           09 xxxxxxxx Delta-N frequency scale (low)
	//           0a xxxxxxxx Delta-N frequency scale (high)
	//           0b xxxxxxxx Level control
	//           0c xxxxxxxx Limit address (low)
	//           0d xxxxxxxx Limit address (high)
	//           0e xxxxxxxx DAC data [YM2608/10]
	//           0f xxxxxxxx PCM data [YM2608/10]
	//           0e xxxxxxxx DAC data high [Y8950]
	//           0f xx------ DAC data low [Y8950]
	//           10 -----xxx DAC data exponent [Y8950]
	//
	@Serdes
	static class adpcm_b_registers {

		// constants
		public static final int REGISTERS = 0x11;

		// constructor
		public adpcm_b_registers() {
		}

		/**
		 * reset - reset the register state
		 */
		public void reset() {
			Arrays.fill(m_regdata, 0, REGISTERS, 0);

			// default limit to wide open
			m_regdata[0x0c] = m_regdata[0x0d] = 0xff;
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

		// direct read/write access
		public void write(int index, int data) {
			m_regdata[index] = data;
		}

		// system-wide registers
		public final int execute() {
			return bitfield(m_regdata[0x00], 7);
		}

		public final int record() {
			return bitfield(m_regdata[0x00], 6);
		}

		public final int external() {
			return bitfield(m_regdata[0x00], 5);
		}

		public final int repeat() {
			return bitfield(m_regdata[0x00], 4);
		}

		public final int speaker() {
			return bitfield(m_regdata[0x00], 3);
		}

		public final int resetflag() {
			return bitfield(m_regdata[0x00], 0);
		}

		public final int pan_left() {
			return bitfield(m_regdata[0x01], 7);
		}

		public final int pan_right() {
			return bitfield(m_regdata[0x01], 6);
		}

		public final int start_conversion() {
			return bitfield(m_regdata[0x01], 3);
		}

		public final int dac_enable() {
			return bitfield(m_regdata[0x01], 2);
		}

		public final int dram_8bit() {
			return bitfield(m_regdata[0x01], 1);
		}

		public final int rom_ram() {
			return bitfield(m_regdata[0x01], 0);
		}

		public final int start() {
			return m_regdata[0x02] | (m_regdata[0x03] << 8);
		}

		public final int end() {
			return m_regdata[0x04] | (m_regdata[0x05] << 8);
		}

		public final int prescale() {
			return m_regdata[0x06] | (bitfield(m_regdata[0x07], 0, 3) << 8);
		}

		public final int cpudata() {
			return m_regdata[0x08];
		}

		public final int delta_n() {
			return m_regdata[0x09] | (m_regdata[0x0a] << 8);
		}

		public final int level() {
			return m_regdata[0x0b];
		}

		public final int limit() {
			return m_regdata[0x0c] | (m_regdata[0x0d] << 8);
		}

		public final int dac() {
			return m_regdata[0x0e];
		}

		public final int pcm() {
			return m_regdata[0x0f];
		}

		// internal state
		@Element
		private int[] m_regdata = new int[REGISTERS];         // register data
	}

	// ======================> adpcm_b_channel

	//*********************************************************
	// ADPCM "B" CHANNEL
	//*********************************************************
	static class adpcm_b_channel {

		static final int STEP_MIN = 127;
		static final int STEP_MAX = 24576;

		public static final byte STATUS_EOS = 0x01;
		public static final byte STATUS_BRDY = 0x02;
		public static final byte STATUS_PLAYING = 0x04;

		/**
		 * adpcm_b_channel - constructor
		 */
		public adpcm_b_channel(adpcm_b_engine owner, int addrshift) {
			m_address_shift = addrshift;
			m_status = STATUS_BRDY;
			m_curnibble = 0;
			m_curbyte = 0;
			m_dummy_read = 0;
			m_position = 0;
			m_curaddress = 0;
			m_accumulator = 0;
			m_prev_accum = 0;
			m_adpcm_step = STEP_MIN;
			m_regs = owner.regs();
			m_owner = owner;
		}

		/**
		 * reset - reset the channel state
		 */
		public void reset() {
			m_status = STATUS_BRDY;
			m_curnibble = 0;
			m_curbyte = 0;
			m_dummy_read = 0;
			m_position = 0;
			m_curaddress = 0;
			m_accumulator = 0;
			m_prev_accum = 0;
			m_adpcm_step = STEP_MIN;
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

		// signal key on/off
//		public void keyonoff(boolean on)

		static final int[] s_step_scale = {57, 57, 57, 57, 77, 102, 128, 153};

		/**
		 * clock - master clocking function
		 */
		public void clock() {
			// only process if active and not recording (which we don't support)
			if (m_regs.execute() == 0 || m_regs.record() != 0 || (m_status & STATUS_PLAYING) == 0) {
				m_status &= ~STATUS_PLAYING;
				return;
			}

			// otherwise, advance the step
			int position = m_position + m_regs.delta_n();
			m_position = position;
			if (position < 0x1_0000)
				return;

			// if we're about to process nibble 0, fetch sample
			if (m_curnibble == 0) {
				// playing from RAM/ROM
				if (m_regs.external() != 0)
					m_curbyte = m_owner.intf().ymfm_external_read(ACCESS_ADPCM_B, m_curaddress);
			}

			// extract the nibble from our current byte
			int data = (m_curbyte << (4 * m_curnibble)) >> 4;
			m_curnibble ^= 1;

			// we just processed the last nibble
			if (m_curnibble == 0) {
				// if playing from RAM/ROM, check the end/limit address or advance
				if (m_regs.external() != 0) {
					// handle the sample end, either repeating or stopping
					if (at_end()) {
						// if repeating, go back to the start
						if (m_regs.repeat() != 0)
							load_start();

							// otherwise, done; set the EOS bit
						else {
							m_accumulator = 0;
							m_prev_accum = 0;
							m_status = (m_status & ~STATUS_PLAYING) | STATUS_EOS;
							log_keyon.log(Level.DEBUG, "%s\n", "ADPCM EOS");
							return;
						}
					}

					// wrap at the limit address
					else if (at_limit())
						m_curaddress = 0;

						// otherwise, advance the current address
					else {
						m_curaddress++;
						m_curaddress &= 0xffffff;
					}
				}

				// if CPU-driven, copy the next byte and request more
				else {
					m_curbyte = m_regs.cpudata();
					m_status |= STATUS_BRDY;
				}
			}

			// remember previous value for interpolation
			m_prev_accum = m_accumulator;

			// forecast to next forecast: 1/8, 3/8, 5/8, 7/8, 9/8, 11/8, 13/8, 15/8
			int delta = (2 * bitfield(data, 0, 3) + 1) * m_adpcm_step / 8;
			if (bitfield(data, 3) != 0)
				delta = -delta;

			// add and clamp to 16 bits
			m_accumulator = clamp(m_accumulator + delta, -32768, 32767);

			// scale the ADPCM step: 0.9, 0.9, 0.9, 0.9, 1.2, 1.6, 2.0, 2.4
			m_adpcm_step = clamp((m_adpcm_step * s_step_scale[bitfield(data, 0, 3)]) / 64, STEP_MIN, STEP_MAX);
		}

		/**
		 * output - return the computed output value, with
		 * panning applied
		 */
		//template<int NumOutputs>
		public final void output(ymfm_output output, int rshift) {
			// mask out some channels for debug purposes
			if ((debug.GLOBAL_ADPCM_B_CHANNEL_MASK & 1) == 0)
				return;

			// do a linear interpolation between samples
			int result = (m_prev_accum * ((m_position ^ 0xffff) + 1) + m_accumulator * m_position) >>16;

			// apply volume (level) in a linear fashion and reduce
			result = (result * m_regs.level()) >>(8 + rshift);

			// apply to left/right
			if (output.getNumOutputs() == 1 || m_regs.pan_left() != 0)
				output.data[0] += result;
			if (output.getNumOutputs() > 1 && m_regs.pan_right() != 0)
				output.data[1] += result;
		}

		// return the status register
		public final int status() {
			return m_status;
		}

		/**
		 * read - handle special register reads
		 */
		public int read(int regnum) {
			int result = 0;

			// register 8 reads over the bus under some conditions
			if (regnum == 0x08 && m_regs.execute() == 0 && m_regs.record() == 0 && m_regs.external() != 0) {
				// two dummy reads are consumed first
				if (m_dummy_read != 0) {
					load_start();
					m_dummy_read--;
				}

				// read the data
				else {
					// read from outside of the chip
					result = m_owner.intf().ymfm_external_read(ACCESS_ADPCM_B, m_curaddress++);

					// did we hit the end? if so, signal EOS
					if (at_end()) {
						m_status = STATUS_EOS | STATUS_BRDY;
						log_keyon.log(Level.DEBUG, "%s\n", "ADPCM EOS");
					} else {
						// signal ready
						m_status = STATUS_BRDY;
					}

					// wrap at the limit address
					if (at_limit())
						m_curaddress = 0;
				}
			}
			return result;
		}

		/**
		 * write - handle special register writes
		 */
		public void write(int regnum, int value) {
			// register 0 can do a reset; also use writes here to reset the
			// dummy read counter
			if (regnum == 0x00) {
				if (m_regs.execute() == 0) {
					load_start();

					// don't log masked channels
					if ((debug.GLOBAL_ADPCM_B_CHANNEL_MASK & 1) != 0)
						log_keyon.log(Level.DEBUG, "KeyOn ADPCM-B: rep=%d spk=%d pan=%d%d dac=%d 8b=%d rom=%d ext=%d rec=%d start=%04X end=%04X pre=%04X dn=%04X lvl=%02X lim=%04X\n",
							m_regs.repeat(),
							m_regs.speaker(),
							m_regs.pan_left(),
							m_regs.pan_right(),
							m_regs.dac_enable(),
							m_regs.dram_8bit(),
							m_regs.rom_ram(),
							m_regs.external(),
							m_regs.record(),
							m_regs.start(),
							m_regs.end(),
							m_regs.prescale(),
							m_regs.delta_n(),
							m_regs.level(),
							m_regs.limit());
				} else
					m_status &= ~STATUS_EOS;
				if (m_regs.resetflag() == 0)
					reset();
				if (m_regs.external() == 0)
					m_dummy_read = 2;
			}

			// register 8 writes over the bus under some conditions
			else if (regnum == 0x08) {
				// if writing from the CPU during execute, clear the ready flag
				if (m_regs.execute() != 0 && m_regs.record() == 0 && m_regs.external() == 0)
					m_status &= ~STATUS_BRDY;

					// if writing during "record", pass through as data
				else if (m_regs.execute() == 0 && m_regs.record() != 0 && m_regs.external() != 0) {
					// clear out dummy reads and set start address
					if (m_dummy_read != 0) {
						load_start();
						m_dummy_read = 0;
					}

					// did we hit the end? if so, signal EOS
					if (at_end()) {
						log_keyon.log(Level.DEBUG, "%s\n", "ADPCM EOS");
						m_status = STATUS_EOS | STATUS_BRDY;
					}

					// otherwise, write the data and signal ready
					else {
						m_owner.intf().ymfm_external_write(ACCESS_ADPCM_B, m_curaddress++, value);
						m_status = STATUS_BRDY;
					}
				}
			}
		}

		/**
		 * address_shift - compute the current address
		 * shift amount based on register settings
		 */
		private final int address_shift() {
			// if a constant address shift, just provide that
			if (m_address_shift != 0)
				return m_address_shift;

			// if ROM or 8-bit DRAM, shift is 5 bits
			if (m_regs.rom_ram() != 0)
				return 5;
			if (m_regs.dram_8bit() != 0)
				return 5;

			// otherwise, shift is 2 bits
			return 2;
		}

		/**
		 * load_start - load the start address and
		 * initialize the state
		 */
		private void load_start() {
			m_status = (m_status & ~STATUS_EOS) | STATUS_PLAYING;
			m_curaddress = m_regs.external() != 0 ? (m_regs.start() << address_shift()) : 0;
			m_curnibble = 0;
			m_curbyte = 0;
			m_position = 0;
			m_accumulator = 0;
			m_prev_accum = 0;
			m_adpcm_step = STEP_MIN;
		}

		// limit checker; stops at the last byte of the chunk described by address_shift()
		private final boolean at_limit() {
			return (m_curaddress == (((m_regs.limit() + 1) << address_shift()) - 1));
		}

		// end checker; stops at the last byte of the chunk described by address_shift()
		private final boolean at_end() {
			return (m_curaddress == (((m_regs.end() + 1) << address_shift()) - 1));
		}

		// internal state
		private final int m_address_shift; // address bits shift-left
		@Element(sequence = 0)
		private int m_status;              // currently playing?
		@Element(sequence = 1)
		private int m_curnibble;           // index of the current nibble
		@Element(sequence = 2)
		private int m_curbyte;             // current byte of data
		@Element(sequence = 3)
		private int m_dummy_read;          // dummy read tracker
		@Element(sequence = 4)
		private int m_position;            // current fractional position
		@Element(sequence = 5)
		private int m_curaddress;          // current address
		@Element(sequence = 6)
		private int m_accumulator;          // accumulator
		@Element(sequence = 7)
		private int m_prev_accum;           // previous accumulator (for linear interp)
		@Element(sequence = 8)
		private int m_adpcm_step;           // next forecast
		private adpcm_b_registers m_regs;      // reference to registers
		private adpcm_b_engine m_owner;        // reference to our owner
	}

    // ======================> adpcm_b_engine

	//*********************************************************
	// ADPCM "B" ENGINE
	//*********************************************************
	static class adpcm_b_engine {

		static int NumOutputs;

		/**
		 * adpcm_b_engine - constructor
		 */
		public adpcm_b_engine(ymfm_interface intf, int addrshift /* = 0 */) {
			m_intf = intf;

			// create the channel (only one supported for now, but leaving possibilities open)
			m_channel = new adpcm_b_channel(this, addrshift);
		}

		/**
		 * reset - reset the engine state
		 */
		public void reset() {
			// reset registers
			m_regs.reset();

			// reset each channel
			m_channel.reset();
		}

		/**
		 * save_restore - save or restore the data
		 */
		public void save(OutputStream os) throws IOException {
			// save our state
			m_regs.save(os);

			// save channel state
			m_channel.save(os);
		}

		/**
		 * save_restore - save or restore the data
		 */
		public void restore(InputStream is) throws IOException {
			// save our state
			m_regs.restore(is);

			// save channel state
			m_channel.restore(is);
		}

		/**
		 * clock - master clocking function
		 */
		public void clock() {
			// clock each channel, setting a bit in result if it finished
			m_channel.clock();
		}

		/**
		 * output - master output function
		 */
		//	template<int NumOutputs>
		public void output(ymfm_output output, int rshift) {
			// compute the output of each channel
			m_channel.output(output, rshift);
		}

//	template void adpcm_b_engine.output<1>(ymfm_output<1> output, int rshift);
//	template void adpcm_b_engine.output<2>(ymfm_output<2> output, int rshift);

		// read from the ADPCM-B registers
		public int read(int regnum) {
			return m_channel.read(regnum);
		}

		/**
		 * write - handle writes to the ADPCM-B registers
		 */
		public void write(int regnum, int data) {
			// store the raw value to the register array;
			// most writes are passive, consumed only when needed
			m_regs.write(regnum, data);

			// let the channel handle any special writes
			m_channel.write(regnum, data);
		}

		// status
		public final int status() {
			return m_channel.m_owner.status();
		}

		// return a reference to our interface
		public ymfm_interface intf() {
			return m_intf;
		}

		// return a reference to our registers
		public adpcm_b_registers regs() {
			return m_regs;
		}

		// internal state
		private ymfm_interface m_intf;                     // reference to our interface
		private adpcm_b_channel m_channel; // channel pointer
		private adpcm_b_registers m_regs;                   // registers
	}
}
