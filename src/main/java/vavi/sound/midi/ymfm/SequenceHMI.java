package vavi.sound.midi.ymfm;

import java.util.Arrays;

import vavi.util.ByteUtil;


public class SequenceHMI extends SequenceMID {

    static class HMITrack extends MIDTrack {
        public HMITrack(byte[] data, int size, SequenceHMI sequence) {
            super(data, size, sequence);
            m_useNoteDuration = true;
        }

        @Override
        protected boolean metaEvent(OPLPlayer player) {
            if (m_status == (byte) 0xFE) {
                if (m_pos >= m_size) {
                    // Should not happen if m_pos is checked before access
                    return false;
                }
                byte data = m_data[m_pos++];

                if (data == 0x10) {
                    if (m_pos + 7 >= m_size)
                        return false;

                    // C++: m_pos += m_data[m_pos + 2] + 7;
                    // m_data[m_pos + 2] is the length byte at offset 2 within the event data
                    if (m_pos + 2 >= m_size) return false;
                    m_pos += (m_data[m_pos + 2] & 0xFF) + 7;
                } else if (data == 0x12)
                    m_pos += 2;
                else if (data == 0x13)
                    m_pos += 10;
                else if (data == 0x14) // loop start
                    m_pos += 2;
                else if (data == 0x15) // loop end (Corrected C++ bug: 'm_pos == 0x15' should be 'data == 0x15')
                    m_pos += 6;
                else
                    return false;

                return m_pos < m_size;
            } else {
                return super.metaEvent(player);
            }
        }
    }

    public SequenceHMI() {
        super();
        m_type = 1;
    }

    public void setTimePerBeat(long usec) {
        // ? (Translated comment)
    }

    public static boolean isValid(byte[] data, int size) {
        if (size < 0xEC)
            return false;

        // C++: return !memcmp(data, "HMI-MIDISONG061595", 18);
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
        long numTracksLong  = ByteUtil.readLeInt(data, 0xE4);
        long trackTableLong = ByteUtil.readLeInt(data, 0xE8);

        // Cast to int, assuming the number of tracks and track table offset fit in standard int range for indices/counts.
        int numTracks = (int) numTracksLong;
        int trackTable = (int) trackTableLong;

        m_ticksPerBeat = ByteUtil.readLeShort(data, 0xD2) & 0xffff;
        m_ticksPerSec  = ByteUtil.readLeShort(data, 0xD4) & 0xffff;

        for (int i = 0; i < numTracks; i++) {
            int trackPtr = trackTable + 4 * i;

            if (trackPtr + 8 >= size)
                break;

            // Read pointers and lengths as long (unsigned 32-bit)
            long offsetLong = ByteUtil.readLeInt(data, trackPtr);
            if (offsetLong >= size)
                continue;

            // Cast to int for array indexing
            int offset = (int) offsetLong;

            long trackLenLong = ByteUtil.readLeInt(data, trackPtr + 4) - offsetLong;

            // Cast to int for length, assuming it fits
            int trackLen = (int) trackLenLong;

            if (((offset + trackLen) > size) || (i == numTracks - 1)) {
                // stop at the end of the file if this is the last track
                // (or if the track is just malformed/truncated) (Translated comment)
                trackLen = size - offset;
            }

            // check if trackLen is non-positive or if the track signature is wrong
            if ((trackLen <= 0x5b) || !Arrays.equals(Arrays.copyOfRange(data, offset, offset + 13), "HMI-MIDITRACK".getBytes()))
                continue;

            long trackStartLong = ByteUtil.readLeInt(data, offset + 0x57);
            if (trackStartLong < 0x5b)
                continue;

            int trackStart = (int) trackStartLong;

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
