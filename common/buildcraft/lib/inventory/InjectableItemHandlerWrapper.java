/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.inventory;

import javax.annotation.Nonnull;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;

import net.minecraftforge.items.IItemHandler;

import buildcraft.api.transport.IInjectable;

public class InjectableItemHandlerWrapper implements IItemHandler {
    private final IInjectable injectable;
    private final EnumFacing from;

    public InjectableItemHandlerWrapper(IInjectable injectable, EnumFacing from) {
        this.injectable = injectable;
        this.from = from;
    }

    @Override
    public int getSlots() {
        return 1;
    }

    @Override
    @Nonnull
    public ItemStack getStackInSlot(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    @Nonnull
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        return injectable.injectItem(stack.copy(), !simulate, from, null, 0.04);
    }

    @Override
    @Nonnull
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }
}
