package vgmrender;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import vavi.io.LittleEndianDataOutputStream;
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
import vavi.util.archive.Archives;

import static java.lang.System.getLogger;
import static vavi.sound.ymfm.YmFm.AccessClass.ADPCM_A;
import static vavi.sound.ymfm.YmFm.AccessClass.ADPCM_B;
import static vavi.sound.ymfm.YmFm.AccessClass.PCM;


//
// Simple vgm renderer.
//
public class VgmRender {

    private static final Logger logger = getLogger(VgmRender.class.getName());

    // run this many dummy clocks of each chip before generating
    static final int EXTRA_CLOCKS = 0;

// enable this to run the nuked OPN2 core in parallel; output is not captured,
// but logging can be added to observe behaviors
//#define RUN_NUKED_OPN2 (0)
//#if (RUN_NUKED_OPN2)
//namespace nuked {
//bool s_log_envelopes = false;
//final int s_log_envelopes_channel = 5;
//#include "test/ym3438.h"
//}
//#endif

// enable this to capture each chip at its native rate as well
//#define CAPTURE_NATIVE (0 || RUN_NUKED_OPN2)

    //*********************************************************
    //  GLOBAL TYPES
    //*********************************************************

    // we use an int64_t as emulated time, as a 32.32 fixed point value
    //using long = int64_t;

    // enumeration of the different types of chips we support
    enum ChipType {
        CHIP_YM2149,
        CHIP_YM2151,
        CHIP_YM2203,
        CHIP_YM2413,
        CHIP_YM2608,
        CHIP_YM2610,
        CHIP_YM2612,
        CHIP_YM3526,
        CHIP_Y8950,
        CHIP_YM3812,
        CHIP_YMF262,
        CHIP_YMF278B;
        static final int CHIP_TYPES = values().length;
    }

    //*********************************************************
    //  CLASSES
    //*********************************************************

    // ======================> vgm_chip_base

    // abstract base class for a Yamaha chip; we keep a list of these for processing
    // as new commands come in
    abstract static class VgmChipBase extends YmFm.Interface {

        // construction
        public VgmChipBase(int clock, ChipType type, String name) {
            m_type = type;
            m_name = name;
        }

        // simple getters
        public final ChipType type() {
            return m_type;
        }

        public abstract int sample_rate();

        // required methods for derived classes to implement
        public abstract void write(int reg, int data);

        public abstract void generate(long output_start, long output_step, int[] buffer);

        // write data to the ADPCM-A buffer
        public void write_data(AccessClass type, int base, int length, byte[] src, int offset) {
            int end = base + length;
            if (end > m_data[type.ordinal()].data.length)
                m_data[type.ordinal()].data = new int[end];
            for (int i = 0; i < src.length; i++)
                m_data[type.ordinal()].data[base + i] = src[i] & 0xff;
        }

        // seek within the PCM stream
        public void seek_pcm(int pos) {
            m_pcm_offset = pos;
        }

        public int read_pcm() {
            var pcm = m_data[PCM.ordinal()];
            return (m_pcm_offset < pcm.data.length) ? pcm.data[m_pcm_offset++] : 0;
        }

        // internal state
        protected ChipType m_type;
        protected String m_name;
        protected YmFm.Output[] m_data = new YmFm.Output[AccessClass.values().length];
        protected int m_pcm_offset;
//#if (CAPTURE_NATIVE)

//		public List<Integer> m_native_data;
//#endif
//#if (RUN_NUKED_OPN2)
//		public nuked.ym3438_t m_external =null;
//		public List<Integer> m_nuked_data;
//#endif
    }

    // ======================> vgm_chip

    // actual chip-specific implementation class; includes implementatino of the
    // YmFmInterface as needed for vgmplay purposes
    //template<typename ChipType>
    static class VgmChip<T extends YmFm.Chip> extends VgmChipBase {

