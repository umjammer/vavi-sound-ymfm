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
import java.io.FileInputStream;
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
 * TmbSoundbankReader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/11/07 umjammer initial version <br>
 */
class TmbSoundbankReader extends SoundbankReader {

    private static final Logger logger = getLogger(TmbSoundbankReader.class.getName());

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
        Map<Integer, OplPatch> patches = loadTMB(is.readAllBytes());
        YmfmSoundbank soundbank = new YmfmSoundbank();
        for (Map.Entry<Integer, OplPatch> e : patches.entrySet()) {
            soundbank.addInstrument(0, e.getKey(), e.getValue().name, e.getValue());
        }
        return soundbank;
    }

    /** */
    private static Map<Integer, OplPatch> loadTMB(byte[] data) throws InvalidMidiDataException {
        if (data.length < 256 * 13)
            throw new InvalidMidiDataException("too small");

        Map<Integer, OplPatch> patches = new HashMap<>();

        for (int key = 0; key < 256; key++) {
            OplPatch patch = new OplPatch();
            // clear patch data is implied by new OPLPatch()
            patch.name = MidiConstants.getInstrumentName(key);

            int bytesOffset = key * 13;

            // since this format has no identifying info, we can only really reject it
            // if it has invalid values in a few spots
            if (((data[bytesOffset + 8] | data[bytesOffset + 9] | data[bytesOffset + 10]) & 0xf0) != 0)
                throw new InvalidMidiDataException("invalid data");

            PatchVoice voice = patch.voice[0];
            voice.op_mode[0] = data[bytesOffset + 0];
            voice.op_mode[1] = data[bytesOffset + 1];
            voice.op_ksr[0] = (byte) (data[bytesOffset + 2] & 0xc0);
            voice.op_level[0] = (byte) (data[bytesOffset + 2] & 0x3f);
            voice.op_ksr[1] = (byte) (data[bytesOffset + 3] & 0xc0);
            voice.op_level[1] = (byte) (data[bytesOffset + 3] & 0x3f);
            voice.op_ad[0] = data[bytesOffset + 4];
            voice.op_ad[1] = data[bytesOffset + 5];
            voice.op_sr[0] = data[bytesOffset + 6];
            voice.op_sr[1] = data[bytesOffset + 7];
            voice.op_wave[0] = data[bytesOffset + 8];
            voice.op_wave[1] = data[bytesOffset + 9];
            voice.conn = data[bytesOffset + 10];
            voice.tune = (byte) (data[bytesOffset + 11] - 12);
            patch.velocity = data[bytesOffset + 12];

            patches.put(key, patch);
        }

logger.log(Level.TRACE, "patches: " + patches.size());
        return patches;
    }
}
