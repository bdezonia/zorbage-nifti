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

import java.io.BufferedInputStream;

/*
 * TODO
 * 1) support float 128 bit ieee types (especially with nans and infinities) when zorbage provides them
 * 2) support published extensions if they makes sense for translation
 * 3) the 1-bit bool type is hinted at. I haven't found a lot of docs about it yet. do the bytes
 *      always only have unused space in the column direction? Also does endianness in any way affect
 *      the bit order to scan first (hi vs lo). regardless is it always right to left bits or left to right?
 * 4) test ieee 128 bit decodings, 1-bit files, ANALYZE files, am I reading rgb argb components in the right order?
 */

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

import nom.bdezonia.zorbage.algebra.Algebra;
import nom.bdezonia.zorbage.algebra.Allocatable;
import nom.bdezonia.zorbage.algebra.G;
import nom.bdezonia.zorbage.algorithm.GridIterator;
import nom.bdezonia.zorbage.algorithm.Transform2;
import nom.bdezonia.zorbage.axis.StringDefinedAxisEquation;
import nom.bdezonia.zorbage.data.DimensionedDataSource;
import nom.bdezonia.zorbage.data.DimensionedStorage;
import nom.bdezonia.zorbage.datasource.IndexedDataSource;
import nom.bdezonia.zorbage.misc.DataBundle;
import nom.bdezonia.zorbage.procedure.Procedure2;
import nom.bdezonia.zorbage.sampling.IntegerIndex;
import nom.bdezonia.zorbage.sampling.SamplingIterator;
import nom.bdezonia.zorbage.tuple.Tuple2;
import nom.bdezonia.zorbage.type.color.ArgbMember;
import nom.bdezonia.zorbage.type.color.RgbMember;
import nom.bdezonia.zorbage.type.complex.float32.ComplexFloat32Member;
import nom.bdezonia.zorbage.type.complex.float64.ComplexFloat64Member;
import nom.bdezonia.zorbage.type.complex.highprec.ComplexHighPrecisionMember;
import nom.bdezonia.zorbage.type.integer.int1.UnsignedInt1Member;
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

/**
 * 
 * @author Barry DeZonia
 *
 */
@SuppressWarnings({"rawtypes", "unused","unchecked"})
public class Nifti {
	
