/*
 * zorbage-nifti: code for reading nifti data files into zorbage structures for further processing<
 *
 * Copyright (C) 2021 Barry DeZonia
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package nom.bdezonia.zorbage.nifti;

/*
 * TODO
 * 1) permute axes as specified in a header variable so data is ordered correctly
 * 2) eliminate duplicate code
 * 3) make a static reader class and then use it in zorbage-viewer
 * 4) use header data to improve translations and to tag things with correct metadata
 *    There are some data scaling constants that I am not applying to the data. Using
 *    it might require all data sets to be floating point though.
 * 5) support float 128 bit types (as highprecs for now)
 * 6) figure out how to support old Analyze files when detected
 * 7) deal with extension bytes after the header and before the pixel data
 * 8) support data intents from the intent codes in the header
 * 9) see this page for lots of good info: https://brainder.org/2012/09/23/the-nifti-file-format/
 * 10) there may be a 1-bit bool type referred to as data_type 1. I haven't found a lot of docs about it yet.
 */

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;

import javax.swing.JFileChooser;

import nom.bdezonia.zorbage.algebra.Allocatable;
import nom.bdezonia.zorbage.algebra.G;
import nom.bdezonia.zorbage.algorithm.GridIterator;
import nom.bdezonia.zorbage.data.DimensionedDataSource;
import nom.bdezonia.zorbage.data.DimensionedStorage;
import nom.bdezonia.zorbage.misc.DataBundle;
import nom.bdezonia.zorbage.sampling.IntegerIndex;
import nom.bdezonia.zorbage.sampling.SamplingIterator;
import nom.bdezonia.zorbage.type.color.ArgbMember;
import nom.bdezonia.zorbage.type.color.RgbMember;
import nom.bdezonia.zorbage.type.complex.float32.ComplexFloat32Member;
import nom.bdezonia.zorbage.type.complex.float64.ComplexFloat64Member;
import nom.bdezonia.zorbage.type.complex.highprec.ComplexHighPrecisionMember;
import nom.bdezonia.zorbage.type.integer.int16.SignedInt16Member;
import nom.bdezonia.zorbage.type.integer.int16.UnsignedInt16Member;
import nom.bdezonia.zorbage.type.integer.int32.SignedInt32Member;
import nom.bdezonia.zorbage.type.integer.int32.UnsignedInt32Member;
import nom.bdezonia.zorbage.type.integer.int64.SignedInt64Member;
import nom.bdezonia.zorbage.type.integer.int64.UnsignedInt64Member;
import nom.bdezonia.zorbage.type.integer.int8.SignedInt8Member;
import nom.bdezonia.zorbage.type.integer.int8.UnsignedInt8Member;
import nom.bdezonia.zorbage.type.real.float32.Float32Member;
import nom.bdezonia.zorbage.type.real.float64.Float64Member;
import nom.bdezonia.zorbage.type.real.highprec.HighPrecisionMember;

public class Main {

