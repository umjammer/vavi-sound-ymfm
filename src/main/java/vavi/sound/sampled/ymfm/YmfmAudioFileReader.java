/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.ymfm;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.nio.file.Files;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;

import vavi.util.Debug;
import vavi.util.archive.Archives;

import static java.lang.System.getLogger;


/**
 * Provider for Ymfm audio file reading services. This implementation can parse
 * the format information from Ymfm audio file, and can produce audio input
 * streams from files of this type.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 250824 nsano initial version <br>
 */
public class YmfmAudioFileReader extends AudioFileReader {

    private static final Logger logger = getLogger(YmfmAudioFileReader.class.getName());

    @Override
    public AudioFileFormat getAudioFileFormat(File file) throws UnsupportedAudioFileException, IOException {
        try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            return getAudioFileFormat(inputStream, (int) file.length());
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(URL url) throws UnsupportedAudioFileException, IOException {
        try (InputStream inputStream = new BufferedInputStream(url.openStream())) {
            return getAudioFileFormat(inputStream);
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(InputStream stream) throws UnsupportedAudioFileException, IOException {
        return getAudioFileFormat(stream, AudioSystem.NOT_SPECIFIED);
    }

    /**
     * Return the AudioFileFormat from the given InputStream. Implementation.
     *
     * @param bitStream
     * @param mediaLength
     * @return an AudioInputStream object based on the audio file data contained
     *         in the input stream.
     * @exception UnsupportedAudioFileException if the File does not point to a
     *                valid audio file data recognized by the system.
     * @exception IOException if an I/O exception occurs.
     */
    protected AudioFileFormat getAudioFileFormat(InputStream bitStream, int mediaLength) throws UnsupportedAudioFileException, IOException {
        InputStream is = null;
        try {
            bitStream.mark(1024); // using 1024 because of Archives#getInputStream() brakes mark/reset (consumes 522 bytes)
            is = new BufferedInputStream(Archives.getInputStream(bitStream));
            byte[] magic = new byte[4];
            int r = is.read(magic);
            if (r < 4 || magic[0] != 'V' || magic[1] != 'g' || magic[2] != 'm' || magic[3] != ' ') {
                throw new UnsupportedAudioFileException("not vgm header");
            }
        } catch (IOException e) {
logger.log(Level.DEBUG, e.toString());
logger.log(Level.TRACE, e.getMessage(), e);
            throw (UnsupportedAudioFileException) new UnsupportedAudioFileException(e.getMessage()).initCause(e);
        } finally {
            try {
                bitStream.reset();
            } catch (IOException e) {
                logger.log(Level.TRACE, e.toString());
            }
        }
        AudioFormat format = new AudioFormat(YmfmEncoding.YMFM, 44100, 16, 2, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED, false);
        return new AudioFileFormat(VgmFileFormatType.VGM, format, AudioSystem.NOT_SPECIFIED);
    }

    @Override
    public AudioInputStream getAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        InputStream inputStream = Files.newInputStream(file.toPath());
        return getAudioInputStream(new BufferedInputStream(inputStream), (int) file.length());
    }

    @Override
    public AudioInputStream getAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        InputStream inputStream = url.openStream();
        return getAudioInputStream(inputStream instanceof BufferedInputStream ? inputStream : new BufferedInputStream(inputStream));
    }

    @Override
    public AudioInputStream getAudioInputStream(InputStream stream) throws UnsupportedAudioFileException, IOException {
        return getAudioInputStream(stream, AudioSystem.NOT_SPECIFIED);
    }

    /**
     * Obtains an audio input stream from the input stream provided. The stream
     * must point to valid audio file data.
     *
     * @param inputStream the input stream from which the AudioInputStream
     *            should be constructed.
     * @param medialength
     * @return an AudioInputStream object based on the audio file data contained
     *         in the input stream.
     * @exception UnsupportedAudioFileException if the File does not point to a
     *                valid audio file data recognized by the system.
     * @exception IOException if an I/O exception occurs.
     */
    protected AudioInputStream getAudioInputStream(InputStream inputStream, int medialength) throws UnsupportedAudioFileException, IOException {
        AudioFileFormat audioFileFormat = getAudioFileFormat(inputStream, medialength);
        return new AudioInputStream(inputStream, audioFileFormat.getFormat(), audioFileFormat.getFrameLength());
    }
}
