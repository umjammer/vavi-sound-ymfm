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

#ifndef YMFM_OPN_H
#define YMFM_OPN_H

#pragma once

#include "ymfm.h"
#include "ymfm_adpcm.h"
#include "ymfm_fm.h"
#include "ymfm_ssg.h"

package vavi.sound.ymfm;


//*********************************************************
//  REGISTER CLASSES
//*********************************************************

// ======================> opn_registers_base

//
// OPN register map:
//
//      System-wide registers:
//           21 xxxxxxxx Test register
//           22 ----x--- LFO enable [OPNA+ only]
//              -----xxx LFO rate [OPNA+ only]
//           24 xxxxxxxx Timer A value (upper 8 bits)
//           25 ------xx Timer A value (lower 2 bits)
//           26 xxxxxxxx Timer B value
//           27 xx------ CSM/Multi-frequency mode for channel #2
//              --x----- Reset timer B
//              ---x---- Reset timer A
//              ----x--- Enable timer B
//              -----x-- Enable timer A
//              ------x- Load timer B
//              -------x Load timer A
//           28 x------- Key on/off operator 4
//              -x------ Key on/off operator 3
//              --x----- Key on/off operator 2
//              ---x---- Key on/off operator 1
//              ------xx Channel select
//
//     Per-channel registers (channel in address bits 0-1)
//     Note that all these apply to address+100 as well on OPNA+
//        A0-A3 xxxxxxxx Frequency number lower 8 bits
//        A4-A7 --xxx--- Block (0-7)
//              -----xxx Frequency number upper 3 bits
//        B0-B3 --xxx--- Feedback level for operator 1 (0-7)
//              -----xxx Operator connection algorithm (0-7)
//        B4-B7 x------- Pan left [OPNA]
//              -x------ Pan right [OPNA]
//              --xx---- LFO AM shift (0-3) [OPNA+ only]
//              -----xxx LFO PM depth (0-7) [OPNA+ only]
//
//     Per-operator registers (channel in address bits 0-1, operator in bits 2-3)
//     Note that all these apply to address+100 as well on OPNA+
//        30-3F -xxx---- Detune value (0-7)
//              ----xxxx Multiple value (0-15)
//        40-4F -xxxxxxx Total level (0-127)
//        50-5F xx------ Key scale rate (0-3)
//              ---xxxxx Attack rate (0-31)
//        60-6F x------- LFO AM enable [OPNA]
//              ---xxxxx Decay rate (0-31)
//        70-7F ---xxxxx Sustain rate (0-31)
//        80-8F xxxx---- Sustain level (0-15)
//              ----xxxx Release rate (0-15)
//        90-9F ----x--- SSG-EG enable
//              -----xxx SSG-EG envelope (0-7)
//
//     Special multi-frequency registers (channel implicitly #2; operator in address bits 0-1)
//        A8-AB xxxxxxxx Frequency number lower 8 bits
//        AC-AF --xxx--- Block (0-7)
//              -----xxx Frequency number upper 3 bits
//
//     Internal (fake) registers:
//        B8-BB --xxxxxx Latched frequency number upper bits (from A4-A7)
//        BC-BF --xxxxxx Latched frequency number upper bits (from AC-AF)
//

template<boolean IsOpnA>
class opn_registers_base : public fm_registers_base
{
public:
	// constants
	static final int OUTPUTS = IsOpnA ? 2 : 1;
	static final int CHANNELS = IsOpnA ? 6 : 3;
	static final int ALL_CHANNELS = (1 << CHANNELS) - 1;
	static final int OPERATORS = CHANNELS * 4;
	static final int WAVEFORMS = 1;
	static final int REGISTERS = IsOpnA ? 0x200 : 0x100;
	static final int REG_MODE = 0x27;
	static final int DEFAULT_PRESCALE = 6;
	static final int EG_CLOCK_DIVIDER = 3;
	static final boolean EG_HAS_SSG = true;
	static final boolean MODULATOR_DELAY = false;
	static final int CSM_TRIGGER_MASK = 1 << 2;
	static final byte STATUS_TIMERA = 0x01;
	static final byte STATUS_TIMERB = 0x02;
	static final byte STATUS_BUSY = 0x80;
	static final byte STATUS_IRQ = 0;

	// constructor
	opn_registers_base();

