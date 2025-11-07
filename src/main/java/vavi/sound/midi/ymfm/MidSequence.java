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
import java.util.Arrays;
import java.util.List;

import javax.sound.midi.InvalidMidiDataException;

import vavi.util.ByteUtil;


/**
 * @see "https://github.com/devinacker/ymfmidi"
 */
public class MidSequence extends OplSequence {

    public static class MIDTrack {

        /** */
        public static class MIDNote {
            byte channel;
            byte note;
            int delay;
        }

        protected MidSequence m_sequence;
        protected byte[] m_data;
        protected int m_pos;
        protected int m_size;
        protected int m_delay;
        protected boolean m_atEnd;
        /** for MIDI running status */
        protected byte m_status;

        // these are used for format-specific track data details
        /** true if there is an initial delay value at the start of the track */
        protected boolean m_initDelay;
        /** true if running status is supported by this format */
        protected boolean m_useRunningStatus;
        /** true if note on events are followed by a length */
        protected boolean m_useNoteDuration;

        protected List<MIDNote> m_notes = new ArrayList<>();

        /** */
        public MIDTrack(byte[] data, int size, MidSequence sequence) {
            // Deep copy of data array
            m_data = Arrays.copyOf(data, size);
            m_size = size;
            m_sequence = sequence;

            m_initDelay = true;
            m_useRunningStatus = true;
            m_useNoteDuration = false;

            reset();
        }

        /** */
        public void reset() {
            m_pos = 0;
            m_delay = 0;
            m_atEnd = false;
            m_status = 0x00;
            m_notes.clear();
        }

        /** */
        public void advance(int time) {
            if (m_atEnd)
                return;

            m_delay -= time;
            if (m_useNoteDuration)
                for (MIDNote note : m_notes)
                    note.delay -= time;
        }

        /** */
        protected int readVLQ() {
            int vlq = 0;
            byte data = 0;

            do {
                if (m_pos >= m_size) {
                    m_atEnd = true;
                    return vlq;
                }
                data = m_data[m_pos++];
                vlq <<= 7;
                vlq |= (data & 0x7f);
            } while ((data & 0x80) != 0 && (m_pos < m_size)); // (data & 0x80) is non-zero

            return vlq;
        }

        /** */
        protected int readDelay() {
            return readVLQ();
        }

        /** */
        protected int minDelay() {
            int delay = m_delay;
            if (m_useNoteDuration)
                for (MIDNote note : m_notes)
                    delay = Math.min(delay, note.delay);
            return delay;
        }

        /** */
        public long update(OplPlayer player) {
            if (m_initDelay && m_pos == 0) {
                m_delay = readDelay();
            }

            if (m_useNoteDuration) {
                for (int i = 0; i < m_notes.size();) {
                    if (m_notes.get(i).delay <= 0) {
                        MIDNote note = m_notes.get(i);
                        player.midiNoteOff(note.channel, note.note);
                        m_notes.set(i, m_notes.get(m_notes.size() - 1));
                        m_notes.remove(m_notes.size() - 1);
                    } else {
                        i++;
                    }
                }
            }

            while (m_delay <= 0) {
                byte[] data = new byte[2];
                MIDNote note = new MIDNote();

                // make sure we have enough data left for one full event
                if (m_size - m_pos < 3) {
                    m_atEnd = true;
                    return 0xffff_ffffL; // UINT_MAX;
                }

                if (!m_useRunningStatus || (m_data[m_pos] & 0x80) != 0) {
                    m_status = m_data[m_pos++];
                }

                switch ((m_status & 0xFF) >> 4) {
                    case 9: // note on
                        data[0] = m_data[m_pos++];
                        data[1] = m_data[m_pos++];
                        player.midiEvent(m_status & 0xff, data[0] & 0xff, data[1] & 0xff);

                        if (m_useNoteDuration) {
                            note.channel = (byte) (m_status & 15);
                            note.note = data[0];
                            note.delay = readVLQ();
                            m_notes.add(note);
                        }
                        break;

                    case 8:  // note off
                    case 10: // polyphonic pressure
                    case 11: // controller change
                    case 14: // pitch bend
                        data[0] = m_data[m_pos++];
                        data[1] = m_data[m_pos++];
                        player.midiEvent(m_status & 0xff, data[0] & 0xff, data[1] & 0xff);
                        break;

                    case 12: // program change
                    case 13: // channel pressure (ignored)
                        data[0] = m_data[m_pos++];
                        player.midiEvent(m_status & 0xff, data[0] & 0xff, 0);
                        break;

                    case 15: // sysex / meta event
                        if (!metaEvent(player)) {
                            m_atEnd = true;
                            return 0xffff_ffffL; // UINT_MAX;
                        }
                        break;
                }

                m_delay += readDelay();
            }

            return minDelay();
        }

        /** */
        protected boolean metaEvent(OplPlayer player) {
            int len; // int is used for uint32_t

            if ((m_status & 0xff) != 0xff) {
                len = readVLQ();
                if (m_pos + len < m_size) {
                    if ((m_status & 0xff) == 0xf0) {
                        byte[] sysexData = Arrays.copyOfRange(m_data, m_pos, m_pos + len);
                        player.midiSysEx(sysexData, len);
                    }
                } else {
                    return false;
                }
            } else {
                if (m_pos >= m_size) {
                    return false;
                }

                byte data = m_data[m_pos++];
                len = readVLQ();

                // end-of-track marker (or data just ran out)
                if ((data & 0xff) == 0x2f || (m_pos + len >= m_size)) {
                    return false;
                }
                // tempo change
                if ((data & 0xff) == 0x51) {
                    m_sequence.setTimePerBeat(ByteUtil.readBe24(m_data, m_pos));
                }
            }

            m_pos += len;
            return true;
        }

