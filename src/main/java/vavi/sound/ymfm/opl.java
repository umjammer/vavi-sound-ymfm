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

#ifndef YMFM_OPL_H
#define YMFM_OPL_H

#pragma once

#include "ymfm.h"
#include "ymfm_adpcm.h"
#include "ymfm_fm.h"
#include "ymfm_pcm.h"

package vavi.sound.ymfm;


//*********************************************************
//  REGISTER CLASSES
//*********************************************************

// ======================> opl_registers_base

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

template<int Revision>
class opl_registers_base : public fm_registers_base
{
	static final boolean IsOpl2 = (Revision == 2);
	static final boolean IsOpl2Plus = (Revision >= 2);
	static final boolean IsOpl3Plus = (Revision >= 3);
	static final boolean IsOpl4Plus = (Revision >= 4);

public:
	// constants
	static final int OUTPUTS = IsOpl3Plus ? 4 : 1;
	static final int CHANNELS = IsOpl3Plus ? 18 : 9;
	static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
	static final int OPERATORS = CHANNELS * 2;
	static final int WAVEFORMS = IsOpl3Plus ? 8 : (IsOpl2Plus ? 4 : 1);
	static final int REGISTERS = IsOpl3Plus ? 0x200 : 0x100;
	static final int REG_MODE = 0x04;
	static final int DEFAULT_PRESCALE = IsOpl4Plus ? 19 : (IsOpl3Plus ? 8 : 4);
	static final int EG_CLOCK_DIVIDER = 1;
	static final int CSM_TRIGGER_MASK = ALL_CHANNELS;
	static final boolean DYNAMIC_OPS = IsOpl3Plus;
	static final boolean MODULATOR_DELAY = !IsOpl3Plus;
	static final byte STATUS_TIMERA = 0x40;
	static final byte STATUS_TIMERB = 0x20;
	static final byte STATUS_BUSY = 0;
	static final byte STATUS_IRQ = 0x80;

	// constructor
	opl_registers_base();

	// reset to initial state
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// map channel number to register offset
	static final int channel_offset(int chnum)
	{
		assert(chnum < CHANNELS);
		if (!IsOpl3Plus)
			return chnum;
		else
			return (chnum % 9) + 0x100 * (chnum / 9);
	}

	// map operator number to register offset
	static final int operator_offset(int opnum)
	{
		assert(opnum < OPERATORS);
		if (!IsOpl3Plus)
			return opnum + 2 * (opnum / 6);
		else
			return (opnum % 18) + 2 * ((opnum % 18) / 6) + 0x100 * (opnum / 18);
	}

	// return an array of operator indices for each channel
	struct operator_mapping { int chan[CHANNELS]; };
	void operator_map(operator_mapping &dest) final;

	// OPL4 apparently can read back FM registers?
	byte read(int index) final { return m_regdata[index]; }

	// handle writes to the register array
	boolean write(int index, byte data, int &chan, int &opmask);

	// clock the noise and LFO, if present, returning LFO PM value
	int clock_noise_and_lfo();

	// reset the LFO
	void reset_lfo() { m_lfo_am_counter = m_lfo_pm_counter = 0; }

	// return the AM offset from LFO for the given channel
	// on OPL this is just a fixed value
	int lfo_am_offset(int choffs) final { return m_lfo_am; }

	// return LFO/noise states
	int noise_state() final { return m_noise_lfsr >> 23; }

	// caching helpers
	void cache_operator_data(int choffs, int opoffs, opdata_cache &cache);

	// compute the phase step, given a PM value
	int compute_phase_step(int choffs, int opoffs, opdata_cache final &cache, int lfo_raw_pm);

	// log a key-on event
	std.string log_keyon(int choffs, int opoffs);