	// reset to initial state
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// map channel number to register offset
	static final int channel_offset(int chnum)
	{
		assert(chnum < CHANNELS);
		if (!IsOpnA)
			return chnum;
		else
			return (chnum % 3) + 0x100 * (chnum / 3);
	}

	// map operator number to register offset
	static final int operator_offset(int opnum)
	{
		assert(opnum < OPERATORS);
		if (!IsOpnA)
			return opnum + opnum / 3;
		else
			return (opnum % 12) + ((opnum % 12) / 3) + 0x100 * (opnum / 12);
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
	void reset_lfo() { m_lfo_counter = 0; }

	// return the AM offset from LFO for the given channel
	int lfo_am_offset(int choffs) final;

	// return LFO/noise states
	int noise_state() final { return 0; }

	// caching helpers
	void cache_operator_data(int choffs, int opoffs, opdata_cache &cache);

	// compute the phase step, given a PM value
	int compute_phase_step(int choffs, int opoffs, opdata_cache final &cache, int lfo_raw_pm);

	// log a key-on event
	std.string log_keyon(int choffs, int opoffs);

	// system-wide registers
	int test() final                       { return byte(0x21, 0, 8); }
	int lfo_enable() final                 { return IsOpnA ? byte(0x22, 3, 1) : 0; }
	int lfo_rate() final                   { return IsOpnA ? byte(0x22, 0, 3) : 0; }
	int timer_a_value() final              { return word(0x24, 0, 8, 0x25, 0, 2); }
	int timer_b_value() final              { return byte(0x26, 0, 8); }
	int csm() final                        { return (byte(0x27, 6, 2) == 2); }
	int multi_freq() final                 { return (byte(0x27, 6, 2) != 0); }
	int reset_timer_b() final              { return byte(0x27, 5, 1); }
	int reset_timer_a() final              { return byte(0x27, 4, 1); }
	int enable_timer_b() final             { return byte(0x27, 3, 1); }
	int enable_timer_a() final             { return byte(0x27, 2, 1); }
	int load_timer_b() final               { return byte(0x27, 1, 1); }
	int load_timer_a() final               { return byte(0x27, 0, 1); }
	int multi_block_freq(int num) final    { return word(0xac, 0, 6, 0xa8, 0, 8, num); }

	// per-channel registers
	int ch_block_freq(int choffs) final    { return word(0xa4, 0, 6, 0xa0, 0, 8, choffs); }
	int ch_feedback(int choffs) final      { return byte(0xb0, 3, 3, choffs); }
	int ch_algorithm(int choffs) final     { return byte(0xb0, 0, 3, choffs); }
	int ch_output_any(int choffs) final    { return IsOpnA ? byte(0xb4, 6, 2, choffs) : 1; }
	int ch_output_0(int choffs) final      { return IsOpnA ? byte(0xb4, 7, 1, choffs) : 1; }
	int ch_output_1(int choffs) final      { return IsOpnA ? byte(0xb4, 6, 1, choffs) : 0; }
	int ch_output_2(int choffs) final      { return 0; }
	int ch_output_3(int choffs) final      { return 0; }
	int ch_lfo_am_sens(int choffs) final   { return IsOpnA ? byte(0xb4, 4, 2, choffs) : 0; }
	int ch_lfo_pm_sens(int choffs) final   { return IsOpnA ? byte(0xb4, 0, 3, choffs) : 0; }

	// per-operator registers
	int op_detune(int opoffs) final        { return byte(0x30, 4, 3, opoffs); }
	int op_multiple(int opoffs) final      { return byte(0x30, 0, 4, opoffs); }
	int op_total_level(int opoffs) final   { return byte(0x40, 0, 7, opoffs); }
	int op_ksr(int opoffs) final           { return byte(0x50, 6, 2, opoffs); }
	int op_attack_rate(int opoffs) final   { return byte(0x50, 0, 5, opoffs); }
	int op_decay_rate(int opoffs) final    { return byte(0x60, 0, 5, opoffs); }
	int op_lfo_am_enable(int opoffs) final { return IsOpnA ? byte(0x60, 7, 1, opoffs) : 0; }
	int op_sustain_rate(int opoffs) final  { return byte(0x70, 0, 5, opoffs); }
	int op_sustain_level(int opoffs) final { return byte(0x80, 4, 4, opoffs); }
	int op_release_rate(int opoffs) final  { return byte(0x80, 0, 4, opoffs); }
	int op_ssg_eg_enable(int opoffs) final { return byte(0x90, 3, 1, opoffs); }
	int op_ssg_eg_mode(int opoffs) final   { return byte(0x90, 0, 3, opoffs); }

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
	byte m_lfo_am;                     // current LFO AM value
	byte m_regdata[REGISTERS];         // register data
	int m_waveform[WAVEFORMS][WAVEFORM_LENGTH]; // waveforms
};

using opn_registers = opn_registers_base<false>;
using opna_registers = opn_registers_base<true>;



//*********************************************************
//  OPN IMPLEMENTATION CLASSES
//*********************************************************

// A note about prescaling and sample rates.
//
// YM2203, YM2608, and YM2610 contain an onboard SSG (basically, a YM2149).
// In order to properly generate sound at fully fidelity, the output sample
// rate of the YM2149 must be input_clock / 8. This is much higher than the
// FM needs, but in the interest of keeping things simple, the OPN generate
// functions will output at the higher rate and just replicate the last FM
// sample as many times as needed.
//
// To make things even more complicated, the YM2203 and YM2608 allow for
// software-controlled prescaling, which affects the FM and SSG clocks in
// different ways. There are three settings: divide by 6/4 (FM/SSG); divide
// by 3/2; and divide by 2/1.
//
// Thus, the minimum output sample rate needed by each part of the chip
// varies with the prescale as follows:
//
//             ---- YM2203 -----    ---- YM2608 -----    ---- YM2610 -----
// Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
//     6         /72      /16         /144     /32          /144    /32
//     3         /36      /8          /72      /16
//     2         /24      /4          /48      /8
//
// If we standardized on the fastest SSG rate, we'd end up with the following
// (ratios are output_samples:source_samples):
//
//             ---- YM2203 -----    ---- YM2608 -----    ---- YM2610 -----
//              rate = clock/4       rate = clock/8       rate = clock/16
// Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
//     6         18:1     4:1         18:1     4:1          9:1    2:1
//     3          9:1     2:1          9:1     2:1
//     2          6:1     1:1          6:1     1:1
//
// However, that's a pretty big performance hit for minimal gain. Going to
// the other extreme, we could standardize on the fastest FM rate, but then
// at least one prescale case (3) requires the FM to be smeared across two
// output samples:
//
//             ---- YM2203 -----    ---- YM2608 -----    ---- YM2610 -----
//              rate = clock/24      rate = clock/48      rate = clock/144
// Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
//     6          3:1     2:3          3:1     2:3          1:1    2:9
//     3        1.5:1     1:3        1.5:1     1:3
//     2          1:1     1:6          1:1     1:6
//
// Stepping back one factor of 2 addresses that issue:
//
//             ---- YM2203 -----    ---- YM2608 -----    ---- YM2610 -----
//              rate = clock/12      rate = clock/24      rate = clock/144
// Prescale    FM rate  SSG rate    FM rate  SSG rate    FM rate  SSG rate
//     6          6:1     4:3          6:1     4:3          1:1    2:9
//     3          3:1     2:3          3:1     2:3
//     2          2:1     1:3          2:1     1:3
//
// This gives us three levels of output fidelity:
//    OPN_FIDELITY_MAX -- highest sample rate, using fastest SSG rate
//    OPN_FIDELITY_MIN -- lowest sample rate, using fastest FM rate
//    OPN_FIDELITY_MED -- medium sample rate such that FM is never smeared
//
// At the maximum clocks for YM2203/YM2608 (4Mhz/8MHz), these rates will
// end up as:
//    OPN_FIDELITY_MAX = 1000kHz
//    OPN_FIDELITY_MIN =  166kHz
//    OPN_FIEDLITY_MED =  333kHz


// ======================> opn_fidelity

enum opn_fidelity : byte
{
	OPN_FIDELITY_MAX,
	OPN_FIDELITY_MIN,
	OPN_FIDELITY_MED,

