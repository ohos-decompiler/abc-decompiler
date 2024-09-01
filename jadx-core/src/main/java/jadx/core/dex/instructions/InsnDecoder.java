package jadx.core.dex.instructions;

import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	public static int getIntOpUnit(Asm.AsmItem asmItem, int index) {
		int r = asmItem.getOpUnits().get(index).intValue();
		return r;
	}

	public static int getRegisterByOpIndex(Asm.AsmItem asmItem, int index) {
		return ((InstFmt.RegV) asmItem.getIns().getFormat().get(index)).getRegister(asmItem);
	}

	private RegisterArg getRegisterArg(Asm.AsmItem asmItem, int opIndex, ArgType type) {
		int regIndex = getRegisterByOpIndex(asmItem, opIndex);
		return InsnArg.reg(regIndex, type);
	}

	public static String getStringOpFormat(Asm.AsmItem asmItem, int index) {
		List<InstFmt> formats = asmItem.getIns().getFormat();
		return ((InstFmt.SId) formats.get(index)).getString(asmItem);
	}

	public static AbcClass getAbcClassByInsn(InsnData insn) {
		return (AbcClass) ((FieldType.ClassType) insn.getAsmItem().getAsm().getCode().getMethod().getClazz()).getClazz();
	}

	@NotNull
	protected InsnNode decode(InsnData insn) throws DecodeException {

		Asm.AsmItem asmItem = insn.getAsmItem();

		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accRegister = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		int nOp = asmItem.getOpUnits().get(0).shortValue() & 0xff;
		switch (nOp) {
			case 0x8f:
			case 0x44: // mov vA, vB
			case 0x45:
				RegisterArg dst = getRegisterArg(asmItem, 1, ArgType.NARROW);
				RegisterArg src = getRegisterArg(asmItem, 2, ArgType.NARROW);
				return insn(InsnType.MOVE, dst, src);
			case 0x62:
				RegisterArg acc = InsnArg.reg(accRegister, ArgType.INT);
				LiteralArg narrowLitArg = InsnArg.lit(getIntOpUnit(asmItem, 1), ArgType.INT);
				return insn(InsnType.CONST, acc, narrowLitArg);

			case 0:
				return insn(InsnType.CONST, InsnArg.reg(accRegister, ArgType.NARROW), InsnArg.lit(0, ArgType.NARROW));

			// ldnull
			case 0x01: {
				LiteralArg litArg = InsnArg.lit(0, ArgType.INT);
				return insn(InsnType.CONST, InsnArg.reg(accRegister, ArgType.INT), litArg);
			}

			// ldtrue
			case 0x02: {
				LiteralArg litArg = InsnArg.lit(1, ArgType.INT);
				return insn(InsnType.CONST, InsnArg.reg(accRegister, ArgType.INT), litArg);
			}

			case 0x03: {
				LiteralArg litArg = InsnArg.lit(0, ArgType.INT);
				return insn(InsnType.CONST, InsnArg.reg(accRegister, ArgType.INT), litArg);
			}

			case 0x63: {
				LiteralArg litArg = InsnArg.lit(asmItem.getOpUnits().get(1).longValue(), ArgType.BOOLEAN);
				return insn(InsnType.CONST, InsnArg.reg(accRegister, ArgType.FLOAT), litArg);
			}
			case 0xd5:
				return new InsnNode(InsnType.NOP, 0);
			case 0x6a: {
				ArgType clsType = ArgType.object("nan");
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return constClsInsn;
			}

			case 0x6b: {
				ArgType clsType = ArgType.object("infinity");
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return constClsInsn;
			}
			case 0x6c: {
				ArgType clsType = ArgType.object("arguments");
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return constClsInsn;
			}

			case 0x6d: {
				ArgType clsType = ArgType.object("global");
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return constClsInsn;
			}
			case 0x6f: {
				ArgType clsType = ArgType.object("this");
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return constClsInsn;
			}

			case 0x70: {
				ArgType clsType = ArgType.object("hole");
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return constClsInsn;
			}

			case 0xad: {
				ArgType clsType = ArgType.object("ldsymbol");
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return constClsInsn;
			}

			case 0x61:
				return insn(InsnType.MOVE, getRegisterArg(asmItem, 1, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0x60:
				return insn(InsnType.MOVE, InsnArg.reg(accRegister, ArgType.NARROW),
						getRegisterArg(asmItem, 1, ArgType.NARROW));

			case 0x13:
				return cmp(getRegisterByOpIndex(asmItem, 2), accRegister, accRegister, InsnType.CMP_G, ArgType.NARROW);

			case 0x11:
				return cmp(getRegisterByOpIndex(asmItem, 2), accRegister, accRegister, InsnType.CMP_L, ArgType.NARROW);

			case 0x0f:
			case 0x28: // stricteq
				return cmp(accRegister, getRegisterByOpIndex(asmItem, 2), accRegister, InsnType.CMP_EQ, ArgType.NARROW);

			case 0x10:
			case 0x27: // strictnoteq
				return cmp(accRegister, getRegisterByOpIndex(asmItem, 2), accRegister, InsnType.CMP_NE, ArgType.NARROW);

			case 0x14:
				return cmp(getRegisterByOpIndex(asmItem, 2), accRegister,  accRegister, InsnType.CMP_GE, ArgType.NARROW);

			case 0x12:
				return cmp(getRegisterByOpIndex(asmItem, 2), accRegister, accRegister, InsnType.CMP_LE, ArgType.NARROW);

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
				invoke.addReg(accRegister, ArgType.INT);
				invoke.setResult(InsnArg.reg(accRegister, ArgType.INT));
				return invoke;
			}

			case 0xab:
				return invokeHelperArg1(insn, "getiterator", InsnArg.reg(accRegister, ArgType.OBJECT),
						InsnArg.reg(accRegister, ArgType.OBJECT));

			case 0xac:
				return invokeHelperArg1(insn, "closeiterator", getRegisterArg(asmItem, 2, ArgType.OBJECT),
						InsnArg.reg(accRegister, ArgType.OBJECT));

			case 0xaf:
				return invokeHelperArg0(insn, "ldfunction", InsnArg.reg(accRegister, ArgType.OBJECT));
			case 0xb0:
				return invokeHelperArg0(insn, "debugger", null);

			case 0xb1:
				return invokeHelperArg1(insn, "creategeneratorobj", getRegisterArg(asmItem, 1, ArgType.OBJECT),
						InsnArg.reg(accRegister, ArgType.OBJECT));

			case 0xb2:
				return invokeHelperArg2(insn, "createiterresultobj", getRegisterArg(asmItem, 1, ArgType.OBJECT),
						getRegisterArg(asmItem, 2, ArgType.OBJECT), InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xb3:
				return createobjectwithexcludedkeys(insn, false);

			// asyncfunctionenter
			case 0xae: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 0, "asyncfunctionenter");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 0);
				invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return invoke;
			}

			// asyncfunctionresolve
			case 0xcd: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "asyncfunctionresolve");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addReg(accRegister, ArgType.OBJECT);
				invoke.addReg(getRegisterByOpIndex(asmItem, 1), ArgType.OBJECT);
				invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return invoke;
			}

			// asyncfunctionreject
			case 0xce: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "asyncfunctionreject");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addReg(accRegister, ArgType.OBJECT);
				invoke.addReg(getRegisterByOpIndex(asmItem, 1), ArgType.OBJECT);
				invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return invoke;
			}

			case 0x4f: // jeqz
			case 0x50:
			case 0x9a:
				return new IfNode(getIntOpUnit(asmItem, 1) + asmItem.getCodeOffset(), accRegister, IfOp.EQ);

			case 0x9b:
			case 0x9c:
			case 0x51: // jnez
				return new IfNode(getIntOpUnit(asmItem, 1) + asmItem.getCodeOffset(), accRegister, IfOp.NE);

			case 0x98:
			case 0x4d:
			case 0x4e:
				return new GotoNode(getIntOpUnit(asmItem, 1) + asmItem.getCodeOffset());

			case 0x64:
			case 0x65:
				return insn(InsnType.RETURN,
						null,
						InsnArg.reg(accRegister, method.getReturnType()));
			case 0x3e:
				InsnNode constStrInsn = new ConstStringNode(getStringOpFormat(asmItem, 1));
				constStrInsn.setResult(InsnArg.reg(accRegister, ArgType.STRING));
				return constStrInsn;

			case 0x91:
			case 0x43:
				return makePutField(asmItem, getRegisterByOpIndex(asmItem, 3), accRegister, 2);

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

			case 0x72:
			case 0x71:
				return invokeHelperArg2(insn, "createregexpwithliteral",
						InsnArg.wrapArg(new ConstStringNode(getStringOpFormat(asmItem, 2))),
						InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 3))),
						InsnArg.reg(accRegister, ArgType.OBJECT));

			case 0xcf: // copyrestargs
				return copyrestargs(insn, false);
			case 0xb9: // supercallspread
				return supercallspread(insn);

			case 0xb5:
			case 0xb4:
				return invokeHelperArg2(insn, "newobjapply", getRegisterArg(asmItem, 2, ArgType.OBJECT),
						InsnArg.reg(accRegister, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.OBJECT));

			// defineclasswithbuffer
			case 0x75:
			case 0x35: {
				List<InstFmt> formats = asmItem.getIns().getFormat();
				MethodItem targetMth = ((InstFmt.MId) formats.get(2)).getMethod(asmItem);
				LiteralArray la = ((InstFmt.LId) formats.get(3)).getLA(asmItem);
				int parentReg = getRegisterByOpIndex(asmItem, 5);

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 5, targetMth.getName());

				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.VIRTUAL, 5);
				invoke.addReg(parentReg, ArgType.OBJECT); // object
				invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object2"))));
				invoke.addArg(InsnArg.wrapArg(new ConstClassNode(ArgType.object("Object3"))));
				invoke.addReg(parentReg, ArgType.OBJECT);

				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(la.toString(), false)));
				invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return invoke;
			}

			case 0x81:
			case 0x06: {

				List<InstFmt> formats = asmItem.getIns().getFormat();
				LiteralArray la = ((InstFmt.LId) formats.get(2)).getLA(asmItem);

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "createarraywithbuffer");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(la.toString(), false)));
				invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return invoke;
			}

			case 0x80:
			case 0x05:
				return makeNewArray(accRegister);

			case 0x82:
			case 0x07: {
				List<InstFmt> formats = asmItem.getIns().getFormat();
				LiteralArray la = ((InstFmt.LId) formats.get(2)).getLA(asmItem);

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "createobjectwithbuffer");

				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(la.toString(), false)));
				invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return invoke;
			}

			// newlexenvwithname
			case 0xb6: {
				return newlexenvwithname(insn, asmItem, accRegister, false);
			}

			// stmodulevar
			case 0x7c:
				return stmodulevar(insn, accRegister, asmItem, false);

			// stlexvar
			case 0x8b:
			case 0x3d:
				return stlexvar(insn, asmItem, accRegister, false);

			// definefunc
			case 0x74:
			case 0x33: {
				List<InstFmt> formats = asmItem.getIns().getFormat();
				MethodItem targetMth = ((InstFmt.MId) formats.get(2)).getMethod(asmItem);
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "definefunc");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(targetMth.getName(), false)));
				invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(3).intValue())));
				invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return invoke;
			}

			// definemethod
			case 0xbe:
			case 0x34: {
				List<InstFmt> formats = asmItem.getIns().getFormat();
				MethodItem targetMth = ((InstFmt.MId) formats.get(2)).getMethod(asmItem);
				return makeGetField(asmItem, accRegister, accRegister, targetMth.getName());
			}

			// ldexternalmodulevar
			case 0x7e:
				return ldexternalmodulevar(insn, asmItem, accRegister, false);

			// typeof
			case 0x84:
			case 0x1c: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "typeof");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addArg(InsnArg.reg(accRegister, ArgType.OBJECT));
				invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return invoke;
			}

			// isin RR, vAA
			case 0x25: {

				int aReg = getRegisterByOpIndex(asmItem, 2);

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "isIn");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addArg(InsnArg.reg(aReg, ArgType.OBJECT));
				invoke.addArg(InsnArg.reg(accRegister, ArgType.OBJECT));
				invoke.setResult(InsnArg.reg(accRegister, ArgType.BOOLEAN));
				return invoke;
			}

			case 0x26:
				return invokeHelperArg2(insn, "instanceof", getRegisterArg(asmItem, 2, ArgType.OBJECT),
						InsnArg.reg(accRegister, ArgType.OBJECT),
						InsnArg.reg(accRegister, ArgType.INT));

			case 0x48:
			case 0x47:
				return invokeHelperArg2(insn, "set_global", InsnArg.wrapArg(new ConstStringNode(getStringOpFormat(asmItem, 2), false)),
						InsnArg.reg(accRegister, ArgType.NARROW),
						null);

			case 0xc1:
			case 0x76:
				return invokeHelperArg1(insn, "gettemplateobject", InsnArg.reg(accRegister, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xb7:
				return invokeHelperArg1(insn, "createasyncgeneratorobj", getRegisterArg(asmItem, 1, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xc7:
			case 0x77:
				return makePutField(asmItem, accRegister, getRegisterByOpIndex(asmItem, 2), "__proto__");

			case 0x93:
			case 0x49:
				return invokeHelperArg1(insn, "ldthisbyname", InsnArg.wrapArg(new ConstStringNode(getStringOpFormat(asmItem, 2), false)),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0x94:
			case 0x4a:
				return invokeHelperArg2(insn, "stthisbyname", InsnArg.wrapArg(new ConstStringNode(getStringOpFormat(asmItem, 2), false)),
						InsnArg.reg(accRegister, ArgType.NARROW),
						null);

			case 0x95:
			case 0x4b:
				return invokeHelperArg1(insn, "ldthisbyvalue", InsnArg.reg(accRegister, ArgType.INT),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0x96:
			case 0x4c:
				return invokeHelperArg2(insn, "stthisbyvalue", getRegisterArg(asmItem, 2, ArgType.INT),
						InsnArg.reg(accRegister, ArgType.NARROW),
						null);

			case 0x97:
				// asyncgeneratorreject
				return invokeHelperArg2(insn, "asyncgeneratorreject", InsnArg.reg(accRegister, ArgType.NARROW),
						getRegisterArg(asmItem, 1, ArgType.OBJECT),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xc8:
			case 0x78:
				return invokeHelperArg3(insn, "stownbyvalue", getRegisterArg(asmItem, 2, ArgType.OBJECT),
						getRegisterArg(asmItem, 3, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW),
						null);

			case 0xb8:
				return invokeHelperArg3(insn, "asyncgeneratorresolve", getRegisterArg(asmItem, 1, ArgType.OBJECT),
						getRegisterArg(asmItem, 2, ArgType.NARROW), getRegisterArg(asmItem, 3, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xd4:
			case 0x8e:
				return invokeHelperArg3(insn, "stownbynamewithnameset", getRegisterArg(asmItem, 3, ArgType.OBJECT),
						InsnArg.wrapArg(new ConstStringNode(getStringOpFormat(asmItem, 2))),
						InsnArg.reg(accRegister, ArgType.NARROW),
						null);

			case 0xd2:
			case 0x99:
				return invokeHelperArg3(insn, "stownbyvaluewithnameset", getRegisterArg(asmItem, 2, ArgType.OBJECT),
						getRegisterArg(asmItem, 3, ArgType.OBJECT),
						InsnArg.reg(accRegister, ArgType.NARROW),
						null);

			case 0xd3:
				return invokeHelperArg1(insn, "ldbigint", InsnArg.wrapArg(new ConstStringNode(getStringOpFormat(asmItem, 1))),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xd7:
				return invokeHelperArg1(insn, "getasynciterator", InsnArg.reg(accRegister, ArgType.OBJECT),
						InsnArg.reg(accRegister, ArgType.OBJECT));

			case 0xd8:
				return invokeHelperArg2(insn, "ldprivateproperty", InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 2))),
						InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 3))),
						InsnArg.reg(accRegister, ArgType.OBJECT));

			case 0xd9:
				return invokeHelperArg3(insn, "stprivateproperty", InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 2))),
						InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 3))),
						getRegisterArg(asmItem, 4, ArgType.OBJECT), InsnArg.reg(accRegister, ArgType.OBJECT));

			case 0xda:
				return invokeHelperArg3(insn, "testin", InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 2))),
						InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 3))),
						InsnArg.reg(accRegister, ArgType.OBJECT), InsnArg.reg(accRegister, ArgType.OBJECT));

			case 0xdb:
				return makePutField(asmItem, getRegisterByOpIndex(asmItem, 3), accRegister, 2);

			case 0xd6:
				return invokeHelperArg2(insn, "setgeneratorstate", InsnArg.reg(accRegister, ArgType.NARROW),
						InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 1))),
						null);

			case 0xd1:
			case 0xd0:
				return invokeHelperArg3(insn, "stsuperbyname", InsnArg.wrapArg(new ConstStringNode(getStringOpFormat(asmItem, 2))),
						InsnArg.reg(accRegister, ArgType.NARROW), getRegisterArg(asmItem, 3, ArgType.OBJECT),
						null);

			case 0xba:
				return invokeHelperArg3(insn, "apply", InsnArg.reg(getIntOpUnit(asmItem, 2), ArgType.OBJECT),
						getRegisterArg(asmItem, 3, ArgType.OBJECT),
						InsnArg.reg(accRegister, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xbb:
				return supercallarrowrange(insn);

			case 0xbc: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 4, "definegettersetterbyvalue");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 4);

				for (int i = 0; i < 4; i++) {
					invoke.addArg(getRegisterArg(asmItem, i + 1, ArgType.OBJECT));
				}
				invoke.setResult(InsnArg.reg(accRegister, ArgType.BOOLEAN));
				return invoke;
			}

			case 0xcc:
			case 0x7a:
				return makePutField(asmItem, getRegisterByOpIndex(asmItem, 3), accRegister, 2);

			case 0x7b:
				return invokeHelperArg1(insn, "getmodulenamespace", InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 1))),
						InsnArg.reg(accRegister, ArgType.NARROW));

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
				return supercallthisrange(insn, false);

			case 0x83:
			case 0x08:
				return newobjrange(insn);

			case 0x8a:
			case 0x3c:
				return ldlexvar(insn, asmItem, accRegister, false);

			case 0x85:
			case 0x37: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "ldobjbyvalue");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addArg(getRegisterArg(asmItem, 2, ArgType.OBJECT));
				invoke.addArg(InsnArg.reg(accRegister, ArgType.NARROW));
				invoke.setResult(InsnArg.reg(accRegister, ArgType.NARROW));
				return invoke;
			}

			case 0x39:
			case 0x87:
				return invokeHelperArg2(insn, "ldsuperbyvalue", InsnArg.reg(accRegister, ArgType.NARROW),
						getRegisterArg(asmItem, 1, ArgType.OBJECT), InsnArg.reg(accRegister, ArgType.NARROW));

			case 0x86:
			case 0x38: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "stobjbyvalue");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
				invoke.addArg(getRegisterArg(asmItem, 2, ArgType.NARROW));
				invoke.addArg(getRegisterArg(asmItem, 3, ArgType.NARROW));
				invoke.addArg(InsnArg.reg(accRegister, ArgType.NARROW));
				return invoke;
			}

			case 0xcb:
			case 0x79:
				return stownbyindex(insn, asmItem, accRegister);

			case 0x04: {

				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 0, "createemptyobject");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 0);
				invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
				return invoke;
			}

			case 0x42:
			case 0x90:
				return makeGetField(asmItem, accRegister, accRegister, 2);

			case 0x09:
				return newlexenv(insn, asmItem, accRegister, false);

			case 0x69: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 0, "poplexenv");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 0);
				return invoke;
			}

			case 0x1d:
			case 0x1e: {
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "tonumer");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
				invoke.addArg(InsnArg.reg(accRegister, ArgType.NARROW));
				invoke.setResult(InsnArg.reg(accRegister, ArgType.INT));
				return invoke;
			}

			case 0xbd:
				return invokeHelperArg1(insn, "dynamicimport", InsnArg.reg(accRegister, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xbf:
				return invokeHelperArg1(insn, "resumegenerator", InsnArg.reg(accRegister, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));
			case 0xc0:
				return invokeHelperArg1(insn, "getresumemode", InsnArg.reg(accRegister, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xc2:
				return invokeHelperArg2(insn, "delobjprop", getRegisterArg(asmItem, 1, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW), InsnArg.reg(accRegister, ArgType.NARROW));
			case 0xc3:
				return invokeHelperArg2(insn, "suspendgenerator", getRegisterArg(asmItem, 1, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW), InsnArg.reg(accRegister, ArgType.NARROW));
			case 0xc4:
				return invokeHelperArg2(insn, "asyncfunctionawaituncaught",
						getRegisterArg(asmItem, 1, ArgType.NARROW), InsnArg.reg(accRegister, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xc5:
				return invokeHelperArg2(insn, "copydataproperties", getRegisterArg(asmItem, 1, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW), InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xc6:
				return invokeHelperArg3(insn, "starrayspread ", getRegisterArg(asmItem, 1, ArgType.NARROW),
						getRegisterArg(asmItem, 2, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW), InsnArg.reg(accRegister, ArgType.NARROW));

			case 0x66:
				return invokeHelperArg1(insn, "getpropiterator", InsnArg.reg(accRegister, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));
			case 0x67:
				return invokeHelperArg1(insn, "getiterator", InsnArg.reg(accRegister, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));
			case 0x68:
				return invokeHelperArg1(insn, "closeiterator", getRegisterArg(asmItem, 2, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0xca:
			case 0xc9:
				return invokeHelperArg3(insn, "stsuperbyvalue ", getRegisterArg(asmItem, 3, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW), getRegisterArg(asmItem, 2, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0x36:
				return invokeHelperArg1(insn, "getnextpropname", getRegisterArg(asmItem, 1, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0x88:
			case 0x3a:
				return invokeHelperArg2(insn, "ldobjbyindex",
						InsnArg.reg(accRegister, ArgType.NARROW), getRegisterArg(asmItem, 2, ArgType.NARROW),
						InsnArg.reg(accRegister, ArgType.NARROW));

			case 0x89:
			case 0x3b:
				return invokeHelperArg3(insn, "stobjbyindex", getRegisterArg(asmItem, 2, ArgType.OBJECT),
						InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 3))), InsnArg.reg(accRegister, ArgType.NARROW), null);

			case 0x92:
			case 0x46: {
				List<InstFmt> formats = asmItem.getIns().getFormat();
				String name = ((InstFmt.SId) formats.get(2)).getString(asmItem);
				return invokeHelperArg2(insn, "ldsuperbyname", InsnArg.reg(accRegister, ArgType.OBJECT),
						InsnArg.wrapArg(new ConstStringNode(name)), InsnArg.reg(accRegister, ArgType.NARROW));
			}

			case 0x7d:
				return ldlocalmodulevar(insn, asmItem, accRegister, false);

			case 0x8d:
			case 0x7f:
			case 0x40: {

				List<InstFmt> formats = asmItem.getIns().getFormat();
				String name = ((InstFmt.SId) formats.get(2)).getString(asmItem);
				MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "trystglobalbyname");
				InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
				invoke.addArg(InsnArg.wrapArg(new ConstStringNode(name, false)));
				invoke.addArg(InsnArg.reg(accRegister, ArgType.NARROW));
				return invoke;
			}

			case 0x3f:
			case 0x8c:
			case 0x41: {
				ArgType clsType = ArgType.object(getStringOpFormat(asmItem, 2));
				InsnNode constClsInsn = new ConstClassNode(clsType);
				constClsInsn.setResult(InsnArg.reg(accRegister, ArgType.generic(Consts.CLASS_CLASS, clsType)));
				return constClsInsn;
			}

		}

		if (nOp == 0xfe) {
			nOp = asmItem.getOpUnits().get(1).intValue();
			switch (nOp) {
				case 0x09:
				case 0x08:
				case 0x07:
					return new InsnNode(InsnType.NOP, 0);
				case 0x00: {
					MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "throw");
					InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
					invoke.addArg(InsnArg.reg(accRegister, ArgType.NARROW));
					return invoke;
				}
				case 0x01:
					invokeHelperArg0(insn, "throw.notexists", null);

				case 0x05:
					return invokeHelperArg1(insn, "throw.ifnotobject", getRegisterArg(asmItem, 2, ArgType.NARROW),
							null);
				case 0x06:
					return invokeHelperArg2(insn, "throw.undefinedifhole",
							getRegisterArg(asmItem, 2, ArgType.OBJECT), getRegisterArg(asmItem, 3, ArgType.NARROW), null);
				case 0x02:
					return invokeHelperArg0(insn, "throw.patternnoncoercible", null);
				case 0x03:
					return invokeHelperArg0(insn, "throw.deletesuperproperty", null);
				case 0x04:
					return invokeHelperArg1(insn, "throw.constassignment", getRegisterArg(asmItem, 2, ArgType.NARROW),
							null);

			}
		} else if (nOp == 0xfd) {
			nOp = asmItem.getOpUnits().get(1).intValue() & 0xff;
			switch (nOp) {
				case 0x08:
					return invokeHelperArg2(insn, "ldobjbyindex",
							InsnArg.reg(accRegister, ArgType.NARROW), getRegisterArg(asmItem, 2, ArgType.NARROW),
							InsnArg.reg(accRegister, ArgType.NARROW));
				case 0x09:
					return invokeHelperArg3(insn, "stobjbyindex", getRegisterArg(asmItem, 2, ArgType.OBJECT),
							InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 3))), InsnArg.reg(accRegister, ArgType.NARROW), null);

				case 0x00:
					return createobjectwithexcludedkeys(insn, true);
				case 0x01:
					return newobjrange(insn);

				case 0x06:
					return supercallthisrange(insn, true);
				case 0x0c:
					return ldlexvar(insn, asmItem, accRegister, true);
				case 0x0d:
					return stlexvar(insn, asmItem, accRegister, true);
				case 0x02:
					return newlexenv(insn, asmItem, accRegister, true);
				case 0x03:
					return newlexenvwithname(insn, asmItem, accRegister, true);
				case 0x04:
					return callrange(insn);
				case 0x05:
					return callThisRange(insn);
				case 0x0a:
					return stownbyindex(insn, asmItem, accRegister);
				case 0x0b:
					return copyrestargs(insn, true);
				case 0x0f:
					return stmodulevar(insn, accRegister, asmItem, true);
				case 0x10:
					return ldlocalmodulevar(insn, asmItem, accRegister, true);
				case 0x11:
					return ldexternalmodulevar(insn, asmItem, accRegister, true);

				case 0x12:
					return invokeHelperArg1(insn, "ldpatchvar", InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 2))),
							InsnArg.reg(accRegister, ArgType.NARROW));
				case 0x13:
					return invokeHelperArg2(insn, "stpatchvar", InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 2))),
							InsnArg.reg(accRegister, ArgType.NARROW), null);
			}
		} else if (nOp == 0xfb) {
			nOp = asmItem.getOpUnits().get(1).intValue();
			switch (nOp) {
				case 0x00:
					return invokeHelperArg1(insn, "callruntime.notifyconcurrentresult",
							InsnArg.reg(accRegister, ArgType.NARROW), null);
				case 0x01:
					return invokeHelperArg3(insn, "definefieldbyvalue", getRegisterArg(asmItem, 4, ArgType.OBJECT),
							getRegisterArg(asmItem, 3, ArgType.NARROW), InsnArg.reg(accRegister, ArgType.NARROW), null);

				case 0x02:
					return invokeHelperArg3(insn, "definefieldbyindex", getRegisterArg(asmItem, 4, ArgType.OBJECT),
							InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 3))), InsnArg.reg(accRegister, ArgType.NARROW), null);

				case 0x03:
					return invokeHelperArg1(insn, "callruntime.topropertykey",
							InsnArg.reg(accRegister, ArgType.NARROW), null);
				case 0x04: {
					//createprivateproperty
					int cnt = getIntOpUnit(asmItem, 2);
					LiteralArray la = ((InstFmt.LId) asmItem.getIns().getFormat().get(3)).getLA(asmItem);
					return invokeHelperArg2(insn, "createprivateproperty",
							InsnArg.wrapArg(new ConstIntNode(cnt)),
							InsnArg.wrapArg(new ConstStringNode(la.toString(), false)),
							null);
				}

				case 0x05: {
					return invokeHelperArg4(insn, "defineprivateproperty", InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 3))),
							InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 4))),
							getRegisterArg(asmItem, 5, ArgType.NARROW), InsnArg.reg(accRegister, ArgType.NARROW), null);

				}

				case 0x07: {
					List<InstFmt> formats = asmItem.getIns().getFormat();
					MethodItem targetMth = ((InstFmt.MId) formats.get(3)).getMethod(asmItem);
					LiteralArray la = ((InstFmt.LId) formats.get(4)).getLA(asmItem);
					int cnt = getIntOpUnit(asmItem, 5);
					int parentClassReg = getIntOpUnit(asmItem, 6);

					return invokeHelperArg4(insn, "definesendableclass", InsnArg.wrapArg(new ConstStringNode(targetMth.getName())),
							InsnArg.wrapArg(new ConstStringNode(la.toString())),
							InsnArg.wrapArg(new ConstIntNode(cnt)),
							InsnArg.reg(parentClassReg, ArgType.NARROW), InsnArg.reg(accRegister, ArgType.NARROW));
				}
				case 0x08:
					return invokeHelperArg1(insn, "ldsendableclass",
							InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, 2))), null);

			}
		}

		throw new DecodeException("Unknown instruction: '" + asmItem.getIns().getInstruction().toString() + '\'');

	}

	private @NotNull InvokeNode ldexternalmodulevar(InsnData insn, Asm.AsmItem asmItem, int accRegister, boolean wide) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "ldexternalmodulevar");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
		int slotIndex = 1;
		if(wide) {
			slotIndex += 1;
		}
		AbcClass abcClass = getAbcClassByInsn(insn);
		ModuleLiteralArray.RegularImport imp =
				abcClass.getModuleInfo().getRegularImports().get(getIntOpUnit(asmItem, slotIndex));
		invoke.addArg(InsnArg.wrapArg(new ConstStringNode(imp.toString(), false)));
		invoke.addArg(InsnArg.wrapArg(new ConstStringNode(imp.getImportName(), false)));
		invoke.addArg(InsnArg.wrapArg(new ConstStringNode(imp.getLocalName(), false)));
		invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
		return invoke;
	}

	private @NotNull InvokeNode ldlocalmodulevar(InsnData insn, Asm.AsmItem asmItem, int accRegister, boolean wide) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "ldlocalmodulevar");
		int slotIndex = 1;
		if(wide) {
			slotIndex += 1;
		}
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, slotIndex))));
		invoke.setResult(InsnArg.reg(accRegister, ArgType.NARROW));
		return invoke;
	}

	private @NotNull InvokeNode stmodulevar(InsnData insn, int accRegister, Asm.AsmItem asmItem, boolean wide) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "stmodulevar");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);

		int slotIndex = 1;
		if(wide) {
			slotIndex += 1;
		}

		invoke.addReg(accRegister, ArgType.OBJECT);
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(getIntOpUnit(asmItem, slotIndex))));
		return invoke;
	}

	private @NotNull InvokeNode stownbyindex(InsnData insn, Asm.AsmItem asmItem, int accRegister) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "stownbyindex");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
		int b = asmItem.getOpUnits().get(3).intValue();
		invoke.addArg(getRegisterArg(asmItem, 2, ArgType.NARROW));
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(b)));
		invoke.addArg(InsnArg.reg(accRegister, ArgType.NARROW));
		return invoke;
	}

	private @NotNull InvokeNode newlexenvwithname(InsnData insn, Asm.AsmItem asmItem, int accRegister, boolean wide) {
		int k = 0;
		if (wide) {
			k++;
		}

		List<InstFmt> formats = asmItem.getIns().getFormat();
		LiteralArray la = ((InstFmt.LId) formats.get(2 + k)).getLA(asmItem);
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "newlexenvwithname");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
		invoke.addArg(InsnArg.wrapArg(new ConstStringNode(la.toString(), false)));
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(1 + k).intValue())));

		invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
		return invoke;
	}

	private @NotNull InvokeNode newlexenv(InsnData insn, Asm.AsmItem asmItem, int accRegister, boolean wide) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, "newlexenv");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);

		int k = 0;
		if (wide) {
			k++;
		}

		int a = asmItem.getOpUnits().get(1 + k).intValue();
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(a)));
		invoke.setResult(InsnArg.reg(accRegister, ArgType.OBJECT));
		return invoke;
	}

	private @NotNull InvokeNode stlexvar(InsnData insn, Asm.AsmItem asmItem, int accRegister, boolean wide) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, "stlexvar");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);

		int k = 0;
		if (wide) {
			k++;
		}

		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(1 + k).intValue())));
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(asmItem.getOpUnits().get(2 + k).intValue())));
		invoke.addReg(accRegister, ArgType.OBJECT);
		return invoke;
	}

	private @NotNull InvokeNode ldlexvar(InsnData insn, Asm.AsmItem asmItem, int accRegister, boolean wide) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, "ldlexvar");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);

		int k = 0;
		if (wide) {
			k++;
		}
		int a = asmItem.getOpUnits().get(k + 1).intValue();
		int b = asmItem.getOpUnits().get(k + 2).intValue();

		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(a)));
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(b)));
		invoke.setResult(InsnArg.reg(accRegister, ArgType.NARROW));
		return invoke;
	}

	private @NotNull InsnNode makePutField(Asm.AsmItem asmItem, int objReg, int valueReg, int fieldNameOpIndex) {
		String fieldName = getStringOpFormat(asmItem, fieldNameOpIndex);
		FieldInfo iputFld2 = FieldInfo.fromAsm(root, asmItem, fieldName);
		InsnNode iputInsn2 = new IndexInsnNode(InsnType.IPUT, iputFld2, 2);
		iputInsn2.addArg(InsnArg.reg(valueReg, ArgType.NARROW));
		iputInsn2.addArg(InsnArg.reg(objReg, ArgType.OBJECT));
		return iputInsn2;
	}

	private @NotNull InsnNode makePutField(Asm.AsmItem asmItem, int objReg, int valueReg, String fieldName) {
		FieldInfo iputFld2 = FieldInfo.fromAsm(root, asmItem, fieldName);

		InsnNode iputInsn2 = new IndexInsnNode(InsnType.IPUT, iputFld2, 2);
		iputInsn2.addArg(InsnArg.reg(valueReg, tryResolveFieldType(iputFld2)));
		iputInsn2.addArg(InsnArg.reg(objReg, iputFld2.getDeclClass().getType()));
		return iputInsn2;
	}

	private @NotNull InsnNode makeGetField(Asm.AsmItem asmItem, int objectReg, int resultRegister, int nameOpIndex) {
		List<InstFmt> formats = asmItem.getIns().getFormat();
		String gName = ((InstFmt.SId) formats.get(nameOpIndex)).getString(asmItem);
		FieldInfo igetFld2 = FieldInfo.fromAsm(root, asmItem, gName);
		InsnNode igetInsn2 = new IndexInsnNode(InsnType.IGET, igetFld2, 1);
		igetInsn2.setResult(InsnArg.reg(resultRegister, tryResolveFieldType(igetFld2)));
		igetInsn2.addArg(InsnArg.reg(objectReg, igetFld2.getDeclClass().getType()));
		return igetInsn2;
	}

	private @NotNull InsnNode makeGetField(Asm.AsmItem asmItem, int objectReg, int resultRegister, String fieldName) {
		List<InstFmt> formats = asmItem.getIns().getFormat();
		FieldInfo igetFld2 = FieldInfo.fromAsm(root, asmItem, fieldName);
		InsnNode igetInsn2 = new IndexInsnNode(InsnType.IGET, igetFld2, 1);
		igetInsn2.setResult(InsnArg.reg(resultRegister, tryResolveFieldType(igetFld2)));
		igetInsn2.addArg(InsnArg.reg(objectReg, igetFld2.getDeclClass().getType()));
		return igetInsn2;
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
			invoke.addArg(getRegisterArg(asmItem, i + 2, ArgType.NARROW));
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

	private InsnNode copyrestargs(InsnData insn, boolean wide) {
		ArgType arrType = new ArgType.ArrayArg(ArgType.OBJECT);
		ArgType elType = arrType.getArrayElement();

		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int numVRegs = mth.getCodeItem().getNumVRegs();
		int numArgs = mth.getCodeItem().getNumArgs();
		int accReg = numArgs + numVRegs;
		List<Number> opUnits = asmItem.getOpUnits();

		int argRegStart = numVRegs;

		int argIndex = 1;
		if(wide) {
			argIndex++;
		}

		int insnArg = getIntOpUnit(asmItem, argIndex);
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

	private InsnNode cmp(int a1, int a2, int resultRegister, InsnType itype, ArgType argType) {
		InsnNode inode = new InsnNode(itype, 2);
		inode.setResult(InsnArg.reg(resultRegister, ArgType.INT));
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

	private InsnNode invokeHelperArg0(InsnData insn, String helper, RegisterArg result) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 0, helper);
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 0);
		invoke.setResult(result);
		return invoke;
	}

	private InsnNode invokeHelperArg1(InsnData insn, String helper, InsnArg arg, RegisterArg result) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 1, helper);
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 1);
		invoke.addArg(arg);
		invoke.setResult(result);
		return invoke;
	}

	private InsnNode invokeHelperArg2(InsnData insn, String helper, InsnArg arg1, InsnArg arg2, RegisterArg result) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 2, helper);
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2);
		invoke.addArg(arg1);
		invoke.addArg(arg2);
		invoke.setResult(result);
		return invoke;
	}

	private InsnNode invokeHelperArg3(InsnData insn, String helper, InsnArg arg1, InsnArg arg2, InsnArg arg3, RegisterArg result) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, helper);
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
		invoke.addArg(arg1);
		invoke.addArg(arg2);
		invoke.addArg(arg3);
		invoke.setResult(result);
		return invoke;
	}

	private InsnNode invokeHelperArg4(InsnData insn, String helper, InsnArg arg1, InsnArg arg2, InsnArg arg3, InsnArg arg4, RegisterArg result) {
		MethodInfo mthInfo = MethodInfo.fromAsm(root, insn.getAsmItem(), 3, helper);
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3);
		invoke.addArg(arg1);
		invoke.addArg(arg2);
		invoke.addArg(arg3);
		invoke.addArg(arg4);
		invoke.setResult(result);
		return invoke;
	}

	private InsnNode createobjectwithexcludedkeys(InsnData insn, boolean wide) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();

		int k = 0;
		if (wide) {
			k++;
		}

		int n = getIntOpUnit(asmItem, 1 + k);
		int objectRegister = getRegisterByOpIndex(asmItem, 2 + k);
		int baseReg = getRegisterByOpIndex(asmItem, 3 + k);

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, 3 + n, "createobjectwithexcludedkeys");

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3 + n);
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(n)));
		invoke.addReg(objectRegister, ArgType.OBJECT); // object
		for (int i = 0; i <= n; i++) {
			invoke.addReg(baseReg + i, ArgType.NARROW);
		}
		invoke.setResult(InsnArg.reg(accReg, ArgType.OBJECT));
		return invoke;
	}

	private InsnNode supercallarrowrange(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();

		int n = getIntOpUnit(asmItem, 1);
		int baseReg = getRegisterByOpIndex(asmItem, 2);

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, 2 + n, "supercallarrowrange");

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 2 + n);
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(n)));
		invoke.addReg(accReg, ArgType.OBJECT); // object
		for (int i = 0; i < n; i++) {
			invoke.addReg(baseReg + i, ArgType.NARROW);
		}
		invoke.setResult(InsnArg.reg(accReg, ArgType.OBJECT));
		return invoke;
	}

	private InsnNode callthisN(InsnData insn, int n) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		int thisReg = getRegisterByOpIndex(asmItem, 2);

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, 3 + n, "callthisN");

		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, 3 + n);
		invoke.addArg(InsnArg.wrapArg(new ConstIntNode(n)));
		invoke.addReg(thisReg, ArgType.OBJECT); // object
		invoke.addReg(accReg, ArgType.NARROW); // object
		for (int i = 0; i < n; i++) {
			invoke.addReg(getRegisterByOpIndex(asmItem, 3 + i), ArgType.NARROW);
		}
		invoke.setResult(InsnArg.reg(accReg, ArgType.NARROW));
		return invoke;
	}

	private InsnNode callThisRange(InsnData insn) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		List<Number> opUnits = asmItem.getOpUnits();
		int thisReg = getRegisterByOpIndex(asmItem, 3);
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
		int baseArg = getRegisterByOpIndex(asmItem, 3);
		int argc = getIntOpUnit(asmItem, 2);

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

		int argReg = getRegisterByOpIndex(asmItem, 2);
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

	private InsnNode supercallthisrange(InsnData insn, boolean wide) {
		Asm.AsmItem asmItem = insn.getAsmItem();
		AbcMethod mth = asmItem.getAsm().getCode().getMethod();
		int accReg = mth.getCodeItem().getNumArgs() + mth.getCodeItem().getNumVRegs();
		List<Number> opUnits = asmItem.getOpUnits();

		int k = 0;
		if (wide) {
			k++;
		}

		int baseArg = getRegisterByOpIndex(asmItem, 3);
		int argc = opUnits.get(2).intValue();

		MethodInfo mthInfo = MethodInfo.fromAsm(root, asmItem, argc, "super");
		InvokeNode invoke = new InvokeNode(mthInfo, InvokeType.STATIC, argc);

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

		int classArg = getRegisterByOpIndex(asmItem, 3);

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

		return new ArithNode(op, InsnArg.reg(accReg, ArgType.INT), getRegisterArg(asmItem, 2, ArgType.INT),
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
