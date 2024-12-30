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

#ifndef YMFM_PCM_H
#define YMFM_PCM_H

#pragma once

#include "ymfm.h"

package vavi.sound.ymfm;


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

//*********************************************************
//  INTERFACE CLASSES
//*********************************************************

class pcm_engine;


// ======================> pcm_cache

// this class holds data that is computed once at the start of clocking
// and remains static during subsequent sound generation
struct pcm_cache
{
	int step;                    // sample position step, as a .16 value
	int total_level;             // target total level, as a .10 value
	int pan_left;                // left panning attenuation
	int pan_right;               // right panning attenuation
	int eg_sustain;              // sustain level, shifted up to envelope values
	byte eg_rate[EG_STATES];       // envelope rate, including KSR
	byte lfo_step;                 // stepping value for LFO
	byte am_depth;                 // scale value for AM LFO
	byte pm_depth;                 // scale value for PM LFO
};


// ======================> pcm_registers

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

class pcm_registers
{
public:
	// constants
	static final int OUTPUTS = 4;
	static final int CHANNELS = 24;
	static final int REGISTERS = 0x100;
	static final int ALL_CHANNELS = (1 << CHANNELS) - 1;

	// constructor
	pcm_registers() { }

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// reset to initial state
	void reset();

	// update cache information
	void cache_channel_data(int choffs, pcm_cache &cache);

	// direct read/write access
	byte read(int index ) { return m_regdata[index]; }
	void write(int index, byte data) { m_regdata[index] = data; }

	// system-wide registers
	int memory_access_mode() final                 { return bitfield(m_regdata[0x02], 0); }
	int memory_type() final                        { return bitfield(m_regdata[0x02], 1); }
	int wave_table_header() final                  { return bitfield(m_regdata[0x02], 2, 3); }
	int device_id() final                          { return bitfield(m_regdata[0x02], 5, 3); }
	int memory_address() final                     { return (bitfield(m_regdata[0x03], 0, 6) << 16) | (m_regdata[0x04] << 8) | m_regdata[0x05]; }
	int memory_data() final                        { return m_regdata[0x06]; }
	int mix_fm_r() final                           { return bitfield(m_regdata[0xf8], 3, 3); }
	int mix_fm_l() final                           { return bitfield(m_regdata[0xf8], 0, 3); }
	int mix_pcm_r() final                          { return bitfield(m_regdata[0xf9], 3, 3); }
	int mix_pcm_l() final                          { return bitfield(m_regdata[0xf9], 0, 3); }

	// per-channel registers
	int ch_wave_table_num(int choffs) final   { return m_regdata[choffs + 0x08] | (bitfield(m_regdata[choffs + 0x20], 0) << 8); }
	int ch_fnumber(int choffs) final          { return bitfield(m_regdata[choffs + 0x20], 1, 7) | (bitfield(m_regdata[choffs + 0x38], 0, 3) << 7); }
	int ch_pseudo_reverb(int choffs) final    { return bitfield(m_regdata[choffs + 0x38], 3); }
	int ch_octave(int choffs) final           { return bitfield(m_regdata[choffs + 0x38], 4, 4); }
	int ch_total_level(int choffs) final      { return bitfield(m_regdata[choffs + 0x50], 1, 7); }
	int ch_level_direct(int choffs) final     { return bitfield(m_regdata[choffs + 0x50], 0); }
	int ch_keyon(int choffs) final            { return bitfield(m_regdata[choffs + 0x68], 7); }
	int ch_damp(int choffs) final             { return bitfield(m_regdata[choffs + 0x68], 6); }
	int ch_lfo_reset(int choffs) final        { return bitfield(m_regdata[choffs + 0x68], 5); }
	int ch_output_channel(int choffs) final   { return bitfield(m_regdata[choffs + 0x68], 4); }
	int ch_panpot(int choffs) final           { return bitfield(m_regdata[choffs + 0x68], 0, 4); }
	int ch_lfo_speed(int choffs) final        { return bitfield(m_regdata[choffs + 0x80], 3, 3); }
	int ch_vibrato(int choffs) final          { return bitfield(m_regdata[choffs + 0x80], 0, 3); }
	int ch_attack_rate(int choffs) final      { return bitfield(m_regdata[choffs + 0x98], 4, 4); }
	int ch_decay_rate(int choffs) final       { return bitfield(m_regdata[choffs + 0x98], 0, 4); }
	int ch_sustain_level(int choffs) final    { return bitfield(m_regdata[choffs + 0xb0], 4, 4); }
	int ch_sustain_rate(int choffs) final     { return bitfield(m_regdata[choffs + 0xb0], 0, 4); }
	int ch_rate_correction(int choffs) final  { return bitfield(m_regdata[choffs + 0xc8], 4, 4); }
	int ch_release_rate(int choffs) final     { return bitfield(m_regdata[choffs + 0xc8], 0, 4); }
	int ch_am_depth(int choffs) final         { return bitfield(m_regdata[choffs + 0xe0], 0, 3); }

