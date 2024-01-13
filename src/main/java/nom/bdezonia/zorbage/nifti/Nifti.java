/*
 * zorbage-nifti: code for reading nifti data files into zorbage structures for further processing<
 *
 * Copyright (C) 2021-2022 Barry DeZonia
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package nom.bdezonia.zorbage.nifti;

import java.io.BufferedInputStream;

/*
 * TODO
 * 1) support published extensions if they makes sense for translation
 * 2) the 1-bit bool type is hinted at. I haven't found a lot of docs about it yet. do the bytes
 *      always only have unused space in the column direction? Also does endianness in any way affect
 *      the bit order to scan first (hi vs lo). regardless is it always right to left bits or left to right?
 * 3) test ieee 128 bit decodings, 1-bit files, ANALYZE files, am I reading rgb/argb components in the right order?
 * 4) if you create an affine coord space should you multiply its scales by the pixel spacings? Or do the params
 *      already contain that info? depending on that choice do you also set axis offsets and spacings of the data
 *      source to 0 and 1?
 * 5) if numD == 4 (a common case I would think) should I hatch an Affine4d space that is 3d with time row just
 *      translating by toffset and maybe scaling by spacings[3] but no other params set?
 */

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;

import nom.bdezonia.zorbage.algebra.Algebra;
import nom.bdezonia.zorbage.algebra.Allocatable;
import nom.bdezonia.zorbage.algebra.G;
import nom.bdezonia.zorbage.algorithm.GridIterator;
import nom.bdezonia.zorbage.algorithm.Transform2;
import nom.bdezonia.zorbage.coordinates.Affine2dCoordinateSpace;
import nom.bdezonia.zorbage.coordinates.Affine3dCoordinateSpace;
import nom.bdezonia.zorbage.coordinates.CoordinateSpace;
import nom.bdezonia.zorbage.coordinates.LinearNdCoordinateSpace;
import nom.bdezonia.zorbage.coordinates.StringDefinedAxisEquation;
import nom.bdezonia.zorbage.data.DimensionedDataSource;
import nom.bdezonia.zorbage.data.DimensionedStorage;
import nom.bdezonia.zorbage.datasource.IndexedDataSource;
import nom.bdezonia.zorbage.dataview.PlaneView;
import nom.bdezonia.zorbage.metadata.MetaDataStore;
import nom.bdezonia.zorbage.misc.DataBundle;
import nom.bdezonia.zorbage.misc.DataSourceUtils;
import nom.bdezonia.zorbage.procedure.Procedure2;
import nom.bdezonia.zorbage.sampling.IntegerIndex;
import nom.bdezonia.zorbage.sampling.SamplingIterator;
import nom.bdezonia.zorbage.tuple.Tuple2;
import nom.bdezonia.zorbage.type.color.ArgbMember;
import nom.bdezonia.zorbage.type.color.RgbMember;
import nom.bdezonia.zorbage.type.complex.float128.ComplexFloat128Member;
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
import nom.bdezonia.zorbage.type.real.float128.Float128Member;
import nom.bdezonia.zorbage.type.real.float32.Float32Member;
import nom.bdezonia.zorbage.type.real.float64.Float64Member;
import nom.bdezonia.zorbage.type.real.highprec.HighPrecisionMember;

/**
 * 
 * @author Barry DeZonia
 *
 */
@SuppressWarnings({"rawtypes", "unused", "unchecked"})
public class Nifti {
	
	private static Float32Member typeFlt;
	private static Float64Member typeDbl;
	private static Float128Member typeQuad;
	private static UnsignedInt8Member typeUInt8;
	private static UnsignedInt16Member typeUInt16;
	private static UnsignedInt32Member typeUInt32;
	private static UnsignedInt64Member typeUInt64;
	private static SignedInt8Member typeInt8;
	private static SignedInt16Member typeInt16;
	private static SignedInt32Member typeInt32;
	private static SignedInt64Member typeInt64;
	private static RgbMember typeRgb;
	private static ArgbMember typeArgb;
	private static ComplexFloat32Member typeCFlt;
	private static ComplexFloat64Member typeCDbl;
	private static ComplexFloat128Member typeCQuad;
	
