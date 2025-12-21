/*
 * BSD 3-Clause License
 *
 * Copyright (c) 2021-2024, Devin Acker
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package vavi.sound.midi.ymfm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import vavi.sound.midi.ymfm.OplPatch.PatchVoice;
import vavi.sound.ymfm.Opl.Ymf262;
import vavi.sound.ymfm.YmFm;


/**
 * @see "https://github.com/devinacker/ymfmidi"
 */
public class OplPlayer extends YmFm.Interface {

    static class MIDIChannel {

        public int num = 0;

        public boolean percussion = false;
        public int bank = 0;
        public int patchNum = 0;
        public int volume = 127;
        public int pan = 64;
        /** pitch wheel position */
        public double basePitch = 0.0;
        /** frequency multiplier */
        public double pitch = 1.0;

        public int rpn = 0x3fff;

        public int bendRange = 2;
    }

    static class OPLVoice {

        public int chip = 0;
        public MIDIChannel channel = null;
        public OplPatch patch = null;
        public PatchVoice patchVoice = null;

        public int num = 0;
        /** base operator number, set based on voice num. */
        public int op = 0;
        public boolean fourOpPrimary = false;
        public OPLVoice fourOpOther = null;

        public boolean on = false;
        /** true after note on/off, false after generating at least 1 sample */
        public boolean justChanged = false;
        public int note = 0;
        public int velocity = 0;

        /** block and F number, calculated from note and channel pitch */
        public int freq = 0;

        /** how long has this note been playing (incremented each midi update) */
        public long duration = 0xffff_ffffL;
    }

    public enum MIDIType {
        GeneralMIDI,
        RolandGS,
        YamahaXG,
        GeneralMIDI2
    }

    public enum ChipType {
        ChipOPL,
        ChipOPL2,
        ChipOPL3
    }

    private static final int masterClock = 14318181;

    private static final int REG_TEST = 0x01;

    private static final int REG_OP_MODE = 0x20;
    private static final int REG_OP_LEVEL = 0x40;
    private static final int REG_OP_AD = 0x60;
    private static final int REG_OP_SR = 0x80;
    private static final int REG_VOICE_FREQL = 0xA0;
    private static final int REG_VOICE_FREQH = 0xB0;
    private static final int REG_VOICE_CNT = 0xC0;
    private static final int REG_OP_WAVEFORM = 0xE0;

    private static final int REG_4OP = 0x104;
    private static final int REG_NEW = 0x105;

    private static final int[] voice_num = {
            0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8,
            0x100, 0x101, 0x102, 0x103, 0x104, 0x105, 0x106, 0x107, 0x108
    };

    private static final int[] oper_num = {
            0x0, 0x1, 0x2, 0x8, 0x9, 0xA, 0x10, 0x11, 0x12,
            0x100, 0x101, 0x102, 0x108, 0x109, 0x10A, 0x110, 0x111, 0x112
    };

    private final List<Ymf262> m_opl3;
    private final int m_numChips;
    private final ChipType m_chipType;

    private boolean m_stereo;
    /** output sample rate (default 44.1k) */
    private int m_sampleRate;
    private double m_sampleGain;
    /** ratio of OPL sample rate to output sample rate (usually < 1.0) */
    private double m_sampleStep;
    /** number of pending output samples (when >= 1.0, output one) */
    private double m_samplePos;
    /** remaining samples until next midi event */
    private int m_samplesLeft;
    /** output sample data */
    private final YmFm.Output m_output = new YmFm.Output(2);
    /** if we need to clock one of the OPLs between register writes, save the resulting sample */
    private final List<Queue<YmFm.Output[]>> m_sampleFIFO;

    /** last output for downsampling */
    private final int[] m_lastOut = {0, 0};
    /** recursive highpass filter to remove/reduce DC offset */
    private double m_hpFilterFreq;
    private double m_hpFilterCoef;
    private final int[] m_hpLastIn = {0, 0};
    private final int[] m_hpLastOut = {0, 0};
    private final float[] m_hpLastInF = {0.0f, 0.0f};
    private final float[] m_hpLastOutF = {0.0f, 0.0f};

    private final boolean m_looping;
    private boolean m_timePassed;

    private final MIDIChannel[] m_channels = new MIDIChannel[16];
    private final List<OPLVoice> m_voices;
    private MIDIType m_midiType;

    private final OplSequence m_sequence;
    private Map<Integer, OplPatch> m_patches = new HashMap<>();

