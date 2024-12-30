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

#ifndef YMFM_OPM_H
#define YMFM_OPM_H

#pragma once

#include "ymfm.h"
#include "ymfm_fm.h"

package vavi.sound.ymfm;


//*********************************************************
//  REGISTER CLASSES
//*********************************************************

// ======================> opm_registers

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

class opm_registers : public fm_registers_base
{
	// LFO waveforms are 256 entries long
	static final int LFO_WAVEFORM_LENGTH = 256;

public:
	// constants
	static final int OUTPUTS = 2;
	static final int CHANNELS = 8;
	static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
	static final int OPERATORS = CHANNELS * 4;
	static final int WAVEFORMS = 1;
	static final int REGISTERS = 0x100;
	static final int DEFAULT_PRESCALE = 2;
	static final int EG_CLOCK_DIVIDER = 3;
	static final int CSM_TRIGGER_MASK = ALL_CHANNELS;
	static final int REG_MODE = 0x14;
	static final byte STATUS_TIMERA = 0x01;
	static final byte STATUS_TIMERB = 0x02;
	static final byte STATUS_BUSY = 0x80;
	static final byte STATUS_IRQ = 0;

	// constructor
	opm_registers();

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

	// map operator number to register offset
	static final int operator_offset(int opnum)
	{
		assert(opnum < OPERATORS);
		return opnum;
	}

	// return an array of operator indices for each channel
	struct operator_mapping { int chan[CHANNELS]; };
	void operator_map(operator_mapping &dest) final;

	// handle writes to the register array
	boolean write(int index, byte data, int &chan, int &opmask);

	// clock the noise and LFO, if present, returning LFO PM value
	int clock_noise_and_lfo();

	// return the AM offset from LFO for the given channel
	int lfo_am_offset(int choffs) final;

	// return the current noise state, gated by the noise clock
	int noise_state() final { return m_noise_state; }

	// caching helpers
	void cache_operator_data(int choffs, int opoffs, opdata_cache &cache);

	// compute the phase step, given a PM value
	int compute_phase_step(int choffs, int opoffs, opdata_cache final &cache, int lfo_raw_pm);

	// log a key-on event
	std.string log_keyon(int choffs, int opoffs);

	// system-wide registers
	int test() final                            { return byte(0x01, 0, 8); }
	int lfo_reset() final                       { return byte(0x01, 1, 1); }
	int noise_frequency() final                 { return byte(0x0f, 0, 5) ^ 0x1f; }
	int noise_enable() final                    { return byte(0x0f, 7, 1); }
	int timer_a_value() final                   { return word(0x10, 0, 8, 0x11, 0, 2); }
	int timer_b_value() final                   { return byte(0x12, 0, 8); }
	int csm() final                             { return byte(0x14, 7, 1); }
	int reset_timer_b() final                   { return byte(0x14, 5, 1); }
	int reset_timer_a() final                   { return byte(0x14, 4, 1); }
	int enable_timer_b() final                  { return byte(0x14, 3, 1); }
	int enable_timer_a() final                  { return byte(0x14, 2, 1); }
	int load_timer_b() final                    { return byte(0x14, 1, 1); }
	int load_timer_a() final                    { return byte(0x14, 0, 1); }
	int lfo_rate() final                        { return byte(0x18, 0, 8); }
	int lfo_am_depth() final                    { return byte(0x19, 0, 7); }
	int lfo_pm_depth() final                    { return byte(0x1a, 0, 7); }
	int output_bits() final                     { return byte(0x1b, 6, 2); }
	int lfo_waveform() final                    { return byte(0x1b, 0, 2); }

	// per-channel registers
	int ch_output_any(int choffs) final    { return byte(0x20, 6, 2, choffs); }
	int ch_output_0(int choffs) final      { return byte(0x20, 6, 1, choffs); }
	int ch_output_1(int choffs) final      { return byte(0x20, 7, 1, choffs); }
	int ch_output_2(int choffs) final      { return 0; }
	int ch_output_3(int choffs) final      { return 0; }
	int ch_feedback(int choffs) final      { return byte(0x20, 3, 3, choffs); }
	int ch_algorithm(int choffs) final     { return byte(0x20, 0, 3, choffs); }
	int ch_block_freq(int choffs) final    { return word(0x28, 0, 7, 0x30, 2, 6, choffs); }
	int ch_lfo_pm_sens(int choffs) final   { return byte(0x38, 4, 3, choffs); }
	int ch_lfo_am_sens(int choffs) final   { return byte(0x38, 0, 2, choffs); }

