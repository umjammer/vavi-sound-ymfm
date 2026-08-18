/*
 * BSD 3-Clause License
 *
 * Copyright (c) 2021-2024, Devin Acker
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package vavi.sound.midi.ymfm;


/**
 * @see "https://github.com/devinacker/ymfmidi"
 */
public class OplPatch {

    /** one carrier/modulator pair in a patch, out of a possible two */
    public static class PatchVoice {

        // regs 0x20+
        public byte[] op_mode = new byte[2];
        // regs 0x40+ (upper bits)
        public byte[] op_ksr = new byte[2];
        // regs 0x40+ (lower bits)
        public byte[] op_level = new byte[2];
        // regs 0x60+
        public byte[] op_ad = new byte[2];
        // regs 0x80+
        public byte[] op_sr = new byte[2];
        // regs 0xC0+
        public byte conn = 0;
        // regs 0xE0+
        public byte[] op_wave = new byte[2];

        // MIDI note offset
        public byte tune = 0; // signed
        // frequency multiplier
        public double finetune = 1.0;
    }

    public String name;
    // true 4op
    public boolean fourOp = false;
    // only valid if fourOp = false
    public boolean dualTwoOp = false;
    public byte fixedNote = 0;
    // MIDI velocity offset
    public byte velocity = 0; // signed

    public PatchVoice[] voice = {new PatchVoice(), new PatchVoice()};
}
