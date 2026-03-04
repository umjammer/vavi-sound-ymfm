/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymfm;

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Track;

import vavi.sound.midi.MidiConstants;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static vavi.sound.midi.MidiUtil.volume;


/**
 * TestCase.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025-11-08 nsano initial version <br>
 */
@EnabledIf("localPropertiesExists")
@PropsEntity(url = "file:local.properties")
class TestCase {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property(name = "synthesizer")
    String synthesizer = "#YmFm OPL3 MIDI Synthesizer";

    @Property(name = "tmb")
    String tmb = "src/test/resources/test.tmb";

    @Property(name = "wopl")
    String wopl = "src/test/resources/test.wopl";

    @Property(name = "ail")
    String ail = "src/test/resources/test.opl";

    @Property(name = "op2")
    String op2 = "src/test/resources/test.op2";

    @Property(name = "hmi")
    String hmi = "src/test/resources/test.hmi";

    @Property(name = "hmp")
    String hmp = "src/test/resources/test.hmp";

    @Property(name = "mus")
    String mus = "src/test/resources/test.mus";

    @Property(name = "xmi")
    String xmi = "src/test/resources/test.xmi";

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    static boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static long time = onIde ? 1000 * 1000 : 10 * 1000;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
        System.setProperty("javax.sound.midi.Synthesizer", synthesizer);

Debug.print("volume: " + volume);
    }

    @Test
    @DisplayName("soundbank: tmb")
    void test1() throws Exception {
Debug.print("tmb: " + tmb);
        TmbSoundbankReader soundbankReader = new TmbSoundbankReader();
        soundbankReader.getSoundbank(new BufferedInputStream(Files.newInputStream(Path.of(tmb))));
    }

    @Test
    @DisplayName("soundbank: wopl")
    void test2() throws Exception {
Debug.print("wopl: " + wopl);
        WoplSoundbankReader soundbankReader = new WoplSoundbankReader();
        soundbankReader.getSoundbank(new BufferedInputStream(Files.newInputStream(Path.of(wopl))));
    }

    @Test
    @DisplayName("soundbank: ail")
    void test3() throws Exception {
Debug.print("ail: " + ail);
        AilSoundbankReader soundbankReader = new AilSoundbankReader();
        soundbankReader.getSoundbank(new BufferedInputStream(Files.newInputStream(Path.of(ail))));
    }

    @Test
    @DisplayName("soundbank: op2")
    void test4() throws Exception {
Debug.print("op2: " + op2);
        Op2SoundbankReader soundbankReader = new Op2SoundbankReader();
        soundbankReader.getSoundbank(new BufferedInputStream(Files.newInputStream(Path.of(op2))));
    }

    @Test
    @DisplayName("reader: hmi")
    void test21() throws Exception {
Debug.print("hmi: " + hmi);
        HmiMidiFileReader midiFileReader = new HmiMidiFileReader();
        Sequence sequence = midiFileReader.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(hmi))));
//        int t = 0;
//Debug.println("tracks: " + sequence.getTracks().length);
//        for (Track track : sequence.getTracks()) {
//            Debug.println(t++ + ": " + track.size());
//        }
MidiSystem.write(sequence, 1, Path.of("tmp/hmi.mid").toFile());
        play(sequence);
    }

    @Test
    @DisplayName("reader: hmp")
    void test22() throws Exception {
Debug.print("hmp: " + hmp);
        HmpMidiFileReader midiFileReader = new HmpMidiFileReader();
        Sequence sequence = midiFileReader.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(hmp))));
//        int t = 0;
//Debug.println("tracks: " + sequence.getTracks().length);
//        for (Track track : sequence.getTracks()) {
//            Debug.println(t++ + ": " + track.size());
//        }
MidiSystem.write(sequence, 1, Path.of("tmp/hmp.mid").toFile());
        play(sequence);
    }

    // TODO tick
    @Test
    @DisplayName("reader: mus")
    void test23() throws Exception {
Debug.print("mus: " + mus);
        MusMidiFileReader midiFileReader = new MusMidiFileReader();
        Sequence sequence = midiFileReader.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(mus))));
MidiSystem.write(sequence, 1, Path.of("tmp/mus.mid").toFile());
        play(sequence);
    }

    @Test
    @DisplayName("reader: xmi")
    void test24() throws Exception {
Debug.print("xmi: " + xmi);
        XmiMidiFileReader midiFileReader = new XmiMidiFileReader();
        Sequence sequence = midiFileReader.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(xmi))));
MidiSystem.write(sequence, 1, Path.of("tmp/xmi.mid").toFile());
        play(sequence);
    }

    /** */
    void play(Sequence sequence) throws Exception {

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
