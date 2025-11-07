/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymfm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.midi.spi.MidiFileReader;


/**
 * OplMidiFileReader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025-11-07 nsano initial version <br>
 */
public abstract class OplMidiFileReader extends MidiFileReader {

    /**
     * load a sequence from the given path/file
     *
     * @param path The file path as a String.
     * @return The loaded Sequence object or null if loading fails.
     */
    public static OplSequence load(String path) throws IOException {
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
    public static OplSequence load(InputStream stream) throws IOException {

        byte[] data = stream.readAllBytes();

        return load(data);
    }

    /**
     * load a sequence from the given byte array data
     *
     * @param data The byte array containing the sequence data.
     * @return The loaded Sequence object or null if loading fails.
     */
    public static OplSequence load(byte[] data) {
        OplSequence seq = null;

//        if (SequenceMUS.isValid(data))
//            seq = new SequenceMUS();
//        else if (SequenceMID.isValid(data))
//            seq = new SequenceMID();
//        else if (SequenceXMI.isValid(data))
//            seq = new SequenceXMI();
//        else if (SequenceHMI.isValid(data))
//            seq = new SequenceHMI();
//        else if (SequenceHMP.isValid(data))
//            seq = new SequenceHMP();

        if (seq != null) {
            seq.read(data);
            seq.reset();
        }

        return seq;
    }

    /**
     * load MIDI data from the specified path
     */
    public boolean loadSequence(String path) throws IOException {
//        m_sequence = OplSequence.load(path);
//        return m_sequence != null;
        return false;
    }

    /**
     * load MIDI data from an already opened file, optionally at a given offset
     * if 'size' is 0, the full file will be read (starting from 'offset')
     */
    public boolean loadSequence(InputStream file, int offset, int size) throws IOException {
//        m_sequence = OplSequence.load(file);
//        return m_sequence != null;
        return false;
    }

    /**
     * load MIDI data from a block of memory
     */
    public boolean loadSequence(byte[] data, int size) {
//        m_sequence = OplSequence.load(data);
//        return m_sequence != null;
        return false;
    }
}