    /** */
    public OplPlayer(int numChips, ChipType type) {
        m_chipType = type;
        if (type == ChipType.ChipOPL3) {
            m_numChips = numChips;
            m_voices = new ArrayList<>(numChips * 18);
            for (int i = 0; i < numChips * 18; i++) m_voices.add(new OPLVoice());
            m_stereo = true;
        } else {
            // simulate two OPL2 on one OPL3, etc
            m_numChips = (numChips + 1) / 2;
            m_voices = new ArrayList<>(numChips * 9);
            for (int i = 0; i < numChips * 9; i++) m_voices.add(new OPLVoice());
            m_stereo = false;
        }

        m_opl3 = new ArrayList<>(m_numChips);
        for (int i = 0; i < m_numChips; i++) {
            m_opl3.add(new Ymf262(this));
        }
        m_sampleFIFO = new ArrayList<>(m_numChips);
        for (int i = 0; i < m_numChips; i++) {
            m_sampleFIFO.add(new ConcurrentLinkedQueue<>());
        }

        m_sequence = null;

        m_samplePos = 0.0;
        m_samplesLeft = 0;
        m_hpFilterFreq = 5.0; // 5Hz default to reduce DC offset
        setSampleRate(44100); // setup both sample step and filter coefficients
        setGain(1.0);

        m_looping = false;

        reset();

        tmp1 = new YmFm.Output[m_opl3.getFirst().getOutputs()];
        for (int i = 0; i < tmp1.length; i++) tmp1[i] = m_opl3.getFirst().outputFactory();

        tmp2 = new YmFm.Output[m_opl3.getFirst().getOutputs()];
        for (int i = 0; i < tmp2.length; i++) tmp2[i] = m_opl3.getFirst().outputFactory();
    }

    private final YmFm.Output[] tmp1;
    private final YmFm.Output[] tmp2;

    /** */
    public void setSampleRate(int rate) {
        int rateOPL = m_opl3.getFirst().sample_rate(masterClock);
        m_sampleStep = (double) rate / rateOPL;
        m_sampleRate = rate;

        setFilter(m_hpFilterFreq);
//logger.log(Level.DEBUG, "OPL sample rate = %u / output sample rate = %u / step %02f".formatted(rateOPL, rate, m_sampleStep);
    }

    /** */
    public void setGain(double gain) {
        m_sampleGain = gain;
    }

    /** */
    public void setFilter(double cutoff) {
        m_hpFilterFreq = cutoff;

        if (m_hpFilterFreq <= 0.0) {
            m_hpFilterCoef = 1.0;
        } else {
            m_hpFilterCoef = 1.0 / ((2 * Math.PI * cutoff) / m_sampleRate + 1);
        }
//logger.log(Level.DEBUG, "sample rate = %u / cutoff %f Hz / filter coef %f".formatted(m_sampleRate, cutoff, m_hpFilterCoef);
    }

    /**
     * enable/disable OPL3 stereo support. can be called during active playback
     * (note: the output of OPLPlayer::generate is a stereo stream regardless of this setting)
     */
    public void setStereo(boolean on) {
        if (m_chipType == ChipType.ChipOPL3) {
            m_stereo = on;
            updateChannelVoices((byte) -1, this::updatePanning);
        }
    }

    /**
     * set instrument patches
     */
    public void setPatches(Map<Integer, OplPatch> patches) {
        this.m_patches = patches;
    }

    /**
     * render the audio output during playback.
     * note: regardless of sound settings, output stream is always stereo (two floats or int16s per sample)
     */
    public void generate(float[] data, int numSamples) {
        int samp = 0;

        while (samp < numSamples * 2) {
            updateMIDI();

            float[] samples = new float[2];
            samples[0] = (float) m_output.data[0] / 32767.0f;
            samples[1] = (float) m_output.data[1] / 32767.0f;

            while (m_samplePos >= 1.0 && samp < numSamples * 2) {
                data[samp] = samples[0];
                data[samp + 1] = samples[1];

                if (m_hpFilterCoef < 1.0) {
                    for (int i = 0; i < 2; i++) {
                        float lastIn = m_hpLastInF[i];
                        m_hpLastInF[i] = data[samp + i];

                        m_hpLastOutF[i] = (float) (m_hpFilterCoef * (m_hpLastOutF[i] + data[samp + i] - lastIn));
                        data[samp + i] = m_hpLastOutF[i];
                    }
                }

                samp += 2;
                m_samplePos -= 1.0;
                if (m_samplesLeft > 0)
                    m_samplesLeft--;
            }
        }
    }

    /**
     * render the audio output during playback.
     * note: regardless of sound settings, output stream is always stereo (two floats or int16s per sample)
     */
    public void generate(short[] data, int numSamples) {
        int samp = 0;

        while (samp < numSamples * 2) {
            updateMIDI();

            while (m_samplePos >= 1.0 && samp < numSamples * 2) {
                if (m_hpFilterCoef < 1.0) {
                    for (int i = 0; i < 2; i++) {
                        int lastIn = m_hpLastIn[i];
                        m_hpLastIn[i] = m_output.data[i];

                        m_hpLastOut[i] = (int) (m_hpFilterCoef * (m_hpLastOut[i] + m_output.data[i] - lastIn));
                        m_output.data[i] = m_hpLastOut[i];
                    }
                }

                data[samp] = (short) YmFm.clamp(m_output.data[0], -32768, 32767);
                data[samp + 1] = (short) YmFm.clamp(m_output.data[1], -32768, 32767);

                samp += 2;
                m_samplePos -= 1.0;
                if (m_samplesLeft > 0)
                    m_samplesLeft--;
            }
        }
    }