	/**
	 * 
	 * @param filename
	 * @return
	 */
	public static DataBundle open(String filename) {
				
		File file1 = new File(filename);

		System.out.println("File length = "+file1.length());
		
		FileInputStream f1 = null;
		
		FileInputStream f2 = null;
		
		BufferedInputStream bf1 = null;

		BufferedInputStream bf2 = null;

		DataInputStream hdr = null;
		
		DataInputStream values = null;
				
		try {
			f1 = new FileInputStream(file1);
			
			bf1 = new BufferedInputStream(f1);
			
			hdr = new DataInputStream(bf1);

			boolean two_files;
			
			long numD;
			
			long[] dims;
			
			short data_type = 0;
			
			double scl_slope;
			
			double scl_inter;
			
			boolean swapBytes = false;
			
			byte[] buf128 = new byte[16];
			
			double[] spacings;
			
			String[] units;
			
			double toffset;
			
			String auxname;
			
			String description;
			
			String intent;
			
			Integer nifti_intent_code;

			Double nifti_intent_param1;
			
			Double nifti_intent_param2;
			
			Double nifti_intent_param3;
			
			double sx = 0;
			double sy = 0;
			double sz = 0;
			
			boolean is_analyze = false;
			
			int headerSize = hdr.readInt();
			
			if (headerSize == 348 || swapInt(headerSize) == 348) {
				
				// possibly nifti 1
				
				System.out.println("Possibly NIFTI 1");

				for (int i = 0; i < 35; i++) {
					readByte(hdr);
				}
				
				byte dim_info = readByte(hdr);

				// pixel dimensions
				
				numD = readShort(hdr, false);
				if (numD < 0 || numD > 7) {
					numD = swapShort((short)numD);
					swapBytes = true;
				}
				short d1 = readShort(hdr, swapBytes);
				short d2 = readShort(hdr, swapBytes);
				short d3 = readShort(hdr, swapBytes);
				short d4 = readShort(hdr, swapBytes);
				short d5 = readShort(hdr, swapBytes);
				short d6 = readShort(hdr, swapBytes);
				short d7 = readShort(hdr, swapBytes);
				
				dims = new long[(int)numD];
				if (numD > 0) dims[0] = d1;
				if (numD > 1) dims[1] = d2;
				if (numD > 2) dims[2] = d3;
				if (numD > 3) dims[3] = d4;
				if (numD > 4) dims[4] = d5;
				if (numD > 5) dims[5] = d6;
				if (numD > 6) dims[6] = d7;
				
				nifti_intent_param1 = (double) readFloat(hdr, swapBytes);
				nifti_intent_param2 = (double) readFloat(hdr, swapBytes);
				nifti_intent_param3 = (double) readFloat(hdr, swapBytes);
				
				nifti_intent_code = (int) readShort(hdr, swapBytes);
				data_type = readShort(hdr, swapBytes);
				short bitpix = readShort(hdr, swapBytes);
				short slice_start = readShort(hdr, swapBytes);
				
				System.out.println("data type: "+data_type+" bitpix "+bitpix);
				
				// pixel spacings
				
				float sd0 = readFloat(hdr, swapBytes);
				float sd1 = readFloat(hdr, swapBytes);
				float sd2 = readFloat(hdr, swapBytes);
				float sd3 = readFloat(hdr, swapBytes);
				float sd4 = readFloat(hdr, swapBytes);
				float sd5 = readFloat(hdr, swapBytes);
				float sd6 = readFloat(hdr, swapBytes);
				float sd7 = readFloat(hdr, swapBytes);

				spacings = new double[(int)numD];
				if (numD > 0) spacings[0] = sd1;
				if (numD > 1) spacings[1] = sd2;
				if (numD > 2) spacings[2] = sd3;
				if (numD > 3) spacings[3] = sd4;
				if (numD > 4) spacings[4] = sd5;
				if (numD > 5) spacings[5] = sd6;
				if (numD > 6) spacings[6] = sd7;
				
				float vox_offset = readFloat(hdr, swapBytes);
				
				scl_slope = readFloat(hdr, swapBytes);
				scl_inter = readFloat(hdr, swapBytes);

				short slice_end = readShort(hdr, swapBytes);
				byte slice_code = readByte(hdr);
				
				byte xyzt_units = readByte(hdr);
				
				int v;
				units = new String[(int)numD];
				String space_units = "unknown";
				v = xyzt_units & 0x7;
				if (v == 1) space_units = "meter";
				if (v == 2) space_units = "mm";
				if (v == 3) space_units = "micron";
				String time_units = "unknown";
				v = xyzt_units & 0x38;
				if (v == 6) time_units = "secs";
				if (v == 16) time_units = "millisecs";
				if (v == 24) time_units = "microsecs";
				// do these apply to time axis or the other 3 upper indices?
				if (v == 32) time_units = "hertz";
				if (v == 40) time_units = "ppm";
				if (v == 48) time_units = "rad/sec";
				String other_units = "unknown";

				if (numD > 0) units[0] = space_units;
				if (numD > 1) units[1] = space_units;
				if (numD > 2) units[2] = space_units;
				if (numD > 3) units[3] = time_units;
				if (numD > 4) units[4] = other_units;
				if (numD > 5) units[5] = other_units;
				if (numD > 6) units[6] = other_units;

				float cal_max = readFloat(hdr, swapBytes);
				float cal_min = readFloat(hdr, swapBytes);
				
				float slice_duration = readFloat(hdr, swapBytes);
				toffset = readFloat(hdr, swapBytes);

				for (int i = 0; i < 2; i++) {
					readInt(hdr, swapBytes);
				}

				description = readString(hdr, 80);

				auxname = readString(hdr, 24);

				short qform_code = readShort(hdr, swapBytes);
				short sform_code = readShort(hdr, swapBytes);

				float quatern_b = readFloat(hdr, swapBytes);
				float quatern_c = readFloat(hdr, swapBytes);
				float quatern_d = readFloat(hdr, swapBytes);
				float qoffset_x = readFloat(hdr, swapBytes);
				float qoffset_y = readFloat(hdr, swapBytes);
				float qoffset_z = readFloat(hdr, swapBytes);

				// affine transform : row 0 = x, row 1 = y, row 2 = z
				
				sx = readFloat(hdr, swapBytes);
				float x1 = readFloat(hdr, swapBytes);
				float x2 = readFloat(hdr, swapBytes);
				float x3 = readFloat(hdr, swapBytes);
				float y0 = readFloat(hdr, swapBytes);
				sy = readFloat(hdr, swapBytes);
				float y2 = readFloat(hdr, swapBytes);
				float y3 = readFloat(hdr, swapBytes);
				float z0 = readFloat(hdr, swapBytes);
				float z1 = readFloat(hdr, swapBytes);
				sz = readFloat(hdr, swapBytes);
				float z3 = readFloat(hdr, swapBytes);

				intent = readString(hdr, 16);

				byte magic0 = readByte(hdr);
				byte magic1 = readByte(hdr);
				byte magic2 = readByte(hdr);
				byte magic3 = readByte(hdr);

				if (magic0 == 'n' && magic1 == 'i' && magic2 == '1' && magic3 == 0) {
					System.out.println("VALID and of type 1a");
					two_files = true;
				}
				else if (magic0 == 'n' && magic1 == '+' && magic2 == '1' && magic3 == 0) {
					two_files = false;
					System.out.println("VALID and of type 1b");
				}
				else {
					System.out.println("INVALID type 1 header : treat it as ANALYZE data");
					// TODO: read header as an ANALYZE 7.5 file and then read pixels correctly
					// For now expect the current header vars will work for us as is.
					is_analyze = true;
					two_files = true;
				}
			}
			else if (headerSize == 540 || swapInt(headerSize) == 540) {
				
				// possibly nifti 2
				
				System.out.println("Possibly NIFTI 2");

				byte magic0 = readByte(hdr);
				byte magic1 = readByte(hdr);
				byte magic2 = readByte(hdr);
				byte magic3 = readByte(hdr);
				byte magic4 = readByte(hdr);
				byte magic5 = readByte(hdr);
				byte magic6 = readByte(hdr);
				byte magic7 = readByte(hdr);

				if (magic0 == 'n' && magic1 == 'i' && magic2 == '2' && magic3 == 0) {
					System.out.println("VALID and of type 2a");
					two_files = true;
				}
				else if (magic0 == 'n' && magic1 == '+' && magic2 == '2' && magic3 == 0) {
					System.out.println("VALID and of type 2b");
					two_files = false;
				}
				else {
					System.out.println("INVALID type 2 header");
					
					hdr.close();
					
					return new DataBundle();
				}

				data_type = readShort(hdr, false);
				short bitpix = readShort(hdr, false);
				
				// pixel dimensions
				
				numD = readLong(hdr, false);
				if (numD < 0 || numD > 7) {
					numD = swapLong(numD);
					swapBytes = true;
				}
				long d1 = readLong(hdr, swapBytes);
				long d2 = readLong(hdr, swapBytes);
				long d3 = readLong(hdr, swapBytes);
				long d4 = readLong(hdr, swapBytes);
				long d5 = readLong(hdr, swapBytes);
				long d6 = readLong(hdr, swapBytes);
				long d7 = readLong(hdr, swapBytes);
				
				dims = new long[(int)numD];
				if (numD > 0) dims[0] = d1;
				if (numD > 1) dims[1] = d2;
				if (numD > 2) dims[2] = d3;
				if (numD > 3) dims[3] = d4;
				if (numD > 4) dims[4] = d5;
				if (numD > 5) dims[5] = d6;
				if (numD > 6) dims[6] = d7;

				// some vars were read before we knew whether they needing swapping. swap them now.
				if (swapBytes) {
					data_type = swapShort(data_type);
					bitpix = swapShort(bitpix);
				}
				
				System.out.println("data type: "+data_type+" bitpix "+bitpix);
				
				nifti_intent_param1 = readDouble(hdr, swapBytes);
				nifti_intent_param2 = readDouble(hdr, swapBytes);
				nifti_intent_param3 = readDouble(hdr, swapBytes);
				
				// pixel spacings
				
				double sd0 = readDouble(hdr, swapBytes);
				double sd1 = readDouble(hdr, swapBytes);
				double sd2 = readDouble(hdr, swapBytes);
				double sd3 = readDouble(hdr, swapBytes);
				double sd4 = readDouble(hdr, swapBytes);
				double sd5 = readDouble(hdr, swapBytes);
				double sd6 = readDouble(hdr, swapBytes);
				double sd7 = readDouble(hdr, swapBytes);

				spacings = new double[(int)numD];
				if (numD > 0) spacings[0] = sd1;
				if (numD > 1) spacings[1] = sd2;
				if (numD > 2) spacings[2] = sd3;
				if (numD > 3) spacings[3] = sd4;
				if (numD > 4) spacings[4] = sd5;
				if (numD > 5) spacings[5] = sd6;
				if (numD > 6) spacings[6] = sd7;
				
				long vox_offset = readLong(hdr, swapBytes);
				
				scl_slope = readDouble(hdr, swapBytes);
				scl_inter = readDouble(hdr, swapBytes);
				
				double cal_max = readDouble(hdr, swapBytes);
				double cal_min = readDouble(hdr, swapBytes);
				
				double slice_duration = readDouble(hdr, swapBytes);
				toffset = readDouble(hdr, swapBytes);

				long slice_start = readLong(hdr, swapBytes);
				long slice_end = readLong(hdr, swapBytes);

				description = readString(hdr, 80);

				auxname = readString(hdr, 24);

				int qform_code = readInt(hdr, swapBytes);
				int sform_code = readInt(hdr, swapBytes);

				double quatern_b = readDouble(hdr, swapBytes);
				double quatern_c = readDouble(hdr, swapBytes);
				double quatern_d = readDouble(hdr, swapBytes);
				double qoffset_x = readDouble(hdr, swapBytes);
				double qoffset_y = readDouble(hdr, swapBytes);
				double qoffset_z = readDouble(hdr, swapBytes);

				// affine transform : row 0 = x, row 1 = y, row 2 = z
				
				sx = readDouble(hdr, swapBytes);
				double x1 = readDouble(hdr, swapBytes);
				double x2 = readDouble(hdr, swapBytes);
				double x3 = readDouble(hdr, swapBytes);
				double y0 = readDouble(hdr, swapBytes);
				sy = readDouble(hdr, swapBytes);
				double y2 = readDouble(hdr, swapBytes);
				double y3 = readDouble(hdr, swapBytes);
				double z0 = readDouble(hdr, swapBytes);
				double z1 = readDouble(hdr, swapBytes);
				sz = readDouble(hdr, swapBytes);
				double z3 = readDouble(hdr, swapBytes);

				int slice_code = readInt(hdr, swapBytes);
				
				int xyzt_units = readInt(hdr, swapBytes);

				int v;
				units = new String[(int)numD];
				String space_units = "unknown";
				v = xyzt_units & 0x7;
				if (v == 1) space_units = "meter";
				if (v == 2) space_units = "mm";
				if (v == 3) space_units = "micron";
				String time_units = "unknown";
				v = xyzt_units & 0x38;
				if (v == 6) time_units = "secs";
				if (v == 16) time_units = "millisecs";
				if (v == 24) time_units = "microsecs";
				if (v == 32) time_units = "hertz";
				if (v == 40) time_units = "ppm";
				if (v == 48) time_units = "rad/sec";
				String other_units = "unknown";

				if (numD > 0) units[0] = space_units;
				if (numD > 1) units[1] = space_units;
				if (numD > 2) units[2] = space_units;
				if (numD > 3) units[3] = time_units;
				if (numD > 4) units[4] = other_units;
				if (numD > 5) units[5] = other_units;
				if (numD > 6) units[6] = other_units;

				nifti_intent_code = readInt(hdr, swapBytes);
				
				intent = readString(hdr, 16);

				byte dim_info = readByte(hdr);
				
				for (int i = 0; i < 15; i++) {
					// unused stuff
					readByte(hdr);
				}
			}
			else {
				
				System.out.println("unknown header size  "+headerSize);
				
				hdr.close();
				
				return new DataBundle();
			}

			byte ext0, ext1, ext2, ext3;
			do {
				// I'm assuming after every extension there is another extension sentinel. docs are not clear. hopefully this works.
				ext0 = readByte(hdr);
				ext1 = readByte(hdr);
				ext2 = readByte(hdr);
				ext3 = readByte(hdr);
				
				if (ext0 != 0) {
					// an extension is present. for now just skip past it.
					int esize = readInt(hdr, swapBytes);
					int ecode = readInt(hdr, swapBytes);
					for (int i = 0; i < esize - 8; i++) {
						readByte(hdr);
					}
					System.out.println("Extension found (and skipped) with code "+ecode);
				}
			} while (ext0 != 0);

			if (two_files) {
				
				File file2 = new File(filename.substring(0, filename.length()-4)+ ".img");

				f2 = new FileInputStream(file2);
				
				bf2 = new BufferedInputStream(f2);
				
				values = new DataInputStream(bf2);
			}
			else {
				
				f2 = f1;
				
				bf2 = bf1;
				
				values = hdr;
			}

			DimensionedDataSource data;
			
			Allocatable type;

			Tuple2<Allocatable,DimensionedDataSource> result;

			System.out.println("dims = " + Arrays.toString(dims));

			// NIFTI bit data requires a little different approach
			if (data_type == 1) {
				UnsignedInt1Member pix = G.UINT1.construct();
				type = pix;
				data = DimensionedStorage.allocate(pix, dims);
				IntegerIndex idx = new IntegerIndex((int)numD);
				SamplingIterator<IntegerIndex> itr = GridIterator.compute(dims);
				byte bucket = 0;
				while (itr.hasNext()) {
					itr.next(idx);
					long saved0 = (numD > 0) ? idx.get(0) : 0;
					long saved1 = (numD > 1) ? idx.get(1) : 0;
					long saved2 = (numD > 2) ? idx.get(2) : 0;
					// orient the axis data correctly
					if ((numD > 0) && ((!is_analyze && sx < 0) || (is_analyze && sx > 0))) {
						idx.set(0, dims[0] - saved0 - 1);
					}
					if ((numD > 1) && sy > 0) {
						idx.set(1, dims[1] - saved1 - 1);
					}
					if ((numD > 2) && sz < 0) {
						idx.set(2, dims[2] - saved2 - 1);
					}
					int bitNum = (int) (idx.get(0) % 8); 
					if (bitNum == 0) {
						bucket = readByte(values);
					}
					int val = (bucket & (1 << bitNum)) > 0 ? 1 : 0;
					pix.setV(val);
					data.set(idx, pix);
					if ((numD > 0) && ((!is_analyze && sx < 0) || (is_analyze && sx > 0))) {
						idx.set(0, saved0);
					}
					if ((numD > 1) && sy > 0) {
						idx.set(1, saved1);
					}
					if ((numD > 2) && sz < 0) {
						idx.set(2, saved2);
					}
				}
				if (scl_slope != 0) {
					result = scale(data, pix, scl_slope, scl_inter);
					type = result.a();
					data = result.b();
				}
			}
			else {
				// all other types are straightforward
				type = value(data_type);
				data = DimensionedStorage.allocate(type, dims);
				IntegerIndex idx = new IntegerIndex((int) numD);
				SamplingIterator<IntegerIndex> itr = GridIterator.compute(dims);
				while (itr.hasNext()) {
					itr.next(idx);
					long saved0 = (numD > 0) ? idx.get(0) : 0;
					long saved1 = (numD > 1) ? idx.get(1) : 0;
					long saved2 = (numD > 2) ? idx.get(2) : 0;
					// orient the axis data correctly
					if ((numD > 0) && ((!is_analyze && sx < 0) || (is_analyze && sx > 0))) {
						idx.set(0, dims[0] - saved0 - 1);
					}
					if ((numD > 1) && sy > 0) {
						idx.set(1, dims[1] - saved1 - 1);
					}
					if ((numD > 2) && sz < 0) {
						idx.set(2, dims[2] - saved2 - 1);
					}
					readValue(values, type, data_type, swapBytes, buf128);
					data.set(idx, type);
					if ((numD > 0) && ((!is_analyze && sx < 0) || (is_analyze && sx > 0))) {
						idx.set(0, saved0);
					}
					if ((numD > 1) && sy > 0) {
						idx.set(1, saved1);
					}
					if ((numD > 2) && sz < 0) {
						idx.set(2, saved2);
					}
				}
				if (scl_slope != 0) {
					result = scale(data, type, scl_slope, scl_inter);
					type = result.a();
					data = result.b();
				}
			}

			System.out.println("DONE READING");
			System.out.println("  bytes remaining in header file = " + f1.available());
			System.out.println("  bytes remaining in values file = " + f2.available());
			
			data.setName("nifti file");
			
			data.setSource(filename);
			
			if (numD > 0) {
				data.setAxisType(0, "x");
				data.setAxisUnit(0, units[0]);
				data.setAxisEquation(0, new StringDefinedAxisEquation("" + spacings[0] + " * $0"));
			}
			if (numD > 1) {
				data.setAxisType(1, "y");
				data.setAxisUnit(1, units[1]);
				data.setAxisEquation(1, new StringDefinedAxisEquation("" + spacings[1] + " * $0"));
			}
			if (numD > 2) {
				data.setAxisType(2, "z");
				data.setAxisUnit(2, units[2]);
				data.setAxisEquation(2, new StringDefinedAxisEquation("" + spacings[2] + " * $0"));
			}
			if (numD > 3) {
				data.setAxisType(3, "t");
				data.setAxisUnit(3, units[3]);
				data.setAxisEquation(3, new StringDefinedAxisEquation("" + toffset + " + " + spacings[3] + " * $0"));
			}
			if (numD > 4) {
				data.setAxisType(4, "l");
				data.setAxisUnit(4, units[4]);
				data.setAxisEquation(4, new StringDefinedAxisEquation("" + spacings[4] + " * $0"));
			}
			if (numD > 5) {
				data.setAxisType(5, "m");
				data.setAxisUnit(5, units[5]);
				data.setAxisEquation(5, new StringDefinedAxisEquation("" + spacings[5] + " * $0"));
			}
			if (numD > 6) {
				data.setAxisType(6, "n");
				data.setAxisUnit(6, units[6]);
				data.setAxisEquation(6, new StringDefinedAxisEquation("" + spacings[6] + " * $0"));
			}

			data.metadata().put("auxiliary file name", auxname);
			
			data.metadata().put("description", description);
			
			data.metadata().put("intent", intent);
			
			data.metadata().put("nifti intent code", nifti_intent_code.toString());
			
			data.metadata().put("nifti intent parameter 1", nifti_intent_param1.toString());
			
			data.metadata().put("nifti intent parameter 2", nifti_intent_param2.toString());
			
			data.metadata().put("nifti intent parameter 3", nifti_intent_param3.toString());
			
			DataBundle bundle = new DataBundle();
			
			mergeData(bundle, type, data);

			if (two_files) {
				values.close();
			}
			hdr.close();
			
			return bundle;

		} catch (Exception e) {
		
			try {
				if (values != hdr) { // two files
					if (values != null)
						values.close();
					else if (bf2 != null)
						bf2.close();
					else if (f2 != null)
						f2.close();
				}
				if (hdr != null)
					hdr.close();
				else if (bf1 != null)
					bf1.close();
				else if (f1 != null)
					f1.close();
			} catch (IOException x) {
				;
			}
			System.out.println(e);
			return new DataBundle();
		}
	}
	