        // construction
        public VgmChip(int clock, ChipType type, String name, Class<T> c) {
            super(clock, type, name);
            try {
                m_chip = c.getDeclaredConstructor(YmFm.Interface.class).newInstance(this);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            m_clock = clock;
            m_clocks = 0;
            m_step = 0x100000000L / m_chip.sample_rate(clock);
            m_pos = 0;
            m_output = m_chip.outputFactory();

            m_chip.reset();

            for (int clock_ = 0; clock_ < EXTRA_CLOCKS; clock_++)
                m_chip.generate(m_output, 1);

//#if (RUN_NUKED_OPN2)
//			if (type == chip_type.CHIP_YM2612) {
//				m_external = new nuked.ym3438_t;
//				nuked.OPN2_SetChipType (nuked.ym3438_mode_ym2612);
//				nuked.OPN2_Reset (m_external);
//				nuked.Bit16s buffer[2];
//				for (int clocks = 0; clocks < 24 * EXTRA_CLOCKS; clocks++)
//					nuked.OPN2_Clock (m_external, buffer);
//			}
//#endif
        }

        /** */
        public final void reset() {
            m_chip.reset();
        }

        @Override
        public final int sample_rate() {
            return m_chip.sample_rate(m_clock);
        }

        // handle a register write: just queue for now
        @Override
        public void write(int reg, int data) {
            m_queue.add(new int[] {reg, data});
        }

        // generate one output sample of output
        @Override
        public void generate(long output_start, long output_step, int[] buffer) {
            int addr1 = 0xffff, addr2 = 0xffff;
            int data1 = 0, data2 = 0;

            // see if there is data to be written; if so, extract it and dequeue
            if (!m_queue.isEmpty()) {
                var front = m_queue.get(0);
                addr1 = 0 + 2 * ((front[0] >> 8) & 3);
                data1 = front[0] & 0xff;
                addr2 = addr1 + ((m_type == ChipType.CHIP_YM2149) ? 2 : 1);
                data2 = front[1];
                m_queue.remove(m_queue.get(0));
            }

            // write to the chip
            if (addr1 != 0xffff) {
                logger.log(Level.TRACE, "%10.5f: %s %03X=%02X".formatted((double) output_start / (double) (1L << 32), m_name, data1 + 0x100 * (addr1 / 2), data2));
                m_chip.write(addr1, data1);
                m_chip.write(addr2, data2);
            }

            // generate at the appropriate sample rate
//		nuked.s_log_envelopes = (output_start >= (22ll << 32) && output_start < (24ll << 32));
            for (; m_pos <= output_start; m_pos += m_step) {
                m_chip.generate(m_output, 1);

//#if (CAPTURE_NATIVE)
                // if capturing native, append each generated sample
//				m_native_data.push_back(m_output.data[0]);
//				m_native_data.push_back(m_output.data[ChipType.OUTPUTS > 1 ? 1 : 0]);
//#endif

//#if (RUN_NUKED_OPN2)
//				// if running nuked, capture its output as well
//				if (m_external != null) {
//					int[] sum = {0};
//					if (addr1 != 0xffff)
//						nuked.OPN2_Write (m_external, addr1, data1);
//					nuked.Bit16s buffer[2];
//					for (int clocks = 0; clocks < 12; clocks++) {
//						nuked.OPN2_Clock (m_external, buffer);
//						sum[0] += buffer[0];
//						sum[1] += buffer[1];
//					}
//					if (addr2 != 0xffff)
//						nuked.OPN2_Write (m_external, addr2, data2);
//					for (int clocks = 0; clocks < 12; clocks++) {
//						nuked.OPN2_Clock (m_external, buffer);
//						sum[0] += buffer[0];
//						sum[1] += buffer[1];
//					}
//					addr1 = addr2 = 0xffff;
//					m_nuked_data.push_back(sum[0] / 24);
//					m_nuked_data.push_back(sum[1] / 24);
//				}
//#endif
            }

            int OUTPUTS = m_chip.getOutputs();
//logger.log(Level.DEBUG, m_type + ", " + OUTPUTS + ", " + m_output.data.length);
            int p = 0; // buffer
            // add the final result to the buffer
            if (m_type == ChipType.CHIP_YM2203) {
                int out0 = m_output.data[0];
                int out1 = m_output.data[1 % OUTPUTS];
                int out2 = m_output.data[2 % OUTPUTS];
                int out3 = m_output.data[3 % OUTPUTS];
                buffer[p++] += out0 + out1 + out2 + out3;
                buffer[p++] += out0 + out1 + out2 + out3;
            } else if (m_type == ChipType.CHIP_YM2608 || m_type == ChipType.CHIP_YM2610) {
                int out0 = m_output.data[0];
                int out1 = m_output.data[1 % OUTPUTS];
                int out2 = m_output.data[2 % OUTPUTS];
                buffer[p++] += out0 + out2;
                buffer[p++] += out1 + out2;
            } else if (m_type == ChipType.CHIP_YMF278B) {
                buffer[p++] += m_output.data[4 % OUTPUTS];
                buffer[p++] += m_output.data[5 % OUTPUTS];
            } else if (OUTPUTS == 1) {
                buffer[p++] += m_output.data[0];
                buffer[p++] += m_output.data[0];
            } else {
                buffer[p++] += m_output.data[0];
                buffer[p++] += m_output.data[1 % OUTPUTS];
            }
            m_clocks++;
        }

        // handle a read from the buffer
        public int ymfm_external_read(AccessClass type, int offset) {
            var data = m_data[type.ordinal()];
            return (offset < data.data.length) ? data.data[offset] : 0;
        }

        // internal state
        protected T m_chip;
        protected int m_clock;
        protected long m_clocks;
        protected YmFm.Output m_output;
        long m_step;
        long m_pos;
        protected List<int[]> m_queue = new ArrayList<>();
    }

	//*********************************************************
	//  GLOBAL HELPERS
	//*********************************************************

    // global list of active chips
    static List<VgmChipBase> active_chips = new ArrayList<>();

	//-------------------------------------------------
	//  parse_uint32 - parse a little-endian int
	//-------------------------------------------------
    static int parse_uint32(byte[] buffer, int[] offset) {
        int result = (buffer[offset[0]++] & 0xff);
        result |= (buffer[offset[0]++] & 0xff) << 8;
        result |= (buffer[offset[0]++] & 0xff) << 16;
        result |= (buffer[offset[0]++] & 0xff) << 24;
        return result;
    }

