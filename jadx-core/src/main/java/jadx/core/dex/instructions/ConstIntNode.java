package jadx.core.dex.instructions;

import jadx.core.dex.nodes.InsnNode;

public final class ConstIntNode extends InsnNode {

	private final int number;

	public ConstIntNode(int number) {
		super(InsnType.CONST_INT, 0);
		this.number = number;
	}

	public int getNumber() {
		return number;
	}

	@Override
	public InsnNode copy() {
		return copyCommonParams(new ConstIntNode(number));
	}

	@Override
	public boolean isSame(InsnNode obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof ConstIntNode) || !super.isSame(obj)) {
			return false;
		}
		ConstIntNode other = (ConstIntNode) obj;
		return number == other.number;
	}

	@Override
	public String toString() {
		return super.toString() + ' ' + String.format("%d", number);
	}
}