	// system-wide registers
	int test() final                            { return byte(0x01, 0, 8); }
	int waveform_enable() final                 { return IsOpl2 ? byte(0x01, 5, 1) : (IsOpl3Plus ? 1 : 0); }
	int timer_a_value() final                   { return byte(0x02, 0, 8) * 4; } // 8->10 bits
	int timer_b_value() final                   { return byte(0x03, 0, 8); }
	int status_mask() final                     { return byte(0x04, 0, 8) & 0x78; }
	int irq_reset() final                       { return byte(0x04, 7, 1); }
	int reset_timer_b() final                   { return byte(0x04, 7, 1) | byte(0x04, 5, 1); }
	int reset_timer_a() final                   { return byte(0x04, 7, 1) | byte(0x04, 6, 1); }
	int enable_timer_b() final                  { return 1; }
	int enable_timer_a() final                  { return 1; }
	int load_timer_b() final                    { return byte(0x04, 1, 1); }
	int load_timer_a() final                    { return byte(0x04, 0, 1); }
	int csm() final                             { return IsOpl3Plus ? 0 : byte(0x08, 7, 1); }
	int note_select() final                     { return byte(0x08, 6, 1); }
	int lfo_am_depth() final                    { return byte(0xbd, 7, 1); }
	int lfo_pm_depth() final                    { return byte(0xbd, 6, 1); }
	int rhythm_enable() final                   { return byte(0xbd, 5, 1); }
	int rhythm_keyon() final                    { return byte(0xbd, 4, 0); }
	int newflag() final                         { return IsOpl3Plus ? byte(0x105, 0, 1) : 0; }
	int new2flag() final                        { return IsOpl4Plus ? byte(0x105, 1, 1) : 0; }
	int fourop_enable() final                   { return IsOpl3Plus ? byte(0x104, 0, 6) : 0; }

	// per-channel registers
	int ch_block_freq(int choffs) final    { return word(0xb0, 0, 5, 0xa0, 0, 8, choffs); }
	int ch_feedback(int choffs) final      { return byte(0xc0, 1, 3, choffs); }
	int ch_algorithm(int choffs) final     { return byte(0xc0, 0, 1, choffs) | (IsOpl3Plus ? (8 | (byte(0xc3, 0, 1, choffs) << 1)) : 0); }
	int ch_output_any(int choffs) final    { return newflag() ? byte(0xc0 + choffs, 4, 4) : 1; }
	int ch_output_0(int choffs) final      { return newflag() ? byte(0xc0 + choffs, 4, 1) : 1; }
	int ch_output_1(int choffs) final      { return newflag() ? byte(0xc0 + choffs, 5, 1) : (IsOpl3Plus ? 1 : 0); }
	int ch_output_2(int choffs) final      { return newflag() ? byte(0xc0 + choffs, 6, 1) : 0; }
	int ch_output_3(int choffs) final      { return newflag() ? byte(0xc0 + choffs, 7, 1) : 0; }

	// per-operator registers
	int op_lfo_am_enable(int opoffs) final { return byte(0x20, 7, 1, opoffs); }
	int op_lfo_pm_enable(int opoffs) final { return byte(0x20, 6, 1, opoffs); }
	int op_eg_sustain(int opoffs) final    { return byte(0x20, 5, 1, opoffs); }
	int op_ksr(int opoffs) final           { return byte(0x20, 4, 1, opoffs); }
	int op_multiple(int opoffs) final      { return byte(0x20, 0, 4, opoffs); }
	int op_ksl(int opoffs) final           { int temp = byte(0x40, 6, 2, opoffs); return bitfield(temp, 1) | (bitfield(temp, 0) << 1); }
	int op_total_level(int opoffs) final   { return byte(0x40, 0, 6, opoffs); }
	int op_attack_rate(int opoffs) final   { return byte(0x60, 4, 4, opoffs); }
	int op_decay_rate(int opoffs) final    { return byte(0x60, 0, 4, opoffs); }
	int op_sustain_level(int opoffs) final { return byte(0x80, 4, 4, opoffs); }
	int op_release_rate(int opoffs) final  { return byte(0x80, 0, 4, opoffs); }
	int op_waveform(int opoffs) final      { return IsOpl2Plus ? byte(0xe0, 0, newflag() ? 3 : 2, opoffs) : 0; }

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

	// helper to determine if the this channel is an active rhythm channel
	boolean is_rhythm(int choffs) final
	{
		return rhythm_enable() && (choffs >= 6 && choffs <= 8);
	}

	// internal state
	int m_lfo_am_counter;            // LFO AM counter
	int m_lfo_pm_counter;            // LFO PM counter
	int m_noise_lfsr;                // noise LFSR state
	byte m_lfo_am;                     // current LFO AM value
	byte m_regdata[REGISTERS];         // register data
	int m_waveform[WAVEFORMS][WAVEFORM_LENGTH]; // waveforms
};

using opl_registers = opl_registers_base<1>;
using opl2_registers = opl_registers_base<2>;
using opl3_registers = opl_registers_base<3>;
using opl4_registers = opl_registers_base<4>;



// ======================> opll_registers

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