	OPN_FIDELITY_DEFAULT = OPN_FIDELITY_MAX
};


// ======================> ssg_resampler

template<typename OutputType, int FirstOutput, boolean MixTo1>
class ssg_resampler
{
private:
	// helper to add the last computed value to the sums, applying the given scale
	void add_last(int &sum0, int &sum1, int &sum2, int scale = 1);

	// helper to clock a new value and then add it to the sums, applying the given scale
	void clock_and_add(int &sum0, int &sum1, int &sum2, int scale = 1);

	// helper to write the sums to the appropriate outputs, applying the given
	// divisor to the final result
	void write_to_output(OutputType *output, int sum0, int sum1, int sum2, int divisor = 1);

public:
	// constructor
	ssg_resampler(ssg_engine &ssg);

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// get the current sample index
	int sampindex() final { return m_sampindex; }

	// configure the ratio
	void configure(byte outsamples, byte srcsamples);

	// resample
	void resample(OutputType *output, int numsamples)
	{
		(this.*m_resampler)(output, numsamples);
	}

private:
	// resample SSG output to the target at a rate of 1 SSG sample
	// to every n output samples
	template<int Multiplier>
	void resample_n_1(OutputType *output, int numsamples);

	// resample SSG output to the target at a rate of n SSG samples
	// to every 1 output sample
	template<int Divisor>
	void resample_1_n(OutputType *output, int numsamples);

