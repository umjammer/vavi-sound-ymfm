/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymfm;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.midi.Instrument;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiDeviceReceiver;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Patch;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Transmitter;
import javax.sound.midi.VoiceStatus;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import vavi.sound.midi.ymfm.OplPlayer.ChipType;
import vavi.sound.midi.ymfm.YmfmSoundbank.YmfmInstrument;
import vavi.util.ByteUtil;
import vavi.util.StringUtil;

import static java.lang.System.getLogger;
import static vavi.sound.SoundUtil.volume;


/**
 * YmfmSynthesizer.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/11/07 umjammer initial version <br>
 */
public class YmfmSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(YmfmSynthesizer.class.getName());

    static {
        try {
            try (InputStream is = YmfmSynthesizer.class.getResourceAsStream("/META-INF/maven/vavi/vavi-sound-ymfm/pom.properties")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    version = props.getProperty("version", "undefined in pom.properties");
                } else {
                    version = System.getProperty("vavi.test.version", "undefined");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String version;

    /** the device information */
    protected static final Info info =
        new Info("YmFm OPL3 MIDI Synthesizer",
                            "vavi",
                            "YmFm Software synthesizer for OPL3",
                            "Version " + version) {};

    private long timestamp;

    private boolean isOpen;

    private final AudioFormat audioFormat = new AudioFormat(44100, 16, 2, true, false);

    private SourceDataLine line;

    private OplPlayer player;

    private Soundbank soundbank;

    // ----

    @Override
    public Info getDeviceInfo() {
        return info;
    }

    @Override
    public void open() throws MidiUnavailableException {
        if (isOpen()) {
logger.log(Level.WARNING, "already open: " + hashCode());
            return;
        }

        // soundbank
        try {
            // TODO fixed
            soundbank = new WoplSoundbankReader().getSoundbank(YmfmSynthesizer.class.getResourceAsStream("/soundbank/genmidi.wopl"));
        } catch (IOException | InvalidMidiDataException e) {
            throw (MidiUnavailableException) new MidiUnavailableException().initCause(e);
        }

        // player
        player = new OplPlayer(1, ChipType.ChipOPL3);
        player.setPatches(((YmfmSoundbank) soundbank).patches);
        player.setSampleRate((int) audioFormat.getSampleRate());
        player.setGain(1.0);
        player.setFilter(5.0);
        player.setStereo(true);

        //
        isOpen = true;

        init();
        executor.submit(this::play);
    }

    /** when midi spi */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** when midi spi */
    private void init() throws MidiUnavailableException {
        try {
            DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, audioFormat, AudioSystem.NOT_SPECIFIED);
            line = (SourceDataLine) AudioSystem.getLine(lineInfo);
logger.log(Level.DEBUG, line.getClass().getName());
            line.addLineListener(event -> logger.log(Level.DEBUG, "Line: " + event.getType()));

            // Get the line buffer size for reference
            int lineBufferSize = line.getBufferSize();
            line.open(audioFormat, lineBufferSize * 2);
            line.start();
        } catch (LineUnavailableException e) {
            throw (MidiUnavailableException) new MidiUnavailableException().initCause(e);
        }
    }

    private long start;
    private static final int audioBufferTimeMs = 100; // 100ms audio buffer
    private final int bufferSizeInBytes = (int) (audioFormat.getSampleRate() * audioFormat.getChannels() * 2 * audioBufferTimeMs / 1000.0);
    private final short[] buf = new short[audioFormat.getChannels() * bufferSizeInBytes];

    /**
     * when midi spi
     *
     * @see "https://claude.ai/chat/f24ee70d-b639-49dd-99ac-437eff189ce5"
     */
    private void play() {
try {
        timestamp = System.currentTimeMillis();
        start = timestamp;

        // Use a significantly larger buffer for ultra-smooth playback
        byte[] lineBuffer = new byte[bufferSizeInBytes];

        // Pre-fill the buffer to avoid startup issues
        int initialSamples = bufferSizeInBytes / (audioFormat.getChannels() * 2);
        player.generate(buf, initialSamples);
        int lineBufferPos = 0;
        for (int i = 0; i < initialSamples; i++) {
            for (int c = 0; c < audioFormat.getChannels(); c++) {
                ByteUtil.writeLeShort(buf[c * audioFormat.getChannels() + i], lineBuffer, lineBufferPos + (c * 2));
            }
            lineBufferPos += audioFormat.getChannels() * 2;
        }
        line.write(lineBuffer, 0, lineBufferPos);

        // Critical: Precise sample counting to maintain consistent timing
        float sampleRate = audioFormat.getSampleRate();
        double samplesPerMs = sampleRate / 1000.0;

        // Track total samples for perfect timing
        long totalSamplesGenerated = initialSamples;
        long startTimeNanos = System.nanoTime();

        // For consistency, use a fixed chunk size
        final int fixedChunkSizeMs = 5; // Smaller chunks for more consistent timing
        int fixedSamplesToGenerate = (int) (sampleRate * fixedChunkSizeMs / 1000.0);

logger.log(Level.TRACE, "isOpen: " + isOpen);
        while (isOpen) {
            try {
                long cycleStartTime = System.nanoTime();

                // Check exactly how many samples should have been generated by now based on elapsed time
                long elapsedTimeNanos = cycleStartTime - startTimeNanos;
                double elapsedTimeMs = elapsedTimeNanos / 1_000_000.0;
                long expectedSampleCount = (long) (elapsedTimeMs * samplesPerMs);

                // Calculate how many samples we need to generate to catch up precisely
                long sampleDeficit = expectedSampleCount - totalSamplesGenerated;

                // Maintain a minimum safe sample generation size
                int samplesToGenerate = (int) Math.max(fixedSamplesToGenerate, sampleDeficit);

                // Cap to reasonable size to prevent flooding the buffer
                samplesToGenerate = Math.min(samplesToGenerate, fixedSamplesToGenerate * 3);

                // Generate audio data
                player.generate(buf, samplesToGenerate);

                // Process samples
                lineBufferPos = 0;
                for (int i = 0; i < samplesToGenerate; i++) {
                    for (int c = 0; c < audioFormat.getChannels(); c++) {
                        ByteUtil.writeLeShort(buf[c * audioFormat.getChannels() + i], lineBuffer, lineBufferPos + (c * 2));
                    }
                    lineBufferPos += audioFormat.getChannels() * 2;
                }

                // Write all samples at once
                if (lineBufferPos > 0) {
                    line.write(lineBuffer, 0, lineBufferPos);
                }

                // Update our sample count - critical for maintaining steady playback rate
                totalSamplesGenerated += samplesToGenerate;

                // Calculate the theoretically perfect time for the next cycle
                // This is key to eliminating wow and flutter - we base timing on sample count, not real time
                long idealNextCycleTimeNanos = startTimeNanos + (long) ((totalSamplesGenerated / samplesPerMs) * 1_000_000);
                long timeToNextCycleNanos = idealNextCycleTimeNanos - System.nanoTime();

                // Sleep until the next ideal cycle time, but never go negative
                if (timeToNextCycleNanos > 0) {
                    if (timeToNextCycleNanos > 2_000_000) { // If more than 2ms
                        Thread.sleep(timeToNextCycleNanos / 1_000_000);
                        // Fine-grained waiting for the remainder
                        long refinedWaitUntil = idealNextCycleTimeNanos - 500_000; // Wake 0.5ms early
                        while (System.nanoTime() < refinedWaitUntil) {
                            Thread.yield(); // Less aggressive than pure busy-wait
                        }
                    }

                    // Final precise busy-wait
                    while (System.nanoTime() < idealNextCycleTimeNanos) {
                        // Pure busy-wait for final microsecond precision
                    }
                } else if (timeToNextCycleNanos < -10_000_000) { // If we're more than 10ms behind
                    // We're falling behind - adjust our timing reference point to avoid perpetual catch-up
                    // This prevents buffer starvation while maintaining rate stability
                    long adjustmentNanos = timeToNextCycleNanos + 5_000_000; // Recover more gradually (keep 5ms of the deficit)
                    startTimeNanos -= adjustmentNanos;
                    logger.log(Level.TRACE, "Timing adjusted to prevent starvation: " + (adjustmentNanos / 1_000_000) + "ms");
                }

                // Update timestamp for compatibility with existing code
                timestamp = System.currentTimeMillis();

            } catch (Exception e) {
                logger.log(Level.INFO, "Audio processing error: " + e.getMessage(), e);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
logger.log(Level.TRACE, "out loop");
} catch (Exception e) {
 logger.log(Level.ERROR, e.getMessage(), e);
}
    }

    @Override
    @SuppressWarnings("ForLoopReplaceableByForEach")
    public void close() {
        isOpen = false;
        for (int i = 0; i < receivers.size(); i++) receivers.get(i).close();
        line.drain();
        line.close();
        executor.shutdown();
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public long getMicrosecondPosition() {
        return (timestamp - start) / 10;
    }

    @Override
    public int getMaxReceivers() {
        return -1;
    }

    @Override
    public int getMaxTransmitters() {
        return 0;
    }

    @Override
    public Receiver getReceiver() throws MidiUnavailableException {
        return new Opl3Receiver();
    }

    @Override
    public List<Receiver> getReceivers() {
        return receivers;
    }

    @Override
    public Transmitter getTransmitter() throws MidiUnavailableException {
        throw new MidiUnavailableException("No transmitter available");
    }

    @Override
    public List<Transmitter> getTransmitters() {
        return Collections.emptyList();
    }

    @Override
    public int getMaxPolyphony() {
        return 18; // TODO OPL3 class said
    }

    @Override
    public long getLatency() {
        return 33;
    }

    @Override
    public MidiChannel[] getChannels() {
        return null;
    }

    @Override
    public VoiceStatus[] getVoiceStatus() {
        return null;
    }

    @Override
    public boolean isSoundbankSupported(Soundbank soundbank) {
        return soundbank instanceof YmfmInstrument;
    }

    @Override
    public boolean loadInstrument(Instrument instrument) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void unloadInstrument(Instrument instrument) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public boolean remapInstrument(Instrument from, Instrument to) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public Soundbank getDefaultSoundbank() {
        return soundbank;
    }

    @Override
    public Instrument[] getAvailableInstruments() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public Instrument[] getLoadedInstruments() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public boolean loadAllInstruments(Soundbank soundbank) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void unloadAllInstruments(Soundbank soundbank) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public boolean loadInstruments(Soundbank soundbank, Patch[] patchList) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void unloadInstruments(Soundbank soundbank, Patch[] patchList) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    private final List<Receiver> receivers = new ArrayList<>();

    private class Opl3Receiver implements MidiDeviceReceiver {

        private boolean isOpen;

        public Opl3Receiver() {
            receivers.add(this);
            isOpen = true;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (!isOpen) throw new IllegalStateException("Receiver is not open");

            switch (message) {
                case ShortMessage shortMessage -> {
                    int data1 = shortMessage.getData1();
                    int data2 = shortMessage.getData2();
                    player.midiEvent(shortMessage.getStatus(), data1, data2);
                }
                case SysexMessage sysexMessage -> {
                    byte[] data = sysexMessage.getData();
logger.log(Level.DEBUG, "sysex: %02X\n%s".formatted(sysexMessage.getStatus(), StringUtil.getDump(data, 32)));
                    switch (data[0]) {
                        case 0x7f -> { // Universal Realtime
                            int c = data[1]; // 0x7f: Disregards channel
                            // Sub-ID, Sub-ID2
                            if (data[2] == 0x04 && data[3] == 0x01) { // Device Control / Master Volume
                                float gain = ((data[4] & 0x7f) | ((data[5] & 0x7f) << 7)) / 16383f;
logger.log(Level.DEBUG, "sysex volume: gain: %3.0f".formatted(gain * 127));
                                volume(line, gain);
                            }
                        }
                    }
                    player.midiSysEx(data, sysexMessage.getLength());
                }
                default -> {}
            }
        }

        @Override
        public void close() {
            isOpen = false;
            receivers.remove(this);
        }

        @Override
        public MidiDevice getMidiDevice() {
            return YmfmSynthesizer.this;
        }
    }
}