class opll_registers : public fm_registers_base
{
public:
	static final int OUTPUTS = 2;
	static final int CHANNELS = 9;
	static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
	static final int OPERATORS = CHANNELS * 2;
	static final int WAVEFORMS = 2;
	static final int REGISTERS = 0x40;
	static final int REG_MODE = 0x3f;
	static final int DEFAULT_PRESCALE = 4;
	static final int EG_CLOCK_DIVIDER = 1;
	static final int CSM_TRIGGER_MASK = 0;
	static final boolean EG_HAS_DEPRESS = true;
	static final boolean MODULATOR_DELAY = true;
	static final byte STATUS_TIMERA = 0;
	static final byte STATUS_TIMERB = 0;
	static final byte STATUS_BUSY = 0;
	static final byte STATUS_IRQ = 0;

	// OPLL-specific constants
	static final int INSTDATA_SIZE = 0x90;

	// constructor
	opll_registers();

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

	// read a register value
	byte read(int index) final { return m_regdata[index]; }

	// handle writes to the register array
	boolean write(int index, byte data, int &chan, int &opmask);

	// clock the noise and LFO, if present, returning LFO PM value
	int clock_noise_and_lfo();

	// reset the LFO
	void reset_lfo() { m_lfo_am_counter = m_lfo_pm_counter = 0; }

	// return the AM offset from LFO for the given channel
	// on OPL this is just a fixed value
	int lfo_am_offset(int choffs) final { return m_lfo_am; }

	// return LFO/noise states
	int noise_state() final { return m_noise_lfsr >> 23; }

	// caching helpers
	void cache_operator_data(int choffs, int opoffs, opdata_cache &cache);

	// compute the phase step, given a PM value
	int compute_phase_step(int choffs, int opoffs, opdata_cache final &cache, int lfo_raw_pm);

	// log a key-on event
	std.string log_keyon(int choffs, int opoffs);

	// set the instrument data
	void set_instrument_data(byte final *data)
	{
		std.copy_n(data, INSTDATA_SIZE, &m_instdata[0]);
	}

	// system-wide registers
	int rhythm_enable() final                   { return byte(0x0e, 5, 1); }
	int rhythm_keyon() final                    { return byte(0x0e, 4, 0); }
	int test() final                            { return byte(0x0f, 0, 8); }
	int waveform_enable() final                 { return 1; }
	int timer_a_value() final                   { return 0; }
	int timer_b_value() final                   { return 0; }
	int status_mask() final                     { return 0; }
	int irq_reset() final                       { return 0; }
	int reset_timer_b() final                   { return 0; }
	int reset_timer_a() final                   { return 0; }
	int enable_timer_b() final                  { return 0; }
	int enable_timer_a() final                  { return 0; }
	int load_timer_b() final                    { return 0; }
	int load_timer_a() final                    { return 0; }
	int csm() final                             { return 0; }

	// per-channel registers
	int ch_block_freq(int choffs) final    { return word(0x20, 0, 4, 0x10, 0, 8, choffs); }
	int ch_sustain(int choffs) final       { return byte(0x20, 5, 1, choffs); }
	int ch_total_level(int choffs) final   { return instchbyte(0x02, 0, 6, choffs); }
	int ch_feedback(int choffs) final      { return instchbyte(0x03, 0, 3, choffs); }
	int ch_algorithm(int choffs) final     { return 0; }
	int ch_instrument(int choffs) final    { return byte(0x30, 4, 4, choffs); }
	int ch_output_any(int choffs) final    { return 1; }
	int ch_output_0(int choffs) final      { return !is_rhythm(choffs); }
	int ch_output_1(int choffs) final      { return is_rhythm(choffs); }
	int ch_output_2(int choffs) final      { return 0; }
	int ch_output_3(int choffs) final      { return 0; }

	// per-operator registers
	int op_lfo_am_enable(int opoffs) final { return instopbyte(0x00, 7, 1, opoffs); }
	int op_lfo_pm_enable(int opoffs) final { return instopbyte(0x00, 6, 1, opoffs); }
	int op_eg_sustain(int opoffs) final    { return instopbyte(0x00, 5, 1, opoffs); }
	int op_ksr(int opoffs) final           { return instopbyte(0x00, 4, 1, opoffs); }
	int op_multiple(int opoffs) final      { return instopbyte(0x00, 0, 4, opoffs); }
	int op_ksl(int opoffs) final           { return instopbyte(0x02, 6, 2, opoffs); }
	int op_waveform(int opoffs) final      { return instchbyte(0x03, 3 + bitfield(opoffs, 0), 1, opoffs >> 1); }
	int op_attack_rate(int opoffs) final   { return instopbyte(0x04, 4, 4, opoffs); }
	int op_decay_rate(int opoffs) final    { return instopbyte(0x04, 0, 4, opoffs); }
	int op_sustain_level(int opoffs) final { return instopbyte(0x06, 4, 4, opoffs); }
	int op_release_rate(int opoffs) final  { return instopbyte(0x06, 0, 4, opoffs); }
	int op_volume(int opoffs) final        { return byte(0x30, 4 * bitfield(~opoffs, 0), 4, opoffs >> 1); }

private:
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