	//-------------------------------------------------
	//  add_chips - add 1 or 2 instances of the given
	//  supported chip type
	//-------------------------------------------------
    //template<typename ChipType>
    static <T extends YmFm.Chip> void add_chips(int clock, ChipType type, String chipname, Class<T> c) {
        int clockval = clock & 0x3fff_ffff;
        int numchips = (clock & 0x4000_0000L) != 0 ? 2 : 1;
        logger.log(Level.INFO, "Adding %s%s @ %dHz".formatted((numchips == 2) ? "2 x " : "", chipname, clockval));
        for (int index = 0; index < numchips; index++) {
            String name = "%s #%d".formatted(chipname, index);
            active_chips.add(new VgmChip(clockval, type, (numchips == 2) ? name : chipname, c));
        }

        if (type == ChipType.CHIP_YM2608) {
            Path rom = Path.of("ym2608_adpcm_rom.bin");
            if (rom == null)
                logger.log(Level.WARNING, "YM2608 enabled but ym2608_adpcm_rom.bin not found");
            else {
                byte[] temp;
                try {
                    temp = Files.readAllBytes(rom);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                for (var chip : active_chips)
                    if (chip.type() == type)
                        chip.write_data(ADPCM_A, 0, temp.length, temp, 0);
            }
        }
    }

    //-------------------------------------------------
    //  parse_header - parse the vgm header, adding
    //  chips for anything we encounter that we can
    //  support
    //-------------------------------------------------
    static int parse_header(byte[] buffer) {
        // +00: already checked the ID
        int[] offset = new int[] {4};

        // +04: parse the size
        int size = parse_uint32(buffer, offset);
        if (offset[0] - 4 + size > buffer.length) {
            logger.log(Level.INFO, "Total size for file is too small; file may be truncated");
            size = buffer.length - 4;
        }
//        buffer = new byte[size + 4]; // TODO vavi buffer is not a reference

        // +08: parse the version
        int version = parse_uint32(buffer, offset);
        if (version > 0x171)
            logger.log(Level.WARNING, "Warning: version > 1.71 detected, some things may not work");

        // +0C: SN76489 clock
        int clock = parse_uint32(buffer, offset);
        if (clock != 0)
            logger.log(Level.WARNING, "clock for SN76489 specified (%d), but not supported%n".formatted(clock));

        // +10: YM2413 clock
        clock = parse_uint32(buffer, offset);
        if (clock != 0)
            add_chips(clock, ChipType.CHIP_YM2413, "YM2413", Ym2413.class);

        // +14: GD3 offset
        int dummy = parse_uint32(buffer, offset);

        // +18: Total # samples
        dummy = parse_uint32(buffer, offset);

        // +1C: Loop offset
        dummy = parse_uint32(buffer, offset);

        // +20: Loop # samples
        dummy = parse_uint32(buffer, offset);

        // +24: Rate
        dummy = parse_uint32(buffer, offset);

        // +28: SN76489 feedback / SN76489 shift register width / SN76489 Flags
        dummy = parse_uint32(buffer, offset);

        // +2C: YM2612 clock
        clock = parse_uint32(buffer, offset);
        if (version >= 0x110 && clock != 0)
            add_chips(clock, ChipType.CHIP_YM2612, "YM2612", Ym2612.class);

        // +30: YM2151 clock
        clock = parse_uint32(buffer, offset);
        if (version >= 0x110 && clock != 0)
            add_chips(clock, ChipType.CHIP_YM2151, "YM2151", Ym2151.class);

        // +34: VGM data offset
        int data_start = parse_uint32(buffer, offset);
        data_start += offset[0] - 4;
        if (version < 0x150)
            data_start = 0x40;

        // +38: Sega PCM clock
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            logger.log(Level.WARNING, "clock for Sega PCM specified, but not supported%n");

        // +3C: Sega PCM interface register
        dummy = parse_uint32(buffer, offset);

        // +40: RF5C68 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            logger.log(Level.WARNING, "clock for RF5C68 specified, but not supported%n");

        // +44: YM2203 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            add_chips(clock, ChipType.CHIP_YM2203, "YM2203", Ym2203.class);

        // +48: YM2608 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            add_chips(clock, ChipType.CHIP_YM2608, "YM2608", Ym2608.class);

        // +4C: YM2610/2610B clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0) {
            if ((clock & 0x80000000) != 0)
                add_chips(clock, ChipType.CHIP_YM2610, "YM2610B", Ym2610b.class);
            else
                add_chips(clock, ChipType.CHIP_YM2610, "YM2610", Ym2610.class);
        }

        // +50: YM3812 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            add_chips(clock, ChipType.CHIP_YM3812, "YM3812", Ym3812.class);

        // +54: YM3526 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            add_chips(clock, ChipType.CHIP_YM3526, "YM3526", Ym3526.class);

        // +58: Y8950 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            add_chips(clock, ChipType.CHIP_Y8950, "Y8950", Y8950.class);

        // +5C: YMF262 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            add_chips(clock, ChipType.CHIP_YMF262, "YMF262", Ymf262.class);

        // +60: YMF278B clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            add_chips(clock, ChipType.CHIP_YMF278B, "YMF278B", Ymf278b.class);

        // +64: YMF271 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            logger.log(Level.WARNING, "clock for YMF271 specified, but not supported");

        // +68: YMF280B clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            logger.log(Level.WARNING, "clock for YMF280B specified, but not supported");

        // +6C: RF5C164 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            logger.log(Level.WARNING, "clock for RF5C164 specified, but not supported");

        // +70: PWM clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0)
            logger.log(Level.WARNING, "clock for PWM specified, but not supported");

        // +74: AY8910 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x151 && clock != 0) {
            logger.log(Level.WARNING, "clock for AY8910 specified, substituting YM2149");
            add_chips(clock, ChipType.CHIP_YM2149, "YM2149", Ym2149.class);
        }

