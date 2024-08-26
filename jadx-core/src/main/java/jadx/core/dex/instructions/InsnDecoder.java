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

			case 0x61:
				return insn(InsnType.MOVE, InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW),
						InsnArg.reg(accIndex, ArgType.NARROW));

			case 0x60:
				return insn(InsnType.MOVE, InsnArg.reg(accIndex, ArgType.NARROW),
						InsnArg.reg(asmItem.getOpUnits().get(1).intValue(), ArgType.NARROW));

			case 0x13:
				return cmp(accIndex, asmItem.getOpUnits().get(2).intValue(), accIndex, InsnType.CMP_G, ArgType.INT);

			case 0x11:
				return cmp(accIndex, asmItem.getOpUnits().get(2).intValue(), accIndex, InsnType.CMP_L, ArgType.INT);

			case 0x28: // stricteq
				return cmp(accIndex, asmItem.getOpUnits().get(2).intValue(), accIndex, InsnType.CMP_L, ArgType.OBJECT);

			// isfalse
			case 0x23:
			case 0x24: {
				Asm.AsmItem asmItem1 = insn.getAsmItem();
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, nOp == 0x24 ? "isfalse" : "istrue");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addReg(accIndex, ArgType.OBJECT);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// asyncfunctionenter
			case 0xae: {
				Asm.AsmItem asmItem1 = insn.getAsmItem();
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 0, "asyncfunctionenter");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 0);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// asyncfunctionresolve
			case 0xcd: {
				Asm.AsmItem asmItem1 = insn.getAsmItem();
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "asyncfunctionresolve");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addReg(accIndex, ArgType.OBJECT);
				invoke.addReg(asmItem.getOpUnits().get(1).intValue(), ArgType.OBJECT);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// asyncfunctionreject
			case 0xce: {
				Asm.AsmItem asmItem1 = insn.getAsmItem();
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "asyncfunctionreject");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addReg(accIndex, ArgType.OBJECT);
				invoke.addReg(asmItem.getOpUnits().get(1).intValue(), ArgType.OBJECT);
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			case 0x4f: // eq
				return new IfNode(asmItem.getOpUnits().get(1).intValue() + asmItem.getCodeOffset(), accIndex, IfOp.NE);

			case 0x51: // jnez
				return new IfNode(asmItem.getOpUnits().get(1).intValue() + asmItem.getCodeOffset(), accIndex, IfOp.NE);

			case 0xa:
				return new ArithNode(ArithOp.ADD, InsnArg.reg(accIndex, ArgType.INT),
						InsnArg.reg(asmItem.getOpUnits().get(2).intValue(), ArgType.INT), InsnArg.reg(accIndex, ArgType.INT));

			case 0xc:
				return new ArithNode(ArithOp.MUL, InsnArg.reg(accIndex, ArgType.INT),
						InsnArg.reg(asmItem.getOpUnits().get(2).intValue(), ArgType.INT), InsnArg.reg(accIndex, ArgType.INT));
			case 0x4d:
				return new GotoNode(asmItem.getOpUnits().get(1).intValue() + asmItem.getCodeOffset());

			case 0xb:
				return new ArithNode(ArithOp.SUB, InsnArg.reg(accIndex, ArgType.INT),
						InsnArg.reg(asmItem.getOpUnits().get(2).intValue(), ArgType.INT), InsnArg.reg(accIndex, ArgType.INT));

			case 0:
			case 1:
				return insn(InsnType.CONST, InsnArg.reg(accIndex, ArgType.NARROW), InsnArg.lit(0, ArgType.NARROW));

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
				int obj = asmItem.getOpUnits().get(2).intValue();
				int arg = asmItem.getOpUnits().get(3).intValue();
				RegisterArg mthObj = InsnArg.reg(accIndex, ArgType.OBJECT);
				return invoke(insn, InvokeType.DIRECT, accIndex, obj, arg, searchMethod(insn));

			case 0x2f: // callthis2
				return callthis2(insn);

			case 0x31:
				return callThisRange(insn);

			case 0x30: // callthis3
				return callthis3(insn);

			case 0x3f: {
				ArgType clsType = ArgType.object(getAbcString(asmItem, 2));
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accIndex, ArgType.generic(Consts.CLASS_CLASS, clsType)));
				return constClsInsn;
			}

			case 0xcf: // copyrestargs
				return copyrestargs(insn);
			case 0xb9: // supercallspread
				return supercallspread(insn);

			// defineclasswithbuffer
			case 0x35: {
				Asm.AsmItem asmItem1 = insn.getAsmItem();
				List<InstFmt> formats = asmItem1.getIns().getFormat();
				MethodItem targetMth = ((InstFmt.MId) formats.get(2)).getMethod(asmItem1);
				LiteralArray la = ((InstFmt.LId) formats.get(3)).getLA(asmItem1);
				int cnt = asmItem1.getOpUnits().get(4).intValue();
				int parentReg = asmItem1.getOpUnits().get(5).intValue();

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 5, targetMth.getName());

				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.VIRTUAL, 5);
				invoke.addReg(parentReg, ArgType.OBJECT); // object
				invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object2"))));
				invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object3"))));
				invoke.addReg(parentReg, ArgType.OBJECT);

				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(la.toString())));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// newlexenvwithname
			case 0xb6: {
				Asm.AsmItem asmItem1 = insn.getAsmItem();
				List<InstFmt> formats = asmItem1.getIns().getFormat();
				LiteralArray la = ((InstFmt.LId) formats.get(2)).getLA(asmItem1);

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "newlexenvwithname");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(la.toString())));
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(String.format("%d", asmItem1.getOpUnits().get(1).intValue()))));

				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// stmodulevar
			case 0x7c: {
				Asm.AsmItem asmItem1 = insn.getAsmItem();
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "stmodulevar");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addReg(accIndex, ArgType.OBJECT);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(String.format("%d", asmItem1.getOpUnits().get(1).intValue()))));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// stlexvar
			case 0x3d: {
				Asm.AsmItem asmItem1 = insn.getAsmItem();
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "stlexvar");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(String.format("%d", asmItem1.getOpUnits().get(1).intValue()))));
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(String.format("%d", asmItem1.getOpUnits().get(2).intValue()))));
				invoke.addReg(accIndex, ArgType.OBJECT);
				return invoke;
			}

			// definefunc
			case 0x33: {
				Asm.AsmItem asmItem1 = insn.getAsmItem();
				List<InstFmt> formats = asmItem1.getIns().getFormat();
				MethodItem targetMth = ((InstFmt.MId) formats.get(2)).getMethod(asmItem1);
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "definefunc");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(targetMth.getName())));
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(String.format("%d", asmItem1.getOpUnits().get(3).intValue()))));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}

			// ldexternalmodulevar
			case 0x7e: {
				Asm.AsmItem asmItem1 = insn.getAsmItem();
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "ldexternalmodulevar");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);

				AbcClass abcClass = getAbcClassByInsn(insn);
				ModuleLiteralArray.RegularImport imp =
						abcClass.getModuleInfo().getRegularImports().get(asmItem1.getOpUnits().get(1).intValue());
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(imp.toString())));
				invoke.setResult(InsnArg.reg(accIndex, ArgType.OBJECT));
				return invoke;
			}
		}

		if (nOp == 0xfe || nOp == -2) {
			nOp = asmItem.getOpUnits().get(1).intValue();
			switch (nOp) {
				case 0x09: {
					ArgType clsType = ArgType.object(getAbcString(asmItem, 2));
					InsnNode constClsInsn = new ConstClassNode(clsType);
					constClsInsn.setResult(InsnArg.reg(accIndex, ArgType.generic(Consts.CLASS_CLASS, clsType)));
					return constClsInsn;
				}
				case 0x07: {
					Asm.AsmItem asmItem1 = insn.getAsmItem();
					MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "throw$ifsupernotcorrectcall");
					InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
					invoke.addArg(InsnArg.wrapArg(new ConstStringNode(String.format("%d", asmItem1.getOpUnits().get(2).intValue()))));
					return invoke;
				}
			}
		}

		return new InsnNode(InsnType.NOP, 0);

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

	private InsnNode makeNewArray(InsnData insn) {
		ArgType indexType = ArgType.parse(insn.getIndexAsType());
		int dim = (int) insn.getLiteral();
		ArgType arrType;
		if (dim == 0) {
			arrType = indexType;
		} else {
			if (indexType.isArray()) {
				// java bytecode can pass array as a base type
				arrType = indexType;
			} else {
				arrType = ArgType.array(indexType, dim);
			}
		}
		int regsCount = insn.getRegsCount();
		NewArrayNode newArr = new NewArrayNode(arrType, regsCount - 1);
		newArr.setResult(InsnArg.reg(insn, 0, arrType));
		for (int i = 1; i < regsCount; i++) {
			newArr.addArg(InsnArg.typeImmutableReg(insn, i, ArgType.INT));
		}
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

	private InsnNode invoke(InsnData insn, InvokeType type, boolean isRange) {
		IMethodRef mthRef = InsnDataUtils.getMethodRef(insn);
		if (mthRef == null) {
			throw new JadxRuntimeException("Failed to load method reference for insn: " + insn);
		}
		MethodInfo mthInfo = MethodInfo.fromRef(root, mthRef);
		return new InvokeNode(mthInfo, insn, type, isRange);
	}

	private InsnNode invoke(InsnData insn, InvokeType type, int accReg, int thisReg, int argReg, String target) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 5, target);

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.VIRTUAL, 5);
		invoke.addReg(thisReg, ArgType.OBJECT); // object
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object2"))));
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object3"))));
		invoke.addReg(thisReg, ArgType.OBJECT);
		invoke.addReg(argReg, ArgType.OBJECT);

		invoke.setResult(InsnArg.reg(accReg, ArgType.OBJECT));
		return invoke;
	}

	private InsnNode callthis3(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		List<Number> opUnits = asmItem.getOpUnits();
		int thisReg = opUnits.get(2).intValue();

		String target = searchMethod(insn);
		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, 4 + 3, target);

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.VIRTUAL, 4 + 3);
		invoke.addReg(thisReg, ArgType.OBJECT); // object
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object2"))));
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object3"))));
		invoke.addReg(thisReg, ArgType.OBJECT);

		for (int i = 0; i < 3; i++) {
			invoke.addReg(opUnits.get(3 + i).intValue(), ArgType.OBJECT);
		}
		invoke.setResult(InsnArg.reg(accReg, ArgType.OBJECT));
		return invoke;
	}

	private InsnNode callthis2(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		List<Number> opUnits = asmItem.getOpUnits();
		int thisReg = opUnits.get(2).intValue();

		String target = searchMethod(insn);
		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, 4 + 2, target);

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.VIRTUAL, 4 + 2);
		invoke.addReg(thisReg, ArgType.OBJECT); // object
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object2"))));
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object3"))));
		invoke.addReg(thisReg, ArgType.OBJECT);

		for (int i = 0; i < 2; i++) {
			invoke.addReg(opUnits.get(3 + i).intValue(), ArgType.OBJECT);
		}
		invoke.setResult(InsnArg.reg(accReg, ArgType.OBJECT));
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
		String target = searchMethod(insn);

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, 4 + argc, target);

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.VIRTUAL, 4 + argc);
		invoke.addReg(thisReg, ArgType.OBJECT); // object
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object2"))));
		invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object3"))));
		invoke.addReg(thisReg, ArgType.OBJECT);

		for (int i = 0; i < argc; i++) {
			invoke.addReg(baseArg + i, ArgType.OBJECT);
		}

		invoke.setResult(InsnArg.reg(accReg, ArgType.OBJECT));
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

	private InsnNode arithLit(InsnData insn, ArithOp op, ArgType type) {
		return ArithNode.buildLit(insn, op, type);
	}

	private InsnNode neg(InsnData insn, ArgType type) {
		InsnNode inode = new InsnNode(InsnType.NEG, 1);
		inode.setResult(InsnArg.reg(insn, 0, type));
		inode.addArg(InsnArg.reg(insn, 1, type));
		return inode;
	}

	private InsnNode not(InsnData insn, ArgType type) {
		InsnNode inode = new InsnNode(InsnType.NOT, 1);
		inode.setResult(InsnArg.reg(insn, 0, type));
		inode.addArg(InsnArg.reg(insn, 1, type));
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
