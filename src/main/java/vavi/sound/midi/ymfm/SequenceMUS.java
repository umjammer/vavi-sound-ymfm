package vavi.sound.midi.ymfm;

import java.util.Arrays;


/** */
public class SequenceMUS extends Sequence {

    private byte[] m_data = new byte[1 << 16];
    private int m_pos; // uint16_t in C++
    private byte[] m_lastVol = new byte[16]; // uint8_t[16] in C++

    public SequenceMUS() {
        Arrays.fill(m_data, (byte) 0x60);
        setDefaults();
    }

    /** */
    public static boolean isValid(byte[] data) {
        int size = data.length;
        if (size < 8)
            return false;

        if (data[0] != 'M' || data[1] != 'U' || data[2] != 'S' || data[3] != 0x1a)
            return false;

        return true;
    }

    @Override
    public void read(byte[] data) {
        int size = data.length;
        if (size > 8) {
            int length = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8);
            int pos    = (data[6] & 0xFF) | ((data[7] & 0xFF) << 8);

            if (pos < size) {
                if (pos + length > size)
                    length = size - pos;
                System.arraycopy(data, pos, m_data, 0, length);
            }
        }
    }

    @Override
    public void reset() {
        super.reset();
        setDefaults();
    }

    /** */
    private void setDefaults() {
        m_pos = 0;
        Arrays.fill(m_lastVol, (byte) 0x7f);
    }

    @Override
    public long update(OPLPlayer player) {
        int event, channel, data, param;
        int lastPos;

        m_atEnd = false;

        do {
            lastPos = m_pos;
            event = m_data[m_pos++] & 0xFF;
            channel = event & 0xf;

            // map MUS channels to MIDI channels
            // (don't bother with the primary/secondary channel thing unless we need to)
            if (channel == 15) // percussion
                channel = 9;
            else if (channel >= 9)
                channel++;

            switch ((event >> 4) & 0x7) {
                case 0: // note off
                    player.midiNoteOff(channel, m_data[m_pos++] & 0xff);
                    break;

                case 1: // note on
                    data = m_data[m_pos++] & 0xFF;
                    if ((data & 0x80) != 0)
                        m_lastVol[channel] = m_data[m_pos++];
                    player.midiNoteOn(channel, data, m_lastVol[channel] & 0xff);
                    break;

                case 2: // pitch bend
                    // Division by 128.0 and subtraction of 1.0 is for mapping 0..127 to -1.0..+1.0
                    player.midiPitchControl(channel, ((m_data[m_pos++] & 0xFF) / 128.0) - 1.0);
                    break;

                case 3: // system event (channel mode messages)
                    data = m_data[m_pos++] & 0x7f;
                    switch (data) {
                        case 10: player.midiControlChange(channel, 120, 0); break; // all sounds off
                        case 11: player.midiControlChange(channel, 123, 0); break; // all notes off
                        case 12: player.midiControlChange(channel, 126, 0); break; // mono on
                        case 13: player.midiControlChange(channel, 127, 0); break; // poly on
                        case 14: player.midiControlChange(channel, 121, 0); break; // reset all controllers
                        default: break;
                    }
                    break;

                case 4: // controller
                    data  = m_data[m_pos++] & 0x7f;
                    param = m_data[m_pos++] & 0xFF; // Read as unsigned byte
                    // clamp CC param value - some tracks from tnt.wad have bad volume CCs
                    if (param > 0x7f)
                        param = 0x7f;
                    switch (data) {
                        case 0: player.midiProgramChange(channel, param); break;
                        case 1: player.midiControlChange(channel, 0,  param); break; // bank select
                        case 2: player.midiControlChange(channel, 1,  param); break; // mod wheel
                        case 3: player.midiControlChange(channel, 7,  param); break; // volume
                        case 4: player.midiControlChange(channel, 10, param); break; // pan
                        case 5: player.midiControlChange(channel, 11, param); break; // expression
                        case 6: player.midiControlChange(channel, 91, param); break; // reverb
                        case 7: player.midiControlChange(channel, 93, param); break; // chorus
                        case 8: player.midiControlChange(channel, 64, param); break; // sustain pedal
                        case 9: player.midiControlChange(channel, 67, param); break; // soft pedal
                        default: break;
                    }
                    break;

                case 5: // end of measure
                    break;

                case 6: // end of track
                    reset();
                    m_atEnd = true;
                    return 0;

                case 7: // unused
                    m_pos++;
                    break;
            }
        } while (((event & 0x80) == 0) && (m_pos > lastPos));

        // read delay in ticks, convert to # of samples
        long tickDelay = 0; // Using long for uint32_t
        do {
            event = m_data[m_pos++] & 0xFF;
            tickDelay <<= 7;
            tickDelay |= (event & 0x7f);
        } while (((event & 0x80) != 0) && (m_pos > lastPos));

        if (m_pos < lastPos) {
            // premature end of track, 16 bit position overflowed
            reset();
            m_atEnd = true;
            return 0;
        }

        double samplesPerTick = player.sampleRate() / 140.0;
        return Math.round(tickDelay * samplesPerTick);
    }
}