        // +78: AY8910 flags
        if (offset[0] + 4 > data_start)
            return data_start;
        dummy = parse_uint32(buffer, offset);

        // +7C: volume / loop info
        if (offset[0] + 4 > data_start)
            return data_start;
        dummy = parse_uint32(buffer, offset);
        if ((dummy & 0xff) != 0)
            logger.log(Level.INFO, "Volume modifier: %02X (=%d)".formatted(dummy & 0xff, (int) (Math.pow(2, (double) (dummy & 0xff) / 0x20))));

        // +80: GameBoy DMG clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for GameBoy DMG specified, but not supported");

        // +84: NES APU clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for NES APU specified, but not supported");

        // +88: MultiPCM clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for MultiPCM specified, but not supported");

        // +8C: uPD7759 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for uPD7759 specified, but not supported");

        // +90: OKIM6258 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for OKIM6258 specified, but not supported");

        // +94: OKIM6258 Flags / K054539 Flags / C140 Chip Type / reserved
        if (offset[0] + 4 > data_start)
            return data_start;
        dummy = parse_uint32(buffer, offset);

        // +98: OKIM6295 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for OKIM6295 specified, but not supported");

        // +9C: K051649 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for K051649 specified, but not supported");

        // +A0: K054539 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for K054539 specified, but not supported");

        // +A4: HuC6280 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for HuC6280 specified, but not supported");

        // +A8: C140 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for C140 specified, but not supported");

        // +AC: K053260 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for K053260 specified, but not supported");

        // +B0: Pokey clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for Pokey specified, but not supported");

        // +B4: QSound clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x161 && clock != 0)
            logger.log(Level.WARNING, "clock for QSound specified, but not supported");

        // +B8: SCSP clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x171 && clock != 0)
            logger.log(Level.WARNING, "clock for SCSP specified, but not supported");

        // +BC: extra header offset
        if (offset[0] + 4 > data_start)
            return data_start;
        int extra_header = parse_uint32(buffer, offset);

        // +C0: WonderSwan clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x171 && clock != 0)
            logger.log(Level.WARNING, "clock for WonderSwan specified, but not supported");

        // +C4: VSU clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x171 && clock != 0)
            logger.log(Level.WARNING, "clock for VSU specified, but not supported");

        // +C8: SAA1099 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x171 && clock != 0)
            logger.log(Level.WARNING, "clock for SAA1099 specified, but not supported");

        // +CC: ES5503 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x171 && clock != 0)
            logger.log(Level.WARNING, "clock for ES5503 specified, but not supported");

        // +D0: ES5505/ES5506 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x171 && clock != 0)
            logger.log(Level.WARNING, "clock for ES5505/ES5506 specified, but not supported");

        // +D4: ES5503 output channels / ES5505/ES5506 amount of output channels / C352 clock divider
        if (offset[0] + 4 > data_start)
            return data_start;
        dummy = parse_uint32(buffer, offset);

        // +D8: X1-010 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x171 && clock != 0)
            logger.log(Level.WARNING, "clock for X1-010 specified, but not supported");

        // +DC: C352 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x171 && clock != 0)
            logger.log(Level.WARNING, "clock for C352 specified, but not supported");

        // +E0: GA20 clock
        if (offset[0] + 4 > data_start)
            return data_start;
        clock = parse_uint32(buffer, offset);
        if (version >= 0x171 && clock != 0)
            logger.log(Level.WARNING, "clock for GA20 specified, but not supported");

        return data_start;
    }

	//-------------------------------------------------
	//  find_chip - find the given chip and index
	//-------------------------------------------------
    static VgmChipBase find_chip(ChipType type, int index) {
        for (var chip : active_chips)
            if (chip.type() == type && index-- == 0)
                return chip;
        return null;
    }

	//-------------------------------------------------
	//  write_chip - handle a write to the given chip
	//  and index
	//-------------------------------------------------
    static void write_chip(ChipType type, int index, int reg, int data) {
        VgmChipBase chip = find_chip(type, index);
        if (chip != null)
            chip.write(reg, data);
    }

