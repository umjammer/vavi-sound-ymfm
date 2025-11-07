package vavi.sound.midi.ymfm;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;


public class OPLPatch {

    // one carrier/modulator pair in a patch, out of a possible two
    public static class PatchVoice {

        // regs 0x20+
        public byte[] op_mode = new byte[2];
        // regs 0x40+ (upper bits)
        public byte[] op_ksr = new byte[2];
        // regs 0x40+ (lower bits)
        public byte[] op_level = new byte[2];
        // regs 0x60+
        public byte[] op_ad = new byte[2];
        // regs 0x80+
        public byte[] op_sr = new byte[2];
        // regs 0xC0+
        public byte conn = 0;
        // regs 0xE0+
        public byte[] op_wave = new byte[2];

        // MIDI note offset
        public byte tune = 0;
        // frequency multiplier
        public double finetune = 1.0;
    }

    public String name;
    // true 4op
    public boolean fourOp = false;
    // only valid if fourOp = false
    public boolean dualTwoOp = false;
    public byte fixedNote = 0;
    // MIDI velocity offset
    public byte velocity = 0;

    public PatchVoice[] voice = {new PatchVoice(), new PatchVoice()};

    public static final String[] names = new String[256]; // TODO MidiConstants?

    public static void load(Map<Integer, OPLPatch> patches, String path) throws IOException {
        try (InputStream file = Files.newInputStream(Path.of(path))) {
            load(patches, file);
        }
    }

    public static void load(Map<Integer, OPLPatch> patches, InputStream file) throws IOException {
        load(patches, file, 0, 0);
    }

    public static void load(Map<Integer, OPLPatch> patches, InputStream file, int offset, long size) throws IOException {
        byte[] data;
        long actualSize = size;
        long skipped = 0;

        if (offset > 0) {
            skipped = file.skip(offset);
            if (skipped != offset) throw new EOFException();
        }

        if (actualSize == 0) {
            if (!(file instanceof FileInputStream))
                throw new EOFException();
            long currentPos = ((FileInputStream) file).getChannel().position();
            long totalSize = ((FileInputStream) file).getChannel().size();
            actualSize = totalSize - currentPos;
        }

        if (actualSize <= 0) throw new EOFException();

        data = new byte[(int) actualSize];
        int readBytes = file.read(data);
        if (readBytes != actualSize) throw new EOFException();

        load(patches, data, (int) actualSize);
    }

    public static void load(Map<Integer, OPLPatch> patches, byte[] data, int size) {
        if (loadWOPL(patches, data, size)) {
        } else if (loadOP2(patches, data, size)) {
        } else if (loadAIL(patches, data, size)) {
        } else if (loadTMB(patches, data, size)) {
        }
    }

    public static boolean loadWOPL(Map<Integer, OPLPatch> patches, final byte[] data, int size) {
        if (size < 19)
            return false;

        String header = new String(data, 0, 10);
        if (!header.equals("WOPL3-BANK"))
            return false;

        // mixed endianness? why???
        int version = (data[11] & 0xff) | ((data[12] & 0xff) << 8); // Little Endian
        int numMelody = ((data[13] & 0xff) << 8) | (data[14] & 0xff); // Big Endian
        int numPerc = ((data[15] & 0xff) << 8) | (data[16] & 0xff); // Big Endian

        if (version > 3)
            return false;

        // currently not supported: global LFO flags, volume model options

        final int bankOffset = 19;
        final int patchOffset = bankOffset + 34 * (numMelody + numPerc);

        final int instSize = (version >= 3) ? 66 : 62;
        final int bankInfoSize = (version >= 2) ? 34 : 0;

        if (size < (numMelody + numPerc) * (128 * instSize + bankInfoSize))
            return false;

        for (int i = 0; i < 128 * (numMelody + numPerc); i++) {
            final byte[] bytes;
            int key = i & 0x7f;
            int bankBytesOffset = 0;

            if (version >= 2) {
                final int bank = i >> 7;
                bankBytesOffset = bankOffset + 34 * bank;

                if (bank >= numMelody) { // percussion banks (use LSB)
                    key |= ((data[bankBytesOffset + 32] & 0xff) << 8) | 0x80;
                } else if (data[bankBytesOffset + 32] != 0) { // bank LSB set (XG)
                    key |= ((data[bankBytesOffset + 32] & 0xff) << 8);
                } else if (data[bankBytesOffset + 33] != 0) { // bank MSB set (GS)
                    key |= ((data[bankBytesOffset + 33] & 0xff) << 8);
                }
            }

            int patchDataOffset = patchOffset + instSize * i;

            // ignore other data for this patch if it's a blank instrument
            // *or* if one of the rhythm mode bits is set (not supported here)
            if ((data[patchDataOffset + 39] & 0x3c) != 0)
                continue;

            OPLPatch patch = new OPLPatch();
            // clear patch data is implied by new OPLPatch()

            // patch names
            if (data[patchDataOffset + 0] != 0)
                patch.name = new String(data, patchDataOffset, 31).trim();
            else
                patch.name = names[key & 0xff];

            // patch global settings
            patch.voice[0].tune = (byte) (data[patchDataOffset + 33] - 12);
            patch.voice[1].tune = (byte) (data[patchDataOffset + 35] - 12);
            patch.velocity = data[patchDataOffset + 36];
            patch.voice[1].finetune = OPLPlayer.midiCalcBend(((double) ((byte) data[patchDataOffset + 37])) / 64.0); // Assuming OPLPlayer.midiCalcBend exists
            patch.fixedNote = data[patchDataOffset + 38];
            patch.fourOp = (data[patchDataOffset + 39] & 3) == 1;
            patch.dualTwoOp = (data[patchDataOffset + 39] & 3) == 3;
            patch.voice[0].conn = data[patchDataOffset + 40];
            patch.voice[1].conn = data[patchDataOffset + 41];

            // patch operator settings
            int pos = patchDataOffset + 42;
            for (int op = 0; op < 4; op++) {
                PatchVoice voice = patch.voice[op / 2];

                final int n = (op % 2) ^ 1;

                voice.op_mode[n] = data[pos++];
                voice.op_ksr[n] = (byte) (data[pos] & 0xc0);
                voice.op_level[n] = (byte) (data[pos++] & 0x3f);
                voice.op_ad[n] = data[pos++];
                voice.op_sr[n] = data[pos++];
                voice.op_wave[n] = data[pos++];
            }

            patches.put(key, patch);
        }

        return true;
    }

