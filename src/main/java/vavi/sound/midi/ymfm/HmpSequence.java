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

import java.util.ArrayList;
import javax.sound.midi.InvalidMidiDataException;

import vavi.util.ByteUtil;


/**
 * @see "https://github.com/devinacker/ymfmidi"
 */
public class HmpSequence extends MidSequence {

    static class HMPTrack extends MidSequence.MIDTrack {
        public HMPTrack(byte[] data, int size, HmpSequence sequence) {
            super(data, size, sequence);
        }

        @Override
        protected int readDelay() {
            int delay = 0;
            int data;
            int shift = 0;

            do {
                if (m_pos >= m_size) {
                    break;
                }

                data = m_data[m_pos++] & 0xFF;
                delay |= ((data & 0x7f) << shift);
                shift += 7;
            } while ((data & 0x80) == 0 && (m_pos < m_size));

            return delay;
        }
    }

    /** */
    public HmpSequence(float divisionType, int resolution) throws InvalidMidiDataException {
        super(divisionType, resolution);
        init();
    }

    /** */
    public HmpSequence(float divisionType, int resolution, int numTracks) throws InvalidMidiDataException {
        super(divisionType, resolution, numTracks);
        init();
    }

    private void init() {
        m_type = 1;
        m_ticksPerBeat = 120;
        m_ticksPerSec = 120;
    }

    @Override
    public void setTimePerBeat(int usec) {
    }

    public static boolean isValid(byte[] data) {
        int size = data.length;
        if (size < 0x40)
            return false;

        // Check for signature "HMIMIDIP" (8 bytes)
        byte[] signature = new byte[] { 'H', 'M', 'I', 'M', 'I', 'D', 'I', 'P' };

        if (size < signature.length) {
            return false; // Not enough data for signature
        }

        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void read(byte[] data) {
        int size = data.length;

        int numTracks = ByteUtil.readLeInt(data, 0x30);

        m_ticksPerBeat = ByteUtil.readLeInt(data, 0x34);
        m_ticksPerSec  = ByteUtil.readLeInt(data, 0x38);

        // longer signature = extended format
        int data8 = data[8] & 0xFF;
        int offset = (data8 == 0) ? 0x308 : 0x388;

        if (m_tracks == null) {
            m_tracks = new ArrayList<>();
        }

        for (int i = 0; i < numTracks; i++) {
            if (offset + 12 >= size)
                break;

            int trackLen = ByteUtil.readLeInt(data, offset + 4);
            if (offset + trackLen >= size)
                // try to handle a malformed/truncated chunk
                trackLen = (size - offset);

            if (trackLen <= 12)
                break;

            byte[] trackDataChunk = new byte[trackLen - 12];
            System.arraycopy(data, offset + 12, trackDataChunk, 0, trackLen - 12);

            m_tracks.add(new HMPTrack(trackDataChunk, trackLen - 12, this));

            offset += trackLen;
        }
    }
}