	private static Allocatable value(short data_type) {
		switch (data_type) {
		case 1: // bit
			throw new IllegalArgumentException("bit types should never pass through this routine");
		case 2: // uint8
			return G.UINT8.construct();
		case 4: // int16
			return G.INT16.construct();
		case 8: // int32
			return G.INT32.construct();
		case 16: // float32
			return G.FLT.construct();
		case 32: // cfloat32
			return G.CFLT.construct();
		case 64: // float64
			return G.DBL.construct();
		case 128: // rgb
			return G.RGB.construct();
		case 256: // int8
			return G.INT8.construct();
		case 512: // uint16
			return G.UINT16.construct();
		case 768: // uint32
			return G.UINT32.construct();
		case 1024: // int64
			return G.INT64.construct();
		case 1280: // uint64
			return G.UINT64.construct();
		case 1536: // float128 : treat as highprec
			return G.HP.construct();
		case 1792: // cfloat64
			return G.CDBL.construct();
		case 2048: // cfloat128 : treat as highprec
			return G.CHP.construct();
		case 2304: // rgba
			return G.ARGB.construct();
		default:
			throw new IllegalArgumentException("Unknown data type! "+data_type);
		}
	}

	private static void readValue(DataInputStream d, Allocatable type, short data_type, boolean swapBytes, byte[] buf128) throws IOException {
		byte tb;
		short ts;
		int ti;
		long tl;
		float tf;
		double td;
		BigDecimal tbd;
		switch (data_type) {
		case 1: // bit
			throw new IllegalArgumentException("bit types should never pass through this routine");
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
			throw new IllegalArgumentException("Unknown data type! "+data_type);
		}

	}
	
