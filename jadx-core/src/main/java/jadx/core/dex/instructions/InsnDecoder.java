package jadx.core.dex.instructions;

import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kotlin.Pair;
import me.yricky.oh.abcd.cfm.AbcClass;
import me.yricky.oh.abcd.cfm.AbcMethod;
import me.yricky.oh.abcd.cfm.FieldType;
import me.yricky.oh.abcd.cfm.MethodItem;
import me.yricky.oh.abcd.isa.Asm;
import me.yricky.oh.abcd.isa.InstFmt;
import me.yricky.oh.abcd.literal.LiteralArray;
import me.yricky.oh.abcd.literal.ModuleLiteralArray;

import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IMethodProto;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.insns.InsnData;
import jadx.api.plugins.input.insns.custom.ICustomPayload;
import jadx.api.plugins.input.insns.custom.ISwitchPayload;
import jadx.core.Consts;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.JadxError;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.LiteralArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.Utils;
import jadx.core.utils.exceptions.DecodeException;
import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.core.utils.input.InsnDataUtils;

public class InsnDecoder {
	private static final Logger LOG = LoggerFactory.getLogger(InsnDecoder.class);

	private final MethodNode method;
	private final RootNode root;
	public InsnNode[] rinstructions;

	public InsnDecoder(MethodNode mthNode) {
		this.method = mthNode;
		this.root = method.root();
	}

	public InsnNode[] process(ICodeReader codeReader) {
		InsnNode[] instructions = new InsnNode[codeReader.getUnitsCount()];
		rinstructions = instructions;
		codeReader.visitInstructions(rawInsn -> {
			int offset = rawInsn.getOffset();
			InsnNode insn;
			try {
				insn = decode(rawInsn);
			} catch (Exception e) {
				method.addError("Failed to decode insn: " + rawInsn + ", method: " + method, e);
				insn = new InsnNode(InsnType.NOP, 0);
				insn.addAttr(AType.JADX_ERROR, new JadxError("decode failed: " + e.getMessage(), e));
			}
			insn.setOffset(offset);
			instructions[offset] = insn;
		});
		return instructions;
	}

	public String getAbcString(Asm.AsmItem asmItem, int strIdx) {
		int stringOff = asmItem.getOpUnits().get(strIdx).intValue();
		int x2 = asmItem.getAsm().getCode().getMethod().getRegion().getMslIndex().get(stringOff);
		Pair<String, Integer> strItem = asmItem.getAsm().getCode().getAbc().stringItem(x2);
		return strItem.getFirst();
	}

	public String searchMethod(InsnData insn) {
		int offset = insn.getOffset();
		while (offset >= 0) {
			if (rinstructions[offset] == null) {
				offset--;
				continue;
			}

			if (rinstructions[offset].getType() == InsnType.IGET) {
				return ((IndexInsnNode) rinstructions[offset]).getFieldName();
			}
			offset--;
		}
		return "Dummy";
	}

	public static int getUnsignedInt(short x) {
		return x & (-1 >>> 16);
	}

	public static AbcClass getAbcClassByInsn(InsnData insn) {
		return (AbcClass) ((FieldType.ClassType) insn.getAsmItem().getAsm().getCode().getMethod().getClazz()).getClazz();
	}

	@NotNull
	protected InsnNode decode(InsnData insn) throws DecodeException {

		Asm.AsmItem asmItem = insn.getAsmItem();

		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accIndex = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		int nOp = asmItem.getOpUnits().get(0).shortValue() & 0xff;
		switch (nOp) {
			case 0x44: // mov vA, vB
			case 0x45:
				RegisterArg dst = InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW);
				RegisterArg src = InsnArg.reg(asmItem.getOpUnits().get(2).intValue(), ArgType.NARROW);
				return insn(InsnType.MOVE, dst, src);
			case 0x62:
				RegisterArg acc = InsnArg.reg(accIndex, ArgType.NARROW);
				LiteralArg narrowLitArg = InsnArg.lit(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW);
				return insn(InsnType.CONST, acc, narrowLitArg);

			case 0:
				return insn(InsnType.CONST, InsnArg.reg(accIndex, ArgType.NARROW), InsnArg.lit(0, ArgType.NARROW));

			case 0x01: {
				LiteralArg litArg = InsnArg.lit(0, ArgType.OBJECT);
				return insn(InsnType.CONST, InsnArg.reg(accIndex, ArgType.OBJECT), litArg);
			}

			case 0x02: {
				LiteralArg litArg = InsnArg.lit(1, ArgType.BOOLEAN);
				return insn(InsnType.CONST, InsnArg.reg(accIndex, ArgType.BOOLEAN), litArg);
			}

			case 0x03: {
				LiteralArg litArg = InsnArg.lit(0, ArgType.BOOLEAN);
				return insn(InsnType.CONST, InsnArg.reg(accIndex, ArgType.BOOLEAN), litArg);
			}

			case 0x63: {
				List<InstFmt> formats = asmItem.getIns().getFormat();
				LiteralArg litArg = InsnArg.lit(asmItem.getOpUnits().get(1).longValue(), ArgType.BOOLEAN);
				return insn(InsnType.CONST, InsnArg.reg(accIndex, ArgType.FLOAT), litArg);
			}

			case 0x6d: {
				ArgType clsType = ArgType.object("Global");
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return constClsInsn;
			}

			case 0x61:
				return insn(InsnType.MOVE, InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW),
						InsnArg.reg(accIndex, ArgType.NARROW));

