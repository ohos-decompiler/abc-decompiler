package jadx.core.codegen;

import java.util.HashMap;
import java.util.Map;

import jadx.api.ICodeWriter;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.utils.exceptions.CodegenException;

interface SpecialMethodHandler {
	void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException;
}

class NewObjRangeHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		InsnArg arg = insn.getArg(0);
		gen.addArg(code, arg, true);
		gen.generateMethodArguments(code, insn, 1, callMthNode);
	}
}

class StownByNameHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		gen.addArg(code, insn.getArg(0), false);
		code.add(".");
		gen.addStrArg(code, insn.getArg(1));
		code.add(" = ");
		gen.addArg(code, insn.getArg(2), false);
	}
}

// callthisN
class CallThisNHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		gen.addArg(code, insn.getArg(2), true);
		gen.generateMethodArguments(code, insn, 3, callMthNode);
	}
}

// callargsN
class CallArgsNHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		gen.addArg(code, insn.getArg(1), true);
		gen.generateMethodArguments(code, insn, 2, callMthNode);
	}
}

// stownbyindex
class StownByIndexHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		gen.addArg(code, insn.getArg(0), false);
		code.add("[");
		gen.addArg(code, insn.getArg(1), false);
		code.add("]");
		code.add(" = ");
		gen.addArg(code, insn.getArg(2), false);
	}
}

// stmodulevar
class StModuleVarHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		code.add("__module__[");
		gen.addArg(code, insn.getArg(1), false);
		code.add("]");
		code.add(" = ");
		gen.addArg(code, insn.getArg(0), false);
	}
}

// ldexternalmodulevar
class LdexternalModuleVarHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		gen.addArg(code, insn.getArg(0), false);
	}
}

public class SpecialMethodGen {
	Map<String, SpecialMethodHandler> handlers = new HashMap<String, SpecialMethodHandler>();

	public SpecialMethodGen() {
		handlers.put("newobjrange", new NewObjRangeHandler());
		handlers.put("stownbyname", new StownByNameHandler());
		handlers.put("callthisN", new CallThisNHandler());
		handlers.put("callargsN", new CallArgsNHandler());
		handlers.put("stownbyindex", new StownByIndexHandler());
		handlers.put("stmodulevar", new StModuleVarHandler());
		handlers.put("ldexternalmodulevar", new LdexternalModuleVarHandler());
	}

	public boolean processMethod(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		MethodInfo callMth = insn.getCallMth();
		SpecialMethodHandler handler = handlers.get(callMth.getName());
		if (handler == null) {
			return false;
		}
		handler.process(gen, insn, code, callMthNode);
		return true;
	}
}