	// per-operator registers
	int op_detune(int opoffs) final        { return byte(0x40, 4, 3, opoffs); }
	int op_multiple(int opoffs) final      { return byte(0x40, 0, 4, opoffs); }
	int op_total_level(int opoffs) final   { return byte(0x60, 0, 7, opoffs); }
	int op_ksr(int opoffs) final           { return byte(0x80, 6, 2, opoffs); }
	int op_attack_rate(int opoffs) final   { return byte(0x80, 0, 5, opoffs); }
	int op_lfo_am_enable(int opoffs) final { return byte(0xa0, 7, 1, opoffs); }
	int op_decay_rate(int opoffs) final    { return byte(0xa0, 0, 5, opoffs); }
	int op_detune2(int opoffs) final       { return byte(0xc0, 6, 2, opoffs); }
	int op_sustain_rate(int opoffs) final  { return byte(0xc0, 0, 5, opoffs); }
	int op_sustain_level(int opoffs) final { return byte(0xe0, 4, 4, opoffs); }
	int op_release_rate(int opoffs) final  { return byte(0xe0, 0, 4, opoffs); }

protected:
	// return a bitfield extracted from a byte
	int byte(int offset, int start, int count, int extra_offset = 0) final
	{
		return bitfield(m_regdata[offset + extra_offset], start, count);
	}

	// return a bitfield extracted from a pair of bytes, MSBs listed first
	int word(int offset1, int start1, int count1, int offset2, int start2, int count2, int extra_offset = 0) final
	{
		return (byte(offset1, start1, count1, extra_offset) << count2) | byte(offset2, start2, count2, extra_offset);
	}

	// internal state
	int m_lfo_counter;               // LFO counter
	int m_noise_lfsr;                // noise LFSR state
	byte m_noise_counter;              // noise counter
	byte m_noise_state;                // latched noise state
	byte m_noise_lfo;                  // latched LFO noise value
	byte m_lfo_am;                     // current LFO AM value
	byte m_regdata[REGISTERS];         // register data
	int m_lfo_waveform[4][LFO_WAVEFORM_LENGTH]; // LFO waveforms; AM in low 8, PM in upper 8
	int m_waveform[WAVEFORMS][WAVEFORM_LENGTH]; // waveforms
};



//*********************************************************
//  OPM IMPLEMENTATION CLASSES
//*********************************************************

// ======================> ym2151

class ym2151
{
public:
	using fm_engine = fm_engine_base<opm_registers>;
	using output_data = fm_engine.output_data;
	static final int OUTPUTS = fm_engine.OUTPUTS;

	// constructor
	ym2151(ymfm_interface &intf) : ym2151(intf, VARIANT_YM2151) { }

	// reset
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// pass-through helpers
	int sample_rate(int input_clock) final { return m_fm.sample_rate(input_clock); }
	void invalidate_caches() { m_fm.invalidate_caches(); }

	// read access
	byte read_status();
	byte read(int offset);

	// write access
	void write_address(byte data);
	void write_data(byte data);
	void write(int offset, byte data);

	// generate one sample of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// variants
	enum opm_variant
	{
		VARIANT_YM2151,
		VARIANT_YM2164
	};

	// internal constructor
	ym2151(ymfm_interface &intf, opm_variant variant);

	// internal state
	opm_variant m_variant;           // chip variant
	byte m_address;               // address register
	fm_engine m_fm;                  // core FM engine
};



//*********************************************************
//  OPP IMPLEMENTATION CLASSES
//*********************************************************

// ======================> ym2164

// the YM2164 is almost 100% functionally identical to the YM2151, except
// it apparently has some mystery registers in the 00-07 range, and timer
// B's frequency is half that of the 2151
class ym2164 : public ym2151
{
public:
	// constructor
	ym2164(ymfm_interface &intf) : ym2151(intf, VARIANT_YM2164) { }
};

}


#endif // YMFM_OPM_H