	//-------------------------------------------------
	//  add_rom_data - add data to the given chip
	//  type in the given access class
	//-------------------------------------------------
    static void add_rom_data(ChipType type, AccessClass access, byte[] buffer, int[] localoffset, int size) {
        int length = parse_uint32(buffer, localoffset);
        int start = parse_uint32(buffer, localoffset);
        for (int index = 0; index < 2; index++) {
            VgmChipBase chip = find_chip(type, index);
            if (chip != null)
                chip.write_data(access, start, size, buffer, localoffset[0]);
        }
    }

	//-------------------------------------------------
	//  generate_all - generate everything described
	//  in the vgmplay file
	//-------------------------------------------------
    static void generate_all(byte[] buffer, int data_start, int output_rate, List<Integer> wav_buffer) {
        // set the offset to the data start and go
        int offset = data_start;
        boolean done = false;
        long output_step = 0x1_0000_0000L / output_rate;
        long output_pos = 0;
        while (!done && offset < buffer.length) {
            int delay = 0;
//logger.log(Level.DEBUG, "offset: " + offset);
            int cmd = buffer[offset++] & 0xff;
            switch (cmd) {
                // YM2413, write value dd to register aa
                case 0x51:
                case 0xa1:
                    write_chip(ChipType.CHIP_YM2413, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YM2612 port 0, write value dd to register aa
                case 0x52:
                case 0xa2:
                    write_chip(ChipType.CHIP_YM2612, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YM2612 port 1, write value dd to register aa
                case 0x53:
                case 0xa3:
                    write_chip(ChipType.CHIP_YM2612, cmd >> 7, (buffer[offset] & 0xff) | 0x100, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YM2151, write value dd to register aa
                case 0x54:
                case 0xa4:
                    write_chip(ChipType.CHIP_YM2151, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YM2203, write value dd to register aa
                case 0x55:
                case 0xa5:
                    write_chip(ChipType.CHIP_YM2203, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YM2608 port 0, write value dd to register aa
                case 0x56:
                case 0xa6:
                    write_chip(ChipType.CHIP_YM2608, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YM2608 port 1, write value dd to register aa
                case 0x57:
                case 0xa7:
                    write_chip(ChipType.CHIP_YM2608, cmd >> 7, (buffer[offset] & 0xff) | 0x100, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YM2610 port 0, write value dd to register aa
                case 0x58:
                case 0xa8:
                    write_chip(ChipType.CHIP_YM2610, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YM2610 port 1, write value dd to register aa
                case 0x59:
                case 0xa9:
                    write_chip(ChipType.CHIP_YM2610, cmd >> 7, (buffer[offset] & 0xff) | 0x100, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YM3812, write value dd to register aa
                case 0x5a:
                case 0xaa:
                    write_chip(ChipType.CHIP_YM3812, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YM3526, write value dd to register aa
                case 0x5b:
                case 0xab:
                    write_chip(ChipType.CHIP_YM3526, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // Y8950, write value dd to register aa
                case 0x5c:
                case 0xac:
                    write_chip(ChipType.CHIP_Y8950, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YMF262 port 0, write value dd to register aa
                case 0x5e:
                case 0xae:
                    write_chip(ChipType.CHIP_YMF262, cmd >> 7, buffer[offset] & 0xff, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // YMF262 port 1, write value dd to register aa
                case 0x5f:
                case 0xaf:
                    write_chip(ChipType.CHIP_YMF262, cmd >> 7, (buffer[offset] & 0xff) | 0x100, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // Wait n samples, n can range from 0 to 65535 (approx 1.49 seconds)
                case 0x61:
                    delay = (buffer[offset] & 0xff) | ((buffer[offset + 1] & 0xff) << 8);
                    offset += 2;
                    break;

                // wait 735 samples (60th of a second)
                case 0x62:
                    delay = 735;
                    break;

                // wait 882 samples (50th of a second)
                case 0x63:
                    delay = 882;
                    break;

                // end of sound data
                case 0x66:
                    done = true;
                    break;

                // data block
                case 0x67: {
                    int dummy = buffer[offset++] & 0xff;
                    if (dummy != 0x66)
                        break;
                    int type = buffer[offset++] & 0xff;
                    int[] tmp = new int[] {offset};
                    int size = parse_uint32(buffer, tmp);
                    offset = tmp[0];
                    int[] localoffset = new int[] {offset};

                    switch (type) {
                        case 0x01: // RF5C68 PCM data for use with associated commands
                        case 0x02: // RF5C164 PCM data for use with associated commands
                        case 0x03: // PWM PCM data for use with associated commands
                        case 0x04: // OKIM6258 ADPCM data for use with associated commands
                        case 0x05: // HuC6280 PCM data for use with associated commands
                        case 0x06: // SCSP PCM data for use with associated commands
                        case 0x07: // NES APU DPCM data for use with associated commands
                            break;

                        case 0x00: // YM2612 PCM data for use with associated commands
                        {
                            VgmChipBase chip = find_chip(ChipType.CHIP_YM2612, 0);
                            if (chip != null)
                                chip.write_data(PCM, 0, size - 8, buffer, localoffset[0]);
                            break;
                        }

                        case 0x82: // YM2610 ADPCM ROM data
                            add_rom_data(ChipType.CHIP_YM2610, ADPCM_A, buffer, localoffset, size - 8);
                            break;

                        case 0x81: // YM2608 DELTA-T ROM data
                            add_rom_data(ChipType.CHIP_YM2608, ADPCM_B, buffer, localoffset, size - 8);
                            break;

                        case 0x83: // YM2610 DELTA-T ROM data
                            add_rom_data(ChipType.CHIP_YM2610, ADPCM_B, buffer, localoffset, size - 8);
                            break;

                        case 0x84: // YMF278B ROM data
                        case 0x87: // YMF278B RAM data
                            add_rom_data(ChipType.CHIP_YMF278B, PCM, buffer, localoffset, size - 8);
                            break;

                        case 0x88: // Y8950 DELTA-T ROM data
                            add_rom_data(ChipType.CHIP_Y8950, ADPCM_B, buffer, localoffset, size - 8);
                            break;

                        case 0x80: // Sega PCM ROM data
                        case 0x85: // YMF271 ROM data
                        case 0x86: // YMZ280B ROM data
                        case 0x89: // MultiPCM ROM data
                        case 0x8A: // uPD7759 ROM data
                        case 0x8B: // OKIM6295 ROM data
                        case 0x8C: // K054539 ROM data
                        case 0x8D: // C140 ROM data
                        case 0x8E: // K053260 ROM data
                        case 0x8F: // Q-Sound ROM data
                        case 0x90: // ES5505/ES5506 ROM data
                        case 0x91: // X1-010 ROM data
                        case 0x92: // C352 ROM data
                        case 0x93: // GA20 ROM data
                            break;

                        case 0xC0: // RF5C68 RAM write
                        case 0xC1: // RF5C164 RAM write
                        case 0xC2: // NES APU RAM write
                        case 0xE0: // SCSP RAM write
                        case 0xE1: // ES5503 RAM write
                            break;

                        default:
                            if (type >= 0x40 && type < 0x7f)
                                logger.log(Level.INFO, "Compressed data block not supported");
                            else
                                logger.log(Level.INFO, "Unknown data block type 0x%02X".formatted(type));
                            break;
                    }
                    offset += size;
                    break;
                }

                // PCM RAM write
                case 0x68:
                    logger.log(Level.INFO, "68: PCM RAM write");
                    break;

                // AY8910, write value dd to register aa
                case 0xa0:
                    write_chip(ChipType.CHIP_YM2149, (buffer[offset] >>> 7) & 0xff, buffer[offset] & 0x7f, buffer[offset + 1] & 0xff);
                    offset += 2;
                    break;

                // pp aa dd: YMF278B, port pp, write value dd to register aa
                case 0xd0:
                    write_chip(ChipType.CHIP_YMF278B, (buffer[offset] >> 7) & 0xff, ((buffer[offset] & 0x7f) << 8) | (buffer[offset + 1] & 0xff), buffer[offset + 2] & 0xff);
                    offset += 3;
                    break;

                case 0x70:
                case 0x71:
                case 0x72:
                case 0x73:
                case 0x74:
                case 0x75:
                case 0x76:
                case 0x77:
                case 0x78:
                case 0x79:
                case 0x7a:
                case 0x7b:
                case 0x7c:
                case 0x7d:
                case 0x7e:
                case 0x7f:
                    delay = (cmd & 15) + 1;
                    break;

                case 0x80:
                case 0x81:
                case 0x82:
                case 0x83:
                case 0x84:
                case 0x85:
                case 0x86:
                case 0x87:
                case 0x88:
                case 0x89:
                case 0x8a:
                case 0x8b:
                case 0x8c:
                case 0x8d:
                case 0x8e:
                case 0x8f: {
                    VgmChipBase chip = find_chip(ChipType.CHIP_YM2612, 0);
                    if (chip != null)
                        chip.write(0x2a, chip.read_pcm());
                    delay = cmd & 15;
                    break;
                }

                // ignored, consume one byte
                case 0x30:
                case 0x31:
                case 0x32:
                case 0x33:
                case 0x34:
                case 0x35:
                case 0x36:
                case 0x37:
                case 0x38:
                case 0x39:
                case 0x3a:
                case 0x3b:
                case 0x3c:
                case 0x3d:
                case 0x3e:
                case 0x3f:
                case 0x4f:    // dd: Game Gear PSG stereo, write dd to port 0x06
                case 0x50:    // dd: PSG (SN76489/SN76496) write value dd
                    offset++;
                    break;

                // ignored, consume two bytes
                case 0x40:
                case 0x41:
                case 0x42:
                case 0x43:
                case 0x44:
                case 0x45:
                case 0x46:
                case 0x47:
                case 0x48:
                case 0x49:
                case 0x4a:
                case 0x4b:
                case 0x4c:
                case 0x4d:
                case 0x4e:
                case 0x5d:    // aa dd: YMZ280B, write value dd to register aa
                case 0xb0:    // aa dd: RF5C68, write value dd to register aa
                case 0xb1:    // aa dd: RF5C164, write value dd to register aa
                case 0xb2:    // aa dd: PWM, write value ddd to register a (d is MSB, dd is LSB)
                case 0xb3:    // aa dd: GameBoy DMG, write value dd to register aa
                case 0xb4:    // aa dd: NES APU, write value dd to register aa
                case 0xb5:    // aa dd: MultiPCM, write value dd to register aa
                case 0xb6:    // aa dd: uPD7759, write value dd to register aa
                case 0xb7:    // aa dd: OKIM6258, write value dd to register aa
                case 0xb8:    // aa dd: OKIM6295, write value dd to register aa
                case 0xb9:    // aa dd: HuC6280, write value dd to register aa
                case 0xba:    // aa dd: K053260, write value dd to register aa
                case 0xbb:    // aa dd: Pokey, write value dd to register aa
                case 0xbc:    // aa dd: WonderSwan, write value dd to register aa
                case 0xbd:    // aa dd: SAA1099, write value dd to register aa
                case 0xbe:    // aa dd: ES5506, write value dd to register aa
                case 0xbf:    // aa dd: GA20, write value dd to register aa
                    offset += 2;
                    break;

                // ignored, consume three bytes
                case 0xc9:
                case 0xca:
                case 0xcb:
                case 0xcc:
                case 0xcd:
                case 0xce:
                case 0xcf:
                case 0xd7:
                case 0xd8:
                case 0xd9:
                case 0xda:
                case 0xdb:
                case 0xdc:
                case 0xdd:
                case 0xde:
                case 0xdf:
                case 0xc0:    // bbaa dd: Sega PCM, write value dd to memory offset aabb
                case 0xc1:    // bbaa dd: RF5C68, write value dd to memory offset aabb
                case 0xc2:    // bbaa dd: RF5C164, write value dd to memory offset aabb
                case 0xc3:    // cc bbaa: MultiPCM, write set bank offset aabb to channel cc
                case 0xc4:    // mmll rr: QSound, write value mmll to register rr (mm - data MSB, ll - data LSB)
                case 0xc5:    // mmll dd: SCSP, write value dd to memory offset mmll (mm - offset MSB, ll - offset LSB)
                case 0xc6:    // mmll dd: WonderSwan, write value dd to memory offset mmll (mm - offset MSB, ll - offset LSB)
                case 0xc7:    // mmll dd: VSU, write value dd to memory offset mmll (mm - offset MSB, ll - offset LSB)
                case 0xc8:    // mmll dd: X1-010, write value dd to memory offset mmll (mm - offset MSB, ll - offset LSB)
                case 0xd1:    // pp aa dd: YMF271, port pp, write value dd to register aa
                case 0xd2:    // pp aa dd: SCC1, port pp, write value dd to register aa
                case 0xd3:    // pp aa dd: K054539, write value dd to register ppaa
                case 0xd4:    // pp aa dd: C140, write value dd to register ppaa
                case 0xd5:    // pp aa dd: ES5503, write value dd to register ppaa
                case 0xd6:    // pp aa dd: ES5506, write value aadd to register pp
                    offset += 3;
                    break;

                // ignored, consume four bytes
                case 0xe0:    // dddddddd: Seek to offset dddddddd (Intel byte order) in PCM data bank of data block type 0 (YM2612).
                {
                    VgmChipBase chip = find_chip(ChipType.CHIP_YM2612, 0);
                    int[] tmp = new int[] {offset};
                    int pos = parse_uint32(buffer, tmp);
                    offset = tmp[0];
                    if (chip != null)
                        chip.seek_pcm(pos);
                    offset += 4;
                    break;
                }
                case 0xe1:    // mmll aadd: C352, write value aadd to register mmll
                case 0xe2:
                case 0xe3:
                case 0xe4:
                case 0xe5:
                case 0xe6:
                case 0xe7:
                case 0xe8:
                case 0xe9:
                case 0xea:
                case 0xeb:
                case 0xec:
                case 0xed:
                case 0xee:
                case 0xef:
                case 0xf0:
                case 0xf1:
                case 0xf2:
                case 0xf3:
                case 0xf4:
                case 0xf5:
                case 0xf6:
                case 0xf7:
                case 0xf8:
                case 0xf9:
                case 0xfa:
                case 0xfb:
                case 0xfc:
                case 0xfd:
                case 0xfe:
                case 0xff:
                    offset += 4;
                    break;
            }

            // handle delays
            while (delay-- != 0) {
                boolean more_remaining = false;
                int[] outputs = new int[2];
                for (var chip : active_chips)
                    chip.generate(output_pos, output_step, outputs);
                output_pos += output_step;
                wav_buffer.add(outputs[0]);
                wav_buffer.add(outputs[1]);
            }
        }
    }

    //-------------------------------------------------
    //  write_wav - write a WAV file from the provided
    //  stereo data
    //-------------------------------------------------

    static int write_wav(String filename, int output_rate, List<Integer> wav_buffer_src) throws IOException {
        // determine normalization parameters
        int max_scale = 0;
        for (int index = 0; index < wav_buffer_src.size(); index++) {
            int absval = Math.abs(wav_buffer_src.get(index));
            max_scale = Math.max(max_scale, absval);
        }

        // warn if only silence was detected (and also avoid divide by zero)
        if (max_scale == 0) {
            logger.log(Level.WARNING, "The WAV file data will only contain silence.");
            max_scale = 1;
        }

        // now convert
        short[] wav_buffer = new short[wav_buffer_src.size()];
        for (int index = 0; index < wav_buffer_src.size(); index++)
            wav_buffer[index] = (short) (wav_buffer_src.get(index) * 26000 / max_scale);

        // write the WAV file
        LittleEndianDataOutputStream out = new LittleEndianDataOutputStream(Files.newOutputStream(Path.of(filename)));

        // write the 'RIFF' header
        out.write("RIFF".getBytes());
//			System.err.printf("Error writing to output file%n");
//			return 7;

        // write the total size
        int total_size = 48 + wav_buffer.length * 2 - 8;
        out.writeInt(total_size);
//			System.err.printf("Error writing to output file%n");
//			return 7;

        // write the 'WAVE' type
        out.write("WAVE".getBytes());
//			System.err.printf("Error writing to output file%n");
//			return 7;

        // write the 'fmt ' tag
        out.write("fmt ".getBytes());
//			System.err.printf("Error writing to output file%n");
//			return 7;

        // write the format length
        out.writeInt(16);

        // write the format (PCM)
        out.writeShort(1);

        // write the channels
        out.writeShort(2);

        // write the sample rate
        out.writeInt(output_rate);

        // write the bytes/second
        int bps = output_rate * 2 * 2;
        out.writeInt(bps);

        // write the block align
        out.writeShort(4);

        // write the bits/sample
        out.writeShort(16);

        // write the 'data' tag
        out.write("data".getBytes());

        // write the data length
        int datalen = wav_buffer.length * 2;
        out.writeInt(datalen);

        // write the data
        for (short value : wav_buffer) out.writeShort(value);

        out.close();
        return 0;
    }

    /**
     * program entry point.
     *
     * @param argv "-o|--output" output_file "-r|--samplerate" sample-rate
     */
    public static void main(String[] argv) throws Exception {
        String filename = null;
        String outfilename = null;
        int output_rate = 44100;

        // parse command line
        boolean argerr = false;
        for (int arg = 0; arg < argv.length; arg++) {
            String curarg = argv[arg];
            if (curarg.charAt(0) == '-') {
                if (curarg.equals("-o") || curarg.equals("--output"))
                    outfilename = argv[++arg];
                else if (curarg.equals("-r") || curarg.equals("--samplerate"))
                    output_rate = Integer.parseInt(argv[++arg]);
                else {
                    logger.log(Level.WARNING, "Unknown argument: %s".formatted(curarg));
                    argerr = true;
                }
            } else
                filename = curarg;
        }

        // if invalid syntax, show usage
        if (argerr || filename == null || outfilename == null) {
            System.err.printf("Usage: vgmrender <inputfile> -o <outputfile> [-r <rate>]%n");
            return;
        }

        // attempt to read the file
        Path file = Path.of(filename);

        // get the length and create a buffer
        byte[] buffer = Archives.getInputStream(file).readAllBytes();

        // check the ID
        int offset = 0;
        if (buffer.length < 64 || buffer[0] != 'V' || buffer[1] != 'g' || buffer[2] != 'm' || buffer[3] != ' ') {
            logger.log(Level.WARNING, "File '%s' does not appear to be a valid VGM file".formatted(filename));
            return; // 4;
        }

        // parse the header, creating any chips needed
        int data_start = parse_header(buffer);

        // if no chips created, fail
        if (active_chips.isEmpty()) {
            logger.log(Level.WARNING, "No compatible chips found, exiting.");
            return; // 5;
        }

        // generate the output
        List<Integer> wav_buffer = new ArrayList<>();
        generate_all(buffer, data_start, output_rate, wav_buffer);

        int err = write_wav(outfilename, output_rate, wav_buffer);

//#if (CAPTURE_NATIVE)
//		{
//			int chipnum = 0;
//			for (var chip : active_chips)
//				if (err == 0 && chip.m_native_data.size() > 0) {
//					String filename = "native-%d.wav".formatted(chipnum++);
//					err = write_wav(filename, chip.sample_rate(), chip.m_native_data);
//				}
//		}
//#endif
//#if (RUN_NUKED_OPN2)
//		{
//			int chipnum = 0;
//			for (var chip :active_chips)
//			if (err == 0 && chip.m_nuked_data.size() > 0) {
//				String filename = "nuked-%d.wav".formatted(chipnum++);
//				err = write_wav(filename, chip.sample_rate(), chip.m_nuked_data);
//			}
//		}
//#endif

        active_chips.clear();
    }
}
