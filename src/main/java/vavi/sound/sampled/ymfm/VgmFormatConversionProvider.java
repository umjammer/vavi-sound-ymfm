/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.ymfm;

import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.spi.FormatConversionProvider;

import static javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED;
import static javax.sound.sampled.AudioSystem.NOT_SPECIFIED;


/**
 * VgmFormatConversionProvider.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 250909 nsano initial version <br>
 */
public class VgmFormatConversionProvider extends FormatConversionProvider {

    @Override
    public Encoding[] getSourceEncodings() {
        return new Encoding[] { VgmEncoding.VGM};
    }

    @Override
    public Encoding[] getTargetEncodings() {
        return new Encoding[] { PCM_SIGNED };
    }

    @Override
    public Encoding[] getTargetEncodings(AudioFormat sourceFormat) {
        if (sourceFormat.getEncoding() instanceof VgmEncoding) {
            return new Encoding[] { PCM_SIGNED };
        } else {
            return new Encoding[0];
        }
    }

    @Override
    public AudioFormat[] getTargetFormats(Encoding targetEncoding, AudioFormat sourceFormat) {
        if (sourceFormat.getEncoding() instanceof VgmEncoding && targetEncoding.equals(PCM_SIGNED)) {
            return new AudioFormat[] {
                new AudioFormat(sourceFormat.getSampleRate(),
                                16,             // sample size in bits
                                sourceFormat.getChannels(),
                                true,                  // signed
                                false)                        // little endian (for PCM wav)
            };
        } else {
            return new AudioFormat[0];
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(Encoding targetEncoding, AudioInputStream sourceStream) {
        try {
            if (isConversionSupported(targetEncoding, sourceStream.getFormat())) {
                AudioFormat[] formats = getTargetFormats(targetEncoding, sourceStream.getFormat());
                if (formats != null && formats.length > 0) {
                    AudioFormat sourceFormat = sourceStream.getFormat();
                    AudioFormat targetFormat = formats[0];
                    if (sourceFormat.equals(targetFormat)) {
                        return sourceStream;
                    } else if (sourceFormat.getEncoding() instanceof VgmEncoding && targetFormat.getEncoding().equals(PCM_SIGNED)) {
                        return new Vgm2PcmAudioInputStream(sourceStream, targetFormat, NOT_SPECIFIED, targetFormat.properties());
                    } else if (sourceFormat.getEncoding().equals(PCM_SIGNED) && targetFormat.getEncoding() instanceof VgmEncoding) {
                        throw new IllegalArgumentException("unable to convert " + sourceFormat + " to " + targetFormat);
                    } else {
                        throw new IllegalArgumentException("unable to convert " + sourceFormat + " to " + targetFormat.toString());
                    }
                } else {
                    throw new IllegalArgumentException("target format not found");
                }
            } else {
                throw new IllegalArgumentException("conversion not supported");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(AudioFormat targetFormat, AudioInputStream sourceStream) {
        try {
            if (isConversionSupported(targetFormat, sourceStream.getFormat())) {
                AudioFormat[] formats = getTargetFormats(targetFormat.getEncoding(), sourceStream.getFormat());
                if (formats != null && formats.length > 0) {
                    AudioFormat sourceFormat = sourceStream.getFormat();
                    if (sourceFormat.equals(targetFormat)) {
                        return sourceStream;
                    } else if (sourceFormat.getEncoding() instanceof VgmEncoding &&
                               targetFormat.getEncoding().equals(PCM_SIGNED)) {
                        return new Vgm2PcmAudioInputStream(sourceStream, targetFormat, NOT_SPECIFIED, targetFormat.properties());
                    } else if (sourceFormat.getEncoding().equals(PCM_SIGNED) && targetFormat.getEncoding() instanceof VgmEncoding) {
                        throw new IllegalArgumentException("unable to convert " + sourceFormat + " to " + targetFormat);
                    } else {
                        throw new IllegalArgumentException("unable to convert " + sourceFormat + " to " + targetFormat);
                    }
                } else {
                    throw new IllegalArgumentException("target format not found");
                }
            } else {
                throw new IllegalArgumentException("conversion not supported");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
