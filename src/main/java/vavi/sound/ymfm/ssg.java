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


//*********************************************************
//  OVERRIDE INTERFACE
//*********************************************************

// ======================> ssg_override

import vavi.sound.ymfm.debug.ymfm_interface;
import vavi.sound.ymfm.debug.ymfm_saved_state;


// this class describes a simple interface to allow the internal SSG to be
// overridden with another implementation
interface ssg_override
{

	// reset our status
	void ssg_reset();

	// read/write to the SSG registers
	byte ssg_read(int regnum);
	void ssg_write(int regnum, byte data);

	// notification when the prescale has changed
	void ssg_prescale_changed();
}


//*********************************************************
//  REGISTER CLASS
//*********************************************************

// ======================> ssg_registers

//
// SSG register map:
//
//      System-wide registers:
//           06 ---xxxxx Noise period
//           07 x------- I/O B in(0) or out(1)
//              -x------ I/O A in(0) or out(1)
//              --x----- Noise enable(0) or disable(1) for channel C
//              ---x---- Noise enable(0) or disable(1) for channel B
//              ----x--- Noise enable(0) or disable(1) for channel A
//              -----x-- Tone enable(0) or disable(1) for channel C
//              ------x- Tone enable(0) or disable(1) for channel B
//              -------x Tone enable(0) or disable(1) for channel A
//           0B xxxxxxxx Envelope period fine
//           0C xxxxxxxx Envelope period coarse
//           0D ----x--- Envelope shape: continue
//              -----x-- Envelope shape: attack/decay
//              ------x- Envelope shape: alternate
//              -------x Envelope shape: hold
//           0E xxxxxxxx 8-bit parallel I/O port A
//           0F xxxxxxxx 8-bit parallel I/O port B
//
//      Per-channel registers:
//     00,02,04 xxxxxxxx Tone period (fine) for channel A,B,C
//     01,03,05 ----xxxx Tone period (coarse) for channel A,B,C
//     08,09,0A ---x---- Mode: fixed(0) or variable(1) for channel A,B,C
//              ----xxxx Amplitude for channel A,B,C
//
class ssg_registers
{

	// constants
	public static final int OUTPUTS = 3;
	public static final int CHANNELS = 3;
	public static final int REGISTERS = 0x10;
	public static final int ALL_CHANNELS = (1 << CHANNELS) - 1;

	// constructor
	public ssg_registers() { }

	// reset to initial state
	public void reset();

	// save/restore
	public void save_restore(ymfm_saved_state state);

	// direct read/write access
	byte read(int index) { return m_regdata[index]; }
	void write(int index, byte data) { m_regdata[index] = data; }

	// system-wide registers
	public final int noise_period()                       { return bitfield(m_regdata[0x06], 0, 5); }
	public final int io_b_out()                            { return bitfield(m_regdata[0x07], 7); }
	public final int io_a_out()                            { return bitfield(m_regdata[0x07], 6); }
	public final int envelope_period()                     { return m_regdata[0x0b] | (m_regdata[0x0c] << 8); }
	public final int envelope_continue()                   { return bitfield(m_regdata[0x0d], 3); }
	public final int envelope_attack()                     { return bitfield(m_regdata[0x0d], 2); }
	public final int envelope_alternate()                  { return bitfield(m_regdata[0x0d], 1); }
	public final int envelope_hold()                       { return bitfield(m_regdata[0x0d], 0); }
	public final int io_a_data()                           { return m_regdata[0x0e]; }
	public final int io_b_data()                           { return m_regdata[0x0f]; }

	// per-channel registers
	public final int ch_noise_enable_n(int choffs)      { return bitfield(m_regdata[0x07], 3 + choffs); }
	public final int ch_tone_enable_n(int choffs)       { return bitfield(m_regdata[0x07], 0 + choffs); }
	public final int ch_tone_period(int choffs)       { return m_regdata[0x00 + 2 * choffs] | (bitfield(m_regdata[0x01 + 2 * choffs], 0, 4) << 8); }
	public final int ch_envelope_enable(int choffs)   { return bitfield(m_regdata[0x08 + choffs], 4); }
	public final int ch_amplitude(int choffs)         { return bitfield(m_regdata[0x08 + choffs], 0, 4); }


	// internal state
	private byte[] m_regdata = new byte[REGISTERS];         // register data
};


// ======================> ssg_engine

class ssg_engine
{

	public static final int OUTPUTS = ssg_registers.OUTPUTS;
	public static final int CHANNELS = ssg_registers.CHANNELS;
	public static final int CLOCK_DIVIDER = 8;

	using output_data = ymfm_output<OUTPUTS>;

	// constructor
	public ssg_engine(ymfm_interface intf);

	// configure an override
	public void override(ssg_override override) { m_override = override; }

	// reset our status
	public void reset();

	// save/restore
	public void save_restore(ymfm_saved_state state);

	// master clocking function
	public void clock();

	// compute sum of channel outputs
	public void output(output_data output);

	// read/write to the SSG registers
	public byte read(int regnum);
	public void write(int regnum, byte data);

	// return a reference to our interface
	public ymfm_interface intf() { return m_intf; }

	// return a reference to our registers
	public ssg_registers regs() { return m_regs; }

	// true if we are overridden
	public final boolean overridden()  { return (m_override != nullptr); }

	// indicate the prescale has changed
	public void prescale_changed() { if (m_override != nullptr) m_override.ssg_prescale_changed(); }


	// internal state
	private ymfm_interface m_intf;                   // reference to the interface
	private int[] m_tone_count = new int[3];               // current tone counter
	private int[] m_tone_state = new int[3];               // current tone state
	private int m_envelope_count;              // envelope counter
	private int m_envelope_state;              // envelope state
	private int m_noise_count;                 // current noise counter
	private int m_noise_state;                 // current noise state
	private ssg_registers m_regs;                   // registers
	private ssg_override m_override;               // override interface
}
