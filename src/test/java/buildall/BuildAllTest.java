package buildall;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import vavi.sound.ymfm.Misc.Ym2149;
import vavi.sound.ymfm.Opl.Ds1001;
import vavi.sound.ymfm.Opl.Y8950;
import vavi.sound.ymfm.Opl.Ym2413;
import vavi.sound.ymfm.Opl.Ym2423;
import vavi.sound.ymfm.Opl.Ym3526;
import vavi.sound.ymfm.Opl.Ym3812;
import vavi.sound.ymfm.Opl.Ymf262;
import vavi.sound.ymfm.Opl.Ymf278b;
import vavi.sound.ymfm.Opl.Ymf281;
import vavi.sound.ymfm.Opl.Ymf289b;
import vavi.sound.ymfm.Opm.Ym2151;
import vavi.sound.ymfm.Opm.Ym2164;
import vavi.sound.ymfm.Opn.Ym2203;
import vavi.sound.ymfm.Opn.Ym2608;
import vavi.sound.ymfm.Opn.Ym2610;
import vavi.sound.ymfm.Opn.Ym2610b;
import vavi.sound.ymfm.Opn.Ym2612;
import vavi.sound.ymfm.Opn.Ym3438;
import vavi.sound.ymfm.Opn.Ymf276;
import vavi.sound.ymfm.Opn.Ymf288;
import vavi.sound.ymfm.Opq.Ym3533;
import vavi.sound.ymfm.Opq.Ym3806;
import vavi.sound.ymfm.Opz.Ym2414;
import vavi.sound.ymfm.YmFm;
import vavi.util.Debug;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.params.provider.Arguments.arguments;


//
// Simple program that touches all the existing cores to help ensure
// that everything builds cleanly.
//
class BuildAllTest {

    static Path work = Path.of("tmp");

    @BeforeEach
    void setup() throws Exception {
        if (!Files.exists(work)) Files.createDirectory(work);
        if (Files.exists(work.resolve("store"))) Files.delete(work.resolve("store"));
    }

    // just keep adding chip variants here as they are implemented
    static Stream<Arguments> classes() {
        return Stream.of(
                // ymfm_misc.h:
                arguments(Ym2149.class),

                // ymfm_opl.h:
                arguments(Ym3526.class),
                arguments(Y8950.class),
                arguments(Ym3812.class),
                arguments(Ymf262.class),
                arguments(Ymf289b.class),
                arguments(Ymf278b.class),
                arguments(Ym2413.class),
                arguments(Ym2423.class),
                arguments(Ymf281.class),
                arguments(Ds1001.class),

                // ymfm_opm.h:
                arguments(Ym2151.class),
                arguments(Ym2164.class),

                // ymfm_opn.h:
                arguments(Ym2203.class),
                arguments(Ym2608.class),
                arguments(Ymf288.class),
                arguments(Ym2610.class),
                arguments(Ym2610b.class),
                arguments(Ym2612.class),
                arguments(Ym3438.class),
                arguments(Ymf276.class),

                // ymfm_opq.h:
                arguments(Ym3806.class),
                arguments(Ym3533.class),

                // ymfm_opz.h:
                arguments(Ym2414.class)
        );
    }

    @ParameterizedTest
    @MethodSource("classes")
    <T extends YmFm.Chip> void test1(Class<T> c) throws Exception {
Debug.println(c.getSimpleName() + " --------------------------------------");

        T m_chip = c.getDeclaredConstructor(YmFm.Interface.class).newInstance(new YmFm.Interface() {});

        // reset
        m_chip.reset();

        // save/restore
        OutputStream os = Files.newOutputStream(work.resolve("store"));
        m_chip.save(os);
Debug.println(c.getSimpleName() + " >>>>>>>>> " + Files.size(work.resolve("store")));
        InputStream is = Files.newInputStream(work.resolve("store"));
        m_chip.restore(is);

        // dummy read/write
        m_chip.read(0);
        m_chip.write(0, 0);

        // generate
        YmFm.Output output = m_chip.outputFactory();
Debug.println("output.data.length: " + output.data.length);
        YmFm.Output[] outputs = new YmFm.Output[output.data.length];
        outputs[0] = output;
        for (int i = 1; i < outputs.length; i++) {
            outputs[i] = m_chip.outputFactory();
        }
        m_chip.generate(outputs , output.data.length);
    }
}