	private static void mergeData(DataBundle bundle, Allocatable type, DimensionedDataSource data) {
		if (type instanceof UnsignedInt1Member) {
			bundle.mergeUInt1(data);
		}
		else if (type instanceof UnsignedInt8Member) {
			bundle.mergeUInt8(data);
		}
		else if (type instanceof SignedInt8Member) {
			bundle.mergeInt8(data);
		}
		else if (type instanceof UnsignedInt16Member) {
			bundle.mergeUInt16(data);
		}
		else if (type instanceof SignedInt16Member) {
			bundle.mergeInt16(data);
		}
		else if (type instanceof UnsignedInt32Member) {
			bundle.mergeUInt32(data);
		}
		else if (type instanceof SignedInt32Member) {
			bundle.mergeInt32(data);
		}
		else if (type instanceof UnsignedInt64Member) {
			bundle.mergeUInt64(data);
		}
		else if (type instanceof SignedInt64Member) {
			bundle.mergeInt64(data);
		}
		else if (type instanceof Float32Member) {
			bundle.mergeFlt32(data);
		}
		else if (type instanceof ComplexFloat32Member) {
			bundle.mergeComplexFlt32(data);
		}
		else if (type instanceof Float64Member) {
			bundle.mergeFlt64(data);
		}
		else if (type instanceof ComplexFloat64Member) {
			bundle.mergeComplexFlt64(data);
		}
		else if (type instanceof HighPrecisionMember) {
			bundle.mergeHP(data);
		}
		else if (type instanceof ComplexHighPrecisionMember) {
			bundle.mergeComplexHP(data);
		}
		else if (type instanceof RgbMember) {
			bundle.mergeRgb(data);
		}
		else if (type instanceof ArgbMember) {
			bundle.mergeArgb(data);
		}
		else
			throw new IllegalArgumentException("Unknown data type passed to merge() method");
	}