	public static void main(String[] args) {
		
		DataBundle bundle = new DataBundle();
		
		JFileChooser jfc = new JFileChooser();

		int returnValue = jfc.showOpenDialog(null);

		if (returnValue != JFileChooser.APPROVE_OPTION)
			System.out.println("Abort Captain!");
		
		File file = jfc.getSelectedFile();
		
		System.out.println("File length = "+file.length());
		
		try {
			byte[] buf128 = new byte[16];
			
			FileInputStream in = new FileInputStream(file);
			
			DataInputStream d = new DataInputStream(in);
			
			boolean swapBytes = false;
			
			int headerSize = d.readInt();
			
			if (headerSize == 348 || swapInt(headerSize) == 348) {
				
				// possibly nifti 1
				
				System.out.println("Possibly NIFTI 1");

				for (int i = 0; i < 35; i++) {
					readByte(d);
				}
				
				byte dim_info = readByte(d);

				// pixel dimensions
				
				short numD = readShort(d, false);
				if (numD < 0 || numD > 7) {
					numD = swapShort(numD);
					swapBytes = true;
				}
				short d1 = readShort(d, swapBytes);
				short d2 = readShort(d, swapBytes);
				short d3 = readShort(d, swapBytes);
				short d4 = readShort(d, swapBytes);
				short d5 = readShort(d, swapBytes);
				short d6 = readShort(d, swapBytes);
				short d7 = readShort(d, swapBytes);
				
				long[] dims = new long[numD];
				for (int i = 0; i < numD; i++) {
					if (i == 0) dims[0] = d1;
					else if (i == 1) dims[1] = d2;
					else if (i == 2) dims[2] = d3;
					else if (i == 3) dims[3] = d4;
					else if (i == 4) dims[4] = d5;
					else if (i == 5) dims[5] = d6;
					else if (i == 6) dims[6] = d7;
				}
				
				float intent_p1 = readFloat(d, swapBytes);
				float intent_p2 = readFloat(d, swapBytes);
				float intent_p3 = readFloat(d, swapBytes);
				
				short nifti_intent_code = readShort(d, swapBytes);
				short data_type = readShort(d, swapBytes);
				short bitpix = readShort(d, swapBytes);
				short slice_start = readShort(d, swapBytes);
				
				System.out.println("data type: "+data_type+" bitpix "+bitpix);
				
				// pixel spacings
				
				float sd1 = readFloat(d, swapBytes);
				float sd2 = readFloat(d, swapBytes);
				float sd3 = readFloat(d, swapBytes);
				float sd4 = readFloat(d, swapBytes);
				float sd5 = readFloat(d, swapBytes);
				float sd6 = readFloat(d, swapBytes);
				float sd7 = readFloat(d, swapBytes);
				float sd8 = readFloat(d, swapBytes);

				float vox_offset = readFloat(d, swapBytes);
				float scl_slope = readFloat(d, swapBytes);
				float scl_inter = readFloat(d, swapBytes);

				short slice_end = readShort(d, swapBytes);
				byte slice_code = readByte(d);
				byte xyzt_units = readByte(d);
				
				float cal_max = readFloat(d, swapBytes);
				float cal_min = readFloat(d, swapBytes);
				float slice_duration = readFloat(d, swapBytes);
				float toffset = readFloat(d, swapBytes);

				for (int i = 0; i < 2; i++) {
					readInt(d, swapBytes);
				}

				for (int i = 0; i < 80; i++) {
					// TODO: description string: build it
					readByte(d);
				}

				for (int i = 0; i < 24; i++) {
					// TODO: aux filename string: build it
					readByte(d);
				}

				short qform_code = readShort(d, swapBytes);
				short sform_code = readShort(d, swapBytes);

				float quatern_b = readFloat(d, swapBytes);
				float quatern_c = readFloat(d, swapBytes);
				float quatern_d = readFloat(d, swapBytes);
				float qoffset_x = readFloat(d, swapBytes);
				float qoffset_y = readFloat(d, swapBytes);
				float qoffset_z = readFloat(d, swapBytes);

				// affine transform : row 0 = x, row 1 = y, row 2 = z
				
				float x0 = readFloat(d, swapBytes);
				float x1 = readFloat(d, swapBytes);
				float x2 = readFloat(d, swapBytes);
				float x3 = readFloat(d, swapBytes);
				float y0 = readFloat(d, swapBytes);
				float y1 = readFloat(d, swapBytes);
				float y2 = readFloat(d, swapBytes);
				float y3 = readFloat(d, swapBytes);
				float z0 = readFloat(d, swapBytes);
				float z1 = readFloat(d, swapBytes);
				float z2 = readFloat(d, swapBytes);
				float z3 = readFloat(d, swapBytes);

				for (int i = 0; i < 16; i++) {
					// TODO: intent_name string: build it
					readByte(d);
				}

				byte magic0 = readByte(d);
				byte magic1 = readByte(d);
				byte magic2 = readByte(d);
				byte magic3 = readByte(d);

				if (magic0 == 'n' && magic1 == 'i' && magic2 == '1' && magic3 == 0)
					System.out.println("VALID and of type 1a");
				else if (magic0 == 'n' && magic1 == '+' && magic2 == '1' && magic3 == 0)
					System.out.println("VALID and of type 1b");
				else
					System.out.println("INVALID type 1 header");

				// BDZ HACK : i read somewhere that the data starts 4 bytes beyond end of header; is this the first link in the extension chain?
				int jnk = readInt(d, swapBytes);
				
				Allocatable type = null;
				
				switch (data_type) {
				case 2: // uint8
					type = G.UINT8.construct();
					break;
				case 4: // int16
					type = G.INT16.construct();
					break;
				case 8: // int32
					type = G.INT32.construct();
					break;
				case 16: // float32
					type = G.FLT.construct();
					break;
				case 32: // cfloat32
					type = G.CFLT.construct();
					break;
				case 64: // float64
					type = G.DBL.construct();
					break;
				case 128: // rgb
					type = G.RGB.construct();
					break;
				case 256: // int8
					type = G.INT8.construct();
					break;
				case 512: // uint16
					type = G.UINT16.construct();
					break;
				case 768: // uint32
					type = G.UINT32.construct();
					break;
				case 1024: // int64
					type = G.INT64.construct();
					break;
				case 1280: // uint64
					type = G.UINT64.construct();
					break;
				case 1536: // float128 : treat as highprec
					type = G.HP.construct();
					break;
				case 1792: // cfloat64
					type = G.CDBL.construct();
					break;
				case 2048: // cfloat128 : treat as highprec
					type = G.CHP.construct();
					break;
				case 2304: // rgba
					type = G.ARGB.construct();
					break;
				default:
					System.out.println("Unknown data type! "+data_type);
				}

				System.out.println("dims = " + Arrays.toString(dims));
				
				DimensionedDataSource data = DimensionedStorage.allocate(type, dims);
				
				IntegerIndex idx = new IntegerIndex(dims.length);
				SamplingIterator<IntegerIndex> itr = GridIterator.compute(dims);
				byte tb;
				short ts;
				int ti;
				long tl;
				float tf;
				double td;
				BigDecimal tbd;
				while (itr.hasNext()) {
					itr.next(idx);
					switch (data_type) {
					case 2: // uint8
						tb = readByte(d);
						((UnsignedInt8Member) type).setV(tb);
						break;
					case 4: // int16
						ts = readShort(d, swapBytes);
						((SignedInt16Member) type).setV(ts);
						break;
					case 8: // int32
						ti = readInt(d, swapBytes);
						((SignedInt32Member) type).setV(ti);
						break;
					case 16: // float32
						tf = readFloat(d, swapBytes);
						((Float32Member) type).setV(tf);
						break;
					case 32: // cfloat32
						tf = readFloat(d, swapBytes);
						((ComplexFloat32Member) type).setR(tf);
						tf = readFloat(d, swapBytes);
						((ComplexFloat32Member) type).setI(tf);
						break;
					case 64: // float64
						td = readDouble(d, swapBytes);
						((Float64Member) type).setV(td);
						break;
					case 128: // rgb
						tb = readByte(d);
						((RgbMember) type).setR(tb);
						tb = readByte(d);
						((RgbMember) type).setG(tb);
						tb = readByte(d);
						((RgbMember) type).setB(tb);
						break;
					case 256: // int8
						tb = readByte(d);
						((SignedInt8Member) type).setV(tb);
						break;
					case 512: // uint16
						ts = readShort(d, swapBytes);
						((UnsignedInt16Member) type).setV(ts);
						break;
					case 768: // uint32
						ti = readInt(d, swapBytes);
						((UnsignedInt32Member) type).setV(ti);
						break;
					case 1024: // int64
						tl = readLong(d, swapBytes);
						((SignedInt64Member) type).setV(tl);
						break;
					case 1280: // uint64
						tl = readLong(d, swapBytes);
						((UnsignedInt64Member) type).setV(tl);
						break;
					case 1536: // float128 : treat as highprec
						tbd = readFloat128(d, swapBytes, buf128);
						((HighPrecisionMember) type).setV(tbd);
						break;
					case 1792: // cfloat64
						td = readDouble(d, swapBytes);
						((ComplexFloat64Member) type).setR(td);
						td = readDouble(d, swapBytes);
						((ComplexFloat64Member) type).setI(td);
						break;
					case 2048: // cfloat128 : treat as highprec
						tbd = readFloat128(d, swapBytes, buf128);
						((ComplexHighPrecisionMember) type).setR(tbd);
						tbd = readFloat128(d, swapBytes, buf128);
						((ComplexHighPrecisionMember) type).setI(tbd);
						break;
					case 2304: // rgba
						tb = readByte(d);
						((ArgbMember) type).setR(tb);
						tb = readByte(d);
						((ArgbMember) type).setG(tb);
						tb = readByte(d);
						((ArgbMember) type).setB(tb);
						tb = readByte(d);
						((ArgbMember) type).setA(tb);
						break;
					default:
						System.out.println("Unknown data type! "+data_type);
					}
					data.set(idx, type);
				}
				
				switch (data_type) {
				case 2: // uint8
					bundle.mergeUInt8(data);
					break;
				case 4: // int16
					bundle.mergeInt16(data);
					break;
				case 8: // int32
					bundle.mergeInt32(data);
					break;
				case 16: // float32
					bundle.mergeFlt32(data);
					break;
				case 32: // cfloat32
					bundle.mergeComplexFlt32(data);
					break;
				case 64: // float64
					bundle.mergeFlt64(data);
					break;
				case 128: // rgb
					bundle.mergeRgb(data);
					break;
				case 256: // int8
					bundle.mergeInt8(data);
					break;
				case 512: // uint16
					bundle.mergeUInt16(data);
					break;
				case 768: // uint32
					bundle.mergeUInt32(data);
					break;
				case 1024: // int64
					bundle.mergeInt64(data);
					break;
				case 1280: // uint64
					bundle.mergeUInt64(data);
					break;
				case 1536: // float128 : treat as highprec
					bundle.mergeHP(data);
					break;
				case 1792: // cfloat64
					bundle.mergeComplexFlt64(data);
					break;
				case 2048: // cfloat128 : treat as highprec
					bundle.mergeComplexHP(data);
					break;
				case 2304: // rgba
					bundle.mergeArgb(data);
					break;
				default:
					System.out.println("Unknown data type! "+data_type);
				}
			}
			else if (headerSize == 540 || swapInt(headerSize) == 540) {
				
				// possibly nifti 2
				
				System.out.println("Possibly NIFTI 2");

				byte magic0 = readByte(d);
				byte magic1 = readByte(d);
				byte magic2 = readByte(d);
				byte magic3 = readByte(d);
				byte magic4 = readByte(d);
				byte magic5 = readByte(d);
				byte magic6 = readByte(d);
				byte magic7 = readByte(d);

				if (magic0 == 'n' && magic1 == 'i' && magic2 == '2' && magic3 == 0)
					System.out.println("VALID and of type 2a");
				else if (magic0 == 'n' && magic1 == '+' && magic2 == '2' && magic3 == 0)
					System.out.println("VALID and of type 2b");
				else
					System.out.println("INVALID type 2 header");

				short data_type = readShort(d, false);
				short bitpix = readShort(d, false);
				
				// pixel dimensions
				
				long numD = readLong(d, false);
				if (numD < 0 || numD > 7) {
					numD = swapLong(numD);
					swapBytes = true;
				}
				long d1 = readLong(d, swapBytes);
				long d2 = readLong(d, swapBytes);
				long d3 = readLong(d, swapBytes);
				long d4 = readLong(d, swapBytes);
				long d5 = readLong(d, swapBytes);
				long d6 = readLong(d, swapBytes);
				long d7 = readLong(d, swapBytes);
				
				long[] dims = new long[(int)numD];
				for (int i = 0; i < numD; i++) {
					if (i == 0) dims[0] = d1;
					else if (i == 1) dims[1] = d2;
					else if (i == 2) dims[2] = d3;
					else if (i == 3) dims[3] = d4;
					else if (i == 4) dims[4] = d5;
					else if (i == 5) dims[5] = d6;
					else if (i == 6) dims[6] = d7;
				}

				if (swapBytes) {
					data_type = swapShort(data_type);
					bitpix = swapShort(bitpix);
				}
				
				System.out.println("data type: "+data_type+" bitpix "+bitpix);
				
				double intent_p1 = readDouble(d, swapBytes);
				double intent_p2 = readDouble(d, swapBytes);
				double intent_p3 = readDouble(d, swapBytes);
				
				// pixel spacings
				
				double sd1 = readDouble(d, swapBytes);
				double sd2 = readDouble(d, swapBytes);
				double sd3 = readDouble(d, swapBytes);
				double sd4 = readDouble(d, swapBytes);
				double sd5 = readDouble(d, swapBytes);
				double sd6 = readDouble(d, swapBytes);
				double sd7 = readDouble(d, swapBytes);
				double sd8 = readDouble(d, swapBytes);

				long vox_offset = readLong(d, swapBytes);
				
				double scl_slope = readDouble(d, swapBytes);
				double scl_inter = readDouble(d, swapBytes);
				double cal_max = readDouble(d, swapBytes);
				double cal_min = readDouble(d, swapBytes);
				double slice_duration = readDouble(d, swapBytes);
				double toffset = readDouble(d, swapBytes);

				long slice_start = readLong(d, swapBytes);
				long slice_end = readLong(d, swapBytes);

				for (int i = 0; i < 80; i++) {
					// TODO: description string: build it
					readByte(d);
				}

				for (int i = 0; i < 24; i++) {
					// TODO: aux filename string: build it
					readByte(d);
				}

				int qform_code = readInt(d, swapBytes);
				int sform_code = readInt(d, swapBytes);

				double quatern_b = readFloat(d, swapBytes);
				double quatern_c = readFloat(d, swapBytes);
				double quatern_d = readFloat(d, swapBytes);
				double qoffset_x = readFloat(d, swapBytes);
				double qoffset_y = readFloat(d, swapBytes);
				double qoffset_z = readFloat(d, swapBytes);

				// affine transform : row 0 = x, row 1 = y, row 2 = z
				
				double x0 = readDouble(d, swapBytes);
				double x1 = readDouble(d, swapBytes);
				double x2 = readDouble(d, swapBytes);
				double x3 = readDouble(d, swapBytes);
				double y0 = readDouble(d, swapBytes);
				double y1 = readDouble(d, swapBytes);
				double y2 = readDouble(d, swapBytes);
				double y3 = readDouble(d, swapBytes);
				double z0 = readDouble(d, swapBytes);
				double z1 = readDouble(d, swapBytes);
				double z2 = readDouble(d, swapBytes);
				double z3 = readDouble(d, swapBytes);

				int slice_code = readInt(d, swapBytes);
				int xyzt_units = readInt(d, swapBytes);
				int nifti_intent_code = readInt(d, swapBytes);
				
				for (int i = 0; i < 16; i++) {
					// TODO: intent_name string: build it
					readByte(d);
				}

				byte dimInfo = readByte(d);
				
				for (int i = 0; i < 15; i++) {
					// unused stuff
					readByte(d);
				}

				// BDZ HACK : i read somewhere that the data starts 4 bytes beyond end of header; is this the first link in the extension chain?
				int jnk = readInt(d, swapBytes);

				Allocatable type = null;
				
				switch (data_type) {
				case 2: // uint8
					type = G.UINT8.construct();
					break;
				case 4: // int16
					type = G.INT16.construct();
					break;
				case 8: // int32
					type = G.INT32.construct();
					break;
				case 16: // float32
					type = G.FLT.construct();
					break;
				case 32: // cfloat32
					type = G.CFLT.construct();
					break;
				case 64: // float64
					type = G.DBL.construct();
					break;
				case 128: // rgb
					type = G.RGB.construct();
					break;
				case 256: // int8
					type = G.INT8.construct();
					break;
				case 512: // uint16
					type = G.UINT16.construct();
					break;
				case 768: // uint32
					type = G.UINT32.construct();
					break;
				case 1024: // int64
					type = G.INT64.construct();
					break;
				case 1280: // uint64
					type = G.UINT64.construct();
					break;
				case 1536: // float128 : treat as highprec
					type = G.HP.construct();
					break;
				case 1792: // cfloat64
					type = G.CDBL.construct();
					break;
				case 2048: // cfloat128 : treat as highprec
					type = G.CHP.construct();
					break;
				case 2304: // rgba
					type = G.ARGB.construct();
					break;
				default:
					System.out.println("Unknown data type! "+data_type);
				}

				System.out.println("dims = " + Arrays.toString(dims));
				
				DimensionedDataSource data = DimensionedStorage.allocate(type, dims);
				
				IntegerIndex idx = new IntegerIndex(dims.length);
				SamplingIterator<IntegerIndex> itr = GridIterator.compute(dims);
				byte tb;
				short ts;
				int ti;
				long tl;
				float tf;
				double td;
				BigDecimal tbd;
				while (itr.hasNext()) {
					itr.next(idx);
					switch (data_type) {
					case 2: // uint8
						tb = readByte(d);
						((UnsignedInt8Member) type).setV(tb);
						break;
					case 4: // int16
						ts = readShort(d, swapBytes);
						((SignedInt16Member) type).setV(ts);
						break;
					case 8: // int32
						ti = readInt(d, swapBytes);
						((SignedInt32Member) type).setV(ti);
						break;
					case 16: // float32
						tf = readFloat(d, swapBytes);
						((Float32Member) type).setV(tf);
						break;
					case 32: // cfloat32
						tf = readFloat(d, swapBytes);
						((ComplexFloat32Member) type).setR(tf);
						tf = readFloat(d, swapBytes);
						((ComplexFloat32Member) type).setI(tf);
						break;
					case 64: // float64
						td = readDouble(d, swapBytes);
						((Float64Member) type).setV(td);
						break;
					case 128: // rgb
						tb = readByte(d);
						((RgbMember) type).setR(tb);
						tb = readByte(d);
						((RgbMember) type).setG(tb);
						tb = readByte(d);
						((RgbMember) type).setB(tb);
						break;
					case 256: // int8
						tb = readByte(d);
						((SignedInt8Member) type).setV(tb);
						break;
					case 512: // uint16
						ts = readShort(d, swapBytes);
						((UnsignedInt16Member) type).setV(ts);
						break;
					case 768: // uint32
						ti = readInt(d, swapBytes);
						((UnsignedInt32Member) type).setV(ti);
						break;
					case 1024: // int64
						tl = readLong(d, swapBytes);
						((SignedInt64Member) type).setV(tl);
						break;
					case 1280: // uint64
						tl = readLong(d, swapBytes);
						((UnsignedInt64Member) type).setV(tl);
						break;
					case 1536: // float128 : treat as highprec
						tbd = readFloat128(d, swapBytes, buf128);
						((HighPrecisionMember) type).setV(tbd);
						break;
					case 1792: // cfloat64
						td = readDouble(d, swapBytes);
						((ComplexFloat64Member) type).setR(td);
						td = readDouble(d, swapBytes);
						((ComplexFloat64Member) type).setI(td);
						break;
					case 2048: // cfloat128 : treat as highprec
						tbd = readFloat128(d, swapBytes, buf128);
						((ComplexHighPrecisionMember) type).setR(tbd);
						tbd = readFloat128(d, swapBytes, buf128);
						((ComplexHighPrecisionMember) type).setI(tbd);
						break;
					case 2304: // rgba
						tb = readByte(d);
						((ArgbMember) type).setR(tb);
						tb = readByte(d);
						((ArgbMember) type).setG(tb);
						tb = readByte(d);
						((ArgbMember) type).setB(tb);
						tb = readByte(d);
						((ArgbMember) type).setA(tb);
						break;
					default:
						System.out.println("Unknown data type! "+data_type);
					}
					data.set(idx, type);
				}
				
				switch (data_type) {
				case 2: // uint8
					bundle.mergeUInt8(data);
					break;
				case 4: // int16
					bundle.mergeInt16(data);
					break;
				case 8: // int32
					bundle.mergeInt32(data);
					break;
				case 16: // float32
					bundle.mergeFlt32(data);
					break;
				case 32: // cfloat32
					bundle.mergeComplexFlt32(data);
					break;
				case 64: // float64
					bundle.mergeFlt64(data);
					break;
				case 128: // rgb
					bundle.mergeRgb(data);
					break;
				case 256: // int8
					bundle.mergeInt8(data);
					break;
				case 512: // uint16
					bundle.mergeUInt16(data);
					break;
				case 768: // uint32
					bundle.mergeUInt32(data);
					break;
				case 1024: // int64
					bundle.mergeInt64(data);
					break;
				case 1280: // uint64
					bundle.mergeUInt64(data);
					break;
				case 1536: // float128 : treat as highprec
					bundle.mergeHP(data);
					break;
				case 1792: // cfloat64
					bundle.mergeComplexFlt64(data);
					break;
				case 2048: // cfloat128 : treat as highprec
					bundle.mergeComplexHP(data);
					break;
				case 2304: // rgba
					bundle.mergeArgb(data);
					break;
				default:
					System.out.println("Unknown data type! "+data_type);
				}
			}
			else {
				System.out.println("unknown header size  "+headerSize);
			}
			
			in.close();
		} catch (Exception e) {
			System.out.println(e);
		}
		System.out.println("DONE READING");
	}
	