    /** */
    private void updateMIDI() {
        // https://gemini.google.com/app/27978d225cbdb41b
        if (m_sequence != null) {
            while (m_samplesLeft <= 0 && !atEnd()) {
                // time to update midi playback
                m_samplesLeft = (int) m_sequence.update(this);
                for (OPLVoice voice : m_voices) {
                    if (voice.duration < 0xffff_ffffL)
                        voice.duration++;
                    voice.justChanged = false;
                }

                if (m_samplesLeft > 0)
                    m_timePassed = true;
            }
        } else {
            for (OPLVoice voice : m_voices) {
                if (voice.duration < 0xffff_ffffL)
                    voice.duration++;
                voice.justChanged = false; // Reset flag so voice can be reused later
            }
        }

        if (m_samplePos >= 1.0) {
            return; // existing output still waiting to be consumed
        }

        m_output.data[0] = m_lastOut[0];
        m_output.data[1] = m_lastOut[1];

        while (m_samplePos < 1.0) {
            YmFm.Output[] output;
            int[] samples = {0, 0};

            for (int i = 0; i < m_numChips; i++) {
                if (m_sampleFIFO.get(i).isEmpty()) {
                    m_opl3.get(i).generate(tmp1, 1);
                    output = tmp1;
                } else {
                    output = m_sampleFIFO.get(i).poll();
                }

                samples[0] += output[0].data[0];
                samples[1] += output[0].data[1];
            }

            m_samplePos += m_sampleStep;

            if (m_samplePos <= 1.0 || m_sampleStep > 1.0) {
                // full input sample (if downsampling), or always (if upsampling)
                m_output.data[0] += samples[0];
                m_output.data[1] += samples[1];
                m_lastOut[0] = m_lastOut[1] = 0;
            } else {
                // partial input sample (if downsampling):
                // apply a fraction of the sample value now and save the rest for later
                // based on how far past the output sample point we are
                double remainder = (m_samplePos - (int) m_samplePos) / m_sampleStep;
                m_output.data[0] += (int) (samples[0] * (1.0 - remainder));
                m_output.data[1] += (int) (samples[1] * (1.0 - remainder));
                m_lastOut[0] = (int) (samples[0] * remainder);
                m_lastOut[1] = (int) (samples[1] * remainder);
            }
        }

        // apply gain and use sample rate in/out ratio to scale all accumulated samples
        double step = Math.min(m_sampleStep, 1.0);
        m_output.data[0] = (int) (m_output.data[0] * m_sampleGain * step);
        m_output.data[1] = (int) (m_output.data[1] * m_sampleGain * step);
    }

    /** */
    public void displayClear() {
        for (int i = 0; i < 18; i++)
            System.out.printf("%79s\n", "");
    }

    /** */
    public void displayChannels() {
        int[] numVoices = new int[16];
        int totalVoices = 0;
        for (OPLVoice voice : m_voices) {
            if (voice.channel != null && (voice.on || voice.justChanged)) {
                numVoices[voice.channel.num]++;
                totalVoices++;
            }
        }

        System.out.printf("Chn | Patch Name                       | Vol | Pan | Active Voices: %d/%-6d\n", totalVoices, m_voices.size());
        System.out.println("----+----------------------------------+-----+-----+---------------------------");
        for (int i = 0; i < 16; i++) {
            MIDIChannel channel = m_channels[i];
            OplPatch patch = findPatch(i, 0); // Assuming 0 for note when channel is not percussion

            System.out.printf("%3d | %-32.32s | %3d | %3d | ", i + 1,
                    channel.percussion ? "Percussion" : (patch != null ? patch.name : ""),
                    channel.volume, channel.pan);

            if (m_voices.size() < 100) {
                System.out.printf("%2d ", numVoices[i]);
                for (int j = 0; j < 23; j++)
                    System.out.printf("%c", j < numVoices[i] ? '*' : ' ');
            } else {
                System.out.printf("%3d ", numVoices[i]);
                for (int j = 0; j < 22; j++)
                    System.out.printf("%c", j < numVoices[i] ? '*' : ' ');
            }
            System.out.println();
        }
    }

