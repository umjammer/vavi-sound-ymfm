// BSD 3-Clause License
//
// Copyright (c) 2021, Aaron Giles
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this
//    list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// 3. Neither the name of the copyright holder nor the names of its
//    contributors may be used to endorse or promote products derived from
//    this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package vavi.sound.ymfm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;


public abstract class Misc {

    private Misc() {}

    //*********************************************************
    // SSG IMPLEMENTATION CLASSES
    //*********************************************************

    // ======================> ym2149

    //*********************************************************
    // YM2149
    //*********************************************************
    // ym2149 is just an SSG with no FM part, but we expose FM-like parts so that it
    // integrates smoothly with everything else; they just don't do anything
    @Serdes
    public static class Ym2149 implements YmFm.Chip {

        private static final int OUTPUTS = Ssg.Engine.OUTPUTS;
        public static final int SSG_OUTPUTS = Ssg.Engine.OUTPUTS;

        //using output_data = ymfm_output<OUTPUTS>;
        @Override
        public YmFm.Output outputFactory() {
            return new YmFm.Output(OUTPUTS);
        }

        @Override
        public final int getOutputs(){
            return OUTPUTS;
        }

        /**
         * ym2149 - constructor
         */
        public Ym2149(YmFm.Interface intf) {
            m_address = 0;
            m_ssg = new Ssg.Engine(intf);
        }

        // configuration
        void ssg_override(Ssg.Override intf) {
            m_ssg.override(intf);
        }

        /**
         * reset - reset the system
         */
        @Override
        public void reset() {
            // reset the engines
            m_ssg.reset();
        }

        /**
         * save_restore - save or restore the data
         */
        @Override
        public void save(OutputStream os) throws IOException {
            Serdes.Util.serialize(this, os);
            m_ssg.save(os);
        }

        /**
         * save_restore - save or restore the data
         */
        @Override
        public void restore(InputStream is) throws IOException {
            Serdes.Util.deserialize(is, this);
            m_ssg.restore(is);
        }

        // pass-through helpers
        @Override
        public final int sample_rate(int input_clock) {
            return input_clock / Ssg.Engine.CLOCK_DIVIDER / 8;
        }

        /**
         * read_data - read the data register
         */
        int read_data() {
            return m_ssg.read(m_address & 0x0f);
        }

        /**
         * read - handle a read from the device
         */
        @Override
        public int read(int offset) {
            int result = (byte) 0xff;
            switch (offset & 3) { // BC2,BC1
                case 0: // inactive
                    break;
                case 1: // address
                    break;
                case 2: // inactive
                    break;
                case 3: // read
                    result = read_data();
                    break;
            }
            return result;
        }

        /**
         * write_address - handle a write to the address
         * register
         */
        void write_address(int data) {
            // just set the address
            m_address = data;
        }

        /**
         * write - handle a write to the register
         * interface
         */
        void write_data(int data) {
            m_ssg.write(m_address & 0x0f, data);
        }

        /**
         * write - handle a write to the register
         * interface
         */
        @Override
        public void write(int offset, int data) {
            switch (offset & 3) { // BC2,BC1
                case 0: // address
                    write_address(data);
                    break;
                case 1: // inactive
                    break;
                case 2: // write
                    write_data(data);
                    break;
                case 3: // address
                    write_address(data);
                    break;
            }
        }

        /**
         * generate - generate samples of SSG sound
         */
        @Override
        public void generate(YmFm.Output output, int numSamples /* = 1 */) {
            for (int samp = 0; samp < numSamples; samp++, output.inc()) {
                // clock the SSG
                m_ssg.clock();

                // YM2149 keeps the three SSG outputs independent
                m_ssg.output(output);
            }
        }

        // internal state
        @Element
        protected int m_address;               // address register
        protected Ssg.Engine m_ssg;                // SSG engine
    }
}
