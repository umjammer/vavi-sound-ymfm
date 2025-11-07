/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymfm;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;

import vavi.sound.midi.MidiConstants;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static vavi.sound.midi.MidiUtil.volume;


/**
 * YmfmSynthesizerTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025-11-07 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class YmfmSynthesizerTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static {
//        System.setProperty("javax.sound.midi.Sequencer", "com.sun.media.sound.RealTimeSequencer");
    }

    @Property(name = "synthesizer")
    String synthesizer = "#YmFm OPL3 MIDI Synthesizer";

    @Property
    String midi;

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    static boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static long time = onIde ? 1000 * 1000 : 10 * 1000;

    @BeforeEach
    void setupEach() throws IOException {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
        System.setProperty("javax.sound.midi.Synthesizer", synthesizer);

Debug.println("volume: " + volume + ", synthesizer: " + System.getProperty("javax.sound.midi.Synthesizer"));
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test1() throws Exception {
Debug.println(midi + ", " + Files.exists(Paths.get(midi)));
        Sequence sequence = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(Paths.get(midi))));

        CountDownLatch cdl = new CountDownLatch(1);
        MetaEventListener mel = meta -> {
Debug.println("META: " + MidiConstants.MetaEvent.valueOf(meta.getType()));
            if (meta.getType() == 47) cdl.countDown();
        };
        Sequencer sequencer = MidiSystem.getSequencer(false);
Debug.println("sequencer: " + sequencer);
        sequencer.addMetaEventListener(mel);
        sequencer.open();
        Synthesizer synthesizer = MidiSystem.getSynthesizer();
Debug.println("synthesizer: " + synthesizer);
        synthesizer.open();
        sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
        volume(synthesizer.getReceiver(), volume);
        sequencer.setSequence(sequence);

        sequencer.start();
        if (!onIde) {
            Thread.sleep(time);
            sequencer.stop();
Debug.println("STOP");
        } else {
            cdl.await();
        }
        sequencer.removeMetaEventListener(mel);
        sequencer.close();
    }
}
