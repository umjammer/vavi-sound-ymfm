package buildall;

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


//
// Simple program that touches all the existing cores to help ensure
// that everything builds cleanly.
//
class BuildAll {

	//-------------------------------------------------
	//  main - program entry point
	//-------------------------------------------------
//	template<typename ChipType>
	static class ChipWrapper<ChipType extends YmFm.Chip> extends YmFm.Interface {

		public ChipWrapper(Class<ChipType> c) throws Exception {
			m_chip = c.getDeclaredConstructor(YmFm.Interface.class).newInstance(this);

			// reset
			m_chip.reset();

			// save/restore
//			OutputStream os = Files.newOutputStream(Path.of("tmp", "store"));
//			m_chip.save(os);
//			InputStream is = Files.newInputStream(Path.of("tmp", "store"));
//			m_chip.restore(is);

			// dummy read/write
			m_chip.read(0);
			m_chip.write(0, 0);

			// generate
			YmFm.Output output = m_chip.outputFactory();
			m_chip.generate(output, output.data.length);
		}

		private ChipType m_chip;
	}

	//-------------------------------------------------
	//  main - program entry point
	//-------------------------------------------------
	public static void main(String[] args) throws Exception {
		// just keep adding chip variants here as they are implemented

		// ymfm_misc.h:
		ChipWrapper<Ym2149> test2149 = new ChipWrapper(Ym2149.class);

		// ymfm_opl.h:
		ChipWrapper<Ym3526> test3526 = new ChipWrapper(Ym3526.class);
		ChipWrapper<Y8950> test8950 = new ChipWrapper(Y8950.class);
		ChipWrapper<Ym3812> test3812 = new ChipWrapper(Ym3812.class);
		ChipWrapper<Ymf262> test262 = new ChipWrapper(Ymf262.class);
		ChipWrapper<Ymf289b> test289b = new ChipWrapper(Ymf289b.class);
		ChipWrapper<Ymf278b> test278b = new ChipWrapper(Ymf278b.class);
		ChipWrapper<Ym2413> test2413 = new ChipWrapper(Ym2413.class);
		ChipWrapper<Ym2423> test2423 = new ChipWrapper(Ym2423.class);
		ChipWrapper<Ymf281> test281 = new ChipWrapper(Ymf281.class);
		ChipWrapper<Ds1001> test1001 = new ChipWrapper(Ds1001.class);

		// ymfm_opm.h:
		ChipWrapper<Ym2151> test2151 = new ChipWrapper(Ym2151.class);
		ChipWrapper<Ym2164> test2164 = new ChipWrapper(Ym2164.class);

		// ymfm_opn.h:
		ChipWrapper<Ym2203> test2203 = new ChipWrapper(Ym2203.class);
		ChipWrapper<Ym2608> test2608 = new ChipWrapper(Ym2608.class);
		ChipWrapper<Ymf288> test288 = new ChipWrapper(Ymf288.class);
		ChipWrapper<Ym2610> test2610 = new ChipWrapper(Ym2610.class);
		ChipWrapper<Ym2610b> test2610b = new ChipWrapper(Ym2610b.class);
		ChipWrapper<Ym2612> test2612 = new ChipWrapper(Ym2612.class);
		ChipWrapper<Ym3438> test3438 = new ChipWrapper(Ym3438.class);
		ChipWrapper<Ymf276> test276 = new ChipWrapper(Ymf276.class);

		// ymfm_opq.h:
		ChipWrapper<Ym3806> test3806 = new ChipWrapper(Ym3806.class);
		ChipWrapper<Ym3533> test3533 = new ChipWrapper(Ym3533.class);

		// ymfm_opz.h:
		ChipWrapper<Ym2414> test2414 = new ChipWrapper(Ym2414.class);

		System.out.println("Done");
	}
}
