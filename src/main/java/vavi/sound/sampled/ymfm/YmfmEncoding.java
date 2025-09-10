/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.ymfm;


import javax.sound.sampled.AudioFormat;


/**
 * Encodings used by the Ymfm audio decoder.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 250909 nsano initial version <br>
 */
public class YmfmEncoding extends AudioFormat.Encoding {

    /** Specifies any Ymfm encoded data. */
    public static final YmfmEncoding YMFM = new YmfmEncoding("Ymfm");

    /**
     * Constructs a new encoding.
     *
     * @param name Name of the Ymfm encoding.
     */
    private YmfmEncoding(String name) {
        super(name);
    }
}
