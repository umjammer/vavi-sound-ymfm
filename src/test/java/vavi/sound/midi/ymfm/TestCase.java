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

import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;


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

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    @Test
    @DisplayName("soundbank: tmb")
    void test1() throws Exception {
        TmbSoundbankReader soundbankReader = new TmbSoundbankReader();
        soundbankReader.getSoundbank(new BufferedInputStream(Files.newInputStream(Path.of(tmb))));
    }

    @Test
    @DisplayName("soundbank: wopl")
    void test2() throws Exception {
        WoplSoundbankReader soundbankReader = new WoplSoundbankReader();
        soundbankReader.getSoundbank(new BufferedInputStream(Files.newInputStream(Path.of(wopl))));
    }

    @Test
    @DisplayName("soundbank: ail")
    void test3() throws Exception {
        AilSoundbankReader soundbankReader = new AilSoundbankReader();
        soundbankReader.getSoundbank(new BufferedInputStream(Files.newInputStream(Path.of(ail))));
    }

    @Test
    @DisplayName("soundbank: op2")
    void test4() throws Exception {
        Op2SoundbankReader soundbankReader = new Op2SoundbankReader();
        soundbankReader.getSoundbank(new BufferedInputStream(Files.newInputStream(Path.of(op2))));
    }

    @Test
    @DisplayName("reader: hmi")
    void test21() throws Exception {
        HmiMidiFileReader midiFileReader = new HmiMidiFileReader();
        midiFileReader.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(hmi))));
    }

    @Test
    @DisplayName("reader: hmp")
    void test22() throws Exception {
        HmpMidiFileReader midiFileReader = new HmpMidiFileReader();
        midiFileReader.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(hmp))));
    }

    @Test
    @DisplayName("reader: mus")
    void test23() throws Exception {
        MusMidiFileReader midiFileReader = new MusMidiFileReader();
        midiFileReader.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(mus))));
    }

    @Test
    @DisplayName("reader: xmi")
    void test24() throws Exception {
        XmiMidiFileReader midiFileReader = new XmiMidiFileReader();
        midiFileReader.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(xmi))));
    }
}
