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
 * WoplSoundbankReader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/11/07 umjammer initial version <br>
 */
class WoplSoundbankReader extends SoundbankReader {

    private static final Logger logger = getLogger(WoplSoundbankReader.class.getName());

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
        Map<Integer, OplPatch> patches = loadWOPL(is.readAllBytes());
        YmfmSoundbank soundbank = new YmfmSoundbank();
        for (Map.Entry<Integer, OplPatch> e : patches.entrySet()) {
            soundbank.addInstrument(0, e.getKey(), e.getValue().name, e.getValue());
        }
        return soundbank;
    }

    /** */
    private static Map<Integer, OplPatch> loadWOPL(byte[] data) throws InvalidMidiDataException {
        if (data.length < 19)
            throw new InvalidMidiDataException("too small");

        String header = new String(data, 0, 10);
        if (!header.equals("WOPL3-BANK"))
            throw new InvalidMidiDataException("not a WOPL3-BANK: " + header);

        // mixed endianness? why???
        int version = (data[11] & 0xff) | ((data[12] & 0xff) << 8); // Little Endian
        int numMelody = ((data[13] & 0xff) << 8) | (data[14] & 0xff); // Big Endian
        int numPerc = ((data[15] & 0xff) << 8) | (data[16] & 0xff); // Big Endian

        if (version > 3)
            throw new InvalidMidiDataException("unknown version: " + version);

        // currently not supported: global LFO flags, volume model options

        final int bankOffset = 19;
        int patchOffset = bankOffset + 34 * (numMelody + numPerc);

        int instSize = (version >= 3) ? 66 : 62;
        int bankInfoSize = (version >= 2) ? 34 : 0;

        if (data.length < (numMelody + numPerc) * (128 * instSize + bankInfoSize))
            throw new InvalidMidiDataException("enough length");

        Map<Integer, OplPatch> patches = new HashMap<>();

        for (int i = 0; i < 128 * (numMelody + numPerc); i++) {
            int key = i & 0x7f;
            if (version >= 2) {
                int bank = i >> 7;
                int dataOffset = bankOffset + 34 * bank;

                if (bank >= numMelody) { // percussion banks (use LSB)
                    key |= ((data[dataOffset + 32] & 0xff) << 8) | 0x80;
                } else if (data[dataOffset + 32] != 0) { // bank LSB set (XG)
                    key |= ((data[dataOffset + 32] & 0xff) << 8);
                } else if (data[dataOffset + 33] != 0) { // bank MSB set (GS)
                    key |= ((data[dataOffset + 33] & 0xff) << 8);
                }
            }

            int patchDataOffset = patchOffset + instSize * i;

            // ignore other data for this patch if it's a blank instrument
            // *or* if one of the rhythm mode bits is set (not supported here)
            if ((data[patchDataOffset + 39] & 0x3c) != 0)
                continue;

            OplPatch patch = new OplPatch();
            // clear patch data is implied by new OPLPatch()

            // patch names
            if (data[patchDataOffset + 0] != 0)
                patch.name = new String(data, patchDataOffset, 31).trim();
            else
                patch.name = MidiConstants.getInstrumentName(key & 0xff);

            // patch global settings
            patch.voice[0].tune = (byte) (data[patchDataOffset + 33] - 12);
            patch.voice[1].tune = (byte) (data[patchDataOffset + 35] - 12);
            patch.velocity = data[patchDataOffset + 36];
            patch.voice[1].finetune = OplPlayer.midiCalcBend(((double) data[patchDataOffset + 37]) / 64.0);
            patch.fixedNote = data[patchDataOffset + 38];
            patch.fourOp = (data[patchDataOffset + 39] & 3) == 1;
            patch.dualTwoOp = (data[patchDataOffset + 39] & 3) == 3;
            patch.voice[0].conn = data[patchDataOffset + 40];
            patch.voice[1].conn = data[patchDataOffset + 41];

            // patch operator settings
            int pos = patchDataOffset + 42;
            for (int op = 0; op < 4; op++) {
                PatchVoice voice = patch.voice[op / 2];

                int n = (op % 2) ^ 1;

                voice.op_mode[n] = data[pos++];
                voice.op_ksr[n] = (byte) (data[pos] & 0xc0);
                voice.op_level[n] = (byte) (data[pos++] & 0x3f);
                voice.op_ad[n] = data[pos++];
                voice.op_sr[n] = data[pos++];
                voice.op_wave[n] = data[pos++];
            }

            patches.put(key, patch);
        }

logger.log(Level.TRACE, "patches: " + patches.size());
        return patches;
    }
}
