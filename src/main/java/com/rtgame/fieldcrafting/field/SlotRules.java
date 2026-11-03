package com.rtgame.fieldcrafting.field;

import net.minecraft.item.ItemStack;

/**
 * 描述一个容器里哪些槽位可以被场域库存访问。
 * 默认实现（{@link #ALL}）把容器当作普通箱子：所有槽位可用，存入只看 isItemValidForSlot。
 */
public interface SlotRules {

    /** 槽位是否属于场域库存可见的范围（如 GT 机器的电路槽、内部处理槽不可见）。 */
    boolean isUsable(int slot);

    /** 槽位是否允许取出。 */
    boolean canExtract(int slot, ItemStack stack);

    /** 槽位是否允许存入（不含 isItemValidForSlot 的检查，由调用方另行判断）。 */
    boolean canInsert(int slot, ItemStack stack);

    SlotRules ALL = new SlotRules() {

        @Override
        public boolean isUsable(int slot) {
            return true;
        }

        @Override
        public boolean canExtract(int slot, ItemStack stack) {
            return true;
        }

        @Override
        public boolean canInsert(int slot, ItemStack stack) {
            return true;
        }
    };
}
