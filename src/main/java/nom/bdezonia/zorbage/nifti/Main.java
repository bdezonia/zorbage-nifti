package nom.bdezonia.zorbage.nifti;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
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
			FileInputStream in = new FileInputStream(file);
			
			DataInputStream d = new DataInputStream(in);
			
			int headerSize = d.readInt();
			
			if (headerSize == 348) {
				
				// possibly nifti 1
				
				System.out.println("Possibly NIFTI 1");

				for (int i = 0; i < 35; i++) {
					d.readByte();
				}
				
				byte dim_info = d.readByte();

				// pixel dimensions
				
				short numD = d.readShort();
				short d1 = d.readShort();
				short d2 = d.readShort();
				short d3 = d.readShort();
				short d4 = d.readShort();
				short d5 = d.readShort();
				short d6 = d.readShort();
				short d7 = d.readShort();
				
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
				
				float intent_p1 = d.readFloat();
				float intent_p2 = d.readFloat();
				float intent_p3 = d.readFloat();
				
				short nifti_intent_code = d.readShort();
				short data_type = d.readShort();
				short bitpix = d.readShort();
				short slice_start = d.readShort();
				
				System.out.println("data type: "+data_type+" bitpix "+bitpix);
				
				// pixel spacings
				
				float sd1 = d.readFloat();
				float sd2 = d.readFloat();
				float sd3 = d.readFloat();
				float sd4 = d.readFloat();
				float sd5 = d.readFloat();
				float sd6 = d.readFloat();
				float sd7 = d.readFloat();
				float sd8 = d.readFloat();

				float vox_offset = d.readFloat();
				float scl_slope = d.readFloat();
				float scl_inter = d.readFloat();

				short slice_end = d.readShort();
				byte slice_code = d.readByte();
				byte xyzt_units = d.readByte();
				
				float cal_max = d.readFloat();
				float cal_min = d.readFloat();
				float slice_duration = d.readFloat();
				float toffset = d.readFloat();

				for (int i = 0; i < 2; i++) {
					d.readInt();
				}

				for (int i = 0; i < 80; i++) {
					// TODO: description string: build it
					d.readByte();
				}

				for (int i = 0; i < 24; i++) {
					// TODO: aux filename string: build it
					d.readByte();
				}

				short qform_code = d.readShort();
				short sform_code = d.readShort();

				float quatern_b = d.readFloat();
				float quatern_c = d.readFloat();
				float quatern_d = d.readFloat();
				float qoffset_x = d.readFloat();
				float qoffset_y = d.readFloat();
				float qoffset_z = d.readFloat();

				// affine transform : row 0 = x, row 1 = y, row 2 = z
				
				float x0 = d.readFloat();
				float x1 = d.readFloat();
				float x2 = d.readFloat();
				float x3 = d.readFloat();
				float y0 = d.readFloat();
				float y1 = d.readFloat();
				float y2 = d.readFloat();
				float y3 = d.readFloat();
				float z0 = d.readFloat();
				float z1 = d.readFloat();
				float z2 = d.readFloat();
				float z3 = d.readFloat();

				for (int i = 0; i < 16; i++) {
					// TODO: intent_name string: build it
					d.readByte();
				}

				byte magic0 = d.readByte();
				byte magic1 = d.readByte();
				byte magic2 = d.readByte();
				byte magic3 = d.readByte();

				if (magic0 == 'n' && magic1 == 'i' && magic2 == '1' && magic3 == 0)
					System.out.println("VALID and of type 1a");
				else if (magic0 == 'n' && magic1 == '+' && magic2 == '1' && magic3 == 0)
					System.out.println("VALID and of type 1b");
				else
					System.out.println("INVALID type 1 header");

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
				case 2304: // argb
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
						tb = d.readByte();
						((UnsignedInt8Member) type).setV(tb);
						break;
					case 4: // int16
						ts = d.readShort();
						((SignedInt16Member) type).setV(ts);
						break;
					case 8: // int32
						ti = d.readInt();
						((SignedInt32Member) type).setV(ti);
						break;
					case 16: // float32
						tf = d.readFloat();
						((Float32Member) type).setV(tf);
						break;
					case 32: // cfloat32
						tf = d.readFloat();
						((ComplexFloat32Member) type).setR(tf);
						tf = d.readFloat();
						((ComplexFloat32Member) type).setI(tf);
						break;
					case 64: // float64
						td = d.readDouble();
						((Float64Member) type).setV(td);
						break;
					case 128: // rgb
						tb = d.readByte();
						((RgbMember) type).setR(tb);
						tb = d.readByte();
						((RgbMember) type).setG(tb);
						tb = d.readByte();
						((RgbMember) type).setB(tb);
						break;
					case 256: // int8
						tb = d.readByte();
						((SignedInt8Member) type).setV(tb);
						break;
					case 512: // uint16
						ts = d.readShort();
						((UnsignedInt16Member) type).setV(ts);
						break;
					case 768: // uint32
						ti = d.readInt();
						((UnsignedInt32Member) type).setV(ti);
						break;
					case 1024: // int64
						tl = d.readLong();
						((SignedInt64Member) type).setV(tl);
						break;
					case 1280: // uint64
						tl = d.readLong();
						((UnsignedInt64Member) type).setV(tl);
						break;
					case 1536: // float128 : treat as highprec
						// TODO
						System.out.println("skipping float 128");
						break;
					case 1792: // cfloat64
						td = d.readDouble();
						((ComplexFloat64Member) type).setR(td);
						td = d.readDouble();
						((ComplexFloat64Member) type).setI(td);
						break;
					case 2048: // cfloat128 : treat as highprec
						// TODO
						System.out.println("skipping complex float 128");
						break;
					case 2304: // argb
						tb = d.readByte();
						((ArgbMember) type).setR(tb);
						tb = d.readByte();
						((ArgbMember) type).setG(tb);
						tb = d.readByte();
						((ArgbMember) type).setB(tb);
						tb = d.readByte();
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
				case 2304: // argb
					bundle.mergeArgb(data);
					break;
				default:
					System.out.println("Unknown data type! "+data_type);
				}
			}
			else if (headerSize == 540) {
				
				// possibly nifti 2
				
				System.out.println("Possibly NIFTI 2");

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
}