    public static boolean loadOP2(Map<Integer, OPLPatch> patches, final byte[] data, int size) {
        if (size < 175 * (36 + 32) + 8)
            return false;

        String header = new String(data, 0, 8);
        if (!header.equals("#OPL_II#"))
            return false;

        // read data for all patches (128 melodic + 47 percussion)
        for (int i = 0; i < 128 + 47; i++) {
            // patches 0-127 are melodic; the rest are for percussion notes 35 thru 81
            int key = (i < 128) ? i : (i + 35);

            OPLPatch patch = new OPLPatch();
            // clear patch data is implied by new OPLPatch()

            // seek to patch data
            int bytesOffset = (36 * i) + 8;

            // read the common data for both 2op voices
            // flag bit 0 is "fixed pitch" (for drums), but it's seemingly only used for drum patches anyway, so ignore it?
            patch.dualTwoOp = (data[bytesOffset + 0] & 4) != 0;
            // second voice detune
            patch.voice[1].finetune = OPLPlayer.midiCalcBend((((double) ((byte) (data[bytesOffset + 2] - 128))) / 64.0));

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
                patch.name = names[key];

            patches.put(key, patch);
        }

        return true;
    }

    public static boolean loadAIL(Map<Integer, OPLPatch> patches, final byte[] data, int size) {
        int index = 0;

        while (true) {
            if (size < index * 6 + 6)
                return false;

            int entryOffset = index * 6;

            if ((data[entryOffset + 0] & 0xff) == 0xff && (data[entryOffset + 1] & 0xff) == 0xff)
                return true; // end of patches

            int key;
            if ((data[entryOffset + 1] & 0xff) == 0x7f)
                key = (data[entryOffset + 0] & 0xff) | 0x80;
            else
                key = ((data[entryOffset + 0] & 0xff) | ((data[entryOffset + 1] & 0xff) << 8)) & 0x7f7f;

            OPLPatch patch = new OPLPatch();
            // clear patch data
            patch.name = names[key & 0xff];

            int patchPos = (data[entryOffset + 2] & 0xff) | ((data[entryOffset + 3] & 0xff) << 8) | ((data[entryOffset + 4] & 0xff) << 16) | ((data[entryOffset + 5] & 0xff) << 24);
            if (size < patchPos)
                return false;

            int bytesOffset = patchPos;

            if (size < patchPos + (data[bytesOffset + 0] & 0xff))
                return false;
            else if (data[bytesOffset + 0] == 0x0e)
                patch.fourOp = false;
            else if (data[bytesOffset + 0] == 0x19)
                patch.fourOp = true;
            else
                return false;
            index++;

            patch.voice[0].tune = patch.voice[1].tune = (byte) (data[bytesOffset + 2] - 12);
            patch.voice[0].conn = (byte) (data[bytesOffset + 8] & 0x0f);
            patch.voice[1].conn = (byte) (data[bytesOffset + 8] >> 7);

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

    public static boolean loadTMB(Map<Integer, OPLPatch> patches, final byte[] data, int size) {
        if (size < 256 * 13)
            return false;

        for (int key = 0; key < 256; key++) {
            OPLPatch patch = new OPLPatch();
            // clear patch data is implied by new OPLPatch()
            patch.name = names[key];

            int bytesOffset = key * 13;

            // since this format has no identifying info, we can only really reject it
            // if it has invalid values in a few spots
            if (((data[bytesOffset + 8] | data[bytesOffset + 9] | data[bytesOffset + 10]) & 0xf0) != 0)
                return false;

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

        return true;
    }
}
