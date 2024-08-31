package jadx.core.codegen;

import java.util.HashMap;
import java.util.Map;

import jadx.api.ICodeWriter;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.InsnWrapArg;
import jadx.core.dex.instructions.args.LiteralArg;
import jadx.core.dex.nodes.InsnNode;
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

// ldobjbyvalue
class LdojbbyvalueHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		gen.addArg(code, insn.getArg(0), false);
		code.add("[");
		gen.addArg(code, insn.getArg(1), false);
		code.add("]");
	}
}

// stobjbyvalue
class StojbbyvalueHandler implements SpecialMethodHandler {
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
		code.add("_module_");
		gen.addArg(code, insn.getArg(1), false);
		code.add("_");
		code.add(" = ");
		gen.addArg(code, insn.getArg(0), false);
	}
}

// ldlocalmodulevar
class LdlocalModuleVarHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		code.add("_module_");
		gen.addArg(code, insn.getArg(0), false);
		code.add("_");
	}
}


// trystglobalbyname
class TrystglobalbynameHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		gen.addArg(code, insn.getArg(0), false);
		code.add(" = ");
		gen.addArg(code, insn.getArg(1), false);
	}
}

// ldlexvar
class LdLexVarHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		code.add("_lexenv_");
		gen.addArg(code, insn.getArg(0), false);
		code.add("_");
		gen.addArg(code, insn.getArg(1), false);
		code.add("_");
	}
}

// stlexvar
class StLexVarHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		code.add("_lexenv_");
		gen.addArg(code, insn.getArg(0), false);
		code.add("_");
		gen.addArg(code, insn.getArg(1), false);
		code.add("_");
		code.add(" = ");
		gen.addArg(code, insn.getArg(2), false);
	}
}

// ldexternalmodulevar
class LdexternalModuleVarHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		gen.addArg(code, insn.getArg(0), false);
	}
}

// definefunc
class DefineFuncHandler implements SpecialMethodHandler {
	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		gen.addArg(code, insn.getArg(0), false);
	}
}





// definegettersetterbyvalue
class DefineGetterSetterByValueHandler implements SpecialMethodHandler {

	public boolean isNull(InsnArg arg) {
		if (arg.isLiteral()) {
			LiteralArg la = (LiteralArg) arg;
			return la.getLiteral() == 0;
		}

		if(arg.isInsnWrap()) {
			InsnNode insn = ((InsnWrapArg) arg).getWrapInsn();
			if(insn.getType() == InsnType.CAST) {
				return isNull(insn.getArg(0));
			}
		}

		return false;
	}

	public boolean generateProp(InsnGen gen, ICodeWriter code, String typ, InsnArg obj, InsnArg field, InsnArg value, boolean isNewLine)
			throws CodegenException {
		if (isNull(value)) {
			return false;
		}

		if (isNewLine) {
			code.add(";");
			code.startLine();
		}
		gen.addArg(code, obj, false);
		code.add("[");
		gen.addArg(code, field, false);
		code.add("].");
		code.add(typ);
		code.add(" = ");
		gen.addArg(code, value, false);
		return true;
	}

	@Override
	public void process(InsnGen gen, InvokeNode insn, ICodeWriter code, MethodNode callMthNode) throws CodegenException {
		boolean isNewLine = false;
		isNewLine = generateProp(gen, code, "getter", insn.getArg(0), insn.getArg(1), insn.getArg(2), isNewLine);
		generateProp(gen, code, "setter", insn.getArg(0), insn.getArg(1), insn.getArg(3), isNewLine);
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
		handlers.put("definegettersetterbyvalue", new DefineGetterSetterByValueHandler());
		handlers.put("ldlexvar", new LdLexVarHandler());
		handlers.put("stlexvar", new StLexVarHandler());
		handlers.put("trystglobalbyname", new TrystglobalbynameHandler());
		handlers.put("ldobjbyvalue", new LdojbbyvalueHandler());
		handlers.put("stobjbyvalue", new StojbbyvalueHandler());
		handlers.put("definefunc", new DefineFuncHandler());
		handlers.put("ldlocalmodulevar", new LdlocalModuleVarHandler());
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