    /** */
    public void displayVoices() {
        int numRows = Math.min(18, m_voices.size());
        for (int i = 0; i < numRows; i++) {
            if (m_voices.size() <= 18) {
                System.out.printf("voice %2d: ", i + 1);
                if (m_voices.get(i).channel != null) {
                    System.out.printf("channel %2d, note %3d %c %-32.32s",
                            m_voices.get(i).channel.num + 1, m_voices.get(i).note,
                            m_voices.get(i).on ? '*' : ' ',
                            m_voices.get(i).patch != null ? m_voices.get(i).patch.name : "");
                } else {
                    System.out.printf("%69s", "");
                }
            } else if (m_voices.size() <= 18 * 2) {
                for (int j = i; j < m_voices.size(); j += 18) {
                    System.out.printf("voice %2d: ", j + 1);
                    if (m_voices.get(j).channel != null) {
                        System.out.printf("channel %2d, note %3d %c",
                                m_voices.get(j).channel.num + 1, m_voices.get(j).note,
                                m_voices.get(j).on ? '*' : ' ');
                    } else {
                        System.out.printf("%22s", "");
                    }

                    if (j < 18)
                        System.out.print("        | ");
                }
            } else if (m_voices.size() <= 18 * 4) {
                for (int j = i; j < m_voices.size(); j += 18) {
                    System.out.printf("%2d: ", j + 1);
                    if (m_voices.get(j).channel != null) {
                        System.out.printf("channel %2d %c",
                                m_voices.get(j).channel.num + 1,
                                m_voices.get(j).on ? '*' : ' ');
                    } else {
                        System.out.printf("%12s", "");
                    }

                    if (j < m_voices.size() - 18)
                        System.out.print(" | ");
                }
            } else if (m_voices.size() <= 18 * 8) {
                for (int j = i; j < m_voices.size(); j += 18) {
                    System.out.printf("%3d: %c ", j + 1, m_voices.get(j).on ? '*' : ' ');

                    if (j < m_voices.size() - 18)
                        System.out.print(" | ");
                }
            }

            System.out.println();
        }
    }

    /**
     * reached end of song?
     */
    public boolean atEnd() {
        // rewind song at end only if looping is enabled
        // AND if the song played for at least one sample,
        // otherwise just leave it at the end
        if (m_looping && m_timePassed)
            return false;
        if (m_sequence != null)
            return m_sequence.atEnd();
        return true;
    }

    /**
     * song selection (for files with multiple songs)
     */
    public void setSongNum(int num) {
        if (m_sequence != null)
            m_sequence.setSongNum(num);
        reset();
    }

    /** */
    public int numSongs() {
        if (m_sequence != null)
            return m_sequence.numSongs();
        return 0;
    }

    /** */
    public int songNum() {
        if (m_sequence != null)
            return m_sequence.songNum();
        return 0;
    }

    /**
     * reset OPL and midi file
     */
    public void reset() {
        for (int i = 0; i < m_opl3.size(); i++) {
            m_opl3.get(i).reset();
            // enable OPL3 stuff
            write(i, REG_NEW, (byte) 1);
        }

        // reset MIDI channel and OPL voice status
        m_midiType = MIDIType.GeneralMIDI;
        for (int i = 0; i < 16; i++) {
            m_channels[i] = new MIDIChannel();
            m_channels[i].num = i;
        }
        m_channels[9].percussion = true;

        for (int i = 0; i < m_voices.size(); i++) {
            OPLVoice voice = m_voices.get(i);
            voice.chip = i / 18;
            voice.num = voice_num[i % 18];
            voice.op = oper_num[i % 18];
            voice.on = false;
            voice.justChanged = false;
            voice.note = 0;
            voice.velocity = 0;
            voice.channel = null;
            voice.patch = null;
            voice.patchVoice = null;
            voice.duration = 0xffff_ffffL;

            if (m_chipType != ChipType.ChipOPL3) continue;
            switch (i % 9) {
                case 0:
                case 1:
                case 2:
                    voice.fourOpPrimary = true;
                    voice.fourOpOther = m_voices.get(i + 3);
                    break;
                case 3:
                case 4:
                case 5:
                    voice.fourOpPrimary = false;
                    voice.fourOpOther = m_voices.get(i - 3);
                    break;
                default:
                    voice.fourOpPrimary = false;
                    voice.fourOpOther = null;
                    break;
            }
        }

        if (m_sequence != null)
            m_sequence.reset();
        m_samplesLeft = 0;
        m_timePassed = false;
    }

    /** */
    private void runSamples(int chip, int count) {
        // add some delay between register writes where needed
        // (i.e. when forcing a voice off, changing 4op flags, etc.)
        while (count-- > 0) {
            m_opl3.get(chip).generate(tmp2, 1);
            m_sampleFIFO.get(chip).add(tmp2);
        }
    }

    /** */
    private void write(int chip, int addr, byte data) {
        if (addr < 0x100)
            m_opl3.get(chip).write_address(addr);
        else
            m_opl3.get(chip).write_address_hi(addr & 0xff);
        m_opl3.get(chip).write_data(data & 0xff);
    }

