package jadx.plugins.input.dex.sections;

public class DexHeader {
	private final String version;
	private final int classDefsSize;
	private final int classDefsOff;
	private final int stringIdsOff = 0;
	private final int typeIdsOff = 0;
	private final int typeIdsSize = 0;
	private final int fieldIdsSize = 0;
	private final int fieldIdsOff = 0;
	private final int protoIdsSize = 0;
	private final int protoIdsOff = 0;
	private final int methodIdsOff = 0;
	private final int methodIdsSize = 0;

	private int callSiteOff;
	private int methodHandleOff;

	public DexHeader(SectionReader buf) {
		version = String.valueOf(0);
		classDefsSize = 0;
		classDefsOff = 0;
	}

	private void readMapList(SectionReader buf, int mapListOff) {
		buf.absPos(mapListOff);
		int size = buf.readInt();
		for (int i = 0; i < size; i++) {
			int type = buf.readUShort();
			buf.skip(6);
			int offset = buf.readInt();

			switch (type) {
				case 0x0007:
					callSiteOff = offset;
					break;

				case 0x0008:
					methodHandleOff = offset;
					break;
			}
		}
	}

	public String getVersion() {
		return version;
	}

	public int getClassDefsSize() {
		return classDefsSize;
	}

	public int getClassDefsOff() {
		return classDefsOff;
	}

	public int getStringIdsOff() {
		return stringIdsOff;
	}

	public int getTypeIdsOff() {
		return typeIdsOff;
	}

	public int getTypeIdsSize() {
		return typeIdsSize;
	}

	public int getFieldIdsSize() {
		return fieldIdsSize;
	}

	public int getFieldIdsOff() {
		return fieldIdsOff;
	}

	public int getProtoIdsSize() {
		return protoIdsSize;
	}

	public int getProtoIdsOff() {
		return protoIdsOff;
	}

	public int getMethodIdsOff() {
		return methodIdsOff;
	}

	public int getMethodIdsSize() {
		return methodIdsSize;
	}

	public int getCallSiteOff() {
		return callSiteOff;
	}

	public int getMethodHandleOff() {
		return methodHandleOff;
	}
}