	private static byte readByte(DataInputStream str) throws IOException {
		return str.readByte();
	}
	
	private static short readShort(DataInputStream str, boolean swapBytes) throws IOException {
		short v = str.readShort();
		if (swapBytes) v = swapShort(v);
		return v;
	}
	
	private static int readInt(DataInputStream str, boolean swapBytes) throws IOException {
		int v = str.readInt();
		if (swapBytes) v = swapInt(v);
		return v;
	}
	
	private static long readLong(DataInputStream str, boolean swapBytes) throws IOException {
		long v = str.readLong();
		if (swapBytes) v = swapLong(v);
		return v;
	}
	
	private static float readFloat(DataInputStream str, boolean swapBytes) throws IOException {
		float v = str.readFloat();
		if (swapBytes) {
			int b = Float.floatToIntBits(v);
			b = swapInt(b);
			v = Float.intBitsToFloat(b);
		}
		return v;
	}
	
	private static double readDouble(DataInputStream str, boolean swapBytes) throws IOException {
		double v = str.readDouble();
		if (swapBytes) {
			long b = Double.doubleToLongBits(v);
			b = swapLong(b);
			v = Double.longBitsToDouble(b);
		}
		return v;
	}
	
	private static BigDecimal readFloat128(DataInputStream str, boolean swapBytes, byte[] buffer) throws IOException {
		
		if (buffer.length != 16)
			throw new IllegalArgumentException("byte buffer has incorrect size");
		
		for (int i = 0; i < 16; i++) {
			buffer[i] = str.readByte();
		}
		
		if (swapBytes) {
			for (int i = 0; i < 8; i++) {
				byte tmp = buffer[i];
				buffer[i] = buffer[15 - i];
				buffer[15-i] = tmp;
			}
		}
		
		// TODO: decode the 16 bytes as a IEEE 128 bit float and then decode that value as a BigDecimal.
		//   One gotcha: can't represent NaNs this way.
		
		return BigDecimal.ZERO;
	}
	
	private static short swapShort(short in) {
		int b0 = (in >> 0) & 0xff;
		int b1 = (in >> 8) & 0xff;
		return (short) ((b0 << 8) | (b1 << 0));
	}
	
	private static int swapInt(int in) {
		int b0 = (in >> 0) & 0xff;
		int b1 = (in >> 8) & 0xff;
		int b2 = (in >> 16) & 0xff;
		int b3 = (in >> 24) & 0xff;
		return (b0 << 24) | (b1 << 16) | (b2 << 8) | (b3 << 0);
	}
	
	private static long swapLong(long in) {
		long b0 = (in >> 0) & 0xff;
		long b1 = (in >> 8) & 0xff;
		long b2 = (in >> 16) & 0xff;
		long b3 = (in >> 24) & 0xff;
		long b4 = (in >> 32) & 0xff;
		long b5 = (in >> 40) & 0xff;
		long b6 = (in >> 48) & 0xff;
		long b7 = (in >> 56) & 0xff;
		return (b0 << 56) | (b1 << 48) | (b2 << 40) | (b3 << 32) | (b4 << 24) | (b5 << 16) | (b6 << 8) | (b7 << 0);
	}
 }