	// helpers to read from instrument channel/operator data
	int instchbyte(int offset, int start, int count, int choffs) final { return bitfield(m_chinst[choffs][offset], start, count); }
	int instopbyte(int offset, int start, int count, int opoffs) final { return bitfield(m_opinst[opoffs][offset], start, count); }

	// helper to determine if the this channel is an active rhythm channel
	boolean is_rhythm(int choffs) final
	{
		return rhythm_enable() && choffs >= 6;
	}

	// internal state
	int m_lfo_am_counter;            // LFO AM counter
	int m_lfo_pm_counter;            // LFO PM counter
	int m_noise_lfsr;                // noise LFSR state
	byte m_lfo_am;                     // current LFO AM value
	byte final *m_chinst[CHANNELS];    // pointer to instrument data for each channel
	byte final *m_opinst[OPERATORS];   // pointer to instrument data for each operator
	byte m_regdata[REGISTERS];         // register data
	byte m_instdata[INSTDATA_SIZE];    // instrument data
	int m_waveform[WAVEFORMS][WAVEFORM_LENGTH]; // waveforms
};



//*********************************************************
//  OPL IMPLEMENTATION CLASSES
//*********************************************************

// ======================> ym3526

class ym3526
{
public:
	using fm_engine = fm_engine_base<opl_registers>;
	using output_data = fm_engine.output_data;
	static final int OUTPUTS = fm_engine.OUTPUTS;

	// constructor
	ym3526(ymfm_interface &intf);

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

	// generate samples of sound
	void generate(output_data *output, int numsamples = 1);
protected:
	// internal state
	byte m_address;               // address register
	fm_engine m_fm;                  // core FM engine
};


// ======================> y8950

class y8950
{
public:
	using fm_engine = fm_engine_base<opl_registers>;
	using output_data = fm_engine.output_data;
	static final int OUTPUTS = fm_engine.OUTPUTS;

	static final byte STATUS_ADPCM_B_PLAYING = 0x01;
	static final byte STATUS_ADPCM_B_BRDY = 0x08;
	static final byte STATUS_ADPCM_B_EOS = 0x10;
	static final byte ALL_IRQS = STATUS_ADPCM_B_BRDY | STATUS_ADPCM_B_EOS | fm_engine.STATUS_TIMERA | fm_engine.STATUS_TIMERB;

	// constructor
	y8950(ymfm_interface &intf);

	// reset
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// pass-through helpers
	int sample_rate(int input_clock) final { return m_fm.sample_rate(input_clock); }
	void invalidate_caches() { m_fm.invalidate_caches(); }

	// read access
	byte read_status();
	byte read_data();
	byte read(int offset);

	// write access
	void write_address(byte data);
	void write_data(byte data);
	void write(int offset, byte data);

	// generate samples of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// internal state
	byte m_address;               // address register
	byte m_io_ddr;                // data direction register for I/O
	fm_engine m_fm;                  // core FM engine
	adpcm_b_engine m_adpcm_b;        // ADPCM-B engine
};



//*********************************************************
//  OPL2 IMPLEMENTATION CLASSES
//*********************************************************

// ======================> ym3812

class ym3812
{
public:
	using fm_engine = fm_engine_base<opl2_registers>;
	using output_data = fm_engine.output_data;
	static final int OUTPUTS = fm_engine.OUTPUTS;

	// constructor
	ym3812(ymfm_interface &intf);

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

	// generate samples of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// internal state
	byte m_address;               // address register
	fm_engine m_fm;                  // core FM engine
};



//*********************************************************
//  OPL3 IMPLEMENTATION CLASSES
//*********************************************************

// ======================> ymf262

class ymf262
{
public:
	using fm_engine = fm_engine_base<opl3_registers>;
	using output_data = fm_engine.output_data;
	static final int OUTPUTS = fm_engine.OUTPUTS;

	// constructor
	ymf262(ymfm_interface &intf);

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
	void write_address_hi(byte data);
	void write(int offset, byte data);

