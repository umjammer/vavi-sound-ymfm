/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymfm;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.nio.file.Files;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiFileFormat;
import javax.sound.midi.Sequence;
import javax.sound.midi.spi.MidiFileReader;

import static java.lang.System.getLogger;
import static javax.sound.midi.MidiFileFormat.UNKNOWN_LENGTH;


/**
 * MusMidiFileReader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 251108 nsano initial version <br>
 */
public class MusMidiFileReader extends MidiFileReader {

    private static final Logger logger = getLogger(MusMidiFileReader.class.getName());

    @Override
    public MidiFileFormat getMidiFileFormat(InputStream stream) throws InvalidMidiDataException, IOException {
        if (MusSequence.isValid(stream.readAllBytes()))
            return new MidiFileFormat(3, Sequence.PPQ, 0, UNKNOWN_LENGTH, UNKNOWN_LENGTH); // TODO
        else
            throw new InvalidMidiDataException();
    }

    @Override
    public MidiFileFormat getMidiFileFormat(File file) throws InvalidMidiDataException, IOException {

logger.log(Level.DEBUG, "file: " + file);
        InputStream is = new BufferedInputStream(Files.newInputStream(file.toPath()));
        return getMidiFileFormat(is);
    }

    @Override
    public MidiFileFormat getMidiFileFormat(URL url) throws InvalidMidiDataException, IOException {

        InputStream is = new BufferedInputStream(url.openStream());
        return getMidiFileFormat(is);
    }

    @Override
    public Sequence getSequence(InputStream stream) throws InvalidMidiDataException, IOException {
        MusSequence sequence = new MusSequence(0, 0);
        sequence.read(stream.readAllBytes());
        return sequence;
    }

    @Override
    public Sequence getSequence(File file) throws InvalidMidiDataException, IOException {

        InputStream is = new BufferedInputStream(Files.newInputStream(file.toPath()));
        return getSequence(is);
    }

    @Override
    public Sequence getSequence(URL url) throws InvalidMidiDataException, IOException {

        InputStream is = new BufferedInputStream(url.openStream());
        return getSequence(is);
    }
}