	// resample SSG output to the target at a rate of 9 SSG samples
	// to every 2 output samples
	void resample_2_9(OutputType *output, int numsamples);

	// resample SSG output to the target at a rate of 3 SSG samples
	// to every 1 output sample
	void resample_1_3(OutputType *output, int numsamples);

	// resample SSG output to the target at a rate of 3 SSG samples
	// to every 2 output samples
	void resample_2_3(OutputType *output, int numsamples);

	// resample SSG output to the target at a rate of 3 SSG samples
	// to every 4 output samples
	void resample_4_3(OutputType *output, int numsamples);

	// no-op resampler
	void resample_nop(OutputType *output, int numsamples);

	// define a pointer type
	using resample_func = void (ssg_resampler.*)(OutputType *output, int numsamples);

	// internal state
	ssg_engine &m_ssg;
	int m_sampindex;
	resample_func m_resampler;
	ssg_engine.output_data m_last;
};


// ======================> ym2203

class ym2203
{
public:
	using fm_engine = fm_engine_base<opn_registers>;
	static final int FM_OUTPUTS = fm_engine.OUTPUTS;
	static final int SSG_OUTPUTS = ssg_engine.OUTPUTS;
	static final int OUTPUTS = FM_OUTPUTS + SSG_OUTPUTS;
	using output_data = ymfm_output<OUTPUTS>;

	// constructor
	ym2203(ymfm_interface &intf);

	// configuration
	void ssg_override(ssg_override &intf) { m_ssg.override(intf); }
	void set_fidelity(opn_fidelity fidelity) { m_fidelity = fidelity; update_prescale(m_fm.clock_prescale()); }

	// reset
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// pass-through helpers
	int sample_rate(int input_clock) final
	{
		switch (m_fidelity)
		{
			case OPN_FIDELITY_MIN:	return input_clock / 24;
			case OPN_FIDELITY_MED:	return input_clock / 12;
			default:
			case OPN_FIDELITY_MAX:	return input_clock / 4;
		}
	}
	int ssg_effective_clock(int input_clock) final { int scale = m_fm.clock_prescale() * 2 / 3; return input_clock * 2 / scale; }
	void invalidate_caches() { m_fm.invalidate_caches(); }

	// read access
	byte read_status();
	byte read_data();
	byte read(int offset);

	// write access
	void write_address(byte data);
	void write_data(byte data);
	void write(int offset, byte data);

	// generate one sample of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// internal helpers
	void update_prescale(byte prescale);
	void clock_fm();

	// internal state
	opn_fidelity m_fidelity;            // configured fidelity
	byte m_address;                  // address register
	byte m_fm_samples_per_output;    // how many samples to repeat
	fm_engine.output_data m_last_fm;   // last FM output
	fm_engine m_fm;                     // core FM engine
	ssg_engine m_ssg;                   // SSG engine
	ssg_resampler<output_data, 1, false> m_ssg_resampler; // SSG resampler helper
};



//*********************************************************
//  OPNA IMPLEMENTATION CLASSES
//*********************************************************

// ======================> ym2608

class ym2608
{
	static final byte STATUS_ADPCM_B_EOS = 0x04;
	static final byte STATUS_ADPCM_B_BRDY = 0x08;
	static final byte STATUS_ADPCM_B_ZERO = 0x10;
	static final byte STATUS_ADPCM_B_PLAYING = 0x20;

public:
	using fm_engine = fm_engine_base<opna_registers>;
	static final int FM_OUTPUTS = fm_engine.OUTPUTS;
	static final int SSG_OUTPUTS = 1;
	static final int OUTPUTS = FM_OUTPUTS + SSG_OUTPUTS;
	using output_data = ymfm_output<OUTPUTS>;

