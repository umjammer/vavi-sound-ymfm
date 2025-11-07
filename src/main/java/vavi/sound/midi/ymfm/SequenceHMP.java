package vavi.sound.midi.ymfm;

import java.util.ArrayList;

import vavi.util.ByteUtil;


public class SequenceHMP extends SequenceMID {

    static class HMPTrack extends SequenceMID.MIDTrack {
        public HMPTrack(byte[] data, int size, SequenceHMP sequence) {
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

    public SequenceHMP() {
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