	// return the memory address and increment it
	int memory_address_autoinc()
	{
		int result = memory_address();
		int newval = result + 1;
		m_regdata[0x05] = newval >> 0;
		m_regdata[0x04] = newval >> 8;
		m_regdata[0x03] = (newval >> 16) & 0x3f;
		return result;
	}

private:
	// internal helpers
	int effective_rate(int raw, int correction);

	// internal state
	byte m_regdata[REGISTERS];         // register data
};


// ======================> pcm_channel

class pcm_channel
{
	static final byte KEY_ON = 0x01;
	static final byte KEY_PENDING_ON = 0x02;
	static final byte KEY_PENDING = 0x04;

	// "quiet" value, used to optimize when we can skip doing working
	static final int EG_QUIET = 0x200;

public:
	using output_data = ymfm_output<pcm_registers.OUTPUTS>;

	// constructor
	pcm_channel(pcm_engine &owner, int choffs);

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// reset the channel state
	void reset();

	// return the channel offset
	int choffs() final { return m_choffs; }

	// prepare prior to clocking
	boolean prepare();

	// master clocking function
	void clock(int env_counter);

	// return the computed output value, with panning applied
	void output(output_data &output) final;

	// signal key on/off
	void keyonoff(boolean on);

	// load a new wavetable entry
	void load_wavetable();

private:
	// internal helpers
	void start_attack();
	void start_release();
	void clock_envelope(int env_counter);
	int fetch_sample() final;
	byte read_pcm(int address) final;

	// internal state
	int final m_choffs;              // channel offset
	int m_baseaddr;                  // base address
	int m_endpos;                    // ending position
	int m_looppos;                   // loop position
	int m_curpos;                    // current position
	int m_nextpos;                   // next position
	int m_lfo_counter;               // LFO counter
	envelope_state m_eg_state;            // envelope state
	int m_env_attenuation;           // computed envelope attenuation
	int m_total_level;               // total level with as 7.10 for interp
	byte m_format;                     // sample format
	byte m_key_state;                  // current key state
	pcm_cache m_cache;                    // cached data
	pcm_registers &m_regs;                // reference to registers
	pcm_engine &m_owner;                  // reference to our owner
};


// ======================> pcm_engine

class pcm_engine
{
public:
	static final int OUTPUTS = pcm_registers.OUTPUTS;
	static final int CHANNELS = pcm_registers.CHANNELS;
	static final int ALL_CHANNELS = pcm_registers.ALL_CHANNELS;
	using output_data = pcm_channel.output_data;

	// constructor
	pcm_engine(ymfm_interface &intf);

	// reset our status
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// master clocking function
	void clock(int chanmask);

	// compute sum of channel outputs
	void output(output_data &output, int chanmask);

	// read from the PCM registers
	byte read(int regnum);

	// write to the PCM registers
	void write(int regnum, byte data);

	// return a reference to our interface
	ymfm_interface &intf() { return m_intf; }

	// return a reference to our registers
	pcm_registers &regs() { return m_regs; }

private:
	// internal state
	ymfm_interface &m_intf;                           // reference to the interface
	int m_env_counter;                           // envelope counter
	int m_modified_channels;                     // bitmask of modified channels
	int m_active_channels;                       // bitmask of active channels
	int m_prepare_count;                         // counter to do periodic prepare sweeps
	std.unique_ptr<pcm_channel> m_channel[CHANNELS]; // array of channels
	pcm_registers m_regs;                             // registers
};

}

#endif // YMFM_PCM_H
