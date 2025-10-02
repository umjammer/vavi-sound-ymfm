/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.ymfm;

import javax.sound.sampled.AudioFormat;


/**
 * Encodings used by the VGM audio decoder.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 250909 nsano initial version <br>
 */
public class VgmEncoding extends AudioFormat.Encoding {

    /** Specifies any Ymfm encoded data. */
    public static final VgmEncoding VGM = new VgmEncoding("vgm");

    /**
     * Constructs a new encoding.
     *
     * @param name Name of the VGM encoding.
     */
    private VgmEncoding(String name) {
        super(name);
    }
}
