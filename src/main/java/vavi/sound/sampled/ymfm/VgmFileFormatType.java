/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.ymfm;

import javax.sound.sampled.AudioFileFormat;


/**
 * FileFormatTypes used by the VGM audio decoder.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 250909 nsano initial version <br>
 */
public class VgmFileFormatType extends AudioFileFormat.Type {

    /**
     * Specifies an VGM file.
     */
    public static final AudioFileFormat.Type VGM = new VgmFileFormatType("VGM", "vgm,vgz");

    /**
     * Constructs a file type.
     *
     * @param name the name of the VGM File Format.
     * @param extension the file extension for this VGM File Format.
     */
    private VgmFileFormatType(String name, String extension) {
        super(name, extension);
    }
}
