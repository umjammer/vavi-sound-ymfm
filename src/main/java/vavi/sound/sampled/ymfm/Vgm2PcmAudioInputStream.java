/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.ymfm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import vavi.io.OutputEngine;
import vavi.io.OutputEngineInputStream;
import vavi.util.ByteUtil;
import vavi.util.archive.Archives;


/**
 * Vgm2PcmAudioInputStream.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025/09/09 nsano initial version <br>
 */
public class Vgm2PcmAudioInputStream extends AudioInputStream {

    /** use format's properties */
    public Vgm2PcmAudioInputStream(InputStream stream, AudioFormat format, long length) throws IOException {
        this(stream, format, length, format.properties());
    }

    /** format's properties are ignored */
    public Vgm2PcmAudioInputStream(InputStream stream, AudioFormat format, long length, Map<String, Object> props) throws IOException {
        super(new OutputEngineInputStream(new VgmOutputEngine(stream, format, props)), format, length);
    }

    public interface VgmRenderer {

        void start(InputStream is, int sampleRate) throws IOException;

        boolean isRenderable();

        int update();

        void render(int[] outputs);

        void close();
    }

    /** */
    private static class VgmOutputEngine implements OutputEngine {

        private OutputStream out;

        private final VgmRenderer renderer;

        public VgmOutputEngine(InputStream in, AudioFormat format, Map<String, Object> props) throws IOException {
            InputStream is = Archives.getInputStream(in);
            this.renderer = new YmfmVgmRenderer(); // TODO DI
            renderer.start(is, (int) format.getSampleRate());
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
            if (renderer.isRenderable()) {
                int delay = renderer.update();

                while (delay-- != 0) {
                    int[] outputs = new int[2];
                    renderer.render(outputs);

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
            renderer.close();
        }
    }
}
