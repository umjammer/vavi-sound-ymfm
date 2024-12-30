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

#ifndef YMFM_OPX_H
#define YMFM_OPX_H

#pragma once

#include "ymfm.h"
#include "ymfm_fm.h"

package vavi.sound.ymfm;


//*********************************************************
//  REGISTER CLASSES
//*********************************************************

// ======================> opx_registers

//
// OPX register map:
//
//      System-wide registers:
//
//     Per-channel registers (channel in address bits 0-2)
//
//     Per-operator registers (4 banks):
//        00-0F x------- Enable
//              -xxxx--- EXT out
//              -------x Key on
//        10-1F xxxxxxxx LFO frequency
//        20-2F xx------ AM sensitivity (0-3)
//              --xxx--- PM sensitivity (0-7)
//              ------xx LFO waveform (0=disable, 1=saw, 2=
//        30-3F -xxx---- Detune (0-7)
//              ----xxxx Multiple (0-15)
//        40-4F -xxxxxxx Total level (0-127)
//        50-5F xxx----- Key scale (0-7)
//              ---xxxxx Attack rate (0-31)
//        60-6F ---xxxxx Decay rate (0-31)
//        70-7F ---xxxxx Sustain rate (0-31)
//        80-8F xxxx---- Sustain level (0-15)
//              ----xxxx Release rate (0-15)
//        90-9F xxxxxxxx Frequency number (low 8 bits)
//        A0-AF xxxx---- Block (0-15)
//              ----xxxx Frequency number (high 4 bits)
//        B0-BF x------- Acc on
//              -xxx---- Feedback level (0-7)
//              -----xxx Waveform (0-7, 7=PCM)
//        C0-CF ----xxxx Algorithm (0-15)
//        D0-DF xxxx---- CH0 level (0-15)
//              ----xxxx CH1 level (0-15)
//        E0-EF xxxx---- CH2 level (0-15)
//              ----xxxx CH3 level (0-15)
//

class opx_registers : public fm_registers_base
{
	// LFO waveforms are 256 entries long
	static final int LFO_WAVEFORM_LENGTH = 256;

public:
	// constants
	static final int OUTPUTS = 8;
	static final int CHANNELS = 24;
	static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
	static final int OPERATORS = CHANNELS * 2;
	static final int WAVEFORMS = 8;
	static final int REGISTERS = 0x800;
	static final int DEFAULT_PRESCALE = 8;
	static final int EG_CLOCK_DIVIDER = 2;
	static final int CSM_TRIGGER_MASK = ALL_CHANNELS;
	static final int REG_MODE = 0x14;
	static final byte STATUS_TIMERA = 0x01;
	static final byte STATUS_TIMERB = 0x02;
	static final byte STATUS_BUSY = 0x80;
	static final byte STATUS_IRQ = 0;

	// constructor
	opz_registers();

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
	int noise_frequency() final                 { return byte(0x0f, 0, 5); }
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
	int lfo2_pm_depth() final                   { return byte(0x148, 0, 7); } // fake
	int lfo2_rate() final                       { return byte(0x16, 0, 8); }
	int lfo2_am_depth() final                   { return byte(0x17, 0, 7); }
	int lfo_rate() final                        { return byte(0x18, 0, 8); }
	int lfo_am_depth() final                    { return byte(0x19, 0, 7); }
	int lfo_pm_depth() final                    { return byte(0x149, 0, 7); } // fake
	int output_bits() final                     { return byte(0x1b, 6, 2); }
	int lfo2_sync() final                       { return byte(0x1b, 5, 1); }
	int lfo_sync() final                        { return byte(0x1b, 4, 1); }
	int lfo2_waveform() final                   { return byte(0x1b, 2, 2); }
	int lfo_waveform() final                    { return byte(0x1b, 0, 2); }

