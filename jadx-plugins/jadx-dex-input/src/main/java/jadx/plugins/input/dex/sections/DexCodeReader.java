package jadx.plugins.input.dex.sections;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import me.yricky.oh.abcd.code.TryBlock;
import org.jetbrains.annotations.Nullable;

import me.yricky.oh.abcd.cfm.AbcMethod;
import me.yricky.oh.abcd.code.Code;
import me.yricky.oh.abcd.isa.Asm;

import jadx.api.plugins.input.data.ICatch;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IDebugInfo;
import jadx.api.plugins.input.data.ITry;
import jadx.api.plugins.input.data.impl.CatchData;
import jadx.api.plugins.input.data.impl.TryData;
import jadx.api.plugins.input.insns.InsnData;
import jadx.plugins.input.dex.insns.DexInsnData;
import jadx.plugins.input.dex.insns.DexInsnFormat;
import jadx.plugins.input.dex.insns.DexInsnInfo;
import jadx.plugins.input.dex.sections.debuginfo.DebugInfoParser;

public class DexCodeReader implements ICodeReader {

	private final SectionReader in;
	private int mthId;
	public AbcMethod abcMethod;

	public DexCodeReader(SectionReader in) {
		this.in = in;
	}

	public DexCodeReader(SectionReader in, AbcMethod abcMethod) {
		this.in = in;
		this.abcMethod = abcMethod;
	}

	@Override
	public DexCodeReader copy() {
		DexCodeReader copy = new DexCodeReader(in.copy(), abcMethod);
		copy.setMthId(this.getMthId());
		return copy;
	}

	public void setOffset(int offset) {
		this.in.setOffset(offset);
	}

	@Override
	public int getRegistersCount() {
		Code codeItem = abcMethod.getCodeItem();
		int numVRegs = codeItem.getNumVRegs();
		int numArgs = codeItem.getNumArgs();
		return numVRegs + numArgs + 1; // for acc
	}

	@Override
	public int getArgsStartReg() {
		return abcMethod.getCodeItem().getNumVRegs();
	}

	@Override
	public int getUnitsCount() {
		Code code = abcMethod.getCodeItem();
		return code.getCodeSize();
	}

	@Override
	public void visitInstructions(Consumer<InsnData> insnConsumer) {
		DexInsnData insnData = new DexInsnData(this, in.copy());

		Code code = abcMethod.getCodeItem();
		Asm asm = code.getAsm();
		List<Asm.AsmItem> asmItems = asm.getList();

		for (Asm.AsmItem asmItem : asmItems) {
			insnData.setAsmItem(asmItem);
			insnConsumer.accept(insnData);
		}
	}

	public void decode(DexInsnData insn) {
		DexInsnFormat format = insn.getInsnInfo().getFormat();
		format.decode(insn, insn.getOpcodeUnit(), insn.getCodeData().in);
		insn.setDecoded(true);
	}

	public void skip(DexInsnData insn) {
		DexInsnInfo insnInfo = insn.getInsnInfo();
		if (insnInfo != null) {
			DexCodeReader codeReader = insn.getCodeData();
			insnInfo.getFormat().skip(insn, codeReader.in);
		}
	}

	@Nullable
	@Override
	public IDebugInfo getDebugInfo() {
		int debugOff = in.pos(8).readInt();
		if (debugOff == 0) {
			return null;
		}
		int regsCount = getRegistersCount();
		DebugInfoParser debugInfoParser = new DebugInfoParser(in, regsCount, getUnitsCount());
		debugInfoParser.initMthArgs(regsCount, in.getMethodParamTypes(mthId));
		return debugInfoParser.process(debugOff);
	}

	private int getTriesCount() {
		return in.pos(6).readUShort();
	}

	private int getTriesOffset() {
		int triesCount = getTriesCount();
		if (triesCount == 0) {
			return -1;
		}
		int insnsCount = getUnitsCount();
		int padding = insnsCount % 2 == 1 ? 2 : 0;
		return 4 * 4 + insnsCount * 2 + padding;
	}

	@Override
	public List<ITry> getTries() {
		Code codeItem = abcMethod.getCodeItem();
		int triesCount = codeItem.getTriesSize();
		if (triesCount == 0) {
			return Collections.emptyList();
		}

		List<TryBlock> tryBlocks = codeItem.getTryBlocks();
		List<ITry> triesList = new ArrayList<>(triesCount);
		for (int i = 0; i < triesCount; i++) {
			TryBlock tryBlock = tryBlocks.get(i);
			int startAddr = tryBlock.getStartPc();
			int insnsCount = tryBlock.getLength();

			ArrayList<TryBlock.CatchBlock> catchBlocks = tryBlock.getCatchBlocks();
			int[] addrs = new int[catchBlocks.size()];
			String[] types = new String[catchBlocks.size()];
			for (int j = 0; j < catchBlocks.size(); j++) {
				TryBlock.CatchBlock catchBlock = catchBlocks.get(j);
				addrs[j] = catchBlock.getHandlerPc();
				types[j] = String.format("ExceptionI%d", catchBlock.getTypeIdx());
			}
			CatchData catchHandler = new CatchData(addrs, types, -1);
			triesList.add(new TryData(startAddr, startAddr + insnsCount - 1, catchHandler));
		}
		return triesList;
	}

	private Map<Integer, ICatch> getCatchHandlers(int offset, SectionReader ext) {
		in.pos(offset);
		int byteOffsetStart = in.getAbsPos();
		int size = in.readUleb128();
		Map<Integer, ICatch> map = new HashMap<>(size);
		for (int i = 0; i < size; i++) {
			int byteIndex = in.getAbsPos() - byteOffsetStart;
			int sizeAndType = in.readSleb128();
			int handlersLen = Math.abs(sizeAndType);
			int[] addr = new int[handlersLen];
			String[] types = new String[handlersLen];
			for (int h = 0; h < handlersLen; h++) {
				types[h] = ext.getType(in.readUleb128());
				addr[h] = in.readUleb128();
			}
			int catchAllAddr;
			if (sizeAndType <= 0) {
				catchAllAddr = in.readUleb128();
			} else {
				catchAllAddr = -1;
			}
			map.put(byteIndex, new CatchData(addr, types, catchAllAddr));
		}
		return map;
	}

	@Override
	public int getCodeOffset() {
		return in.getOffset();
	}

	public void setMthId(int mthId) {
		this.mthId = mthId;
	}

	public int getMthId() {
		return mthId;
	}
}
