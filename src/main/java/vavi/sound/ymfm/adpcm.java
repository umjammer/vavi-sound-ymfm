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

#ifndef YMFM_ADPCM_H
#define YMFM_ADPCM_H

#pragma once

#include "ymfm.h"

package vavi.sound.ymfm;


//*********************************************************
//  INTERFACE CLASSES
//*********************************************************

// forward declarations
class adpcm_a_engine;
class adpcm_b_engine;


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
class adpcm_a_registers
{
public:
	// constants
	static final int OUTPUTS = 2;
	static final int CHANNELS = 6;
	static final int REGISTERS = 0x30;
	static final int ALL_CHANNELS = (1 << CHANNELS) - 1;

	// constructor
	adpcm_a_registers() { }

	// reset to initial state
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// map channel number to register offset
	static final int channel_offset(int chnum)
	{
		assert(chnum < CHANNELS);
		return chnum;
	}

	// direct read/write access
	void write(int index, byte data) { m_regdata[index] = data; }

	// system-wide registers
	int dump() final                               { return bitfield(m_regdata[0x00], 7); }
	int dump_mask() final                          { return bitfield(m_regdata[0x00], 0, 6); }
	int total_level() final                        { return bitfield(m_regdata[0x01], 0, 6); }
	int test() final                               { return m_regdata[0x02]; }

	// per-channel registers
	int ch_pan_left(int choffs) final         { return bitfield(m_regdata[choffs + 0x08], 7); }
	int ch_pan_right(int choffs) final        { return bitfield(m_regdata[choffs + 0x08], 6); }
	int ch_instrument_level(int choffs) final { return bitfield(m_regdata[choffs + 0x08], 0, 5); }
	int ch_start(int choffs) final            { return m_regdata[choffs + 0x10] | (m_regdata[choffs + 0x18] << 8); }
	int ch_end(int choffs) final              { return m_regdata[choffs + 0x20] | (m_regdata[choffs + 0x28] << 8); }

	// per-channel writes
	void write_start(int choffs, int address)
	{
		write(choffs + 0x10, address);
		write(choffs + 0x18, address >> 8);
	}
	void write_end(int choffs, int address)
	{
		write(choffs + 0x20, address);
		write(choffs + 0x28, address >> 8);
	}

private:
	// internal state
	byte m_regdata[REGISTERS];         // register data
};


// ======================> adpcm_a_channel

class adpcm_a_channel
{
public:
	// constructor
	adpcm_a_channel(adpcm_a_engine &owner, int choffs, int addrshift);

	// reset the channel state
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// signal key on/off
	void keyonoff(boolean on);

	// master clockingfunction
	boolean clock();

	// return the computed output value, with panning applied
	template<int NumOutputs>
	void output(ymfm_output<NumOutputs> &output) final;

private:
	// internal state
	int final m_choffs;              // channel offset
	int final m_address_shift;       // address bits shift-left
	int m_playing;                   // currently playing?
	int m_curnibble;                 // index of the current nibble
	int m_curbyte;                   // current byte of data
	int m_curaddress;                // current address
	int m_accumulator;                // accumulator
	int m_step_index;                 // index in the stepping table
	adpcm_a_registers &m_regs;            // reference to registers
	adpcm_a_engine &m_owner;              // reference to our owner
};


// ======================> adpcm_a_engine

class adpcm_a_engine
{
public:
	static final int CHANNELS = adpcm_a_registers.CHANNELS;

	// constructor
	adpcm_a_engine(ymfm_interface &intf, int addrshift);

	// reset our status
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// master clocking function
	int clock(int chanmask);

	// compute sum of channel outputs
	template<int NumOutputs>
	void output(ymfm_output<NumOutputs> &output, int chanmask);

	// write to the ADPCM-A registers
	void write(int regnum, byte data);

	// set the start/end address for a channel (for hardcoded YM2608 percussion)
	void set_start_end(byte chnum, int start, int end)
	{
		int choffs = adpcm_a_registers.channel_offset(chnum);
		m_regs.write_start(choffs, start);
		m_regs.write_end(choffs, end);
	}

	// return a reference to our interface
	ymfm_interface &intf() { return m_intf; }

	// return a reference to our registers
	adpcm_a_registers &regs() { return m_regs; }

private:
	// internal state
	ymfm_interface &m_intf;                                 // reference to the interface
	std.unique_ptr<adpcm_a_channel> m_channel[CHANNELS]; // array of channels
	adpcm_a_registers m_regs;                             // registers
};


// ======================> adpcm_b_registers

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
class adpcm_b_registers
{
public:
	// constants
	static final int REGISTERS = 0x11;

	// constructor
	adpcm_b_registers() { }

