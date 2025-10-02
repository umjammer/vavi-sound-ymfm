/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import vavi.sound.sampled.ymfm.Vgm2PcmAudioInputStream.VgmRenderer;
import vavi.sound.sampled.ymfm.YmfmVgmRenderer;
import vavi.util.ByteUtil;
import vavi.util.Debug;
import vavi.util.archive.Archives;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;
import vgmrender.VgmRender;

import static vavi.sound.SoundUtil.volume;


/**
 * TestCase.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025-01-09 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class TestCase {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property(name = "vavi.test.volume")
    double volume = 0.2;

    @Property(name = "file")
    String file;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
Debug.println("volume: " + volume);
    }

    @Test
    @DisplayName("convert to wav")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test1() throws Exception {
Debug.println(file);
        VgmRender.main(new String[] {file, "-o", "tmp/out.wav"});
    }

    @Test
    @DisplayName("play by prototype")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test2() throws Exception {
Debug.println(file);
        Path path = Path.of(file);
        InputStream is = Archives.getInputStream(path);

        AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
        line.open(format);
        volume(line, volume);
        line.start();

        VgmRender renderer = new VgmRender(is);
        renderer.render((int) format.getSampleRate(), (l, r) -> {
            byte[] b = new byte[4];
            ByteUtil.writeLeShort((short) (int) l, b, 0);
            ByteUtil.writeLeShort((short) (int) r, b, 2);
            line.write(b, 0, b.length);
        });

        line.drain();
        line.close();

        renderer.close();
    }

    @Test
    @DisplayName("play by proper renderer")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test3() throws Exception {
        Debug.println(file);
        Path path = Path.of(file);
        InputStream is = Archives.getInputStream(path);

        AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
        line.open(format);
        volume(line, volume);
        line.start();

        VgmRenderer renderer = new YmfmVgmRenderer();
        renderer.start(is, (int) format.getSampleRate());

        while (true) {
            if (renderer.isRenderable()) {
                int delay = renderer.update();

                while (delay-- != 0) {
                    int[] outputs = new int[2];
                    renderer.render(outputs);

                    byte[] b = new byte[4];
                    ByteUtil.writeLeShort((short) outputs[0], b, 0);
                    ByteUtil.writeLeShort((short) outputs[1], b, 2);
                    line.write(b, 0, b.length);
                }
            } else {
                break;
            }
        }

        line.drain();
        line.close();

        renderer.close();
    }
}
