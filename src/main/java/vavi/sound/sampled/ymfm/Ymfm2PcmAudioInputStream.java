/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.ymfm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import vavi.io.OutputEngine;
import vavi.io.OutputEngineInputStream;
import vavi.sound.ymfm.Misc.Ym2149;
import vavi.sound.ymfm.Opl.Y8950;
import vavi.sound.ymfm.Opl.Ym2413;
import vavi.sound.ymfm.Opl.Ym3526;
import vavi.sound.ymfm.Opl.Ym3812;
import vavi.sound.ymfm.Opl.Ymf262;
import vavi.sound.ymfm.Opl.Ymf278b;
import vavi.sound.ymfm.Opm.Ym2151;
import vavi.sound.ymfm.Opn.Ym2203;
import vavi.sound.ymfm.Opn.Ym2608;
import vavi.sound.ymfm.Opn.Ym2610;
import vavi.sound.ymfm.Opn.Ym2610b;
import vavi.sound.ymfm.Opn.Ym2612;
import vavi.sound.ymfm.YmFm;
import vavi.sound.ymfm.YmFm.AccessClass;
import vavi.sound.ymfm.YmFm.VgmChip;
import vavi.util.ByteUtil;
import vavi.util.archive.Archives;

import static java.lang.System.getLogger;
import static vavi.sound.ymfm.YmFm.AccessClass.ADPCM_A;
import static vavi.sound.ymfm.YmFm.AccessClass.ADPCM_B;
import static vavi.sound.ymfm.YmFm.AccessClass.PCM;


/**
 * Ymfm2PcmAudioInputStream.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025/09/09 nsano initial version <br>
 */
public class Ymfm2PcmAudioInputStream extends AudioInputStream {

    private static final Logger logger = getLogger(Ymfm2PcmAudioInputStream.class.getName());

    /** use format's properties */
    public Ymfm2PcmAudioInputStream(InputStream stream, AudioFormat format, long length) throws IOException {
        this(stream, format, length, format.properties());
    }

    /** format's properties are ignored */
    public Ymfm2PcmAudioInputStream(InputStream stream, AudioFormat format, long length, Map<String, Object> props) throws IOException {
        super(new OutputEngineInputStream(new YmfmOutputEngine(stream, format, props)), format, length);
    }

    /** */
    private static class YmfmOutputEngine implements OutputEngine {

        private OutputStream out;

        private final AudioFormat format;
        private final Map<String, Object> props;

        private final List<VgmChip> active_chips = new ArrayList<>();
        private final byte[] buffer;
        private int offset;
        private boolean done = false;
        private long output_pos = 0;

        public YmfmOutputEngine(InputStream in, AudioFormat format, Map<String, Object> props) throws IOException {
            this.format = format;
            this.props = props;

            InputStream is = Archives.getInputStream(in);
            this.buffer = is.readAllBytes();
            int data_start = parseHeader(buffer);
            if (active_chips.isEmpty()) {
                throw new IOException("No compatible chips found");
            }
            this.offset = data_start;
        }

        @Override
        public void initialize(OutputStream out) throws IOException {
            if (this.out != null) {
                throw new IOException("Already initialized");
            } else {
                this.out = out;
            }
        }

        @Override
        public void execute() throws IOException {
            int output_rate = (int) format.getSampleRate();
            long output_step = 0x1_0000_0000L / output_rate;

            if (!done && offset < buffer.length) {

                int delay = processCommand();

                while (delay-- != 0) {
                    int[] outputs = new int[2];
                    for (var chip : active_chips)
                        chip.generate(output_pos, output_step, outputs);
                    output_pos += output_step;

                    byte[] b = new byte[4];
                    ByteUtil.writeLeShort((short) outputs[0], b, 0);
                    ByteUtil.writeLeShort((short) outputs[1], b, 2);
                    out.write(b, 0, b.length);
                }
            } else {
                out.flush();
                out.close();
            }
        }

        @Override
        public void finish() throws IOException {
            active_chips.clear();
        }