    /** */
    private OPLVoice findVoice(int channel, OplPatch patch, int note) {
        OPLVoice found = null;
        long duration = 0;

        // try to find the "oldest" voice, prioritizing released notes
        // (or voices that haven't ever been used yet)
        for (OPLVoice voice : m_voices) {
            if (useFourOp(patch) && !voice.fourOpPrimary)
                continue;

            if (voice.channel == null)
                return voice;

            if (!voice.on && !voice.justChanged) {
                if (voice.channel.num == channel && voice.note == note &&
                        voice.duration < 0xffff_ffffL) {
                    // found an old voice that was using the same note and patch
                    // don't immediately use it, but make it a high priority candidate for later
                    // (to help avoid pop/click artifacts when retriggering a recently off note)
                    silenceVoice(voice);
                    if (useFourOp(voice.patch) && voice.fourOpOther != null)
                        silenceVoice(voice.fourOpOther);
                } else if (voice.duration > duration) {
                    found = voice;
                    duration = voice.duration;
                }
            }
        }

        if (found != null) return found;
        // if we didn't find one yet, just try to find an old one
        // using the same patch, even if it should still be playing.
        for (OPLVoice voice : m_voices) {
            if (useFourOp(patch) && !voice.fourOpPrimary)
                continue;

            if (voice.patch == patch && voice.duration > duration) {
                found = voice;
                duration = voice.duration;
            }
        }

        if (found != null) return found;
        // last resort - just find any old voice at all

        for (OPLVoice voice : m_voices) {
            if (useFourOp(patch) && !voice.fourOpPrimary)
                continue;
            // don't let a 2op instrument steal an active voice from a 4op one
            if (!useFourOp(patch) && voice.on && useFourOp(voice.patch))
                continue;

            if (voice.justChanged)
                continue;

            if (voice.duration > duration) {
                found = voice;
                duration = voice.duration;
            }
        }

        return found;
    }

    /** */
    private OPLVoice findVoice(int channel, int note, boolean justChanged /* = false */) {
        channel &= 15;
        for (OPLVoice voice : m_voices) {
            if (voice.on &&
                    voice.justChanged == justChanged &&
                    voice.channel == m_channels[channel] &&
                    voice.note == note) {
                return voice;
            }
        }

        return null;
    }

    /** */
    private OplPatch findPatch(int channel, int note) {
        int key;
        OplPlayer.MIDIChannel ch = m_channels[channel & 15];

        if (ch.percussion)
            key = 0x80 | (note & 0x7f) | (ch.patchNum << 8);
        else
            key = ch.patchNum | (ch.bank << 8);

        // if this patch+bank combo doesn't exist, default to bank 0
        if (!m_patches.containsKey(key))
            key &= 0x00ff;
        // if patch still doesn't exist in bank 0, use patch 0 (or drum note 0)
        if (!m_patches.containsKey(key))
            key &= 0x0080;
        // if that somehow still doesn't exist, forget it
        if (!m_patches.containsKey(key))
            return null;

        return m_patches.get(key);
    }

    /** */
    private boolean useFourOp(OplPatch patch) {
        if (m_chipType == ChipType.ChipOPL3)
            return patch.fourOp;
        return false;
    }

    /** */
    private boolean[] activeCarriers(OPLVoice voice) {
        boolean[] scale = {false, false};
        PatchVoice patchVoice = voice.patchVoice;

        if (patchVoice == null) {
            scale[0] = scale[1] = false;
        } else if (!useFourOp(voice.patch)) {
            // 2op FM (0): scale op 2 only
            // 2op AM (1): scale op 1 and 2
            scale[0] = (patchVoice.conn & 1) == 1;
            scale[1] = true;
        } else if (voice.fourOpPrimary) {
            // 4op FM+FM (0, 0): don't scale op 1 or 2
            // 4op AM+FM (1, 0): scale op 1 only
            // 4op FM+AM (0, 1): scale op 2 only
            // 4op AM+AM (1, 1): scale op 1 only
            scale[0] = (voice.patch.voice[0].conn & 1) == 1;
            scale[1] = (voice.patch.voice[1].conn & 1) == 1 && !scale[0];
        } else {
            // 4op FM+FM (0, 0): scale op 4 only
            // 4op AM+FM (1, 0): scale op 4 only
            // 4op FM+AM (0, 1): scale op 4 only
            // 4op AM+AM (1, 1): scale op 3 and 4
            scale[0] = (voice.patch.voice[0].conn & 1) == 1 &&
                       (voice.patch.voice[1].conn & 1) == 1;
            scale[1] = true;
        }

        return scale;
    }

    /** */
    private interface VoiceUpdateFunction {

        void apply(OPLVoice voice);
    }

    private void updateChannelVoices(int channel, VoiceUpdateFunction func) {
        for (OPLVoice voice : m_voices) {
            if ((channel < 0) || (voice.channel == m_channels[channel & 15]))
                func.apply(voice);
        }
    }