	// constructor
	ym2608(ymfm_interface &intf);

	// configuration
	void ssg_override(ssg_override &intf) { m_ssg.override(intf); }
	void set_fidelity(opn_fidelity fidelity) { m_fidelity = fidelity; update_prescale(m_fm.clock_prescale()); }

	// reset
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// pass-through helpers
	int sample_rate(int input_clock) final
	{
		switch (m_fidelity)
		{
			case OPN_FIDELITY_MIN:	return input_clock / 48;
			case OPN_FIDELITY_MED:	return input_clock / 24;
			default:
			case OPN_FIDELITY_MAX:	return input_clock / 8;
		}
	}
	int ssg_effective_clock(int input_clock) final { int scale = m_fm.clock_prescale() * 2 / 3; return input_clock / scale; }
	void invalidate_caches() { m_fm.invalidate_caches(); }

	// read access
	byte read_status();
	byte read_data();
	byte read_status_hi();
	byte read_data_hi();
	byte read(int offset);

	// write access
	void write_address(byte data);
	void write_data(byte data);
	void write_address_hi(byte data);
	void write_data_hi(byte data);
	void write(int offset, byte data);

	// generate one sample of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// internal helpers
	void update_prescale(byte prescale);
	void clock_fm_and_adpcm();

	// internal state
	opn_fidelity m_fidelity;            // configured fidelity
	int m_address;                 // address register
	byte m_fm_samples_per_output;    // how many samples to repeat
	byte m_irq_enable;               // IRQ enable register
	byte m_flag_control;             // flag control register
	fm_engine.output_data m_last_fm;   // last FM output
	fm_engine m_fm;                     // core FM engine
	ssg_engine m_ssg;                   // SSG engine
	ssg_resampler<output_data, 2, true> m_ssg_resampler; // SSG resampler helper
	adpcm_a_engine m_adpcm_a;           // ADPCM-A engine
	adpcm_b_engine m_adpcm_b;           // ADPCM-B engine
};


// ======================> ymf288

class ymf288
{
public:
	using fm_engine = fm_engine_base<opna_registers>;
	static final int FM_OUTPUTS = fm_engine.OUTPUTS;
	static final int SSG_OUTPUTS = 1;
	static final int OUTPUTS = FM_OUTPUTS + SSG_OUTPUTS;
	using output_data = ymfm_output<OUTPUTS>;

	// constructor
	ymf288(ymfm_interface &intf);

	// configuration
	void ssg_override(ssg_override &intf) { m_ssg.override(intf); }
	void set_fidelity(opn_fidelity fidelity) { m_fidelity = fidelity; update_prescale(); }

	// reset
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// pass-through helpers
	int sample_rate(int input_clock) final
	{
		switch (m_fidelity)
		{
			case OPN_FIDELITY_MIN:	return input_clock / 144;
			case OPN_FIDELITY_MED:	return input_clock / 144;
			default:
			case OPN_FIDELITY_MAX:	return input_clock / 16;
		}
	}
	int ssg_effective_clock(int input_clock) final { return input_clock / 4; }
	void invalidate_caches() { m_fm.invalidate_caches(); }

	// read access
	byte read_status();
	byte read_data();
	byte read_status_hi();
	byte read(int offset);

	// write access
	void write_address(byte data);
	void write_data(byte data);
	void write_address_hi(byte data);
	void write_data_hi(byte data);
	void write(int offset, byte data);

	// generate one sample of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// internal helpers
	boolean ymf288_mode() { return ((m_fm.regs().read(0x20) & 0x02) != 0); }
	void update_prescale();
	void clock_fm_and_adpcm();

