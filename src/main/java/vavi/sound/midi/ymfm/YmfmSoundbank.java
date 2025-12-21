/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymfm;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sound.midi.Instrument;
import javax.sound.midi.Patch;
import javax.sound.midi.Soundbank;
import javax.sound.midi.SoundbankResource;
import com.sun.media.sound.ModelPatch;
import com.sun.media.sound.SimpleInstrument;

import vavi.sound.midi.ymfm.OplPatch.PatchVoice;

import static java.lang.System.getLogger;


/**
 * YmfmSoundbank.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/11/07 umjammer initial version <br>
 */
public class YmfmSoundbank implements Soundbank {

    private static final Logger logger = getLogger(YmfmSoundbank.class.getName());

    /** */
    private final List<Instrument> instruments = new ArrayList<>();

    public YmfmSoundbank() {
    }

    @Override
    public String getName() {
        return "YmfmSoundbank";
    }

    @Override
    public String getVersion() {
        return YmfmSynthesizer.info.getVersion();
    }

    @Override
    public String getVendor() {
        return YmfmSynthesizer.info.getVendor();
    }

    @Override
    public String getDescription() {
        return "Soundbank for YmFmSynthesizer";
    }

    @Override
    public SoundbankResource[] getResources() {
        return getInstruments();
    }

    @Override
    public Instrument[] getInstruments() {
        return instruments.toArray(Instrument[]::new);
    }

    @Override
    public Instrument getInstrument(Patch patch) {
        for (Instrument instrument : instruments) {
            if (instrument.getPatch().getProgram() == patch.getProgram() &&
                    instrument.getPatch().getBank() == patch.getBank()) {
                return instrument;
            }
        }
logger.log(Level.DEBUG, "no instrument for: " + patch);
        return null;
    }

    Map<Integer, OplPatch> patches = new HashMap<>();

    /** */
    public void addInstrument(int bank, int program, String name, OplPatch data) {
        patches.put(program, data);
        instruments.add(new YmfmInstrument(bank, program, data.voice[0])); // TODO
    }

    /** */
    public static class YmfmInstrument extends SimpleInstrument {
        final PatchVoice data;
        protected YmfmInstrument(int bank, int program, PatchVoice instrument) {
            setPatch(new ModelPatch(bank, program, false /* TODO */));
            this.name = percussion ? 128 + "." + 0 /* TODO */ + ".p" : bank + "." + program;
            this.data = instrument;
        }

        @Override
        public Class<PatchVoice> getDataClass() {
            return PatchVoice.class;
        }

        @Override
        public PatchVoice getData() {
            return data;
        }
    }
}