	// generate samples of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// internal state
	int m_address;              // address register
	fm_engine m_fm;                  // core FM engine
};


// ======================> ymf289b

class ymf289b
{
	static final byte STATUS_BUSY_FLAGS = 0x05;

public:
	using fm_engine = fm_engine_base<opl3_registers>;
	using output_data = fm_engine.output_data;
	static final int OUTPUTS = 2;

	// constructor
	ymf289b(ymfm_interface &intf);

	// reset
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// pass-through helpers
	int sample_rate(int input_clock) final { return m_fm.sample_rate(input_clock); }
	void invalidate_caches() { m_fm.invalidate_caches(); }

	// read access
	byte read_status();
	byte read_data();
	byte read(int offset);

	// write access
	void write_address(byte data);
	void write_data(byte data);
	void write_address_hi(byte data);
	void write(int offset, byte data);

	// generate samples of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// internal helpers
	boolean ymf289b_mode() { return ((m_fm.regs().read(0x105) & 0x04) != 0); }

	// internal state
	int m_address;              // address register
	fm_engine m_fm;                  // core FM engine
};



//*********************************************************
//  OPL4 IMPLEMENTATION CLASSES
//*********************************************************

// ======================> ymf278b

class ymf278b
{
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

public:
	using fm_engine = fm_engine_base<opl4_registers>;
	static final int OUTPUTS = 6;
	using output_data = ymfm_output<OUTPUTS>;

	static final byte STATUS_BUSY = 0x01;
	static final byte STATUS_LD = 0x02;

	// constructor
	ymf278b(ymfm_interface &intf);

	// reset
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// pass-through helpers
	int sample_rate(int input_clock) final { return input_clock / 768; }
	void invalidate_caches() { m_fm.invalidate_caches(); }

	// read access
	byte read_status();
	byte read_data_pcm();
	byte read(int offset);

	// write access
	void write_address(byte data);
	void write_data(byte data);
	void write_address_hi(byte data);
	void write_address_pcm(byte data);
	void write_data_pcm(byte data);
	void write(int offset, byte data);

	// generate samples of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// internal state
	int m_address;              // address register
	int m_fm_pos;               // FM resampling position
	int m_load_remaining;       // how many more samples until LD flag clears
	boolean m_next_status_id;           // flag to track which status ID to return
	fm_engine m_fm;                  // core FM engine
	pcm_engine m_pcm;                // core PCM engine
};



//*********************************************************
//  OPLL IMPLEMENTATION CLASSES
//*********************************************************

// ======================> opll_base

class opll_base
{
public:
	using fm_engine = fm_engine_base<opll_registers>;
	using output_data = fm_engine.output_data;
	static final int OUTPUTS = fm_engine.OUTPUTS;

	// constructor
	opll_base(ymfm_interface &intf, byte final *data);

	// configuration
	void set_instrument_data(byte final *data) { m_fm.regs().set_instrument_data(data); }

	// reset
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// pass-through helpers
	int sample_rate(int input_clock) final { return m_fm.sample_rate(input_clock); }
	void invalidate_caches() { m_fm.invalidate_caches(); }

	// read access -- doesn't really have any, but provide these for consistency
	byte read_status() { return 0x00; }
	byte read(int offset) { return 0x00; }

	// write access
	void write_address(byte data);
	void write_data(byte data);
	void write(int offset, byte data);

	// generate samples of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// internal state
	byte m_address;               // address register
	fm_engine m_fm;                  // core FM engine
};


// ======================> ym2413

class ym2413 : public opll_base
{
public:
	// constructor
	ym2413(ymfm_interface &intf, byte final *instrument_data = nullptr);

private:
	// internal state
	static byte final s_default_instruments[];
};


// ======================> ym2413

class ym2423 : public opll_base
{
public:
	// constructor
	ym2423(ymfm_interface &intf, byte final *instrument_data = nullptr);

private:
	// internal state
	static byte final s_default_instruments[];
};


// ======================> ymf281

class ymf281 : public opll_base
{
public:
	// constructor
	ymf281(ymfm_interface &intf, byte final *instrument_data = nullptr);

private:
	// internal state
	static byte final s_default_instruments[];
};


// ======================> ds1001

class ds1001 : public opll_base
{
public:
	// constructor
	ds1001(ymfm_interface &intf, byte final *instrument_data = nullptr);

private:
	// internal state
	static byte final s_default_instruments[];
};

}

#endif // YMFM_OPL_H
