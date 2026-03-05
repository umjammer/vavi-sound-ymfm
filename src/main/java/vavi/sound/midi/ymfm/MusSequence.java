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
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import vavi.sound.midi.MidiConstants.MetaEvent;


/**
 * @see "https://github.com/devinacker/ymfmidi"
 */
public class MusSequence extends OplSequence {

    private final byte[] m_data = new byte[1 << 16];
    private int m_pos;
    private final byte[] m_lastVol = new byte[16];

    /** */
    public MusSequence(float divisionType, int resolution) throws InvalidMidiDataException {
        super(divisionType, resolution);
        init();
    }

    /** */
    public MusSequence(float divisionType, int resolution, int numTracks) throws InvalidMidiDataException {
        super(divisionType, resolution, numTracks);
        init();
    }

    private void init() {
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
    public long update(OplPlayer player) {
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

    /**
     * m_pos, m_atEnd will be updated
     * TODO tick
     */
    private void convert(Consumer<MidiEvent> consumer) throws InvalidMidiDataException {
        int event, channel, data, param;
        int lastPos;

        m_pos = 0;
        m_atEnd = false;
        long tick = 0;

        while (!m_atEnd) {
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
                        consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel, m_data[m_pos++] & 0x7f, 0), tick));
                        break;

                    case 1: // note on
                        data = m_data[m_pos++] & 0xFF;
                        if ((data & 0x80) != 0)
                            m_lastVol[channel] = m_data[m_pos++];
                        consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel, data & 0x7F, m_lastVol[channel] & 0x7f), tick));
                        break;

                    case 2: // pitch bend
                        int pb = (m_data[m_pos++] & 0xFF) << 6;
                        consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.PITCH_BEND, channel, pb & 0x7F, (pb >> 7) & 0x7F), tick));
                        break;

                    case 3: // system event (channel mode messages)
                        data = m_data[m_pos++] & 0x7f;
                        switch (data) {
                            case 10: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 120, 0), tick)); break; // all sounds off
                            case 11: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 123, 0), tick)); break; // all notes off
                            case 12: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 126, 0), tick)); break; // mono on
                            case 13: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 127, 0), tick)); break; // poly on
                            case 14: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 121, 0), tick)); break; // reset all controllers
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
                            case 0: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, param, 0), tick)); break;
                            case 1: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 0,  param), tick)); break; // bank select
                            case 2: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 1,  param), tick)); break; // mod wheel
                            case 3: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 7,  param), tick)); break; // volume
                            case 4: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 10, param), tick)); break; // pan
                            case 5: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 11, param), tick)); break; // expression
                            case 6: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 91, param), tick)); break; // reverb
                            case 7: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 93, param), tick)); break; // chorus
                            case 8: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 64, param), tick)); break; // sustain pedal
                            case 9: consumer.accept(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 67, param), tick)); break; // soft pedal
                            default: break;
                        }
                        break;

                    case 5: // end of measure
                        break;

                    case 6: // end of track
                        consumer.accept(new MidiEvent(new MetaMessage(MetaEvent.META_END_OF_TRACK.number(), new byte[0], 0), tick));
                        m_atEnd = true;
                        return;

                    case 7: // unused
                        m_pos++;
                        break;
                }
            } while (((event & 0x80) == 0) && (m_pos > lastPos));

            if (m_atEnd) break;

            long tickDelay = 0;
            do {
                event = m_data[m_pos++] & 0xFF;
                tickDelay <<= 7;
                tickDelay |= (event & 0x7f);
            } while (((event & 0x80) != 0) && (m_pos > lastPos));

            if (m_pos < lastPos) {
                // premature end of track
                consumer.accept(new MidiEvent(new MetaMessage(MetaEvent.META_END_OF_TRACK.number(), new byte[0], 0), tick));
                m_atEnd = true;
                break;
            }

            tick += tickDelay;
        }
    }

    @Override
    public void convert() throws InvalidMidiDataException {
        Track newTrack = createTrack();
        convert(newTrack::add);
    }
}