	/**
	 * 
	 * @param filename
	 * @return
	 */
	public static DataBundle readAllDatasets(String filename) {
				
		File file1 = new File(filename);

		FileInputStream f1 = null;
		
		FileInputStream f2 = null;
		
		BufferedInputStream bf1 = null;

		BufferedInputStream bf2 = null;

		DataInputStream hdr = null;
		
		DataInputStream values = null;
		
		MetaDataStore metadata = new MetaDataStore();
				
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
			
			double sx = 0;
			double x1 = 0;
			double x2 = 0;
			double x3 = 0;
			double y0 = 0;
			double sy = 0;
			double y2 = 0;
			double y3 = 0;
			double z0 = 0;
			double z1 = 0;
			double sz = 0;
			double z3 = 0;
			
			boolean is_analyze = false;
			
			int headerSize = hdr.readInt();
			
			if (headerSize == 348 || swapInt(headerSize) == 348) {
				
				// possibly nifti 1
				
				metadata.putString("NIFTI HEADER: nifti version", "1");

				System.out.println("Possibly NIFTI 1");

				for (int i = 0; i < 35; i++) {
					readByte(hdr);
				}
				
				byte dim_info = readByte(hdr);

				metadata.putByte("NIFTI HEADER: dim info", dim_info);

				// image dimensions
				
				short sNumD = readShort(hdr, false);
				if (sNumD < 0 || sNumD > 7) {
					swapBytes = true;
					// now swap everything we've already read that could have been affected by endianess
					numD = swapShort(sNumD);
				}
				else {
					numD = sNumD;
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
				
				metadata.putLong("NIFTI HEADER: dim 0", numD);
				metadata.putLong("NIFTI HEADER: dim 1", d1);
				metadata.putLong("NIFTI HEADER: dim 2", d2);
				metadata.putLong("NIFTI HEADER: dim 3", d3);
				metadata.putLong("NIFTI HEADER: dim 4", d4);
				metadata.putLong("NIFTI HEADER: dim 5", d5);
				metadata.putLong("NIFTI HEADER: dim 6", d6);
				metadata.putLong("NIFTI HEADER: dim 7", d7);

				float nifti_intent_param1 = readFloat(hdr, swapBytes);
				float nifti_intent_param2 = readFloat(hdr, swapBytes);
				float nifti_intent_param3 = readFloat(hdr, swapBytes);
								
				short nifti_intent_code = readShort(hdr, swapBytes);

				metadata.putInt("NIFTI HEADER: intent code", nifti_intent_code);
				metadata.putDouble("NIFTI HEADER: intent param 1", nifti_intent_param1);
				metadata.putDouble("NIFTI HEADER: intent param 2", nifti_intent_param2);
				metadata.putDouble("NIFTI HEADER: intent param 3", nifti_intent_param3);

				data_type = readShort(hdr, swapBytes);
				
				short bitpix = readShort(hdr, swapBytes);
				
				metadata.putInt("NIFTI HEADER: data_type", data_type);
				metadata.putInt("NIFTI HEADER: bitpix", bitpix);

				short slice_start = readShort(hdr, swapBytes);

				metadata.putLong("NIFTI HEADER: slice start", slice_start);

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
				
				metadata.putDouble("NIFTI HEADER: axis 0 spacing", sd0);
				metadata.putDouble("NIFTI HEADER: axis 1 spacing", sd1);
				metadata.putDouble("NIFTI HEADER: axis 2 spacing", sd2);
				metadata.putDouble("NIFTI HEADER: axis 3 spacing", sd3);
				metadata.putDouble("NIFTI HEADER: axis 4 spacing", sd4);
				metadata.putDouble("NIFTI HEADER: axis 5 spacing", sd5);
				metadata.putDouble("NIFTI HEADER: axis 6 spacing", sd6);
				metadata.putDouble("NIFTI HEADER: axis 7 spacing", sd7);

				float vox_offset = readFloat(hdr, swapBytes);
				
				scl_slope = readFloat(hdr, swapBytes);
				scl_inter = readFloat(hdr, swapBytes);

				metadata.putDouble("NIFTI HEADER: scale slope", scl_slope);
				metadata.putDouble("NIFTI HEADER: scale intercept", scl_inter);

				short slice_end = readShort(hdr, swapBytes);

				metadata.putInt("NIFTI HEADER: slice end", slice_end);

				byte slice_code = readByte(hdr);
				
				metadata.putInt("NIFTI HEADER: slice code", slice_code);
				
				byte xyzt_units = readByte(hdr);

				metadata.putByte("NIFTI HEADER: xyzt units", xyzt_units);

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
				
				metadata.putDouble("NIFTI HEADER: calibration min", cal_max);
				metadata.putDouble("NIFTI HEADER: calibration max", cal_min);
				
				float slice_duration = readFloat(hdr, swapBytes);
				toffset = readFloat(hdr, swapBytes);

				metadata.putDouble("NIFTI HEADER: slice duration", slice_duration);
				metadata.putDouble("NIFTI HEADER: time offset", toffset);

				for (int i = 0; i < 2; i++) {
					readInt(hdr, swapBytes);
				}

				description = readString(hdr, 80);
				
				auxname = readString(hdr, 24);

				metadata.putString("NIFTI HEADER: description", description);
				metadata.putString("NIFTI HEADER: auxiliary file name", auxname);

				short qform_code = readShort(hdr, swapBytes);
				short sform_code = readShort(hdr, swapBytes);

				float quatern_b = readFloat(hdr, swapBytes);
				float quatern_c = readFloat(hdr, swapBytes);
				float quatern_d = readFloat(hdr, swapBytes);
				float qoffset_x = readFloat(hdr, swapBytes);
				float qoffset_y = readFloat(hdr, swapBytes);
				float qoffset_z = readFloat(hdr, swapBytes);

				metadata.putInt("NIFTI HEADER: qform code", qform_code);
				metadata.putInt("NIFTI HEADER: sform_code", sform_code);
				metadata.putDouble("NIFTI HEADER: quaternion b parameter", quatern_b);
				metadata.putDouble("NIFTI HEADER: quaternion c parameter", quatern_c);
				metadata.putDouble("NIFTI HEADER: quaternion d parameter", quatern_d);
				metadata.putDouble("NIFTI HEADER: quaternion z parameter", qoffset_x);
				metadata.putDouble("NIFTI HEADER: quaternion y parameter", qoffset_y);
				metadata.putDouble("NIFTI HEADER: quaternion z parameter", qoffset_z);

				// affine transform : row 0 = x, row 1 = y, row 2 = z
				
				sx = readFloat(hdr, swapBytes);
				x1 = readFloat(hdr, swapBytes);
				x2 = readFloat(hdr, swapBytes);
				x3 = readFloat(hdr, swapBytes);
				y0 = readFloat(hdr, swapBytes);
				sy = readFloat(hdr, swapBytes);
				y2 = readFloat(hdr, swapBytes);
				y3 = readFloat(hdr, swapBytes);
				z0 = readFloat(hdr, swapBytes);
				z1 = readFloat(hdr, swapBytes);
				sz = readFloat(hdr, swapBytes);
				z3 = readFloat(hdr, swapBytes);

				metadata.putDouble("NIFTI HEADER: affine x0 parameter", sx);
				metadata.putDouble("NIFTI HEADER: affine x1 parameter", x1);
				metadata.putDouble("NIFTI HEADER: affine x2 parameter", x2);
				metadata.putDouble("NIFTI HEADER: affine x3 parameter", x3);
				metadata.putDouble("NIFTI HEADER: affine y0 parameter", y0);
				metadata.putDouble("NIFTI HEADER: affine y1 parameter", sy);
				metadata.putDouble("NIFTI HEADER: affine y2 parameter", y2);
				metadata.putDouble("NIFTI HEADER: affine y3 parameter", y3);
				metadata.putDouble("NIFTI HEADER: affine z0 parameter", z0);
				metadata.putDouble("NIFTI HEADER: affine z1 parameter", z1);
				metadata.putDouble("NIFTI HEADER: affine z2 parameter", sz);
				metadata.putDouble("NIFTI HEADER: affine z3 parameter", z3);

				intent = readString(hdr, 16);

				metadata.putString("NIFTI HEADER: intent", intent);

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
					metadata.putString("NIFTI HEADER: nifti version", "pre NIFTI ANALYZE file");
				}
			}
			else if (headerSize == 540 || swapInt(headerSize) == 540) {
				
				// possibly nifti 2
				
				System.out.println("Possibly NIFTI 2");

				metadata.putString("NIFTI HEADER: nifti version", "2");

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
				
				// image dimensions
				
				numD = readLong(hdr, false);
				if (numD < 0 || numD > 7) {
					swapBytes = true;
					// now swap everything we've already read that could have been affected by endianess
					data_type = swapShort(data_type);
					bitpix = swapShort(bitpix);
					numD = swapLong(numD);
				}
				long d1 = readLong(hdr, swapBytes);
				long d2 = readLong(hdr, swapBytes);
				long d3 = readLong(hdr, swapBytes);
				long d4 = readLong(hdr, swapBytes);
				long d5 = readLong(hdr, swapBytes);
				long d6 = readLong(hdr, swapBytes);
				long d7 = readLong(hdr, swapBytes);
				
				metadata.putLong("NIFTI HEADER: dim 0", numD);
				metadata.putLong("NIFTI HEADER: dim 1", d1);
				metadata.putLong("NIFTI HEADER: dim 2", d2);
				metadata.putLong("NIFTI HEADER: dim 3", d3);
				metadata.putLong("NIFTI HEADER: dim 4", d4);
				metadata.putLong("NIFTI HEADER: dim 5", d5);
				metadata.putLong("NIFTI HEADER: dim 6", d6);
				metadata.putLong("NIFTI HEADER: dim 7", d7);

				dims = new long[(int)numD];
				if (numD > 0) dims[0] = d1;
				if (numD > 1) dims[1] = d2;
				if (numD > 2) dims[2] = d3;
				if (numD > 3) dims[3] = d4;
				if (numD > 4) dims[4] = d5;
				if (numD > 5) dims[5] = d6;
				if (numD > 6) dims[6] = d7;

				metadata.putInt("NIFTI HEADER: data_type", data_type);
				metadata.putInt("NIFTI HEADER: bitpix", bitpix);

				double nifti_intent_param1 = readDouble(hdr, swapBytes);
				double nifti_intent_param2 = readDouble(hdr, swapBytes);
				double nifti_intent_param3 = readDouble(hdr, swapBytes);
				
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
				
				metadata.putDouble("NIFTI HEADER: axis 0 spacing", sd0);
				metadata.putDouble("NIFTI HEADER: axis 1 spacing", sd1);
				metadata.putDouble("NIFTI HEADER: axis 2 spacing", sd2);
				metadata.putDouble("NIFTI HEADER: axis 3 spacing", sd3);
				metadata.putDouble("NIFTI HEADER: axis 4 spacing", sd4);
				metadata.putDouble("NIFTI HEADER: axis 5 spacing", sd5);
				metadata.putDouble("NIFTI HEADER: axis 6 spacing", sd6);
				metadata.putDouble("NIFTI HEADER: axis 7 spacing", sd7);

				long vox_offset = readLong(hdr, swapBytes);
				
				scl_slope = readDouble(hdr, swapBytes);
				scl_inter = readDouble(hdr, swapBytes);
				
				metadata.putDouble("NIFTI HEADER: scale slope", scl_slope);
				metadata.putDouble("NIFTI HEADER: scale intercept", scl_inter);

				double cal_max = readDouble(hdr, swapBytes);
				double cal_min = readDouble(hdr, swapBytes);
				
				metadata.putDouble("NIFTI HEADER: calibration min", cal_max);
				metadata.putDouble("NIFTI HEADER: calibration max", cal_min);
				
				double slice_duration = readDouble(hdr, swapBytes);
				toffset = readDouble(hdr, swapBytes);

				metadata.putDouble("NIFTI HEADER: slice duration", slice_duration);
				metadata.putDouble("NIFTI HEADER: time offset", toffset);

				long slice_start = readLong(hdr, swapBytes);
				
				metadata.putLong("NIFTI HEADER: slice start", slice_start);

				long slice_end = readLong(hdr, swapBytes);

				metadata.putLong("NIFTI HEADER: slice end", slice_end);

				description = readString(hdr, 80);

				auxname = readString(hdr, 24);

				metadata.putString("NIFTI HEADER: description", description);
				metadata.putString("NIFTI HEADER: auxiliary file name", auxname);

				int qform_code = readInt(hdr, swapBytes);
				int sform_code = readInt(hdr, swapBytes);

				double quatern_b = readDouble(hdr, swapBytes);
				double quatern_c = readDouble(hdr, swapBytes);
				double quatern_d = readDouble(hdr, swapBytes);
				double qoffset_x = readDouble(hdr, swapBytes);
				double qoffset_y = readDouble(hdr, swapBytes);
				double qoffset_z = readDouble(hdr, swapBytes);

				metadata.putInt("NIFTI HEADER: qform code", qform_code);
				metadata.putInt("NIFTI HEADER: sform_code", sform_code);
				metadata.putDouble("NIFTI HEADER: quaternion b parameter", quatern_b);
				metadata.putDouble("NIFTI HEADER: quaternion c parameter", quatern_c);
				metadata.putDouble("NIFTI HEADER: quaternion d parameter", quatern_d);
				metadata.putDouble("NIFTI HEADER: quaternion z parameter", qoffset_x);
				metadata.putDouble("NIFTI HEADER: quaternion y parameter", qoffset_y);
				metadata.putDouble("NIFTI HEADER: quaternion z parameter", qoffset_z);

				// affine transform : row 0 = x, row 1 = y, row 2 = z
				
				sx = readDouble(hdr, swapBytes);
				x1 = readDouble(hdr, swapBytes);
				x2 = readDouble(hdr, swapBytes);
				x3 = readDouble(hdr, swapBytes);
				y0 = readDouble(hdr, swapBytes);
				sy = readDouble(hdr, swapBytes);
				y2 = readDouble(hdr, swapBytes);
				y3 = readDouble(hdr, swapBytes);
				z0 = readDouble(hdr, swapBytes);
				z1 = readDouble(hdr, swapBytes);
				sz = readDouble(hdr, swapBytes);
				z3 = readDouble(hdr, swapBytes);

				metadata.putDouble("NIFTI HEADER: affine x0 parameter", sx);
				metadata.putDouble("NIFTI HEADER: affine x1 parameter", x1);
				metadata.putDouble("NIFTI HEADER: affine x2 parameter", x2);
				metadata.putDouble("NIFTI HEADER: affine x3 parameter", x3);
				metadata.putDouble("NIFTI HEADER: affine y0 parameter", y0);
				metadata.putDouble("NIFTI HEADER: affine y1 parameter", sy);
				metadata.putDouble("NIFTI HEADER: affine y2 parameter", y2);
				metadata.putDouble("NIFTI HEADER: affine y3 parameter", y3);
				metadata.putDouble("NIFTI HEADER: affine z0 parameter", z0);
				metadata.putDouble("NIFTI HEADER: affine z1 parameter", z1);
				metadata.putDouble("NIFTI HEADER: affine z2 parameter", sz);
				metadata.putDouble("NIFTI HEADER: affine z3 parameter", z3);

				int slice_code = readInt(hdr, swapBytes);

				metadata.putInt("NIFTI HEADER: slice code", slice_code);
				
				int xyzt_units = readInt(hdr, swapBytes);

				metadata.putInt("NIFTI HEADER: xyzt units", xyzt_units);

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

				int nifti_intent_code = readInt(hdr, swapBytes);

				metadata.putInt("NIFTI HEADER: intent code", nifti_intent_code);
				metadata.putDouble("NIFTI HEADER: intent param 1", nifti_intent_param1);
				metadata.putDouble("NIFTI HEADER: intent param 2", nifti_intent_param2);
				metadata.putDouble("NIFTI HEADER: intent param 3", nifti_intent_param3);

				intent = readString(hdr, 16);

				metadata.putString("NIFTI HEADER: intent", intent);

				byte dim_info = readByte(hdr);
				
				metadata.putByte("NIFTI HEADER: dim info", dim_info);

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
				PlaneView planes = new PlaneView<>(data, 0, 1);
				long[] planeDims = new long[data.numDimensions()-2];
				for (int i = 0; i < planeDims.length; i++) {
					planeDims[i] = data.dimension(i+2);
				}
				IntegerIndex idx = new IntegerIndex(planeDims);
				SamplingIterator<IntegerIndex> itr = GridIterator.compute(planeDims);
				byte bucket = 0;
				while (itr.hasNext()) {
					itr.next(idx);
					for (int i = 0; i < planeDims.length; i++) {
						planes.setPositionValue(i, idx.get(i));
					}
					for (long y = 0; y < planes.d1(); y++) {
						for (long x = 0; x < planes.d0(); x++) {
							int bitNum = (int) (x % 8); 
							if (bitNum == 0) {
								bucket = readByte(values);
							}
							int val = (bucket & (1 << bitNum)) > 0 ? 1 : 0;
							pix.setV(val);
							// orient the axis data correctly
							long transformedX = x;
							if ((!is_analyze && sx < 0) || (is_analyze && sx > 0)) {
								transformedX = planes.d0() - 1 - x;
							}
							long transformedY = y;
							if (sy > 0) {
								transformedY = planes.d1() - 1 - y;
							}
							long savedZ = -400;
							if (data.numDimensions() > 2 && sz < 0) {
								savedZ = planes.getPositionValue(0);
								long transformedZ = data.dimension(2) - 1 - savedZ;
								planes.setPositionValue(0, transformedZ);
							}
							planes.set(transformedX, transformedY, pix);
							if (savedZ != -400) {
								planes.setPositionValue(0, savedZ);
							}
						}
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

				typeCast(type, data_type);
				
				data = DimensionedStorage.allocate(type, dims);
				PlaneView planes = new PlaneView<>(data, 0, 1);
				long[] planeDims = new long[data.numDimensions()-2];
				for (int i = 0; i < planeDims.length; i++) {
					planeDims[i] = data.dimension(i+2);
				}
				IntegerIndex idx = new IntegerIndex(planeDims);
				SamplingIterator<IntegerIndex> itr = GridIterator.compute(planeDims);
				while (itr.hasNext()) {
					itr.next(idx);
					for (int i = 0; i < planeDims.length; i++) {
						planes.setPositionValue(i, idx.get(i));
					}
					for (long y = 0; y < planes.d1(); y++) {
						for (long x = 0; x < planes.d0(); x++) {
							readValue(values, data_type, swapBytes, buf128, type);
							// orient the axis data correctly
							long transformedX = x;
							if ((!is_analyze && sx < 0) || (is_analyze && sx > 0)) {
								transformedX = planes.d0() - 1 - x;
							}
							long transformedY = y;
							if (sy > 0) {
								transformedY = planes.d1() - 1 - y;
							}
							long savedZ = -400;
							if (data.numDimensions() > 2 && sz < 0) {
								savedZ = planes.getPositionValue(0);
								long transformedZ = data.dimension(2) - 1 - savedZ;
								planes.setPositionValue(0, transformedZ);
							}
							planes.set(transformedX, transformedY, type);
							if (savedZ != -400) {
								planes.setPositionValue(0, savedZ);
							}
						}
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
			
			BigDecimal[] scales = new BigDecimal[(int)numD];
			BigDecimal[] offsets = new BigDecimal[(int)numD];

			if (numD > 0) {
				data.setAxisType(0, "x");
				data.setAxisUnit(0, units[0]);
				scales[0] = BigDecimal.valueOf(spacings[0]);
			}
			if (numD > 1) {
				data.setAxisType(1, "y");
				data.setAxisUnit(1, units[1]);
				scales[1] = BigDecimal.valueOf(spacings[1]);
			}
			if (numD > 2) {
				data.setAxisType(2, "z");
				data.setAxisUnit(2, units[2]);
				scales[2] = BigDecimal.valueOf(spacings[2]);
			}
			if (numD > 3) {
				data.setAxisType(3, "t");
				data.setAxisUnit(3, units[3]);
				scales[3] = BigDecimal.valueOf(spacings[3]);
				offsets[3] = BigDecimal.valueOf(toffset);
			}
			if (numD > 4) {
				data.setAxisType(4, "l");
				data.setAxisUnit(4, units[4]);
				scales[4] = BigDecimal.valueOf(spacings[4]);
			}
			if (numD > 5) {
				data.setAxisType(5, "m");
				data.setAxisUnit(5, units[5]);
				scales[5] = BigDecimal.valueOf(spacings[5]);
			}
			if (numD > 6) {
				data.setAxisType(6, "n");
				data.setAxisUnit(6, units[6]);
				scales[6] = BigDecimal.valueOf(spacings[6]);
			}

			CoordinateSpace cspace;
			if ((numD == 2) &&
					(
						(sx != 1 || x1 != 0 || x3 != 0) ||
						(y0 != 0 || sy != 1 || y3 != 0)
					)
				)
			{
				cspace = new Affine2dCoordinateSpace(
						BigDecimal.valueOf(sx), BigDecimal.valueOf(x1), BigDecimal.valueOf(x3),
						BigDecimal.valueOf(y0), BigDecimal.valueOf(sy), BigDecimal.valueOf(y3));
			}
			if ((numD == 3) &&
					(
						(sx != 1 || x1 != 0 || x2 != 0 || x3 != 0) ||
						(y0 != 0 || sy != 1 || y2 != 0 || y3 != 0) ||
						(z0 != 0 || z1 != 0 || sz != 1 || z3 != 0)
					)
				)
			{
				cspace = new Affine3dCoordinateSpace(
						BigDecimal.valueOf(sx), BigDecimal.valueOf(x1), BigDecimal.valueOf(x2), BigDecimal.valueOf(x3),
						BigDecimal.valueOf(y0), BigDecimal.valueOf(sy), BigDecimal.valueOf(y2), BigDecimal.valueOf(y3),
						BigDecimal.valueOf(z0), BigDecimal.valueOf(z1), BigDecimal.valueOf(sz), BigDecimal.valueOf(z3));
			}
			else {
				cspace = new LinearNdCoordinateSpace(scales, offsets);
			}
			data.setCoordinateSpace(cspace);
			
			data.metadata().merge(metadata);
			
			data.metadata().putString("auxiliary file name", auxname);
			
			data.metadata().putString("description", description);
			
			data.metadata().putString("intent", intent);
			
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
			System.out.println("In Nifti open " + e);
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
		case 1536: // float128
			return G.QUAD.construct();
		case 1792: // cfloat64
			return G.CDBL.construct();
		case 2048: // cfloat128
			return G.CQUAD.construct();
		case 2304: // rgba
			return G.ARGB.construct();
		default:
			throw new IllegalArgumentException("Unknown data type! "+data_type);
		}
	}

	private static void typeCast(Object type, int data_type) {
		
		switch (data_type) {
		case 1: // bit
			throw new IllegalArgumentException("bit types should never pass through this routine");
		case 2: // uint8
			typeUInt8 = (UnsignedInt8Member) type;
			break;
		case 4: // int16
			typeInt16 = (SignedInt16Member) type;
			break;
		case 8: // int32
			typeInt32 = (SignedInt32Member) type;
			break;
		case 16: // float32
			typeFlt = (Float32Member) type;
			break;
		case 32: // cfloat32
			typeCFlt = (ComplexFloat32Member) type;
			break;
		case 64: // float64
			typeDbl = (Float64Member) type;
			break;
		case 128: // rgb
			typeRgb = (RgbMember) type;
			break;
		case 256: // int8
			typeInt8 = (SignedInt8Member) type;
			break;
		case 512: // uint16
			typeUInt16 = (UnsignedInt16Member) type;
			break;
		case 768: // uint32
			typeUInt32 = (UnsignedInt32Member) type;
			break;
		case 1024: // int64
			typeInt64 = (SignedInt64Member) type;
			break;
		case 1280: // uint64
			typeUInt64 = (UnsignedInt64Member) type;
			break;
		case 1536: // float128
			typeQuad = (Float128Member) type;
			break;
		case 1792: // cfloat64
			typeCDbl = (ComplexFloat64Member) type;
			break;
		case 2048: // cfloat128
			typeCQuad = (ComplexFloat128Member) type;
			break;
		case 2304: // rgba
			typeArgb = (ArgbMember) type;
			break;
		default:
			throw new IllegalArgumentException("Unknown data type! "+data_type);
		}
	}

	private static void readValue(DataInputStream d, short data_type, boolean swapBytes, byte[] buf128, Allocatable type) throws IOException {
		
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
			typeUInt8.setV(tb);
			break;
		case 4: // int16
			ts = readShort(d, swapBytes);
			typeInt16.setV(ts);
			break;
		case 8: // int32
			ti = readInt(d, swapBytes);
			typeInt32.setV(ti);
			break;
		case 16: // float32
			tf = readFloat(d, swapBytes);
			typeFlt.setV(tf);
			break;
		case 32: // cfloat32
			tf = readFloat(d, swapBytes);
			typeCFlt.setR(tf);
			tf = readFloat(d, swapBytes);
			typeCFlt.setI(tf);
			break;
		case 64: // float64
			td = readDouble(d, swapBytes);
			typeDbl.setV(td);
			break;
		case 128: // rgb
			tb = readByte(d);
			typeRgb.setR(tb);
			tb = readByte(d);
			typeRgb.setG(tb);
			tb = readByte(d);
			typeRgb.setB(tb);
			break;
		case 256: // int8
			tb = readByte(d);
			typeInt8.setV(tb);
			break;
		case 512: // uint16
			ts = readShort(d, swapBytes);
			typeUInt16.setV(ts);
			break;
		case 768: // uint32
			ti = readInt(d, swapBytes);
			typeUInt32.setV(ti);
			break;
		case 1024: // int64
			tl = readLong(d, swapBytes);
			typeInt64.setV(tl);
			break;
		case 1280: // uint64
			tl = readLong(d, swapBytes);
			typeUInt64.setV(tl);
			break;
		case 1536: // float128
			readFloat128(d, swapBytes, buf128, typeQuad);
			break;
		case 1792: // cfloat64
			td = readDouble(d, swapBytes);
			typeCDbl.setR(td);
			td = readDouble(d, swapBytes);
			typeCDbl.setI(td);
			break;
		case 2048: // cfloat128
			readFloat128(d, swapBytes, buf128, typeCQuad.r());
			readFloat128(d, swapBytes, buf128, typeCQuad.i());
			break;
		case 2304: // rgba
			tb = readByte(d);
			typeArgb.setR(tb);
			tb = readByte(d);
			typeArgb.setG(tb);
			tb = readByte(d);
			typeArgb.setB(tb);
			tb = readByte(d);
			typeArgb.setA(tb);
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
		else if (type instanceof Float128Member) {
			bundle.mergeFlt128(data);
		}
		else if (type instanceof ComplexFloat128Member) {
			bundle.mergeComplexFlt128(data);
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
		long[] dims = DataSourceUtils.dimensions(data);
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
		else if (type instanceof Float128Member) {
			returnAlg = G.QUAD;
			returnDs = data;
			Float128Member scaled = G.QUAD.construct();
			Float128Member translation = G.QUAD.construct();
			Procedure2<Float128Member,Float128Member> proc = new Procedure2<Float128Member,Float128Member>() {
				@Override
				public void call(Float128Member a, Float128Member b) {
					G.QUAD.scaleByDouble().call(slope, a, scaled);
					translation.setV(BigDecimal.valueOf(intercept));
					G.QUAD.add().call(scaled, translation, b);
				}
			};
			Transform2.compute(G.QUAD, G.QUAD, proc, (IndexedDataSource<Float128Member>) data.rawData(), (IndexedDataSource<Float128Member>) returnDs.rawData());
		}
		else if (type instanceof ComplexFloat128Member) {
			returnAlg = G.CQUAD;
			returnDs = data;
			ComplexFloat128Member scaled = G.CQUAD.construct();
			ComplexFloat128Member translation = G.CQUAD.construct();
			Procedure2<ComplexFloat128Member,ComplexFloat128Member> proc = new Procedure2<ComplexFloat128Member,ComplexFloat128Member>() {
				@Override
				public void call(ComplexFloat128Member a, ComplexFloat128Member b) {
					G.CQUAD.scaleByDouble().call(slope, a, scaled);
					translation.setR(BigDecimal.valueOf(intercept));
					G.CQUAD.add().call(scaled, translation, b);
				}
			};
			Transform2.compute(G.CQUAD, G.CQUAD, proc, (IndexedDataSource<ComplexFloat128Member>) data.rawData(), (IndexedDataSource<ComplexFloat128Member>) returnDs.rawData());
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
	
	private static void readFloat128(DataInputStream str, boolean swapBytes, byte[] buffer, Float128Member val) throws IOException {
		
		if (buffer.length != 16)
			throw new IllegalArgumentException("byte buffer has incorrect size");

		if (swapBytes) {
			for (int i = 15; i >= 0; i--) {
				buffer[i] = str.readByte();
			}
		}
		else {
			for (int i = 0; i < 16; i++) {
				buffer[i] = str.readByte();
			}
		}
		
		val.fromByteArray(buffer, 0);
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