    /** */
    private void updatePatch(OPLVoice voice, OplPatch newPatch, int numVoice) {
        // assign the MIDI channel's current patch (or the current drum patch) to this voice

        PatchVoice patchVoice = newPatch.voice[numVoice];

        if (voice.patchVoice != patchVoice) {
            boolean oldFourOp = voice.patch != null ? useFourOp(voice.patch) : false;

            voice.patch = newPatch;
            voice.patchVoice = patchVoice;

            // update enable status for 4op channels on this chip
            if (useFourOp(newPatch) != oldFourOp) {
                // if going from part of a 4op patch to a 2op one, kill the other one
                OPLVoice other = voice.fourOpOther;
                if (other != null && other.patch != null &&
                        useFourOp(other.patch) && !useFourOp(newPatch)) {
                    silenceVoice(other);
                }

                byte enable = 0x00;
                byte bit = 0x01;
                for (int i = voice.chip * 18; i < voice.chip * 18 + 18; i++) {
                    if (m_voices.get(i).fourOpPrimary) {
                        if (m_voices.get(i).patch != null && useFourOp(m_voices.get(i).patch))
                            enable |= bit;
                        bit <<= 1;
                    }
                }

                write(voice.chip, REG_4OP, enable);
                //runSamples(voice.chip, 1);
            }

            // kill an existing voice, then send the chip far enough forward in time to let the envelope die off
            // (ROTT: fixes nasty reverse cymbal noises in spray.mid
            //        without disrupting note timing too much for the staccato drums in fanfare2.mid)
            silenceVoice(voice);
            runSamples(voice.chip, 48);

            // 0x20: vibrato, sustain, multiplier
            write(voice.chip, REG_OP_MODE + voice.op, patchVoice.op_mode[0]);
            write(voice.chip, REG_OP_MODE + voice.op + 3, patchVoice.op_mode[1]);
            // 0x60: attack/decay
            write(voice.chip, REG_OP_AD + voice.op, patchVoice.op_ad[0]);
            write(voice.chip, REG_OP_AD + voice.op + 3, patchVoice.op_ad[1]);
            // 0xe0: waveform
            if (m_chipType == ChipType.ChipOPL2) {
                write(voice.chip, REG_OP_WAVEFORM + voice.op, (byte) (patchVoice.op_wave[0] & 3));
                write(voice.chip, REG_OP_WAVEFORM + voice.op + 3, (byte) (patchVoice.op_wave[1] & 3));
            } else if (m_chipType == ChipType.ChipOPL3) {
                write(voice.chip, REG_OP_WAVEFORM + voice.op, patchVoice.op_wave[0]);
                write(voice.chip, REG_OP_WAVEFORM + voice.op + 3, patchVoice.op_wave[1]);
            }
        }

        // 0x80: sustain/release
        // update even for the same patch in case silenceVoice was called from somewhere else on this voice
        write(voice.chip, REG_OP_SR + voice.op, patchVoice.op_sr[0]);
        write(voice.chip, REG_OP_SR + voice.op + 3, patchVoice.op_sr[1]);
    }

    // lookup table shamelessly stolen from Nuke.YKT
    private static final byte[] opl_volume_map = {
            80, 63, 40, 36, 32, 28, 23, 21,
            19, 17, 15, 14, 13, 12, 11, 10,
            9, 8, 7, 6, 5, 5, 4, 4,
            3, 3, 2, 2, 1, 1, 0, 0
    };

    /** */
    private void updateVolume(OPLVoice voice) {
        if (voice.patch == null || voice.channel == null) return;

        int atten = opl_volume_map[(voice.velocity * voice.channel.volume) >> 9] & 0xff;
        int level;

        PatchVoice patchVoice = voice.patchVoice;
        boolean[] scale = activeCarriers(voice);

        // 0x40: key scale / volume
        if (scale[0])
            level = Math.min(0x3f, (patchVoice.op_level[0] & 0xff) + atten);
        else
            level = patchVoice.op_level[0] & 0xff;
        write(voice.chip, REG_OP_LEVEL + voice.op, (byte) (level | (patchVoice.op_ksr[0] & 0xc0)));

        if (scale[1])
            level = Math.min(0x3f, (patchVoice.op_level[1] & 0xff) + atten);
        else
            level = patchVoice.op_level[1] & 0xff;
        write(voice.chip, REG_OP_LEVEL + voice.op + 3, (byte) (level | (patchVoice.op_ksr[1] & 0xc0)));
    }

    /** */
    private void updatePanning(OPLVoice voice) {
        if (voice.patch == null || voice.channel == null) return;

        // 0xc0: output/feedback/mode
        int pan = 0x30;
        if (m_stereo) {
            if (voice.channel.pan < 32)
                pan = 0x10;
            else if (voice.channel.pan >= 96)
                pan = 0x20;
        }

        write(voice.chip, REG_VOICE_CNT + voice.num, (byte) ((voice.patchVoice.conn & 0xff) | pan));
    }