			case 0x60:
				return insn(InsnType.MOVE, InsnArg.reg(accIndex, ArgType.NARROW),
						InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW));

			case 0x13:
				return cmp(accIndex, asmItem.getOpUnits().get(2).intValue(), accIndex, InsnType.CMP_G, ArgType.NARROW);

			case 0x11:
				return cmp(accIndex, asmItem.getOpUnits().get(2).intValue(), accIndex, InsnType.CMP_L, ArgType.NARROW);

			case 0xf:
			case 0x28: // stricteq
				return cmp(accIndex, asmItem.getOpUnits().get(2).intValue(), accIndex, InsnType.CMP_EQ, ArgType.NARROW);

			case 0x10:
			case 0x27: // strictnoteq
				return cmp(accIndex, asmItem.getOpUnits().get(2).intValue(), accIndex, InsnType.CMP_NE, ArgType.NARROW);

			case 0x14:
				return cmp(accIndex, asmItem.getOpUnits().get(2).intValue(), accIndex, InsnType.CMP_GE, ArgType.NARROW);

			case 0x12:
				return cmp(accIndex, asmItem.getOpUnits().get(2).intValue(), accIndex, InsnType.CMP_LE, ArgType.NARROW);

			case 0x22:
				return dec(insn);
			case 0x21:
				return inc(insn);

			case 0x0a:
				return arith2(insn, ArithOp.ADD);
			case 0x0b:
				return arith2(insn, ArithOp.SUB);
			case 0x0c:
				return arith2(insn, ArithOp.MUL);
			case 0x0d:
				return arith2(insn, ArithOp.DIV);
			case 0x0e:
				return arith2(insn, ArithOp.REM);

			case 0x15:
				return arith2(insn, ArithOp.SHL);
			case 0x16:
			case 0x17:
				return arith2(insn, ArithOp.SHR);

			case 0x18:
				return arith2(insn, ArithOp.AND);
			case 0x19:
				return arith2(insn, ArithOp.OR);
			case 0x1a:
				return arith2(insn, ArithOp.XOR);

			// todo: A ** acc
			case 0x1b:
				return arith2(insn, ArithOp.EXP);

			// isfalse
			case 0x23:
			case 0x24: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, nOp == 0x24 ? "isfalse" : "istrue");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addReg(accIndex, ArgType.INT);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.INT));
				return invoke;
			}

			// asyncfunctionenter
			case 0xae: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 0, "asyncfunctionenter");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 0);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// asyncfunctionresolve
			case 0xcd: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "asyncfunctionresolve");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addReg(accIndex, ArgType.OBJECT);
				invoke.addReg(asmItem.getOpUnits().get(1).intValue(), ArgType.OBJECT);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// asyncfunctionreject
			case 0xce: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "asyncfunctionreject");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addReg(accIndex, ArgType.OBJECT);
				invoke.addReg(asmItem.getOpUnits().get(1).intValue(), ArgType.OBJECT);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			case 0x4f: // jeqz
			case 0x50:
			case 0x9a:
				return new IfNode(asmItem.getOpUnits().get(1).intValue() + asmItem.getCodeOffset(), accIndex, IfOp.NE);

			case 0x9b:
			case 0x9c:
			case 0x51: // jnez
				return new IfNode(asmItem.getOpUnits().get(1).intValue() + asmItem.getCodeOffset(), accIndex, IfOp.NE);

			case 0x4d:
				return new GotoNode(asmItem.getOpUnits().get(1).intValue() + asmItem.getCodeOffset());
			case 0x4e:
				return new GotoNode(asmItem.getOpUnits().get(1).intValue() + asmItem.getCodeOffset());

			case 0x64:
			case 0x65:
				return insn(InsnType.RETURN,
						null,
						InsnArg.reg(accIndex, method.getReturnType()));
			case 0x3e:
				InsnNode constStrInsn = new ConstStringNode(getAbcString(asmItem, 1));
				constStrInsn.setResult(InsnArg.reg(accIndex, ArgType.STRING));
				return constStrInsn;

			case 0x42:
				FieldInfo igetFld = FieldInfo.fromAsm(root, asmItem, getAbcString(asmItem, 2));
				InsnNode igetInsn = new IndexInsnNode(InsnType.IGET, igetFld, 1);
				igetInsn.setResult(InsnArg.reg(accIndex, tryResolveFieldType(igetFld)));
				igetInsn.addArg(InsnArg.reg(accIndex, igetFld.getDeclClass().getType()));
				return igetInsn;

			case 0x43:
				FieldInfo iputFld2 = FieldInfo.fromAsm(root, asmItem, getAbcString(asmItem, 2));
				InsnNode iputInsn2 = new IndexInsnNode(InsnType.IPUT, iputFld2, 2);
				iputInsn2.addArg(InsnArg.reg(accIndex, tryResolveFieldType(iputFld2)));
				iputInsn2.addArg(InsnArg.reg(asmItem.getOpUnits().get(3).intValue(), iputFld2.getDeclClass().getType()));
				return iputInsn2;

			case 0x91:
				FieldInfo iputFld = FieldInfo.fromAsm(root, asmItem, getAbcString(asmItem, 2));
				InsnNode iputInsn = new IndexInsnNode(InsnType.IPUT, iputFld, 2);
				iputInsn.addArg(InsnArg.reg(accIndex, tryResolveFieldType(iputFld)));
				iputInsn.addArg(InsnArg.reg(asmItem.getOpUnits().get(3).intValue(), iputFld.getDeclClass().getType()));
				return iputInsn;

			case 0x2e: // callthis1
				return callthisN(insn, 1);

			case 0x2d:
				return callthisN(insn, 0);

			case 0x2f: // callthis2
				return callthisN(insn, 2);

			case 0x31:
				return callThisRange(insn);

			case 0x30: // callthis3
				return callthisN(insn, 3);

			case 0x73:
				return callrange(insn);

			case 0xcf: // copyrestargs
				return copyrestargs(insn);
			case 0xb9: // supercallspread
				return supercallspread(insn);

			// defineclasswithbuffer
			case 0x35: {
				List<InstFmt> formats = asmItem.getIns().getFormat();
				MethodItem targetMth = ((InstFmt.MId) formats.get(2)).getMethod(asmItem);
				LiteralArray la = ((InstFmt.LId) formats.get(3)).getLA(asmItem);
				int cnt = asmItem.getOpUnits().get(4).intValue();
				int parentReg = asmItem.getOpUnits().get(5).intValue();

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 5, targetMth.getName());

				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.VIRTUAL, 5);
				invoke.addReg(parentReg, ArgType.OBJECT); // object
				invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object2"))));
				invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object3"))));
				invoke.addReg(parentReg, ArgType.OBJECT);

				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(la.toString(), false)));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			case 0x06: {

				List<InstFmt> formats = asmItem.getIns().getFormat();
				LiteralArray la = ((InstFmt.LId) formats.get(2)).getLA(asmItem);

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "createarraywithbuffer");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(la.toString(), false)));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			case 0x05:
				return makeNewArray(accIndex);

			case 0x07: {
				List<InstFmt> formats = asmItem.getIns().getFormat();
				LiteralArray la = ((InstFmt.LId) formats.get(2)).getLA(asmItem);

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "createobjectwithbuffer");

				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(la.toString(), false)));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// newlexenvwithname
			case 0xb6: {

				List<InstFmt> formats = asmItem.getIns().getFormat();
				LiteralArray la = ((InstFmt.LId) formats.get(2)).getLA(asmItem);

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "newlexenvwithname");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(la.toString(), false)));
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(1).intValue())));

				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// stmodulevar
			case 0x7c: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "stmodulevar");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addReg(accIndex, ArgType.OBJECT);
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(1).intValue())));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// stlexvar
			case 0x8b:
			case 0x3d: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "stlexvar");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(1).intValue())));
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(2).intValue())));
				invoke.addReg(accIndex, ArgType.OBJECT);
				return invoke;
			}

			// definefunc
			case 0x74:
			case 0x33: {

				List<InstFmt> formats = asmItem.getIns().getFormat();
				MethodItem targetMth = ((InstFmt.MId) formats.get(2)).getMethod(asmItem);
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "definefunc");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(targetMth.getName(), false)));
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(3).intValue())));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// definemethod
			case 0x34: {

				List<InstFmt> formats = asmItem.getIns().getFormat();
				MethodItem targetMth = ((InstFmt.MId) formats.get(2)).getMethod(asmItem);
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "definemethod");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
				invoke.addArg(InsnArg.reg(accIndex, ArgType.OBJECT));
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(targetMth.getName())));
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(3).intValue())));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// ldexternalmodulevar
			case 0x7e: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "ldexternalmodulevar");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);

				AbcClass abcClass = getAbcClassByInsn(insn);
				ModuleLiteralArray.RegularImport imp =
						abcClass.getModuleInfo().getRegularImports().get(asmItem.getOpUnits().get(1).intValue());
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(imp.toString(), false)));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// typeof
			case 0x1c: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "typeof");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addArg(InsnArg.reg(accIndex, ArgType.OBJECT));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// isin RR, vAA
			case 0x25: {

				int aReg = asmItem.getOpUnits().get(2).intValue();

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "isIn");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addArg(InsnArg.reg(aReg, ArgType.OBJECT));
				invoke.addArg(InsnArg.reg(accIndex, ArgType.OBJECT));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.BOOLEAN));
				return invoke;
			}

			case 0xbc: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 4, "definegettersetterbyvalue");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 4);

				for (int i = 0; i < 4; i++) {
					invoke.addArg(InsnArg.reg(asmItem.getOpUnits().get(i + 1).intValue(), ArgType.OBJECT));
				}
				invoke.setResult(InsnArg.reg(accIndex, ArgType.BOOLEAN));
				return invoke;
			}

			case 0x7a: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "stownbyname");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
				List<InstFmt> formats = asmItem.getIns().getFormat();
				String fieldName = ((InstFmt.SId) formats.get(2)).getString(asmItem);

				invoke.addArg(InsnArg.reg(asmItem.getOpUnits().get(3).intValue(), ArgType.OBJECT));
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(fieldName)));
				invoke.addArg(InsnArg.reg(accIndex, ArgType.NARROW));
				return invoke;
			}

			case 0x29:
				return callargsN(insn, 0);
			case 0x2a:
				return callargsN(insn, 1);

			case 0x2b:
				return callargsN(insn, 2);
			case 0x2c:
				return callargsN(insn, 3);

			case 0x1f:
				return neg(insn);
			case 0x20:
				return not(insn);
			case 0x32:
				return supercallthisrange(insn);

			case 0x08:
				return newobjrange(insn);

			case 0x8a:
			case 0x3c: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "ldlexvar");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);

				int a = asmItem.getOpUnits().get(1).intValue();
				int b = asmItem.getOpUnits().get(2).intValue();

				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(a)));
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(b)));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.NARROW));
				return invoke;
			}

			case 0x85:
			case 0x37: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "ldobjbyvalue");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				int a = asmItem.getOpUnits().get(2).intValue();
				invoke.addArg(InsnArg.reg(a, ArgType.NARROW));
				invoke.addArg(InsnArg.reg(accIndex, ArgType.NARROW));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.NARROW));
				return invoke;
			}

			case 0x86:
			case 0x38: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "stobjbyvalue");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
				int a = asmItem.getOpUnits().get(2).intValue();
				int b = asmItem.getOpUnits().get(3).intValue();
				invoke.addArg(InsnArg.reg(a, ArgType.NARROW));
				invoke.addArg(InsnArg.reg(b, ArgType.NARROW));
				invoke.addArg(InsnArg.reg(accIndex, ArgType.NARROW));
				return invoke;
			}

			case 0x79: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "stownbyindex");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
				int a = asmItem.getOpUnits().get(2).intValue();
				int b = asmItem.getOpUnits().get(3).intValue();
				invoke.addArg(InsnArg.reg(a, ArgType.NARROW));
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(b)));
				invoke.addArg(InsnArg.reg(accIndex, ArgType.NARROW));
				return invoke;
			}

			case 0x04: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 0, "createemptyobject");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 0);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			case 0x90: {

				List<InstFmt> formats = asmItem.getIns().getFormat();
				String gName = ((InstFmt.SId) formats.get(2)).getString(asmItem);
				FieldInfo igetFld2 = FieldInfo.fromAsm(root, asmItem, gName);
				InsnNode igetInsn2 = new IndexInsnNode(InsnType.IGET, igetFld2, 1);
				igetInsn2.setResult(InsnArg.reg(accIndex, tryResolveFieldType(igetFld2)));
				igetInsn2.addArg(InsnArg.reg(accIndex, igetFld2.getDeclClass().getType()));
				return igetInsn2;
			}

			case 0xc6: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "starrayspread");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);

				int a = asmItem.getOpUnits().get(1).intValue();
				int b = asmItem.getOpUnits().get(2).intValue();

				invoke.addArg(InsnArg.reg(a, ArgType.NARROW));
				invoke.addArg(InsnArg.reg(b, ArgType.NARROW));
				invoke.addArg(InsnArg.reg(accIndex, ArgType.NARROW));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.INT));
				return invoke;
			}

			case 0x09: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "newlexenv");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				int a = asmItem.getOpUnits().get(1).intValue();
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(a)));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			case 0x6c: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 0, "getunmappedargs");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 0);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			case 0x70: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 0, "ldhole");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 0);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			case 0x69: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 0, "poplexenv");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 0);
				return invoke;
			}

			case 0x1d:
			case 0x1e: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "tonumer");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addArg(InsnArg.reg(accIndex, ArgType.NARROW));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.INT));
				return invoke;
			}

			case 0xbf:
				return invokeHelperArg1(insn, "resumegenerator", InsnArg.reg(accIndex, ArgType.NARROW),
						InsnArg.reg(accIndex, ArgType.NARROW));
			case 0xc0:
				return invokeHelperArg1(insn, "getresumemode", InsnArg.reg(accIndex, ArgType.NARROW),
						InsnArg.reg(accIndex, ArgType.NARROW));

			case 0xc2:
				return invokeHelperArg2(insn, "delobjprop", InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW),
						InsnArg.reg(accIndex, ArgType.NARROW), InsnArg.reg(accIndex, ArgType.NARROW));
			case 0xc3:
				return invokeHelperArg2(insn, "suspendgenerator", InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW),
						InsnArg.reg(accIndex, ArgType.NARROW), InsnArg.reg(accIndex, ArgType.NARROW));
			case 0xc4:
				return invokeHelperArg2(insn, "asyncfunctionawaituncaught",
						InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW), InsnArg.reg(accIndex, ArgType.NARROW),
						InsnArg.reg(accIndex, ArgType.NARROW));

			case 0x66:
				return invokeHelperArg1(insn, "getpropiterator", InsnArg.reg(accIndex, ArgType.NARROW),
						InsnArg.reg(accIndex, ArgType.NARROW));
			case 0x67:
				return invokeHelperArg1(insn, "getiterator", InsnArg.reg(accIndex, ArgType.NARROW), InsnArg.reg(accIndex, ArgType.NARROW));
			case 0x68:
				return invokeHelperArg1(insn, "closeiterator", InsnArg.reg(asmItem.getOpUnits().get(2).intValue(), ArgType.NARROW),
						InsnArg.reg(accIndex, ArgType.NARROW));

			case 0x36:
				return invokeHelperArg1(insn, "getnextpropname", InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW),
						InsnArg.reg(accIndex, ArgType.NARROW));

			case 0x46: {
				List<InstFmt> formats = asmItem.getIns().getFormat();
				String name = ((InstFmt.SId) formats.get(2)).getString(asmItem);
				return invokeHelperArg2(insn, "ldsuperbyname", InsnArg.reg(accIndex, ArgType.OBJECT),
						InsnArg.wrapArg(new ConstStringNode(name)), InsnArg.reg(accIndex, ArgType.NARROW));
			}

			case 0x7d: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "ldlocalmodulevar");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(1).intValue())));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.NARROW));
				return invoke;
			}

			case 0x8d:
			case 0x7f:
			case 0x40: {

				List<InstFmt> formats = asmItem.getIns().getFormat();
				String name = ((InstFmt.SId) formats.get(2)).getString(asmItem);
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "trystglobalbyname");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(name, false)));
				invoke.addArg(InsnArg.reg(accIndex, ArgType.NARROW));
				return invoke;
			}

			case 0x3f:
			case 0x8c:
			case 0x41: {
				ArgType clsType = ArgType.object(getAbcString(asmItem, 2));
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accIndex, ArgType.generic(Consts.CLASS_CLASS, clsType)));
				return constClsInsn;
			}

		}

		if (nOp == 0xfe) {
			nOp = asmItem.getOpUnits().get(1).intValue();
			switch (nOp) {
				case 0x09: {
					ArgType clsType = ArgType.object(getAbcString(asmItem, 2));
					InsnNode constClsInsn = new ConstClassNode(clsType);
					constClsInsn.setResult(InsnArg.reg(accIndex, ArgType.generic(Consts.CLASS_CLASS, clsType)));
					return constClsInsn;
				}
				case 0x07: {

					MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "throw$ifsupernotcorrectcall");
					InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
					invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(2).intValue())));
					return invoke;
				}
				case 0: {
					MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "throw");
					InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
					invoke.addArg(InsnArg.reg(accIndex, ArgType.NARROW));
					return invoke;
				}
				case 0x05:
					return invokeHelperArg1(insn, "throw.ifnotobject", InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW),
							null);
				case 0x06:
					return invokeHelperArg1(insn, "throw.undefinedifhole",
							InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW), null);
			}
		}

		throw new DecodeException("Unknown instruction: '" + asmItem.getIns().getInstruction().toString() + '\'');

	}

	private @NotNull InvokeNode callargsN(InsnData insn, int n) {

		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int numVRegs = mth.getCodeItem().getNumVRegs();
		int numArgs = mth.getCodeItem().getNumArgs();
		int accReg = numArgs + numVRegs;

		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2 + n, "callargsN");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2 + n);
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(n)));
		invoke.addArg(InsnArg.reg(accReg, ArgType.OBJECT));
		for (int i = 0; i < n; i++) {
			invoke.addArg(InsnArg.reg(asmItem.getOpUnits().get(i + 2).intValue(), ArgType.NARROW));
		}
		invoke.setResult(InsnArg.reg(accReg, ArgType.NARROW));
		return invoke;
	}

	@NotNull
	private SwitchInsn makeSwitch(InsnData insn, boolean packed) {
		SwitchInsn swInsn = new SwitchInsn(InsnArg.reg(insn, 0, ArgType.UNKNOWN), insn.getTarget(), packed);
		ICustomPayload payload = insn.getPayload();
		if (payload != null) {
			swInsn.attachSwitchData(new SwitchData((ISwitchPayload) payload), insn.getTarget());
		}
		method.add(AFlag.COMPUTE_POST_DOM);
		return swInsn;
	}

	private InsnNode makeNewArray(int acc) {
		ArgType arrType = ArgType.array(ArgType.NARROW, 1);
		NewArrayNode newArr = new NewArrayNode(arrType, 1);
		newArr.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.OBJECT)));
		newArr.setResult(InsnArg.reg(acc, arrType));
		return newArr;
	}

	private ArgType tryResolveFieldType(FieldInfo igetFld) {
		FieldNode fieldNode = root.resolveField(igetFld);
		if (fieldNode != null) {
			return fieldNode.getType();
		}
		return igetFld.getType();
	}

	private InsnNode copyrestargs(InsnData insn) {
		ArgType arrType = new ArgType.ArrayArg(ArgType.OBJECT);
		ArgType elType = arrType.getArrayElement();
		boolean typeImmutable = true;

		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int numVRegs = mth.getCodeItem().getNumVRegs();
		int numArgs = mth.getCodeItem().getNumArgs();
		int accReg = numArgs + numVRegs;
		List<Number> opUnits = asmItem.getOpUnits();

		int argRegStart = numVRegs;

		int insnArg = opUnits.get(1).intValue();

		InsnArg[] regs = new InsnArg[numArgs - insnArg];

		for (int i = 0; i < numArgs - insnArg; i++) {
			regs[i] = InsnArg.reg(argRegStart + insnArg + i, elType);
		}

		InsnNode node = new FilledNewArrayNode(elType, regs.length);
		for (InsnArg arg : regs) {
			node.addArg(arg);
		}
		node.setResult(InsnArg.reg(accReg, arrType));
		return node;
	}

	private InsnNode cmp(InsnData insn, InsnType itype, ArgType argType) {
		InsnNode inode = new InsnNode(itype, 2);
		inode.setResult(InsnArg.reg(insn, 0, ArgType.INT));
		inode.addArg(InsnArg.reg(insn, 1, argType));
		inode.addArg(InsnArg.reg(insn, 2, argType));
		return inode;
	}

	private InsnNode cmp(int a1, int a2, int dst, InsnType itype, ArgType argType) {
		InsnNode inode = new InsnNode(itype, 2);
		inode.setResult(InsnArg.reg(dst, ArgType.INT));
		inode.addArg(InsnArg.reg(a1, argType));
		inode.addArg(InsnArg.reg(a2, argType));
		return inode;
	}

	private InsnNode cast(InsnData insn, ArgType from, ArgType to) {
		InsnNode inode = new IndexInsnNode(InsnType.CAST, to, 1);
		inode.setResult(InsnArg.reg(insn, 0, to));
		inode.addArg(InsnArg.reg(insn, 1, from));
		return inode;
	}

	private InsnNode invokeCustom(InsnData insn, boolean isRange) {
		return InvokeCustomBuilder.build(method, insn, isRange);
	}

	private InsnNode invokePolymorphic(InsnData insn, boolean isRange) {
		IMethodRef mthRef = InsnDataUtils.getMethodRef(insn);
		if (mthRef == null) {
			throw new JadxRuntimeException("Failed to load method reference for insn: " + insn);
		}
		MethodInfo callMth = MethodInfo.fromRef(root, mthRef);
		IMethodProto proto = insn.getIndexAsProto(insn.getTarget());

		// expand call args
		List<ArgType> args = Utils.collectionMap(proto.getArgTypes(), ArgType::parse);
		ArgType returnType = ArgType.parse(proto.getReturnType());
		MethodInfo effectiveCallMth = MethodInfo.fromDetails(root, callMth.getDeclClass(),
				callMth.getName(), args, returnType);
		return new InvokePolymorphicNode(effectiveCallMth, insn, proto, callMth, isRange);
	}

	private InsnNode invokeSpecial(InsnData insn) {
		IMethodRef mthRef = InsnDataUtils.getMethodRef(insn);
		if (mthRef == null) {
			throw new JadxRuntimeException("Failed to load method reference for insn: " + insn);
		}
		MethodInfo mthInfo = MethodInfo.fromRef(root, mthRef);
		// convert 'special' to 'direct/super' same as dx
		InvokeType type;
		if (mthInfo.isConstructor() || Objects.equals(mthInfo.getDeclClass(), method.getParentClass().getClassInfo())) {
			type = InvokeType.DIRECT;
		} else {
			type = InvokeType.SUPER;
		}
		return new InvokeNode(mthInfo, insn, type, false);
	}

	private InsnNode invokeHelperArg1(InsnData insn, String helper, InsnArg arg, RegisterArg result) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, helper);
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
		invoke.addArg(arg);
		invoke.setResult(result);
		return invoke;
	}

	private InsnNode invokeHelperArg2(InsnData insn, String helper, InsnArg arg1, InsnArg arg2, RegisterArg result) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, helper);
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
		invoke.addArg(arg1);
		invoke.addArg(arg2);
		invoke.setResult(result);
		return invoke;
	}

	private InsnNode callthisN(InsnData insn, int n) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		List<Number> opUnits = asmItem.getOpUnits();
		int thisReg = opUnits.get(2).intValue();

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, 3 + n, "callthisN");

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3 + n);
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(n)));
		invoke.addReg(thisReg, ArgType.OBJECT); // object
		invoke.addReg(accReg, ArgType.NARROW); // object
		for (int i = 0; i < n; i++) {
			invoke.addReg(opUnits.get(3 + i).intValue(), ArgType.NARROW);
		}
		invoke.setResult(InsnArg.reg(accReg, ArgType.NARROW));
		return invoke;
	}

	private InsnNode callThisRange(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		List<Number> opUnits = asmItem.getOpUnits();
		int thisReg = opUnits.get(3).intValue();
		int baseArg = thisReg + 1;
		int argc = opUnits.get(2).intValue();

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, 3 + argc, "callthisN");

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3 + argc);
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(argc)));
		invoke.addReg(thisReg, ArgType.OBJECT); // object
		invoke.addReg(accReg, ArgType.OBJECT); // object

		for (int i = 0; i < argc; i++) {
			invoke.addReg(baseArg + i, ArgType.NARROW);
		}

		invoke.setResult(InsnArg.reg(accReg, ArgType.NARROW));
		return invoke;
	}

	private InsnNode callrange(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		List<Number> opUnits = asmItem.getOpUnits();
		int baseArg = opUnits.get(3).intValue();
		int argc = opUnits.get(2).intValue();

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, 2 + argc, "callargsN");

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2 + argc);
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(argc)));
		invoke.addReg(accReg, ArgType.OBJECT); // func

		for (int i = 0; i < argc; i++) {
			invoke.addReg(baseArg + i, ArgType.NARROW);
		}

		invoke.setResult(InsnArg.reg(accReg, ArgType.NARROW));
		return invoke;
	}

	private InsnNode supercallspread(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		List<Number> opUnits = asmItem.getOpUnits();

		int argReg = opUnits.get(2).intValue();
		String target = "superConstructor";

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, 4 + 2, target);

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.DIRECT, 4 + 2);
		invoke.addReg(accReg, ArgType.OBJECT); // object
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object1"))));
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object2"))));
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object3"))));
		invoke.addReg(accReg, ArgType.OBJECT);

		invoke.addReg(argReg, ArgType.OBJECT);

		invoke.setResult(InsnArg.reg(accReg, ArgType.OBJECT));
		return invoke;
	}

	private InsnNode supercallthisrange(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		List<Number> opUnits = asmItem.getOpUnits();
		int baseArg = opUnits.get(3).intValue();
		int argc = opUnits.get(2).intValue();

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, argc, "super");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.DIRECT, argc);

		for (int i = 0; i < argc; i++) {
			invoke.addReg(baseArg + i, ArgType.OBJECT);
		}
		invoke.setResult(InsnArg.reg(accReg, ArgType.OBJECT));
		return invoke;
	}

	private InsnNode newobjrange(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		List<Number> opUnits = asmItem.getOpUnits();

		int classArg = opUnits.get(3).intValue();

		int baseArg = classArg + 1;
		int argc = opUnits.get(2).intValue() - 1;

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, argc + 1, "newobjrange");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, argc + 1);
		invoke.addReg(classArg, ArgType.CLASS);
		for (int i = 0; i < argc; i++) {
			invoke.addReg(baseArg + i, ArgType.OBJECT);
		}
		invoke.setResult(InsnArg.reg(accReg, ArgType.OBJECT));
		return invoke;
	}

	private InsnNode arrayGet(InsnData insn, ArgType argType) {
		return arrayGet(insn, argType, argType);
	}

	private InsnNode arrayGet(InsnData insn, ArgType arrElemType, ArgType resType) {
		InsnNode inode = new InsnNode(InsnType.AGET, 2);
		inode.setResult(InsnArg.typeImmutableIfKnownReg(insn, 0, resType));
		inode.addArg(InsnArg.typeImmutableIfKnownReg(insn, 1, ArgType.array(arrElemType)));
		inode.addArg(InsnArg.reg(insn, 2, ArgType.NARROW_INTEGRAL));
		return inode;
	}

	private InsnNode arrayPut(InsnData insn, ArgType argType) {
		return arrayPut(insn, argType, argType);
	}

	private InsnNode arrayPut(InsnData insn, ArgType arrElemType, ArgType argType) {
		InsnNode inode = new InsnNode(InsnType.APUT, 3);
		inode.addArg(InsnArg.typeImmutableIfKnownReg(insn, 1, ArgType.array(arrElemType)));
		inode.addArg(InsnArg.reg(insn, 2, ArgType.NARROW_INTEGRAL));
		inode.addArg(InsnArg.typeImmutableIfKnownReg(insn, 0, argType));
		return inode;
	}

	private InsnNode arith(InsnData insn, ArithOp op, ArgType type) {
		return ArithNode.build(insn, op, type);
	}

	private InsnNode dec(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		return new ArithNode(ArithOp.SUB, InsnArg.reg(accReg, ArgType.INT), InsnArg.reg(accReg, ArgType.INT),
				InsnArg.wrapArg(new ConstIntNode(1)));
	}

	private InsnNode inc(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		return new ArithNode(ArithOp.ADD, InsnArg.reg(accReg, ArgType.INT), InsnArg.reg(accReg, ArgType.INT),
				InsnArg.wrapArg(new ConstIntNode(1)));
	}

	private InsnNode arith2(InsnData insn, ArithOp op) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		int a = asmItem.getOpUnits().get(2).intValue();

		return new ArithNode(op, InsnArg.reg(accReg, ArgType.INT), InsnArg.reg(a, ArgType.INT),
				InsnArg.reg(accReg, ArgType.INT));
	}

	private InsnNode arithLit(InsnData insn, ArithOp op, ArgType type) {
		return ArithNode.buildLit(insn, op, type);
	}

	private InsnNode neg(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();

		InsnNode inode = new InsnNode(InsnType.NEG, 1);
		inode.addArg(InsnArg.reg(accReg, ArgType.INT));
		inode.setResult(InsnArg.reg(accReg, ArgType.INT));
		return inode;
	}

	private InsnNode not(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();

		InsnNode inode = new InsnNode(InsnType.NOT, 1);
		inode.addArg(InsnArg.reg(accReg, ArgType.INT));
		inode.setResult(InsnArg.reg(accReg, ArgType.INT));
		return inode;
	}

	private InsnNode insn(InsnType type, RegisterArg res) {
		InsnNode node = new InsnNode(type, 0);
		node.setResult(res);
		return node;
	}

	private InsnNode insn(InsnType type, RegisterArg res, InsnArg arg) {
		InsnNode node = new InsnNode(type, 1);
		node.setResult(res);
		node.addArg(arg);
		return node;
	}
}