	// per-channel registers
	int ch_volume(int choffs) final        { return byte(0x00, 0, 8, choffs); }
	int ch_output_any(int choffs) final    { return byte(0x20, 7, 1, choffs) | byte(0x30, 0, 1, choffs); }
	int ch_output_0(int choffs) final      { return byte(0x30, 0, 1, choffs); }
	int ch_output_1(int choffs) final      { return byte(0x20, 7, 1, choffs) | byte(0x30, 0, 1, choffs); }
	int ch_output_2(int choffs) final      { return 0; }
	int ch_output_3(int choffs) final      { return 0; }
	int ch_key_on(int choffs) final        { return byte(0x20, 6, 1, choffs); }
	int ch_feedback(int choffs) final      { return byte(0x20, 3, 3, choffs); }
	int ch_algorithm(int choffs) final     { return byte(0x20, 0, 3, choffs); }
	int ch_block_freq(int choffs) final    { return word(0x28, 0, 7, 0x30, 2, 6, choffs); }
	int ch_lfo_pm_sens(int choffs) final   { return byte(0x38, 4, 3, choffs); }
	int ch_lfo_am_sens(int choffs) final   { return byte(0x38, 0, 2, choffs); }
	int ch_lfo2_pm_sens(int choffs) final  { return byte(0x140, 4, 3, choffs); } // fake
	int ch_lfo2_am_sens(int choffs) final  { return byte(0x140, 0, 2, choffs); } // fake

	// per-operator registers
	int op_detune(int opoffs) final        { return byte(0x40, 4, 3, opoffs); }
	int op_multiple(int opoffs) final      { return byte(0x40, 0, 4, opoffs); }
	int op_fix_range(int opoffs) final     { return byte(0x40, 4, 3, opoffs); }
	int op_fix_frequency(int opoffs) final { return byte(0x40, 0, 4, opoffs); }
	int op_waveform(int opoffs) final      { return byte(0x100, 4, 3, opoffs); } // fake
	int op_fine(int opoffs) final          { return byte(0x100, 0, 4, opoffs); } // fake
	int op_total_level(int opoffs) final   { return byte(0x60, 0, 7, opoffs); }
	int op_ksr(int opoffs) final           { return byte(0x80, 6, 2, opoffs); }
	int op_fix_mode(int opoffs) final      { return byte(0x80, 5, 1, opoffs); }
	int op_attack_rate(int opoffs) final   { return byte(0x80, 0, 5, opoffs); }
	int op_lfo_am_enable(int opoffs) final { return byte(0xa0, 7, 1, opoffs); }
	int op_decay_rate(int opoffs) final    { return byte(0xa0, 0, 5, opoffs); }
	int op_detune2(int opoffs) final       { return byte(0xc0, 6, 2, opoffs); }
	int op_sustain_rate(int opoffs) final  { return byte(0xc0, 0, 5, opoffs); }
	int op_eg_shift(int opoffs) final      { return byte(0x120, 6, 2, opoffs); } // fake
	int op_reverb_rate(int opoffs) final   { return byte(0x120, 0, 3, opoffs); } // fake
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
	int m_lfo_counter[2];            // LFO counter
	int m_noise_lfsr;                // noise LFSR state
	byte m_noise_counter;              // noise counter
	byte m_noise_state;                // latched noise state
	byte m_noise_lfo;                  // latched LFO noise value
	byte m_lfo_am[2];                  // current LFO AM value
	byte m_regdata[REGISTERS];         // register data
	int m_phase_substep[OPERATORS];  // phase substep for fixed frequency
	int m_lfo_waveform[4][LFO_WAVEFORM_LENGTH]; // LFO waveforms; AM in low 8, PM in upper 8
	int m_waveform[WAVEFORMS][WAVEFORM_LENGTH]; // waveforms
};



//*********************************************************
//  IMPLEMENTATION CLASSES
//*********************************************************

// ======================> ym2414

class ym2414
{
public:
	using fm_engine = fm_engine_base<opz_registers>;
	static final int OUTPUTS = fm_engine.OUTPUTS;
	using output_data = fm_engine.output_data;

	// constructor
	ym2414(ymfm_interface &intf);

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
	// internal state
	byte m_address;               // address register
	fm_engine m_fm;                  // core FM engine
};

}


#endif // YMFM_OPZ_H
