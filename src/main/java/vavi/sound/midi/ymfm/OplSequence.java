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

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;


/**
 * @see "https://github.com/devinacker/ymfmidi"
 */
public abstract class OplSequence extends Sequence {

    protected boolean m_atEnd;
    protected int m_songNum;

    /** */
    public OplSequence(float divisionType, int resolution) throws InvalidMidiDataException {
        super(divisionType, resolution);
        init();
    }

    /** */
    public OplSequence(float divisionType, int resolution, int numTracks) throws InvalidMidiDataException {
        super(divisionType, resolution, numTracks);
        init();
    }

    private void init() {
        m_atEnd = false;
        m_songNum = 0;
    }

    // ----

    /**
     * reset track to beginning
     */
    public void reset() {
        m_atEnd = false;
    }

    /**
     * process and play any pending MIDI events
     * returns the number of output audio samples until the next event(s)
     *
     * @param player The OPLPlayer instance to use.
     * @return The number of output audio samples until the next event(s).
     */
    public abstract long update(OplPlayer player);

    /** Convert read tracks to {@link Track} after {@link #read} */
    public abstract void convert() throws InvalidMidiDataException;

    /**
     * Sets the song number.
     *
     * @param num The song number to set.
     */
    public void setSongNum(int num) {
        if (num >= 0 && num < numSongs()) // Added num >= 0 check for unsigned context
            m_songNum = num;
        reset();
    }

    /**
     * Returns the total number of songs in the sequence.
     *
     * @return The number of songs.
     */
    public int numSongs() {
        return 1;
    }

    /**
     * Returns the current song number.
     *
     * @return The current song number.
     */
    public int songNum() {
        return m_songNum;
    }

    /**
     * has this track reached the end?
     * (this is true immediately after ending/looping, then becomes false after updating again)
     *
     * @return True if the track has reached the end, false otherwise.
     */
    public boolean atEnd() {
        return m_atEnd;
    }

    /**
     * Reads the sequence data from a byte array.
     *
     * @param data The byte array containing the sequence data.
     */
    protected abstract void read(byte[] data);
}
