# YAMAHA Audio Chips

Main FM sound chips

- OPL series
  - OPLL ... MSX (MSX-MUSIC)
  - OPL2 ... PC/AT (Sound Blaster)
  - OPL3 ... PC/AT (Sound Blaster Pro2)
- OPN series
  - OPN ... PC-88/98 (26 sound sources), FM-77, MZ-2500 and many others
  - OPNA ... PC-88/98 (86 sound sources),
  - OPN2 ... FM TOWNS, Mega Drive
  - OPN3 ... PC-9821
- OPM series
  - OPM ... X1, X68000, Arcade board (Fantasy Zone, Genpei Touma Den, Salamander, Street Fighter II and many more)

## OPL series

Used in MSX and Sound Blaster.

| Name      | Model    | Ops | FM ch                          | Waveform       | Output   | Others             | Usage                              |
|-----------|----------|-----|--------------------------------|----------------|----------|--------------------|------------------------------------|
| OPL       | YM3526   | 2   | 9                              | 7+R4</br> 6+R5 | 1        | Monaural           | Arcade games                       |
| MSX-AUDIO | Y8950    | 2   | ditto                          | 1              | Monaural | ADPCM 1ch          | MSX expansion cartridge            |
| OPL2      | YM3812   | 2   | ditto                          | 4              | Monaural | Sound Blaster      |                                    |
| OPLL      | YM2413   | 2   | 9</br>6+R5                     | 2              | Monaural | Built-in Tones     | MSX expansion cartridge(MSX-MUSIC) |
| OPL3      | YMF262-M | 2/4 | 18(2op)</br>6(4op)+6(2op) etc. | 8              | 4ch      | Sound Blaster Pro2 |                                    |
| OPL4      | YMF278   | 2/4 | ditto                          | ditto          | ditto    | PCM 24ch           | YAMAHA SOUND EDGE                  |

## OPN series

Used in many domestically produced PCs (PC-88, FM-77, MZ-2500, PC-98, FM TOWNS).

| Name | Model  | Ops | FM ch | Waveform | Output   | Others                         | Usage                                     |
|------|--------|-----|-------|----------|----------|--------------------------------|-------------------------------------------|
| OPN  | YM2203 | 4   | 3     | 1        | Monaural | PSG(SSG)3ch Noise 1ch          | Many Japanese PCs (*)                     |
| OPNA | YM2608 | 4   | 6+R6  | 1        | Stereo   | PSG(SSG)6ch ADPCM1ch Noise 1ch | Late model of PC-88/98 series/sound board |
| OPNB | YM2610 | 4   | 4     | 1        | Stereo   | PSG(SSG)3ch ADPCM7ch Noise 1ch | Neo Geo                                   |
| OPN2 | YM2612 | 4   | 6     | 1        | Stereo   |                                | FM TOWNS, Mega Drive                      |
| OPN3 | YMF288 | 4   | 6+R6  | 1        | Stereo   | PSG(SSG)3ch Noise 1ch          | PC-9821                                   |

(*) PC-8800 series, FM-77 series, MZ-2500 series, PC-9800 series, etc.

## OPM series

Used in X1 and X68000.

| Name | Model  | Ops | FM ch | Waveform | Output | Others | Usage                                            |
|------|--------|-----|-------|----------|--------|--------|--------------------------------------------------|
| OPM  | YM2151 | 4   | 8     | 1        | Stereo |        | X1, X68000 Arcade games from the 80s and 90s (*) |                           
| OPP  | YM2164 | 4   | 8     | 1        | Stereo |        | Synthesizers such as DX21/DX27/DX100             |     
| OPZ  | YM2414 | 4   | 8     | 8        | Stereo |        | Synthesizers such as DX11                        |                            

(*) Fantasy Zone, Genpei Touma Den, Salamander, Street Fighter II and many more

## Others

| Name  | Model    | Ops   | FM ch                  | Waveform | Output | Others  | Usage                                            |
|-------|----------|-------|------------------------|----------|--------|---------|--------------------------------------------------|
| OPX   | YMF271-F | 2/3/4 | 9(All 4op)~18(All 2op) | 7+PCM    | 4ch    | PCM12ch | Arcade games                                     |
| OPS   | YM2128   | 6     | 16                     | 1        | Stereo |         | Synthesizers such as DX7/DX1/DX5/TX216/TX816 (*) |
| OPSII | YM2604   | 6     | 16                     | 1        | Stereo |         | Synthesizers such as DX7IID/DX7IIFD/TX802 (*)    |                                       

(*) The OPS series is not sold externally, and the internal specifications are unknown.

## Mobile Audio(MA) series

Developed as a ringtone for mobile phones.

| Name              | Model  | Ops   | FM ch             | Waveform | Output   | Others                                                            | Usage  |
|-------------------|--------|-------|-------------------|----------|----------|-------------------------------------------------------------------|--------|
| MA-1              | YMU757 | 2     | 4                 | 2        | Stereo   |                                                                   |        |
| MA-2              | YMU759 | 2/4   | 16(2op)   8(4op)  | 8        | Stereo   | 4bitADPCM 1ch                                                     |        |
| PA-1              | YMF761 | ditto | ditto             | ditto    | ditto    | ditto                                                             | PalmOS |
| MA-3              | YMU762 | 2/4   | 32(2op)   16(4op) | 29       | Stereo   | WaveTable 8ch                                                     |        |
| MA-5              | YMU765 | 2/4   | 32(2op)   16(4op) | 29       | Stereo   | WaveTable 32ch                                                    |        |
| MA-7(AudioEngine) | YMU786 | -     | -                 | -        | Stereo   | WaveTable 128ch                                                   |        |
| SD-1              | YMF825 | 4     | 16                | 29       | Monaural | For home appliances in the Chinese market</br>For electronic kits |        |

---

<sub>[sitation](https://lipoyang.hatenablog.com/entry/2024/05/03/134526)</sub>