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
//  SSG IMPLEMENTATION CLASSES
//*********************************************************

// ======================> ym2149

import vavi.sound.ymfm.debug.ymfm_interface;
import vavi.sound.ymfm.debug.ymfm_saved_state;


// ym2149 is just an SSG with no FM part, but we expose FM-like parts so that it
// integrates smoothly with everything else; they just don't do anything
class ym2149
{

	public static final int OUTPUTS = ssg_engine.OUTPUTS;
	public static final int SSG_OUTPUTS = ssg_engine.OUTPUTS;
	using output_data = ymfm_output<OUTPUTS>;

	// constructor
	public ym2149(ymfm_interface intf);

	// configuration
	public void ssg_override(ssg_override intf) { m_ssg.override(intf); }

	// reset
	public void reset();

	// save/restore
	public void save_restore(ymfm_saved_state state);

	// pass-through helpers
	public final int sample_rate(int input_clock)  { return input_clock / ssg_engine.CLOCK_DIVIDER / 8; }

	// read access
	public byte read_data();
	public byte read(int offset);

	// write access
	public void write_address(byte data);
	public void write_data(byte data);
	public void write(int offset, byte data);

	// generate one sample of sound
	public void generate(output_data output, int numsamples /* = 1 */);


	// internal state
	protected byte m_address;               // address register
	protected ssg_engine m_ssg;                // SSG engine
}
