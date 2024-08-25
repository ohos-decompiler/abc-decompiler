package jadx.plugins.input.dex;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import me.yricky.oh.abcd.AbcBuf;
import me.yricky.oh.abcd.cfm.AbcClass;

import jadx.api.plugins.input.data.IClassData;
import jadx.plugins.input.dex.sections.DexClassData;
import jadx.plugins.input.dex.sections.DexHeader;
import jadx.plugins.input.dex.sections.SectionReader;

public class DexReader {
	private final int uniqId;
	private final String inputFileName;
	private final ByteBuffer buf;
	private final DexHeader header;

	public AbcBuf getAbc() {
		return abc;
	}

	public void setAbc(AbcBuf abc) {
		this.abc = abc;
	}

	public AbcBuf abc;

	public DexReader(int uniqId, String inputFileName, byte[] content) {
		this.uniqId = uniqId;
		this.inputFileName = inputFileName;
		this.buf = ByteBuffer.wrap(content);
		this.header = new DexHeader(new SectionReader(this, 0));
	}

	public void visitClasses(Consumer<IClassData> consumer) {
		if (abc == null) {
			return;
		}
		abc.getClasses().forEach((key, value) -> {
			AbcClass abcClass = value instanceof AbcClass ? (AbcClass) value : null;

			if (abcClass != null) {
				DexClassData classData = new DexClassData(new SectionReader(this, 0), null);
				classData.abcClass = abcClass;
				consumer.accept(classData);
			}

		});
	}

	public ByteBuffer getBuf() {
		return buf;
	}

	public DexHeader getHeader() {
		return header;
	}

	public String getInputFileName() {
		return inputFileName;
	}

	public int getUniqId() {
		return uniqId;
	}

	@Override
	public String toString() {
		return inputFileName;
	}
}