        private int processCommand() {
            int delay = 0;
            int cmd = buffer[offset++] & 0xff;
            switch (cmd) {
                case 0x51, 0xa1 -> {
                    write_chip(Ym2413.class, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x52, 0xa2 -> {
                    write_chip(Ym2612.class, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x53, 0xa3 -> {
                    write_chip(Ym2612.class, cmd >> 7, (buffer[offset] & 0xff) | 0x100, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x54, 0xa4 -> {
                    write_chip(Ym2151.class, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x55, 0xa5 -> {
                    write_chip(Ym2203.class, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x56, 0xa6 -> {
                    write_chip(Ym2608.class, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x57, 0xa7 -> {
                    write_chip(Ym2608.class, cmd >> 7, (buffer[offset] & 0xff) | 0x100, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x58, 0xa8 -> {
                    write_chip(Ym2610.class, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x59, 0xa9 -> {
                    write_chip(Ym2610.class, cmd >> 7, (buffer[offset] & 0xff) | 0x100, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x5a, 0xaa -> {
                    write_chip(Ym3812.class, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x5b, 0xab -> {
                    write_chip(Ym3526.class, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x5c, 0xac -> {
                    write_chip(Y8950.class, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x5e, 0xae -> {
                    write_chip(Ymf262.class, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x5f, 0xaf -> {
                    write_chip(Ymf262.class, cmd >> 7, (buffer[offset] & 0xff) | 0x100, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0x61 -> {
                    delay = (buffer[offset] & 0xff) | ((buffer[offset + 1] & 0xff) << 8);
                    offset += 2;
                }
                case 0x62 -> delay = 735;
                case 0x63 -> delay = 882;
                case 0x66 -> done = true;
                case 0x67 -> {
                    int dummy = buffer[offset++] & 0xff;
                    if (dummy != 0x66)
                        break;
                    int type = buffer[offset++] & 0xff;
                    int[] tmp = new int[]{offset};
                    int size = parse_uint32(buffer, tmp);
                    offset = tmp[0];
                    int[] localOffset = new int[]{offset};

                    switch (type) {
                        case 0x00 -> {
                            VgmChip chip = find_chip(Ym2612.class, 0);
                            if (chip != null)
                                chip.write_data(PCM, 0, size - 8, buffer, localOffset[0]);
                        }
                        case 0x81 -> add_rom_data(Ym2608.class, ADPCM_B, buffer, localOffset, size - 8);
                        case 0x82 -> add_rom_data(Ym2610.class, ADPCM_A, buffer, localOffset, size - 8);
                        case 0x83 -> add_rom_data(Ym2610.class, ADPCM_B, buffer, localOffset, size - 8);
                        case 0x84, 0x87 -> add_rom_data(Ymf278b.class, PCM, buffer, localOffset, size - 8);
                        case 0x88 -> add_rom_data(Y8950.class, ADPCM_B, buffer, localOffset, size - 8);
                    }
                    offset += size;
                }
                case 0x68 -> logger.log(Level.INFO, "68: PCM RAM write");
                case 0xa0 -> {
                    write_chip(Ym2149.class, (buffer[offset] >>> 7) & 0xff, buffer[offset] & 0x7f, buffer[offset + 1] & 0xff);
                    offset += 2;
                }
                case 0xd0 -> {
                    write_chip(Ymf278b.class, (buffer[offset] >> 7) & 0xff, ((buffer[offset] & 0x7f) << 8) | (buffer[offset + 1] & 0xff), buffer[offset + 2] & 0xff);
                    offset += 3;
                }
                case 0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x7b, 0x7c, 0x7d, 0x7e, 0x7f ->
                        delay = (cmd & 15) + 1;
                case 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f -> {
                    VgmChip chip = find_chip(Ym2612.class, 0);
                    if (chip != null)
                        chip.write(0x2a, chip.read_pcm());
                    delay = cmd & 15;
                }
                case 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d, 0x3e, 0x3f, 0x4f, 0x50 ->
                        offset++;
                case 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x4b, 0x4c, 0x4d, 0x4e, 0x5d, 0xb0, 0xb1, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xbb, 0xbc, 0xbd, 0xbe, 0xbf ->
                        offset += 2;
                case 0xc9, 0xca, 0xcb, 0xcc, 0xcd, 0xce, 0xcf, 0xd7, 0xd8, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde, 0xdf, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xd1, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6 ->
                        offset += 3;
                case 0xe0 -> {
                    VgmChip chip = find_chip(Ym2612.class, 0);
                    int[] tmp = new int[]{offset};
                    int pos = parse_uint32(buffer, tmp);
                    offset = tmp[0];
                    if (chip != null)
                        chip.seek_pcm(pos);
                    offset += 4;
                }
                case 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xeb, 0xec, 0xed, 0xee, 0xef, 0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, 0xfb, 0xfc, 0xfd, 0xfe, 0xff ->
                        offset += 4;
            }

            return delay;
        }

        private static int parse_uint32(byte[] buffer, int[] offset) {
            int result = (buffer[offset[0]++] & 0xff);
            result |= (buffer[offset[0]++] & 0xff) << 8;
            result |= (buffer[offset[0]++] & 0xff) << 16;
            result |= (buffer[offset[0]++] & 0xff) << 24;
            return result;
        }

        private <T extends YmFm.Chip> void add_chips(int clock, String chipName, Class<T> c) {
            int clockVal = clock & 0x3fff_ffff;
            int numChips = (clock & 0x4000_0000L) != 0 ? 2 : 1;
            logger.log(Level.INFO, "Adding %s%s @ %dHz".formatted((numChips == 2) ? "2 x " : "", chipName, clockVal));
            for (int index = 0; index < numChips; index++) {
                var chip = new VgmChip(clockVal, c);
                chip.setName("%s #%d".formatted(chipName, index));
                active_chips.add(chip);
            }

            if (c == Ym2608.class) {
                Path rom = Path.of(System.getProperty("mdsound.pcm.path", ""), "ym2608_adpcm_rom.bin");
                byte[] temp;
                try {
                    temp = Files.readAllBytes(rom);
                } catch (IOException e) {
                    throw new UncheckedIOException("YM2608 enabled but ym2608_adpcm_rom.bin not found", e);
                }
                for (var chip : active_chips)
                    if (chip.type() == c) {
                        chip.write_data(ADPCM_A, 0, temp.length, temp, 0);
                        logger.log(Level.DEBUG, rom + " loaded, " + chipName + ", " + temp.length);
                    }
            }
        }

        private int parseHeader(byte[] buffer) {
            int[] offset = new int[]{4};

            int size = parse_uint32(buffer, offset);
            if (offset[0] - 4 + size > buffer.length) {
                logger.log(Level.INFO, "Total size for file is too small; file may be truncated");
            }

            int version = parse_uint32(buffer, offset);
            if (version > 0x171)
                logger.log(Level.WARNING, "Warning: version > 1.71 detected, some things may not work");

            parse_uint32(buffer, offset);
            int clock = parse_uint32(buffer, offset);
            if (clock != 0)
                add_chips(clock, "YM2413", Ym2413.class);

            parse_uint32(buffer, offset);
            parse_uint32(buffer, offset);
            parse_uint32(buffer, offset);
            parse_uint32(buffer, offset);
            parse_uint32(buffer, offset);
            parse_uint32(buffer, offset);

            clock = parse_uint32(buffer, offset);
            if (version >= 0x110 && clock != 0)
                add_chips(clock, "YM2612", Ym2612.class);

            clock = parse_uint32(buffer, offset);
            if (version >= 0x110 && clock != 0)
                add_chips(clock, "YM2151", Ym2151.class);

            int data_start = parse_uint32(buffer, offset);
            data_start += offset[0] - 4;
            if (version < 0x150)
                data_start = 0x40;

            if (offset[0] + 4 > data_start) return data_start;
            parse_uint32(buffer, offset);
            if (offset[0] + 4 > data_start) return data_start;
            parse_uint32(buffer, offset);
            if (offset[0] + 4 > data_start) return data_start;
            clock = parse_uint32(buffer, offset);
            if (version >= 0x151 && clock != 0)
                logger.log(Level.WARNING, "clock for RF5C68 specified, but not supported");

            if (offset[0] + 4 > data_start) return data_start;
            clock = parse_uint32(buffer, offset);
            if (version >= 0x151 && clock != 0)
                add_chips(clock, "YM2203", Ym2203.class);

            if (offset[0] + 4 > data_start) return data_start;
            clock = parse_uint32(buffer, offset);
            if (version >= 0x151 && clock != 0)
                add_chips(clock, "YM2608", Ym2608.class);

            if (offset[0] + 4 > data_start) return data_start;
            clock = parse_uint32(buffer, offset);
            if (version >= 0x151 && clock != 0) {
                if ((clock & 0x8000_0000) != 0)
                    add_chips(clock, "YM2610B", Ym2610b.class);
                else
                    add_chips(clock, "YM2610", Ym2610.class);
            }

            if (offset[0] + 4 > data_start) return data_start;
            clock = parse_uint32(buffer, offset);
            if (version >= 0x151 && clock != 0)
                add_chips(clock, "YM3812", Ym3812.class);

            if (offset[0] + 4 > data_start) return data_start;
            clock = parse_uint32(buffer, offset);
            if (version >= 0x151 && clock != 0)
                add_chips(clock, "YM3526", Ym3526.class);

            if (offset[0] + 4 > data_start) return data_start;
            clock = parse_uint32(buffer, offset);
            if (version >= 0x151 && clock != 0)
                add_chips(clock, "Y8950", Y8950.class);

            if (offset[0] + 4 > data_start) return data_start;
            clock = parse_uint32(buffer, offset);
            if (version >= 0x151 && clock != 0)
                add_chips(clock, "YMF262", Ymf262.class);

            if (offset[0] + 4 > data_start) return data_start;
            clock = parse_uint32(buffer, offset);
            if (version >= 0x151 && clock != 0)
                add_chips(clock, "YMF278B", Ymf278b.class);

            return data_start;
        }

        private VgmChip find_chip(Class<? extends YmFm.Chip> type, int index) {
            for (var chip : active_chips)
                if (chip.type() == type && index-- == 0)
                    return chip;
            return null;
        }

        private void write_chip(Class<? extends YmFm.Chip> type, int index, int reg, int data) {
            VgmChip chip = find_chip(type, index);
            if (chip != null)
                chip.write(reg, data);
        }

        private void add_rom_data(Class<? extends YmFm.Chip> type, AccessClass access, byte[] buffer, int[] localOffset, int size) {
            int length = parse_uint32(buffer, localOffset);
            int start = parse_uint32(buffer, localOffset);
            for (int index = 0; index < 2; index++) {
                VgmChip chip = find_chip(type, index);
                if (chip != null) {
                    chip.write_data(access, start, size, buffer, localOffset[0]);
                }
            }
        }
    }
}
