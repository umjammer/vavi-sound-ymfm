import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


@PropsEntity(url = "file:local.properties")
class MidiAnalyzerTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property(name = "midi.analize")
    String midi;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    @Test
    void test1() throws Exception {
Debug.println("midi: " + midi);

        try (InputStream fis = Files.newInputStream(Path.of(midi))) {
            byte[] header = new byte[14];
            fis.read(header);
            System.out.printf("Header: %c%c%c%c\n", header[0], header[1], header[2], header[3]);
            int format = (header[8] << 8) | (header[9] & 0xFF);
            int tracks = (header[10] << 8) | (header[11] & 0xFF);
            int division = (header[12] << 8) | (header[13] & 0xFF);
            System.out.printf("Format: %d, Tracks: %d, Division: %d\n", format, tracks, division);

            for (int t = 0; t < tracks; t++) {
                byte[] trkHeader = new byte[8];
                int read = fis.read(trkHeader);
                if (read < 8) {
                    System.out.println("Unexpected EOF at track " + t);
                    break;
                }
                int len = ((trkHeader[4] & 0xFF) << 24) | ((trkHeader[5] & 0xFF) << 16) | ((trkHeader[6] & 0xFF) << 8) | (trkHeader[7] & 0xFF);
                System.out.printf("Track %d: %c%c%c%c, length: %d\n", t, trkHeader[0], trkHeader[1], trkHeader[2], trkHeader[3], len);

                // Skip length
                long skipped = fis.skip(len);
                if (skipped != len) {
                    System.out.println("Failed to skip entire track");
                    break;
                }
            }
        }
    }
}
