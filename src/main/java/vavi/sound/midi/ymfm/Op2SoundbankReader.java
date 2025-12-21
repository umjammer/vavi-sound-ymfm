/*
 *  ALSA hwdep SBI FM instrument loader
 *  Copyright (c) 2000 Uros Bizjak <uros@kss-loka.si>
 *
 *   This program is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation; either version 2 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program; if not, write to the Free Software
 *   Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *
 *
 *  Oct. 2007 - Takashi Iwai <tiwai@suse.de>
 *    Changed to use hwdep instead of obsoleted seq-instr interface
 */

package vavi.sound.midi.ymfm;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Soundbank;
import javax.sound.midi.spi.SoundbankReader;

import vavi.sound.midi.MidiConstants;
import vavi.sound.midi.ymfm.OplPatch.PatchVoice;

import static java.lang.System.getLogger;


/**
 * Op2SoundbankReader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/11/07 umjammer initial version <br>
 */
class Op2SoundbankReader extends SoundbankReader {

    private static final Logger logger = getLogger(Op2SoundbankReader.class.getName());

    @Override
    public Soundbank getSoundbank(URL url) throws InvalidMidiDataException, IOException {
        return getSoundbank(url.openStream());
    }

    @Override
    public Soundbank getSoundbank(InputStream stream) throws InvalidMidiDataException, IOException {
        return getSoundbankInternal(stream);
    }

    @Override
    public Soundbank getSoundbank(File file) throws InvalidMidiDataException, IOException {
        return getSoundbank(Files.newInputStream(file.toPath()));
    }

    /** */
    private static Soundbank getSoundbankInternal(InputStream is) throws InvalidMidiDataException, IOException {
        Map<Integer, OplPatch> patches = loadOP2(is.readAllBytes());
        YmfmSoundbank soundbank = new YmfmSoundbank();
        for (Map.Entry<Integer, OplPatch> e : patches.entrySet()) {
            soundbank.addInstrument(0, e.getKey(), e.getValue().name, e.getValue());
        }
        return soundbank;
    }

    /** */
    private static Map<Integer, OplPatch> loadOP2(byte[] data) throws InvalidMidiDataException {
        if (data.length < 175 * (36 + 32) + 8)
            throw new InvalidMidiDataException("too small");

        String header = new String(data, 0, 8);
        if (!header.equals("#OPL_II#"))
            throw new InvalidMidiDataException("not a WOPL3-BANK: " + header);

        Map<Integer, OplPatch> patches = new HashMap<>();

        // read data for all patches (128 melodic + 47 percussion)
        for (int i = 0; i < 128 + 47; i++) {
            // patches 0-127 are melodic; the rest are for percussion notes 35 thru 81
            int key = (i < 128) ? i : (i + 35);

            OplPatch patch = new OplPatch();
            // clear patch data is implied by new OPLPatch()

            // seek to patch data
            int bytesOffset = (36 * i) + 8;

            // read the common data for both 2op voices
            // flag bit 0 is "fixed pitch" (for drums), but it's seemingly only used for drum patches anyway, so ignore it?
            patch.dualTwoOp = (data[bytesOffset + 0] & 4) != 0;
            // second voice detune
            patch.voice[1].finetune = OplPlayer.midiCalcBend((((double) ((byte) (data[bytesOffset + 2] - 128))) / 64.0));

            patch.fixedNote = data[bytesOffset + 3];

            // read data for both 2op voices
            int pos = bytesOffset + 4;
            for (int j = 0; j < 2; j++) {
                PatchVoice voice = patch.voice[j];

                for (int op = 0; op < 2; op++) {
                    // operator mode
                    voice.op_mode[op] = data[pos++];
                    // operator envelope
                    voice.op_ad[op] = data[pos++];
                    voice.op_sr[op] = data[pos++];
                    // operator waveform
                    voice.op_wave[op] = data[pos++];
                    // KSR & output level
                    voice.op_ksr[op] = (byte) (data[pos] & 0xc0);
                    voice.op_level[op] = (byte) (data[pos++] & 0x3f);

                    // feedback/connection (first op only)
                    if (op == 0)
                        voice.conn = data[pos];
                    pos++;
                }

                // midi note offset (int16, but only really need the LSB)
                voice.tune = data[pos];
                pos += 2;
            }

            // fix for some bugged DMX patches (e.g. Doom II electric snare)
            if ((patch.voice[1].op_ad[0] | patch.voice[1].op_ad[1]) == 0)
                patch.dualTwoOp = false;

            // seek to patch name
            int nameBytesOffset = (32 * i) + (36 * 175) + 8;
            if (data[nameBytesOffset] != 0)
                patch.name = new String(data, nameBytesOffset, 31).trim();
            else
                patch.name = MidiConstants.getInstrumentName(key);

            patches.put(key, patch);
        }

logger.log(Level.TRACE, "patches: " + patches.size());
        return patches;
    }
}