	private static Tuple2<Allocatable, DimensionedDataSource>
		scale(DimensionedDataSource data, Allocatable type, double slope, double intercept)
	{
		long[] dims = new long[data.numDimensions()];
		for (int i = 0; i < dims.length; i++) {
			dims[i] = data.dimension(i);
		}
		Algebra returnAlg;
		DimensionedDataSource returnDs;
		if (type instanceof UnsignedInt1Member) {
			returnAlg = G.DBL;
			returnDs = DimensionedStorage.allocate(G.DBL.construct(), dims);
			Procedure2<UnsignedInt1Member,Float64Member> proc = new Procedure2<UnsignedInt1Member,Float64Member>() {
				@Override
				public void call(UnsignedInt1Member a, Float64Member b) {
					b.setV(a.v() * slope + intercept);
				}
			};
			Transform2.compute(G.UINT1, G.DBL, proc, (IndexedDataSource<UnsignedInt1Member>) data.rawData(), (IndexedDataSource<Float64Member>) returnDs.rawData());
		}
		else if (type instanceof UnsignedInt8Member) {
			returnAlg = G.DBL;
			returnDs = DimensionedStorage.allocate(G.DBL.construct(), dims);
			Procedure2<UnsignedInt8Member,Float64Member> proc = new Procedure2<UnsignedInt8Member,Float64Member>() {
				@Override
				public void call(UnsignedInt8Member a, Float64Member b) {
					b.setV(a.v() * slope + intercept);
				}
			};
			Transform2.compute(G.UINT8, G.DBL, proc, (IndexedDataSource<UnsignedInt8Member>) data.rawData(), (IndexedDataSource<Float64Member>) returnDs.rawData());
		}
		else if (type instanceof SignedInt8Member) {
			returnAlg = G.DBL;
			returnDs = DimensionedStorage.allocate(G.DBL.construct(), dims);
			Procedure2<SignedInt8Member,Float64Member> proc = new Procedure2<SignedInt8Member,Float64Member>() {
				@Override
				public void call(SignedInt8Member a, Float64Member b) {
					b.setV(a.v() * slope + intercept);
				}
			};
			Transform2.compute(G.INT8, G.DBL, proc, (IndexedDataSource<SignedInt8Member>) data.rawData(), (IndexedDataSource<Float64Member>) returnDs.rawData());
		}
		else if (type instanceof UnsignedInt16Member) {
			returnAlg = G.DBL;
			returnDs = DimensionedStorage.allocate(G.DBL.construct(), dims);
			Procedure2<UnsignedInt16Member,Float64Member> proc = new Procedure2<UnsignedInt16Member,Float64Member>() {
				@Override
				public void call(UnsignedInt16Member a, Float64Member b) {
					b.setV(a.v() * slope + intercept);
				}
			};
			Transform2.compute(G.UINT16, G.DBL, proc, (IndexedDataSource<UnsignedInt16Member>) data.rawData(), (IndexedDataSource<Float64Member>) returnDs.rawData());
		}
		else if (type instanceof SignedInt16Member) {
			returnAlg = G.DBL;
			returnDs = DimensionedStorage.allocate(G.DBL.construct(), dims);
			Procedure2<SignedInt16Member,Float64Member> proc = new Procedure2<SignedInt16Member,Float64Member>() {
				@Override
				public void call(SignedInt16Member a, Float64Member b) {
					b.setV(a.v() * slope + intercept);
				}
			};
			Transform2.compute(G.INT16, G.DBL, proc, (IndexedDataSource<SignedInt16Member>) data.rawData(), (IndexedDataSource<Float64Member>) returnDs.rawData());
		}
		else if (type instanceof UnsignedInt32Member) {
			returnAlg = G.DBL;
			returnDs = DimensionedStorage.allocate(G.DBL.construct(), dims);
			Procedure2<UnsignedInt32Member,Float64Member> proc = new Procedure2<UnsignedInt32Member,Float64Member>() {
				@Override
				public void call(UnsignedInt32Member a, Float64Member b) {
					b.setV(a.v() * slope + intercept);
				}
			};
			Transform2.compute(G.UINT32, G.DBL, proc, (IndexedDataSource<UnsignedInt32Member>) data.rawData(), (IndexedDataSource<Float64Member>) returnDs.rawData());
		}
		else if (type instanceof SignedInt32Member) {
			returnAlg = G.DBL;
			returnDs = DimensionedStorage.allocate(G.DBL.construct(), dims);
			Procedure2<SignedInt32Member,Float64Member> proc = new Procedure2<SignedInt32Member,Float64Member>() {
				@Override
				public void call(SignedInt32Member a, Float64Member b) {
					b.setV(a.v() * slope + intercept);
				}
			};
			Transform2.compute(G.INT32, G.DBL, proc, (IndexedDataSource<SignedInt32Member>) data.rawData(), (IndexedDataSource<Float64Member>) returnDs.rawData());
		}
		else if (type instanceof UnsignedInt64Member) {
			returnAlg = G.HP;
			returnDs = DimensionedStorage.allocate(G.HP.construct(), dims);
			Procedure2<UnsignedInt64Member,HighPrecisionMember> proc = new Procedure2<UnsignedInt64Member,HighPrecisionMember>() {
				@Override
				public void call(UnsignedInt64Member a, HighPrecisionMember b) {
					BigDecimal val = new BigDecimal(a.v()).multiply(BigDecimal.valueOf(slope)).add(BigDecimal.valueOf(intercept)); 
					b.setV(val);
				}
			};
			Transform2.compute(G.UINT64, G.HP, proc, (IndexedDataSource<UnsignedInt64Member>) data.rawData(), (IndexedDataSource<HighPrecisionMember>) returnDs.rawData());
		}
		else if (type instanceof SignedInt64Member) {
			returnAlg = G.HP;
			returnDs = DimensionedStorage.allocate(G.HP.construct(), dims);
			Procedure2<SignedInt64Member,HighPrecisionMember> proc = new Procedure2<SignedInt64Member,HighPrecisionMember>() {
				@Override
				public void call(SignedInt64Member a, HighPrecisionMember b) {
					BigDecimal val = new BigDecimal(a.v()).multiply(BigDecimal.valueOf(slope)).add(BigDecimal.valueOf(intercept)); 
					b.setV(val);
				}
			};
			Transform2.compute(G.INT64, G.HP, proc, (IndexedDataSource<SignedInt64Member>) data.rawData(), (IndexedDataSource<HighPrecisionMember>) returnDs.rawData());
		}
		else if (type instanceof Float32Member) {
			returnAlg = G.DBL;
			returnDs = DimensionedStorage.allocate(G.DBL.construct(), dims);
			Procedure2<Float32Member,Float64Member> proc = new Procedure2<Float32Member,Float64Member>() {
				@Override
				public void call(Float32Member a, Float64Member b) {
					b.setV(a.v() * slope + intercept);
				}
			};
			Transform2.compute(G.FLT, G.DBL, proc, (IndexedDataSource<Float32Member>) data.rawData(), (IndexedDataSource<Float64Member>) returnDs.rawData());
		}
		else if (type instanceof ComplexFloat32Member) {
			returnAlg = G.CDBL;
			returnDs = DimensionedStorage.allocate(G.CDBL.construct(), dims);
			Procedure2<ComplexFloat32Member,ComplexFloat64Member> proc = new Procedure2<ComplexFloat32Member,ComplexFloat64Member>() {
				@Override
				public void call(ComplexFloat32Member a, ComplexFloat64Member b) {
					b.setR(a.r() * slope + intercept);
					b.setI(a.i() * slope + intercept);
				}
			};
			Transform2.compute(G.CFLT, G.CDBL, proc, (IndexedDataSource<ComplexFloat32Member>) data.rawData(), (IndexedDataSource<ComplexFloat64Member>) returnDs.rawData());
		}
		else if (type instanceof Float64Member) {
			returnAlg = G.DBL;
			returnDs = data;
			Procedure2<Float64Member,Float64Member> proc = new Procedure2<Float64Member,Float64Member>() {
				@Override
				public void call(Float64Member a, Float64Member b) {
					b.setV(a.v() * slope + intercept);
				}
			};
			Transform2.compute(G.DBL, G.DBL, proc, (IndexedDataSource<Float64Member>) data.rawData(), (IndexedDataSource<Float64Member>) returnDs.rawData());
		}
		else if (type instanceof ComplexFloat64Member) {
			returnAlg = G.CDBL;
			returnDs = data;
			Procedure2<ComplexFloat64Member,ComplexFloat64Member> proc = new Procedure2<ComplexFloat64Member,ComplexFloat64Member>() {
				@Override
				public void call(ComplexFloat64Member a, ComplexFloat64Member b) {
					b.setR(a.r() * slope + intercept);
					b.setI(a.i() * slope + intercept);
				}
			};
			Transform2.compute(G.CDBL, G.CDBL, proc, (IndexedDataSource<ComplexFloat64Member>) data.rawData(), (IndexedDataSource<ComplexFloat64Member>) returnDs.rawData());
		}
		else if (type instanceof HighPrecisionMember) {
			returnAlg = G.HP;
			returnDs = data;
			Procedure2<HighPrecisionMember,HighPrecisionMember> proc = new Procedure2<HighPrecisionMember,HighPrecisionMember>() {
				@Override
				public void call(HighPrecisionMember a, HighPrecisionMember b) {
					BigDecimal val = a.v().multiply(BigDecimal.valueOf(slope)).add(BigDecimal.valueOf(intercept)); 
					b.setV(val);
				}
			};
			Transform2.compute(G.HP, G.HP, proc, (IndexedDataSource<HighPrecisionMember>) data.rawData(), (IndexedDataSource<HighPrecisionMember>) returnDs.rawData());
		}
		else if (type instanceof ComplexHighPrecisionMember) {
			returnAlg = G.CHP;
			returnDs = data;
			Procedure2<ComplexHighPrecisionMember,ComplexHighPrecisionMember> proc = new Procedure2<ComplexHighPrecisionMember,ComplexHighPrecisionMember>() {
				@Override
				public void call(ComplexHighPrecisionMember a, ComplexHighPrecisionMember b) {
					BigDecimal r = a.r().multiply(BigDecimal.valueOf(slope)).add(BigDecimal.valueOf(intercept)); 
					BigDecimal i = a.i().multiply(BigDecimal.valueOf(slope)).add(BigDecimal.valueOf(intercept)); 
					b.setR(r);
					b.setR(i);
				}
			};
			Transform2.compute(G.CHP, G.CHP, proc, (IndexedDataSource<ComplexHighPrecisionMember>) data.rawData(), (IndexedDataSource<ComplexHighPrecisionMember>) returnDs.rawData());
		}
		else if (type instanceof RgbMember) {
			// do not scale color data
			returnAlg = G.RGB;
			returnDs = data;
		}
		else if (type instanceof ArgbMember) {
			// do not scale color data
			returnAlg = G.ARGB;
			returnDs = data;
		}
		else
			throw new IllegalArgumentException("Unknown data type! passed to scale() method");
		return new Tuple2(returnAlg.construct(), returnDs);
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
		if (swapBytes) {
			int b = str.readInt();
			b = swapInt(b);
			return Float.intBitsToFloat(b);
		}
		return str.readFloat();
	}
	