	// reset to initial state
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// direct read/write access
	void write(int index, byte data) { m_regdata[index] = data; }

	// system-wide registers
	int execute() final          { return bitfield(m_regdata[0x00], 7); }
	int record() final           { return bitfield(m_regdata[0x00], 6); }
	int external() final         { return bitfield(m_regdata[0x00], 5); }
	int repeat() final           { return bitfield(m_regdata[0x00], 4); }
	int speaker() final          { return bitfield(m_regdata[0x00], 3); }
	int resetflag() final        { return bitfield(m_regdata[0x00], 0); }
	int pan_left() final         { return bitfield(m_regdata[0x01], 7); }
	int pan_right() final        { return bitfield(m_regdata[0x01], 6); }
	int start_conversion() final { return bitfield(m_regdata[0x01], 3); }
	int dac_enable() final       { return bitfield(m_regdata[0x01], 2); }
	int dram_8bit() final        { return bitfield(m_regdata[0x01], 1); }
	int rom_ram() final          { return bitfield(m_regdata[0x01], 0); }
	int start() final            { return m_regdata[0x02] | (m_regdata[0x03] << 8); }
	int end() final              { return m_regdata[0x04] | (m_regdata[0x05] << 8); }
	int prescale() final         { return m_regdata[0x06] | (bitfield(m_regdata[0x07], 0, 3) << 8); }
	int cpudata() final          { return m_regdata[0x08]; }
	int delta_n() final          { return m_regdata[0x09] | (m_regdata[0x0a] << 8); }
	int level() final            { return m_regdata[0x0b]; }
	int limit() final            { return m_regdata[0x0c] | (m_regdata[0x0d] << 8); }
	int dac() final              { return m_regdata[0x0e]; }
	int pcm() final              { return m_regdata[0x0f]; }

private:
	// internal state
	byte m_regdata[REGISTERS];         // register data
};


// ======================> adpcm_b_channel

class adpcm_b_channel
{
	static final int STEP_MIN = 127;
	static final int STEP_MAX = 24576;

public:
	static final byte STATUS_EOS = 0x01;
	static final byte STATUS_BRDY = 0x02;
	static final byte STATUS_PLAYING = 0x04;

	// constructor
	adpcm_b_channel(adpcm_b_engine &owner, int addrshift);

	// reset the channel state
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// signal key on/off
	void keyonoff(boolean on);

	// master clocking function
	void clock();

	// return the computed output value, with panning applied
	template<int NumOutputs>
	void output(ymfm_output<NumOutputs> &output, int rshift) final;

	// return the status register
	byte status() final { return m_status; }

	// handle special register reads
	byte read(int regnum);

	// handle special register writes
	void write(int regnum, byte value);

private:
	// helper - return the current address shift
	int address_shift() final;

	// load the start address
	void load_start();

	// limit checker; stops at the last byte of the chunk described by address_shift()
	boolean at_limit() final { return (m_curaddress == (((m_regs.limit() + 1) << address_shift()) - 1)); }

	// end checker; stops at the last byte of the chunk described by address_shift()
	boolean at_end() final { return (m_curaddress == (((m_regs.end() + 1) << address_shift()) - 1)); }

	// internal state
	int final m_address_shift; // address bits shift-left
	int m_status;              // currently playing?
	int m_curnibble;           // index of the current nibble
	int m_curbyte;             // current byte of data
	int m_dummy_read;          // dummy read tracker
	int m_position;            // current fractional position
	int m_curaddress;          // current address
	int m_accumulator;          // accumulator
	int m_prev_accum;           // previous accumulator (for linear interp)
	int m_adpcm_step;           // next forecast
	adpcm_b_registers &m_regs;      // reference to registers
	adpcm_b_engine &m_owner;        // reference to our owner
};


// ======================> adpcm_b_engine

class adpcm_b_engine
{
public:
	// constructor
	adpcm_b_engine(ymfm_interface &intf, int addrshift = 0);

	// reset our status
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// master clocking function
	void clock();

	// compute sum of channel outputs
	template<int NumOutputs>
	void output(ymfm_output<NumOutputs> &output, int rshift);

	// read from the ADPCM-B registers
	int read(int regnum) { return m_channel.read(regnum); }

	// write to the ADPCM-B registers
	void write(int regnum, byte data);

	// status
	byte status() final { return m_channel.status(); }

	// return a reference to our interface
	ymfm_interface &intf() { return m_intf; }

	// return a reference to our registers
	adpcm_b_registers &regs() { return m_regs; }

private:
	// internal state
	ymfm_interface &m_intf;                     // reference to our interface
	std.unique_ptr<adpcm_b_channel> m_channel; // channel pointer
	adpcm_b_registers m_regs;                   // registers
};

}

#endif // YMFM_ADPCM_H
