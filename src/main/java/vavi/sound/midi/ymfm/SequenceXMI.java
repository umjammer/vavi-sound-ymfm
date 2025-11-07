package vavi.sound.midi.ymfm;

import java.nio.charset.StandardCharsets;

import vavi.util.ByteUtil;


public class SequenceXMI extends SequenceMID {

    static class XMITrack extends MIDTrack {

        public XMITrack(byte[] data, int size, SequenceXMI sequence) {
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

    public SequenceXMI() {
        super();
        m_type = 2;
        m_ticksPerBeat = 0; // unused
        m_ticksPerSec = 120;
    }

    /** */
    public void setTimePerBeat(long usec) {
        double usecPerTick = (double) usec / ((usec * 3.0) / 25000.0);
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
        long chunkSize;
        int currentPos = 0;
        int remainingSize = size;

        while ((chunkSize = readRootChunk(data, currentPos, remainingSize)) != 0) {
            currentPos += (int) chunkSize;
            remainingSize -= (int) chunkSize;
        }
    }

    /** */
    private long readRootChunk(byte[] data, int offset, int size) {
        // need at least a root chunk and one subchunk (and its contents)
        if (size > 12 + 8) {
            // length of the root chunk
            long rootLen = ByteUtil.readBeInt(data, offset + 4);
            if (rootLen % 2 != 0) {
                rootLen++;
            }
            // offset to the current sub-chunk
            long subChunkOffset = offset + 12;
            // offset to the data after the root chunk
            long rootEnd = Math.min(rootLen + 8, (long) size);

            String chunkID = new String(data, offset, 4, StandardCharsets.US_ASCII);

            if (chunkID.equals("FORM")) {
                long chunkLen;

                while (subChunkOffset < offset + rootEnd) {
                    int bytesOffset = (int) subChunkOffset;

                    chunkID = new String(data, bytesOffset, 4, StandardCharsets.US_ASCII);
                    chunkLen = ByteUtil.readBeInt(data, bytesOffset + 4);
                    // chunkLen = (chunkLen + 1) & ~1; // C++ padding
                    if (chunkLen % 2 != 0) {
                        chunkLen++;
                    }

                    // move to next subchunk
                    long nextSubChunkOffset = subChunkOffset + chunkLen + 8;
                    if (nextSubChunkOffset > offset + rootEnd) {
                        // try to handle a malformed/truncated chunk
                        chunkLen -= (nextSubChunkOffset - (offset + rootEnd));
                        nextSubChunkOffset = offset + rootEnd;
                    }

                    if (chunkID.equals("EVNT")) {
                        int dataStart = bytesOffset + 8;
                        int trackDataLen = (int) chunkLen;

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
                    subChunkOffset += readRootChunk(data, (int) subChunkOffset, size - (int) (subChunkOffset - offset));
                }
            }

            return rootEnd;
        }

        return 0;
    }
}
