/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymfm;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
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

    /** */
    public final static int MANUFACTURER_ID = 0x43;

    /** */
    private static final MidiDevice.Info[] infos = new MidiDevice.Info[] {
            YmfmSynthesizer.info
    };

    @Override
    public MidiDevice.Info[] getDeviceInfo() {
        return infos;
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
