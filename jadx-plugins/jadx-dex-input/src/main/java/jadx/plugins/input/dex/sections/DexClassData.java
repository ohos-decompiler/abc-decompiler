package jadx.plugins.input.dex.sections;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.yricky.oh.abcd.cfm.AbcClass;
import me.yricky.oh.abcd.cfm.AbcField;
import me.yricky.oh.abcd.cfm.AbcMethod;
import me.yricky.oh.abcd.cfm.ClassItem;

import jadx.api.plugins.input.data.IClassData;
import jadx.api.plugins.input.data.IFieldData;
import jadx.api.plugins.input.data.IMethodData;
import jadx.api.plugins.input.data.ISeqConsumer;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.annotations.IAnnotation;
import jadx.api.plugins.input.data.attributes.IJadxAttribute;
import jadx.api.plugins.input.data.attributes.types.SourceFileAttr;
import jadx.plugins.input.dex.sections.annotations.AnnotationsParser;
import jadx.plugins.input.dex.utils.SmaliUtils;

public class DexClassData implements IClassData {
	private static final Logger LOG = LoggerFactory.getLogger(DexClassData.class);
	public static final int SIZE = 8 * 4;

	private final SectionReader in;
	private final AnnotationsParser annotationsParser;
	public AbcClass abcClass;

	public DexClassData(SectionReader sectionReader, AnnotationsParser annotationsParser) {
		this.in = sectionReader;
		this.annotationsParser = annotationsParser;
	}

	public DexClassData(SectionReader sectionReader, AnnotationsParser annotationsParser, AbcClass abcClass) {
		this.in = sectionReader;
		this.annotationsParser = annotationsParser;
		this.abcClass = abcClass;
	}

	@Override
	public IClassData copy() {
		return new DexClassData(in, null, abcClass);
	}

	@Override
	public String getType() {
		return abcClass.getName().replace("/", ".").replace("@", ".");
	}

	public class AccessFlags {
		private final int value;

		public AccessFlags(int value) {
			this.value = value;
		}

		public boolean isPublic() {
			return (value & 0x0001) != 0;
		}

		public boolean isFinal() {
			return (value & 0x0010) != 0;
		}

		public boolean isInterface() {
			return (value & 0x0200) != 0;
		}

		public boolean isAbstract() {
			return (value & 0x0400) != 0;
		}

		public boolean isSynthetic() {
			return (value & 0x1000) != 0;
		}

		public boolean isAnnotation() {
			return (value & 0x2000) != 0;
		}

		public boolean isEnum() {
			return (value & 0x4000) != 0;
		}
	}

	@Override
	public int getAccessFlags() {
		// AccessFlags accessFlags = new AccessFlags(abcClass.getAccessFlag());
		return abcClass.getAccessFlag();
	}

	@Nullable
	@Override
	public String getSuperType() {
		ClassItem superClass = abcClass.getSuperClass();
		if (superClass == null) {
			return "Object";
		}
		return superClass.getName();
	}

	@Override
	public List<String> getInterfacesTypes() {
		return Collections.emptyList();
	}

	@Nullable
	private String getSourceFile() {
		return null;
	}

	@Override
	public String getInputFileName() {
		return in.getDexReader().getInputFileName();
	}

	public int getAnnotationsOff() {
		return in.pos(5 * 4).readInt();
	}

	public int getClassDataOff() {
		return in.pos(6 * 4).readInt();
	}

	public int getStaticValuesOff() {
		return in.pos(7 * 4).readInt();
	}

	@Override
	public void visitFieldsAndMethods(ISeqConsumer<IFieldData> fieldConsumer, ISeqConsumer<IMethodData> mthConsumer) {

		int fieldsCount = abcClass.getNumFields();
		int mthCount = abcClass.getNumMethods();

		fieldConsumer.init(fieldsCount);
		mthConsumer.init(mthCount);

		visitFields(fieldConsumer);
		visitMethods(mthConsumer);
	}

	private void visitFields(Consumer<IFieldData> fieldConsumer) {
		DexFieldData fieldData = new DexFieldData(annotationsParser);
		fieldData.setParentClassType(abcClass.getName());

		List<AbcField> fields = abcClass.getFields();
		for (AbcField field : fields) {
			fieldData.setName(field.getName());
			fieldData.setType(field.getType().getName());
			fieldData.setAccessFlags(jadx.api.plugins.input.data.AccessFlags.PUBLIC);
			fieldData.setConstValue(null);
			fieldConsumer.accept(fieldData);
		}
	}