    private static final short[] noteFreq = {
            // calculated from A440
            345, 365, 387, 410, 435, 460, 488, 517, 547, 580, 615, 651
    };

    /** */
    private void updateFrequency(OPLVoice voice) {
        if (voice.patch == null || voice.channel == null) return;
        if (useFourOp(voice.patch) && !voice.fourOpPrimary) return;

        int note = (!voice.channel.percussion ? voice.note : (voice.patch.fixedNote & 0xff)) +
                voice.patchVoice.tune;

        int octave = note / 12;
        note %= 12;

        // calculate base frequency (and apply pitch bend / patch detune)
        int freq = (note >= 0) ? noteFreq[note] : (noteFreq[note + 12] >> 1);
        if (octave < 0)
            freq >>>= -octave;
        else if (octave > 0)
            freq <<= octave;

        freq *= (int) (voice.channel.pitch * voice.patchVoice.finetune);

        // convert the calculated frequency back to a block and F-number
        octave = 0;
        while (freq > 0x3ff) {
            freq >>>= 1;
            octave++;
        }
        octave = Math.min(7, octave);
        voice.freq = (freq | (octave << 10)) & 0xffff;

        write(voice.chip, REG_VOICE_FREQL + voice.num, (byte) (voice.freq & 0xff));
        write(voice.chip, REG_VOICE_FREQH + voice.num, (byte) ((voice.freq >>> 8) | (voice.on ? (1 << 5) : 0)));
    }

    /** */
    private void silenceVoice(OPLVoice voice) {
        voice.on = false;
        voice.justChanged = true;
        voice.duration = 0xffff_ffffL;

        write(voice.chip, REG_OP_SR + voice.op, (byte) 0xff);
        write(voice.chip, REG_OP_SR + voice.op + 3, (byte) 0xff);
        write(voice.chip, REG_VOICE_FREQH + voice.num, (byte) (voice.freq >>> 8));
    }

    /**
     * MIDI events, called by the file format handler
     */
    public void midiEvent(int status, int data0, int data1 /* = 0 */) {
        int channel = status & 15;
        double pitch;

//if (CC++ < 300) { System.out.printf("%03d: c: %d, st: %02x, D0: %02x, D1: %02x%n", CC, channel, status & 0xf0, data0, data1); }
        switch (status >>> 4) {
            case 8: // note off (ignore velocity)
                midiNoteOff(channel, data0);
                break;

            case 9: // note on
                midiNoteOn(channel, data0, data1);
                break;

            case 10: // polyphonic pressure (ignored)
                break;

            case 11: // controller change
                midiControlChange(channel, data0, data1);
                break;

            case 12: // program change
                midiProgramChange(channel, data0);
                break;

            case 13: // channel pressure (ignored)
                break;

            case 14: // pitch bend
                pitch = (short) (((data0 & 0xff) | ((data1 & 0xff) << 7)) - 8192);
                midiPitchControl(channel, pitch / 8192.0);
                break;
        }
    }

    /** */
    public void midiNoteOn(int channel, int note, int velocity) {
        note &= 0x7f;
        velocity &= 0x7f;

        // if we just now turned this same note on, don't do it again
        if (findVoice(channel, note, true) != null)
            return;

        if (velocity == 0) {
            midiNoteOff(channel, note);
            return;
        }

//logger.log(Level.DEBUG, "midiNoteOn: chn %u, note %u".formatted(channel, note);
        OplPatch newPatch = findPatch(channel, note);
        if (newPatch == null) return;

        int numVoices = ((useFourOp(newPatch) || newPatch.dualTwoOp) ? 2 : 1);

        OPLVoice voice = null;
        for (int i = 0; i < numVoices; i++) {
            if (voice != null && useFourOp(newPatch) && voice.fourOpOther != null)
                voice = voice.fourOpOther;
            else
                voice = findVoice(channel, newPatch, note);
            if (voice == null) continue; // ??

            updatePatch(voice, newPatch, i);

            // update the note parameters for this voice
            voice.channel = m_channels[channel & 15];
            voice.on = voice.justChanged = true;
            voice.note = note;
            voice.velocity = YmFm.clamp((byte) velocity + newPatch.velocity, 0, 127);
            voice.duration = 0;

            updateVolume(voice);
            updatePanning(voice);

            // for 4op instruments, don't key on until we've written both voices...
            if (!useFourOp(newPatch)) {
                updateFrequency(voice);
            } else if (i > 0) {
                updateFrequency(voice.fourOpOther);
            }
        }
    }

