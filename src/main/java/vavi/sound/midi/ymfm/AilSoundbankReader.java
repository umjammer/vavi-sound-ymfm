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
 * Audio Interface Library (AIL) SoundbankReader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/11/07 umjammer initial version <br>
 */
class AilSoundbankReader extends SoundbankReader {

    private static final Logger logger = getLogger(AilSoundbankReader.class.getName());

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
        Map<Integer, OplPatch> patches = loadAIL(is.readAllBytes());
        YmfmSoundbank soundbank = new YmfmSoundbank();
        for (Map.Entry<Integer, OplPatch> e : patches.entrySet()) {
            if (e.getValue().fourOp)
                soundbank.addInstrument(0, e.getKey(), e.getValue().name, e.getValue());
        }
logger.log(Level.TRACE, "available patches: " + soundbank.patches.size());
        return soundbank;
    }

    /** */
    private static Map<Integer, OplPatch> loadAIL(byte[] data) throws InvalidMidiDataException {
        int index = 0;

        Map<Integer, OplPatch> patches = new HashMap<>();

        while (true) {
            if (data.length < index * 6 + 6)
                throw new InvalidMidiDataException("too small");

            int entryOffset = index * 6;

            if ((data[entryOffset + 0] & 0xff) == 0xff && (data[entryOffset + 1] & 0xff) == 0xff) {
logger.log(Level.TRACE, "patches: " + patches.size());
                return patches;
            }

            int key;
            if ((data[entryOffset + 1] & 0xff) == 0x7f)
                key = (data[entryOffset + 0] & 0xff) | 0x80;
            else
                key = ((data[entryOffset + 0] & 0xff) | ((data[entryOffset + 1] & 0xff) << 8)) & 0x7f7f;

            OplPatch patch = new OplPatch();
            // clear patch data
            patch.name = MidiConstants.getInstrumentName(key & 0xff);

            int patchPos = (data[entryOffset + 2] & 0xff) | ((data[entryOffset + 3] & 0xff) << 8) | ((data[entryOffset + 4] & 0xff) << 16) | ((data[entryOffset + 5] & 0xff) << 24);
            if (data.length < patchPos)
                throw new InvalidMidiDataException("invalid size");

            int bytesOffset = patchPos;

            if (data.length < patchPos + (data[bytesOffset + 0] & 0xff))
                throw new InvalidMidiDataException("invalid data at 0: 0x%02x".formatted(data[bytesOffset] & 0xff));
            else if ((data[bytesOffset + 0] & 0xff) == 0x0e)
                patch.fourOp = false;
            else if ((data[bytesOffset + 0] & 0xff) == 0x19)
                patch.fourOp = true;
            else
                throw new InvalidMidiDataException("invalid data at 0: 0x%02x".formatted(data[bytesOffset] & 0xff));
            index++;

            patch.voice[0].tune = patch.voice[1].tune = (byte) ((data[bytesOffset + 2] & 0xff) - 12);
            patch.voice[0].conn = (byte) (data[bytesOffset + 8] & 0x0f);
            patch.voice[1].conn = (byte) (data[bytesOffset + 8] >>> 7);

            int pos = bytesOffset + 3;
            for (int i = 0; i < (patch.fourOp ? 2 : 1); i++) {
                PatchVoice voice = patch.voice[i];

                for (int op = 0; op < 2; op++) {
                    // operator mode
                    voice.op_mode[op] = data[pos++];
                    // KSR & output level
                    voice.op_ksr[op] = (byte) (data[pos] & 0xc0);
                    voice.op_level[op] = (byte) (data[pos++] & 0x3f);
                    // operator envelope
                    voice.op_ad[op] = data[pos++];
                    voice.op_sr[op] = data[pos++];
                    // operator waveform
                    voice.op_wave[op] = data[pos++];

                    // already handled the feedback/connection byte
                    if (op == 0)
                        pos++;
                }
            }

            patches.put(key, patch);
        }
    }
}