	private void readFields(Consumer<IFieldData> fieldConsumer, SectionReader data, DexFieldData fieldData, int count,
			Map<Integer, Integer> annOffsetMap, boolean staticFields) {
		List<EncodedValue> constValues = staticFields ? getStaticFieldInitValues(data.copy()) : null;
		int fieldId = 0;
		for (int i = 0; i < count; i++) {
			fieldId += data.readUleb128();
			int accFlags = data.readUleb128();
			in.fillFieldData(fieldData, fieldId);
			fieldData.setAccessFlags(accFlags);
			fieldData.setAnnotationsOffset(getOffsetFromMap(fieldId, annOffsetMap));
			fieldData.setConstValue(staticFields && i < constValues.size() ? constValues.get(i) : null);
			fieldConsumer.accept(fieldData);
		}
	}

	private void visitMethods(Consumer<IMethodData> mthConsumer) {
		DexMethodData methodData = new DexMethodData(annotationsParser);
		methodData.setMethodRef(new DexMethodRef());

		List<AbcMethod> mths = abcClass.getMethods();

		int idx = 0;
		for (AbcMethod mth : mths) {
			DexMethodRef methodRef = methodData.getMethodRef();
			methodRef.reset();
			methodRef.initUniqId(in.getDexReader(), mth.getOffset());
			idx += 1;

			methodRef.setDexIdx(in.getDexReader().getUniqId());
			methodRef.setAbcMethod(mth);

			methodData.setAccessFlags(jadx.api.plugins.input.data.AccessFlags.PUBLIC);

			DexCodeReader dexCodeReader = new DexCodeReader(in.copy(), mth);
			methodData.setCodeReader(dexCodeReader);

			mthConsumer.accept(methodData);
		}
	}

	private void readMethods(Consumer<IMethodData> mthConsumer, SectionReader data, DexMethodData methodData, int count,
			Map<Integer, Integer> annotationOffsetMap, Map<Integer, Integer> paramsAnnOffsetMap) {
		DexCodeReader dexCodeReader = new DexCodeReader(in.copy());
		int mthIdx = 0;
		for (int i = 0; i < count; i++) {
			mthIdx += data.readUleb128();
			int accFlags = data.readUleb128();
			int codeOff = data.readUleb128();

			DexMethodRef methodRef = methodData.getMethodRef();
			methodRef.reset();
			in.initMethodRef(mthIdx, methodRef);
			methodData.setAccessFlags(accFlags);
			if (codeOff == 0) {
				methodData.setCodeReader(null);
			} else {
				dexCodeReader.setMthId(mthIdx);
				dexCodeReader.setOffset(codeOff);
				methodData.setCodeReader(dexCodeReader);
			}
			methodData.setAnnotationsOffset(getOffsetFromMap(mthIdx, annotationOffsetMap));
			methodData.setParamAnnotationsOffset(getOffsetFromMap(mthIdx, paramsAnnOffsetMap));
			mthConsumer.accept(methodData);
		}
	}

	private static int getOffsetFromMap(int idx, Map<Integer, Integer> annOffsetMap) {
		Integer offset = annOffsetMap.get(idx);
		return offset != null ? offset : 0;
	}

	private List<EncodedValue> getStaticFieldInitValues(SectionReader reader) {
		int staticValuesOff = getStaticValuesOff();
		if (staticValuesOff == 0) {
			return Collections.emptyList();
		}
		reader.absPos(staticValuesOff);
		return annotationsParser.parseEncodedArray(reader);
	}

	private List<IAnnotation> getAnnotations() {
		annotationsParser.setOffset(getAnnotationsOff());
		return annotationsParser.readClassAnnotations();
	}

	@Override
	public List<IJadxAttribute> getAttributes() {
		List<IJadxAttribute> list = new ArrayList<>();
		String sourceFile = getSourceFile();
		if (sourceFile != null && !sourceFile.isEmpty()) {
			list.add(new SourceFileAttr(sourceFile));
		}
		// DexAnnotationsConvert.forClass(getType(), list, getAnnotations());
		return list;
	}

	public int getClassDefOffset() {
		return in.pos(0).getAbsPos();
	}

	@Override
	public String getDisassembledCode() {
		byte[] dexBuf = in.getDexReader().getBuf().array();
		return SmaliUtils.getSmaliCode(dexBuf, getClassDefOffset());
	}

	@Override
	public String toString() {
		return getType();
	}
}
