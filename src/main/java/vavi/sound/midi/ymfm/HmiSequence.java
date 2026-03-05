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

import java.util.Arrays;
import java.util.function.Consumer;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;

import vavi.util.ByteUtil;


/**
 * @see "https://github.com/devinacker/ymfmidi"
 */
public class HmiSequence extends MidSequence {

    static class HMITrack extends MIDTrack {
        public HMITrack(byte[] data, int size, HmiSequence sequence) {
            super(data, size, sequence);
            m_useNoteDuration = true;
        }

        @Override
        protected boolean metaEvent(OplPlayer player) {
            if (m_status == (byte) 0xFE) {
                if (m_pos >= m_size) {
                    // Should not happen if m_pos is checked before access
                    return false;
                }
                byte data = m_data[m_pos++];

                if (data == 0x10) {
                    if (m_pos + 7 >= m_size)
                        return false;

                    m_pos += (m_data[m_pos + 2] & 0xFF) + 7;
                } else if (data == 0x12)
                    m_pos += 2;
                else if (data == 0x13)
                    m_pos += 10;
                else if (data == 0x14) // loop start
                    m_pos += 2;
                else if (m_pos == 0x15) // loop end
                    m_pos += 6;
                else
                    return false;

                return m_pos < m_size;
            } else {
                return super.metaEvent(player);
            }
        }

        @Override
        protected boolean convertMetaEvent(Consumer<MidiEvent> consumer, long tick) throws InvalidMidiDataException {
            if (m_status == (byte) 0xFE) {
                if (m_pos >= m_size) {
                    return false;
                }
                byte data = m_data[m_pos++];

                if (data == 0x10) {
                    if (m_pos + 7 >= m_size)
                        return false;

                    m_pos += (m_data[m_pos + 2] & 0xFF) + 7;
                } else if (data == 0x12)
                    m_pos += 2;
                else if (data == 0x13)
                    m_pos += 10;
                else if (data == 0x14) // loop start
                    m_pos += 2;
                else if (m_pos == 0x15) // loop end
                    m_pos += 6;
                else
                    return false;

                return m_pos < m_size;
            } else {
                return super.convertMetaEvent(consumer, tick);
            }
        }
    }

    /** */
    public HmiSequence(float divisionType, int resolution) throws InvalidMidiDataException {
        super(divisionType, resolution);
        init();
    }

    /** */
    public HmiSequence(float divisionType, int resolution, int numTracks) throws InvalidMidiDataException {
        super(divisionType, resolution, numTracks);
        init();
    }

    private void init() {
        m_type = 1;
    }

    public void setTimePerBeat(long usec) {
        // ?
    }

    public static boolean isValid(byte[] data, int size) {
        if (size < 0xec)
            return false;

        byte[] magic = "HMI-MIDISONG061595".getBytes();
        if (size < magic.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOfRange(data, 0, magic.length), magic);
    }

    @Override
    public void read(byte[] data) {
        int size = data.length;
        // Use long for 32-bit unsigned values read from data
        int numTracks = ByteUtil.readLeInt(data, 0xe4);
        int trackTable = ByteUtil.readLeInt(data, 0xe8);

        m_ticksPerBeat = ByteUtil.readLeShort(data, 0xd2) & 0xffff;
        m_ticksPerSec  = ByteUtil.readLeShort(data, 0xd4) & 0xffff;

        for (int i = 0; i < numTracks; i++) {
            int trackPtr = trackTable + 4 * i;

            if (trackPtr + 8 >= size)
                break;

            // Read pointers and lengths as long (unsigned 32-bit)
            int offset = ByteUtil.readLeInt(data, trackPtr);
            if (offset >= size)
                continue;

            int trackLen = ByteUtil.readLeInt(data, trackPtr + 4) - offset;

            if (((offset + trackLen) > size) || (i == numTracks - 1)) {
                // stop at the end of the file if this is the last track
                // (or if the track is just malformed/truncated) (Translated comment)
                trackLen = size - offset;
            }

            // check if trackLen is non-positive or if the track signature is wrong
            if ((trackLen <= 0x5b) || !Arrays.equals(Arrays.copyOfRange(data, offset, offset + 13), "HMI-MIDITRACK".getBytes()))
                continue;

            int trackStart  = ByteUtil.readLeInt(data, offset + 0x57);
            if (trackStart < 0x5b)
                continue;

            // Create subarray for the track data
            if (offset + trackStart < size) {
                int trackDataSize = trackLen - trackStart;
                byte[] trackData = Arrays.copyOfRange(data, offset + trackStart, offset + trackLen);

                // Add new HMITrack to m_tracks list/vector
                m_tracks.add(new HMITrack(trackData, trackDataSize, this));
            }
        }
    }
}
