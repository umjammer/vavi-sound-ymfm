package vavi.sound.midi.ymfm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;


public abstract class Sequence {

    protected boolean m_atEnd;
    protected int m_songNum; // unsigned in C++ becomes int in Java for simplicity if range is small

    public Sequence() {
        m_atEnd = false;
        m_songNum = 0;
    }

    /**
     * load a sequence from the given path/file
     *
     * @param path The file path as a String.
     * @return The loaded Sequence object or null if loading fails.
     */
    public static Sequence load(String path) throws IOException {
        try (InputStream fileStream = Files.newInputStream(Path.of(path))) {
            return load(fileStream);
        }
    }

    /**
     * load a sequence from the given file/stream with an offset and size
     *
     * @param stream The input stream (like FileInputStream).
     * @return The loaded Sequence object or null if loading fails.
     */
    public static Sequence load(InputStream stream) throws IOException {

        byte[] data = stream.readAllBytes();

        return load(data);
    }

    /**
     * load a sequence from the given byte array data
     *
     * @param data The byte array containing the sequence data.
     * @return The loaded Sequence object or null if loading fails.
     */
    public static Sequence load(byte[] data) {
        Sequence seq = null;

        if (SequenceMUS.isValid(data))
            seq = new SequenceMUS();
        else if (SequenceMID.isValid(data))
            seq = new SequenceMID();
        else if (SequenceXMI.isValid(data))
            seq = new SequenceXMI();
        else if (SequenceHMI.isValid(data))
            seq = new SequenceHMI();
        else if (SequenceHMP.isValid(data))
            seq = new SequenceHMP();

        if (seq != null) {
            seq.read(data);
            seq.reset();
        }

        return seq;
    }

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
    public abstract long update(OPLPlayer player);

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
    } // unsigned becomes int in Java

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
    protected abstract void read(byte[] data); // size argument omitted as it's data.length
}