        /** */
        public boolean atEnd() {
            return m_atEnd;
        }
    }

    /** */
    protected List<MIDTrack> m_tracks = new ArrayList<>();

    protected int m_type;
    protected int m_ticksPerBeat;
    protected double m_ticksPerSec;

    /** */
    public MidSequence(float divisionType, int resolution) throws InvalidMidiDataException {
        super(divisionType, resolution);
        init();
    }

    /** */
    public MidSequence(float divisionType, int resolution, int numTracks) throws InvalidMidiDataException {
        super(divisionType, resolution, numTracks);
        init();
    }

    /** */
    private void init() {
        m_type = 0;
        m_ticksPerBeat = 24;
        m_ticksPerSec = 48;
    }

    /** */
    public static boolean isValid(byte[] data) {
        int size = data.length;
        if (size < 12)
            return false;

        if (Arrays.equals(Arrays.copyOfRange(data, 0, 4), new byte[]{'M', 'T', 'h', 'd'})) {
            long len = ByteUtil.readBeInt(data, 4);
            if (len < 6) return false;

            int type = ByteUtil.readBeShort(data, 8) & 0xffff;
            if (type > 2) return false;

            return true;
        } else if (Arrays.equals(Arrays.copyOfRange(data, 0, 4), new byte[]{'R', 'I', 'F', 'F'})
                && Arrays.equals(Arrays.copyOfRange(data, 8, 12), new byte[]{'R', 'M', 'I', 'D'})) {
            return true;
        }

        return false;
    }

    @Override
    public void read(byte[] data) {
        int size = data.length;
        // need at least the MIDI header + one track header
        if (size < 23)
            return;

        if (Arrays.equals(Arrays.copyOfRange(data, 0, 4), new byte[]{'R', 'I', 'F', 'F'})) {
            int offset = 12;
            while (offset + 8 < size) {
                byte[] bytes = Arrays.copyOfRange(data, offset, offset + 8);
                int chunkLen = (bytes[4] & 0xFF) | ((bytes[5] & 0xFF) << 8) | ((bytes[6] & 0xFF) << 16) | ((bytes[7] & 0xFF) << 24);
                chunkLen = (chunkLen + 1) & ~1;

                // move to next subchunk
                offset += chunkLen + 8;
                if (offset > size) {
                    // try to handle a malformed/truncated chunk
                    chunkLen -= (offset - size);
                    offset = size;
                }

                if (Arrays.equals(Arrays.copyOfRange(data, offset - (chunkLen + 8), offset - (chunkLen + 4)), new byte[]{'d', 'a', 't', 'a'})) {
                    byte[] midData = Arrays.copyOfRange(data, offset - chunkLen, offset);
                    if (isValid(midData))
                        read(midData);
                    break;
                }
            }
        } else {
            int len = ByteUtil.readBeInt(data, 4);

            m_type = ByteUtil.readBeShort(data, 8) & 0xffff;
            int numTracks = ByteUtil.readBeShort(data, 10) & 0xffff;
            m_ticksPerBeat = ByteUtil.readBeShort(data, 12) & 0xffff;

            int offset = len + 8;
            for (int i = 0; i < numTracks; i++) {
                if (offset + 8 >= size)
                    break;

                byte[] bytes = Arrays.copyOfRange(data, offset, offset + 8);
                if (!Arrays.equals(Arrays.copyOfRange(bytes, 0, 4), new byte[]{'M', 'T', 'r', 'k'}))
                    break;

                int trackLen = ByteUtil.readBeInt(bytes, 4);

                offset += trackLen + 8;
                if (offset > size) {
                    // try to handle a malformed/truncated chunk
                    trackLen -= (offset - size);
                    offset = size;
                }

                // Get slice of data for the track chunk data
                byte[] trackData = Arrays.copyOfRange(data, offset - trackLen, offset);
                m_tracks.add(new MIDTrack(trackData, trackLen, this));
            }
        }
    }

    @Override
    public void reset() {
        super.reset();
        setDefaults();

        for (MIDTrack track : m_tracks)
            track.reset();
    }

    protected void setDefaults() {
        setTimePerBeat(500000);
    }

    public void setTimePerBeat(int usec) {
        double usecPerTick = (double) usec / m_ticksPerBeat;
        m_ticksPerSec = 1000000.0 / usecPerTick;
    }

    @Override
    public int numSongs() {
        if (m_type != 2)
            return 1;
        else
            return m_tracks.size();
    }

    @Override
    public long update(OplPlayer player) {
        long tickDelay = 0xffff_ffffL; // UINT_MAX;

        boolean tracksAtEnd = true;

        if (m_type != 2) {
            for (MIDTrack track : m_tracks) {
                if (!track.atEnd()) {
                    tickDelay = Math.min(tickDelay, track.update(player));
                }
                tracksAtEnd &= track.atEnd();
            }
        } else if (m_songNum < m_tracks.size()) {
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
}
