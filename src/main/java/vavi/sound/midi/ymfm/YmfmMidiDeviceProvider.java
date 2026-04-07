/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymfm;

import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Properties;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.spi.MidiDeviceProvider;

import static java.lang.System.getLogger;


/**
 * YmfmMidiDeviceProvider.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 251107 nsano initial version <br>
 */
public class YmfmMidiDeviceProvider extends MidiDeviceProvider {

    private static final Logger logger = getLogger(YmfmMidiDeviceProvider.class.getName());

    static {
        try {
            try (InputStream is = YmfmMidiDeviceProvider.class.getResourceAsStream("/META-INF/maven/vavi/vavi-sound-ymfm/pom.properties")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    version = props.getProperty("version", "undefined in pom.properties");
                } else {
                    version = System.getProperty("vavi.test.version", "undefined");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String version;

    /** */
    public final static int MANUFACTURER_ID = 0x43;

    @Override
    public MidiDevice.Info[] getDeviceInfo() {
        return new MidiDevice.Info[] {
                YmfmSynthesizer.info
        };
    }

    /** */
    @Override
    public MidiDevice getDevice(MidiDevice.Info info)
        throws IllegalArgumentException {

        if (info == YmfmSynthesizer.info) {
logger.log(Level.DEBUG, "★1 info: " + info);
            YmfmSynthesizer synthesizer = new YmfmSynthesizer();
            return synthesizer;
        } else {
logger.log(Level.DEBUG, "★1 here: " + info);
            throw new IllegalArgumentException();
        }
    }
}