	// internal state
	opn_fidelity m_fidelity;            // configured fidelity
	int m_address;                 // address register
	byte m_fm_samples_per_output;    // how many samples to repeat
	byte m_irq_enable;               // IRQ enable register
	byte m_flag_control;             // flag control register
	fm_engine.output_data m_last_fm;   // last FM output
	fm_engine m_fm;                     // core FM engine
	ssg_engine m_ssg;                   // SSG engine
	ssg_resampler<output_data, 2, true> m_ssg_resampler; // SSG resampler helper
	adpcm_a_engine m_adpcm_a;           // ADPCM-A engine
};


// ======================> ym2610/ym2610b

class ym2610
{
	static final byte EOS_FLAGS_MASK = 0xbf;

public:
	using fm_engine = fm_engine_base<opna_registers>;
	static final int FM_OUTPUTS = fm_engine.OUTPUTS;
	static final int SSG_OUTPUTS = 1;
	static final int OUTPUTS = FM_OUTPUTS + SSG_OUTPUTS;
	using output_data = ymfm_output<OUTPUTS>;

	// constructor
	ym2610(ymfm_interface &intf, byte channel_mask = 0x36);

	// configuration
	void ssg_override(ssg_override &intf) { m_ssg.override(intf); }
	void set_fidelity(opn_fidelity fidelity) { m_fidelity = fidelity; update_prescale(); }

	// reset
	void reset();

	// save/restore
	void save_restore(ymfm_saved_state &state);

	// pass-through helpers
	int sample_rate(int input_clock) final
	{
		switch (m_fidelity)
		{
			case OPN_FIDELITY_MIN:	return input_clock / 144;
			case OPN_FIDELITY_MED:	return input_clock / 144;
			default:
			case OPN_FIDELITY_MAX:	return input_clock / 16;
		}
	}
	int ssg_effective_clock(int input_clock) final { return input_clock / 4; }
	void invalidate_caches() { m_fm.invalidate_caches(); }

	// read access
	byte read_status();
	byte read_data();
	byte read_status_hi();
	byte read_data_hi();
	byte read(int offset);

	// write access
	void write_address(byte data);
	void write_data(byte data);
	void write_address_hi(byte data);
	void write_data_hi(byte data);
	void write(int offset, byte data);

	// generate one sample of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// internal helpers
	void update_prescale();
	void clock_fm_and_adpcm();

	// internal state
	opn_fidelity m_fidelity;            // configured fidelity
	int m_address;                 // address register
	byte final m_fm_mask;            // FM channel mask
	byte m_fm_samples_per_output;    // how many samples to repeat
	byte m_eos_status;               // end-of-sample signals
	byte m_flag_mask;                // flag mask control
	fm_engine.output_data m_last_fm;   // last FM output
	fm_engine m_fm;                     // core FM engine
	ssg_engine m_ssg;                   // core FM engine
	ssg_resampler<output_data, 2, true> m_ssg_resampler; // SSG resampler helper
	adpcm_a_engine m_adpcm_a;           // ADPCM-A engine
	adpcm_b_engine m_adpcm_b;           // ADPCM-B engine
};

class ym2610b : public ym2610
{
public:
	// constructor
	ym2610b(ymfm_interface &intf) : ym2610(intf, 0x3f) { }
};


// ======================> ym2612

class ym2612
{
public:
	using fm_engine = fm_engine_base<opna_registers>;
	static final int OUTPUTS = fm_engine.OUTPUTS;
	using output_data = fm_engine.output_data;

	// constructor
	ym2612(ymfm_interface &intf);

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
	void write_data_hi(byte data);
	void write(int offset, byte data);

	// generate one sample of sound
	void generate(output_data *output, int numsamples = 1);

protected:
	// simulate the DAC discontinuity
	final int dac_discontinuity(int value) final { return (value < 0) ? (value - 3) : (value + 4); }

	// internal state
	int m_address;              // address register
	int m_dac_data;             // 9-bit DAC data
	byte m_dac_enable;            // DAC enabled?
	fm_engine m_fm;                  // core FM engine
};


// ======================> ym3438

class ym3438 : public ym2612
{
public:
	ym3438(ymfm_interface &intf) : ym2612(intf) { }

	// generate one sample of sound
	void generate(output_data *output, int numsamples = 1);
};


// ======================> ymf276

class ymf276 : public ym2612
{
public:
	ymf276(ymfm_interface &intf) : ym2612(intf) { }

	// generate one sample of sound
	void generate(output_data *output, int numsamples);
};

}


#endif // YMFM_OPN_H