	private static double readDouble(DataInputStream str, boolean swapBytes) throws IOException {
		if (swapBytes) {
			long b = str.readLong();
			b = swapLong(b);
			return Double.longBitsToDouble(b);
		}
		return str.readDouble();
	}

	private static String readString(DataInputStream d, int maxChars) throws IOException {
		StringBuilder str = new StringBuilder();
		boolean done = false;
		for (int i = 0; i < maxChars; i++) {
			char ch = (char) readByte(d);
			if (ch == 0) {
				done = true;
			}
			if (!done) {
				str.append(ch);
			}
		}
		return str.toString();
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
				buffer[15 - i] = tmp;
			}
		}
		
		return decodeFloat128(buffer);
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
	
	// https://en.wikipedia.org/wiki/Quadruple-precision_floating-point_format
	
	// Decode the 16 bytes here as a IEEE 128 bit float and then convert that value into a BigDecimal.
	//   One gotcha: can't represent NaNs or infinities this way.
	//   Maybe I will treat infinities as MAX or MIN and NaNs as 0.

	private static BigDecimal decodeFloat128(byte[] buffer) {
		
		int sign = 0;
		int exponent = 0;
		BigInteger fraction = BigInteger.ZERO;
		
		sign = (buffer[15] & 0x80);
		
		exponent = ((buffer[15] & 0x7f) << 8) + (buffer[14] & 0xff);
		
		for (int i = 13; i <= 0; i--) {
			fraction = fraction.shiftLeft(8).add(BigInteger.valueOf(buffer[i] & 0xff));
		}

		if ((exponent > 0) && (exponent < 0x7fff)) {
			// a regular number
			//   (−1)signbit × 2exponentbits2 − 16383 × 1.significandbits2
			BigDecimal two = BigDecimal.valueOf(2);
			BigDecimal value = BigDecimal.ONE;
			BigDecimal inc = BigDecimal.valueOf(0.5);
			for (int i = 111; i >= 0; i--) {
				if (fraction.testBit(i))
					value.add(inc);
				inc = inc.divide(two);
			}
			BigDecimal scale = two.pow(exponent - 16383);
			value = value.multiply(scale);
			if (sign != 0) {
				return value.negate();
			}
			else {
				return value;
			}
		}
		else if (exponent == 0) {
			if (fraction.compareTo(BigInteger.ZERO) == 0) {
				if (sign != 0) {
					return BigDecimal.valueOf(-0.0);
				}
				else {
					return BigDecimal.valueOf(0.0);
				}
			}
			else { // fraction does not equal zero
				// subnormal number
				//   (−1)signbit × 2−16382 × 0.significandbits2
				BigDecimal two = BigDecimal.valueOf(2);
				BigDecimal value = BigDecimal.ZERO;
				BigDecimal inc = BigDecimal.valueOf(0.5);
				for (int i = 111; i >= 0; i--) {
					if (fraction.testBit(i))
						value.add(inc);
					inc = inc.divide(two);
				}
				BigDecimal scale = two.pow(-16382);
				value = value.multiply(scale);
				if (sign != 0) {
					return value.negate();
				}
				else {
					return value;
				}
			}
		}
		else {
			// exponent == 0x7fff
			if (fraction.compareTo(BigInteger.ZERO) == 0) {
				// an infinity : replace with min or max
				// TODO not the best decision but good enough for now
				//   max = 2^16383 × (2 − 2^−112)
				//   min = -max
				BigDecimal two = BigDecimal.valueOf(2);
				BigDecimal value = two.pow(16383);
				BigDecimal frac = two.subtract(two.pow(-112));
				value = value.multiply(frac);
				if (sign != 0) {
					return value.negate();
				}
				else {
					return value;
				}
			}
			else { // fraction does not equal zero
				// a nan : replace with 0
				// TODO not the best decision but good enough for now
				return BigDecimal.ZERO;
			}
		}
	}
	
 }