    /** */
    public void midiNoteOff(int channel, int note) {
        note &= 0x7f;

//logger.log(Level.DEBUG, "midiNoteOff: chn %u, note %u".formatted(channel, note);
        OPLVoice voice;
        while ((voice = findVoice(channel, note, false)) != null) {
            voice.justChanged = voice.on;
            voice.on = false;

            write(voice.chip, REG_VOICE_FREQH + voice.num, (byte) (voice.freq >>> 8));
        }
    }

    /**
     * @param pitch range is -1.0 to 1.0
     */
    public void midiPitchControl(int channel, double pitch) {
//logger.log(Level.DEBUG, "midiPitchControl: chn %u, val %.02f".formatted(channel, pitch);
        MIDIChannel ch = m_channels[channel & 15];

        ch.basePitch = pitch;
        ch.pitch = midiCalcBend(pitch * ch.bendRange);
        updateChannelVoices(channel, this::updateFrequency);
    }

    /** */
    public void midiProgramChange(int channel, int patchNum) {
        m_channels[channel & 15].patchNum = patchNum & 0x7f;
        // patch change will take effect on the next note for this channel
    }

    /** */
    public void midiControlChange(int channel, int control, int value) {
        channel &= 15;
        control &= 0x7f;
        value &= 0x7f;

        MIDIChannel ch = m_channels[channel];

//logger.log(Level.DEBUG, "midiControlChange: chn %u, ctrl %u, val %u".formatted(channel, control, value);
        switch (control) {
            case 0:
                if (m_midiType == MIDIType.RolandGS)
                    ch.bank = value & 0xFF;
                else if (m_midiType == MIDIType.YamahaXG)
                    ch.percussion = (value == 0x7f);
                break;

            case 6:
                if (ch.rpn == 0) {
                    ch.bendRange = value;
                    midiPitchControl(channel, ch.basePitch);
                }
                break;

            case 7:
                ch.volume = value;
                updateChannelVoices(channel, this::updateVolume);
                break;

            case 10:
                ch.pan = value;
                if (m_stereo)
                    updateChannelVoices(channel, this::updatePanning);
                break;

            case 32:
                if (m_midiType == MIDIType.YamahaXG || m_midiType == MIDIType.GeneralMIDI2)
                    ch.bank = value;
                break;

            case 98:
            case 99:
                ch.rpn = 0x3fff;
                break;

            case 100:
                ch.rpn &= 0x3f80;
                ch.rpn |= value;
                break;

            case 101:
                ch.rpn &= 0x7f;
                ch.rpn |= (value << 7);
                break;
        }
    }

    /**
     * sysex data (data and length *don't* include the opening 0xF0)
     */
    public void midiSysEx(byte[] data, int length) {
        int offset = 0; // data
        if (length > 0 && data[0] == (byte) 0xF0) {
            offset = 1;
            length--;
        }

        if (length == 0)
            return;

        if (data[offset] == 0x7e) { // universal non-realtime
            if (length == 5 && data[offset + 1] == 0x7f && data[offset + 2] == 0x09) {
                if (data[offset + 3] == 0x01)
                    m_midiType = MIDIType.GeneralMIDI;
                else if (data[offset + 3] == 0x03)
                    m_midiType = MIDIType.GeneralMIDI2;
            }
        } else if (data[offset] == 0x41 && length >= 10 && // Roland
                data[offset + 2] == 0x42 && data[offset + 3] == 0x12) {
            // if we received one of these, assume GS mode
            // (some MIDIs seem to e.g. send drum map messages without a GS reset)
            m_midiType = MIDIType.RolandGS;

            int address = ((data[offset + 4] & 0xFF) << 16) | ((data[offset + 5] & 0xFF) << 8) | (data[offset + 6] & 0xFF);
            // for single part parameters, map "part number" to channel number
            // (using the default mapping)
            int channel = (address & 0xf00) >> 8;
            if (channel == 0)
                channel = 9;
            else if (channel <= 9)
                channel--;

            // Roland GS part parameters
            if ((address & 0xfff0ff) == 0x401015) // set drum map
                m_channels[channel].percussion = (data[offset + 7] != 0x00);
        } else if (length >= 8 && data.length - offset > 8) {
            byte[] yamahaSig = {(byte) 0x43, (byte) 0x10, (byte) 0x4c, (byte) 0x00, (byte) 0x00, (byte) 0x7e, (byte) 0x00, (byte) 0xf7};
            if (Arrays.compare(data, offset, offset + 8, yamahaSig, 0, yamahaSig.length) == 0) {
                m_midiType = MIDIType.YamahaXG;
            }
        }
    }

    /**
     * helper for pitch bend and finetune
     */
    public static double midiCalcBend(double semitones) {
        return Math.pow(2, semitones / 12.0);
    }

    // misc. informational stuff

    /** */
    public int sampleRate() {
        return m_sampleRate;
    }

    public OplPlayer.ChipType chipType() {
        return m_chipType;
    }

    public boolean stereo() {
        return m_stereo;
    }
}
