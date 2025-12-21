# vavi.sound.midi.ymfm

### status

| name | ext       | type      | status | description                                  |
|------|-----------|-----------|:------:|----------------------------------------------|
| mid  | .mid      | reader    |        | Standard MIDI files                          |
| hmi  | .hmi      | reader    |   ✅️   | HMI Sound Operating System                   |
| hmp  | .hmp      | reader    |   ✅️   | HMI Sound Operating System                   |
| mus  | .mus      | reader    |   ✅️   | DMX sound system / Doom engine               |
| xmi  | .xmi      | reader    |   ✅️   | Miles Sound System / Audio Interface Library |
|      |           |           |        |                                              |
| ail  | .ad, .opl | soundfont |        | Miles Sound System / Audio Interface Library |
| op2  | .op2      | soundfont |   ✅️   | DMX sound system / Doom engine               |
| tmb  | .tmb      | soundfont |   ✅️   | Apogee Sound System                          |
| wopl | .wopl     | soundfont |   ✅️   | Wohlstand OPL3 editor                        |

## References

 * https://github.com/devinacker/ymfmidi
 * sequences
   * https://www.vgmpf.com/Wiki/index.php?title=HMI (hmi)
   * https://www.vgmpf.com/Wiki/index.php?title=HMP (hmp)
   * https://doomwiki.org/wiki/MUS (mus)
   * https://www.vgmpf.com/Wiki/index.php?title=XMI (xmi)
 * soundfonts
   * https://github.com/sneakernets/DMXOPL (op2,wopl)
   * https://github.com/Wohlstand/OPL3BankEditor (wopl,sbi,op2,tmb,SPECS)
   * https://git.1bpm.net/libADLMIDI/tree/fm_banks/tmb_files?id=2b76bdb9b96dcbaaa5fb80e33a264298178e8f10 (tmb)
   * https://github.com/Wohlstand/ADLMIDI (op2,tmb)
 * 

## TODO

 * sequence x4
 * sequencer x4
 * soundfont x4
 * synthesizer <- soundfont
 * ail sample
