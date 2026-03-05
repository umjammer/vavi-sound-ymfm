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

import java.nio.charset.StandardCharsets;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Track;

import vavi.util.ByteUtil;


/**
 * @see "https://github.com/devinacker/ymfmidi"
 */
public class XmiSequence extends MidSequence {

    static class XMITrack extends MIDTrack {

        public XMITrack(byte[] data, int size, XmiSequence sequence) {
            super(data, size, sequence);
            m_initDelay = false;
            m_useRunningStatus = false;
            m_useNoteDuration = true;
        }

        @Override
        protected int readDelay() {
            int delay = 0;
            int dataByte = 0;

            if (m_pos >= m_size || (m_data[m_pos] & 0x80) != 0)
                return 0;

            do {
                dataByte = m_data[m_pos] & 0xFF; // Read byte as unsigned
                if ((dataByte & 0x80) == 0) {
                    delay += dataByte;
                    m_pos++;
                }
            } while ((dataByte == 0x7f) && (m_pos < m_size));

            return delay;
        }
    }

    /** */
    public XmiSequence(float divisionType, int resolution) throws InvalidMidiDataException {
        super(divisionType, resolution);
        init();
    }

    /** */
    public XmiSequence(float divisionType, int resolution, int numTracks) throws InvalidMidiDataException {
        super(divisionType, resolution, numTracks);
        init();
    }

    private void init() {
        m_type = 2;
        m_ticksPerBeat = 0; // unused
        m_ticksPerSec = 120;
    }

    /** */
    public void setTimePerBeat(long usec) {
        double usecPerTick = (double) usec / ((usec * 3) / 25000);
        m_ticksPerSec = 1000000.0 / usecPerTick;
    }

    /** */
    public static boolean isValid(byte[] data) {
        int size = data.length;
        // need at least 2 root chunks and one EVNT chunk header
        if (size < 12)
            return false;

        // Check for "FORM" at offset 0
        if (!new String(data, 0, 4, StandardCharsets.US_ASCII).equals("FORM"))
            return false;

        // Check for "XDIR" at offset 8
        if (!new String(data, 8, 4, StandardCharsets.US_ASCII).equals("XDIR"))
            return false;

        return true;
    }

    @Override
    public void read(byte[] data) {
        int size = data.length;
        int chunkSize;
        int currentPos = 0;
        int remainingSize = size;

        while ((chunkSize = readRootChunk(data, currentPos, remainingSize)) != 0) {
            currentPos += chunkSize;
            remainingSize -= chunkSize;
        }
    }

    /** */
    private int readRootChunk(byte[] data, int offset, int size) {
        // need at least a root chunk and one subchunk (and its contents)
        if (size > 12 + 8) {
            // length of the root chunk
            int rootLen = ByteUtil.readBeInt(data, offset + 4);
            if (rootLen % 2 != 0) {
                rootLen++;
            }
            // offset to the current sub-chunk
            int subChunkOffset = offset + 12;
            // offset to the data after the root chunk
            int rootEnd = Math.min(rootLen + 8, size);

            String chunkID = new String(data, offset, 4, StandardCharsets.US_ASCII);

            if (chunkID.equals("FORM")) {
                int chunkLen;

                while (subChunkOffset < offset + rootEnd) {
                    int bytesOffset = subChunkOffset;

                    chunkID = new String(data, bytesOffset, 4, StandardCharsets.US_ASCII);
                    chunkLen = ByteUtil.readBeInt(data, bytesOffset + 4);
                    if (chunkLen % 2 != 0) {
                        chunkLen++;
                    }

                    // move to next subchunk
                    int nextSubChunkOffset = subChunkOffset + chunkLen + 8;
                    if (nextSubChunkOffset > offset + rootEnd) {
                        // try to handle a malformed/truncated chunk
                        chunkLen -= (nextSubChunkOffset - (offset + rootEnd));
                        nextSubChunkOffset = offset + rootEnd;
                    }

                    if (chunkID.equals("EVNT")) {
                        int dataStart = bytesOffset + 8;
                        int trackDataLen = chunkLen;

                        if (dataStart + trackDataLen <= data.length) {
                            byte[] trackData = new byte[trackDataLen];
                            System.arraycopy(data, dataStart, trackData, 0, trackDataLen);
                            m_tracks.add(new XMITrack(trackData, trackDataLen, this));
                        }
                    }

                    subChunkOffset = nextSubChunkOffset;
                }
            } else if (chunkID.equals("CAT ")) {
                while (subChunkOffset < offset + rootEnd) {
                    subChunkOffset += readRootChunk(data, subChunkOffset, size - (subChunkOffset - offset));
                }
            }

            return rootEnd;
        }

        return 0;
    }

    @Override
    public int numSongs() {
        return 1;
    }

    @Override
    public long update(OplPlayer player) {
        long tickDelay = 0xffff_ffffL; // UINT_MAX;

        boolean tracksAtEnd = true;

        if (m_songNum < m_tracks.size()) {
            tickDelay = m_tracks.get(m_songNum).update(player);
            tracksAtEnd = m_tracks.get(m_songNum).atEnd();
        }

        if (tracksAtEnd) {
            reset();
            m_atEnd = true;
            return 0;
        }

        m_atEnd = false;

        for (MIDTrack track : m_tracks)
            track.advance((int) tickDelay);

        double samplesPerTick = player.sampleRate() / m_ticksPerSec;

        return Math.round(tickDelay * samplesPerTick);
    }

    @Override
    public void convert() throws InvalidMidiDataException {
        if (m_songNum < m_tracks.size()) {
            Track newTrack = createTrack();
            m_tracks.get(m_songNum).convert(newTrack::add);
        }
    }
}
