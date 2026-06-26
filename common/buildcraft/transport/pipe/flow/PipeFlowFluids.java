/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.flow;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import buildcraft.api.core.BCLog;
import buildcraft.api.core.EnumPipePart;
import buildcraft.api.core.IFluidFilter;
import buildcraft.api.core.IFluidHandlerAdv;
import buildcraft.api.core.SafeTimeTracker;
import buildcraft.api.tiles.IDebuggable;
import buildcraft.api.transport.pipe.IFlowFluid;
import buildcraft.api.transport.pipe.IPipe;
import buildcraft.api.transport.pipe.IPipe.ConnectedType;
import buildcraft.api.transport.pipe.PipeApi;
import buildcraft.api.transport.pipe.PipeApi.FluidTransferInfo;
import buildcraft.api.transport.pipe.PipeEventFluid;
import buildcraft.api.transport.pipe.PipeEventHandler;
import buildcraft.api.transport.pipe.PipeEventStatement;
import buildcraft.api.transport.pipe.PipeFlow;

import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.MathUtil;
import buildcraft.lib.misc.StringUtilBC;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.net.PacketBufferBC;
import buildcraft.lib.net.cache.BuildCraftObjectCaches;
import buildcraft.lib.net.cache.NetworkedObjectCache;

import buildcraft.core.BCCoreConfig;
import buildcraft.core.BCCoreItems;
import buildcraft.transport.BCTransportStatements;

public class PipeFlowFluids extends PipeFlow implements IFlowFluid, IDebuggable {

    private static final int DIRECTION_COOLDOWN = 60;
    private static final int COOLDOWN_INPUT = -DIRECTION_COOLDOWN;
    private static final int COOLDOWN_OUTPUT = DIRECTION_COOLDOWN;

    private static final ActionResult<FluidStack> FAILED_EXTRACT = new ActionResult<>(EnumActionResult.FAIL, null);
    private static final ActionResult<FluidStack> PASSED_EXTRACT = new ActionResult<>(EnumActionResult.PASS, null);

    public static final int NET_FLUID_AMOUNTS = 2;
    public static final double FLOW_MULTIPLIER = 0.016;

    private final FluidTransferInfo fluidTransferInfo = PipeApi.getFluidTransferInfo(pipe.getDefinition());

    public final int capacity = Math.max(Fluid.BUCKET_VOLUME, fluidTransferInfo.transferPerTick * (10));// TEMP!

    // Double-buffer: committed = pushable this tick, pending = received this tick (promoted end-of-tick)
    int committed = 0;
    int pending = 0;

    // Per-face fill rate limiter (world-tick-aware)
    private final int[] fillThisTick = new int[6];
    private long fillWorldTick = -1;

    private final Map<EnumPipePart, Section> sections = new EnumMap<>(EnumPipePart.class);
    private FluidStack currentFluid;
    private final SafeTimeTracker tracker = new SafeTimeTracker(BCCoreConfig.networkUpdateRate, 4);

    // Client fields
    private long lastMessage, lastMessageMinus1;
    int clientTotalTarget = 0;
    int clientTotalAmountThis = 0;
    int clientTotalAmountLast = 0;
    private NetworkedObjectCache<FluidStack>.Link clientFluid = null;
    private int lastSentTotal = 0;

    public PipeFlowFluids(IPipe pipe) {
        super(pipe);
        for (EnumPipePart part : EnumPipePart.VALUES) {
            sections.put(part, new Section(part));
        }
    }

    public PipeFlowFluids(IPipe pipe, NBTTagCompound nbt) {
        super(pipe, nbt);
        for (EnumPipePart part : EnumPipePart.VALUES) {
            sections.put(part, new Section(part));
        }
        if (nbt.hasKey("fluid")) {
            setFluid(FluidStack.loadFluidStackFromNBT(nbt.getCompoundTag("fluid")));
        } else {
            setFluid(null);
        }
        if (nbt.hasKey("totalAmount")) {
            committed = nbt.getInteger("totalAmount");
            for (EnumPipePart p : EnumPipePart.FACES) {
                sections.get(p).ticksInDirection = nbt.getShort("dir[" + p.getIndex() + "]");
            }
        } else {
            // Migrate old 7-section format
            int sum = 0;
            for (EnumPipePart p : EnumPipePart.VALUES) {
                String key = "tank[" + p.getIndex() + "]";
                if (nbt.hasKey(key)) {
                    NBTTagCompound compound = nbt.getCompoundTag(key);
                    sum += compound.getShort("capacity");
                    if (p != EnumPipePart.CENTER) {
                        sections.get(p).ticksInDirection = compound.getShort("ticksInDirection");
                    }
                }
            }
            committed = Math.min(sum, capacity);
        }
    }

    @Override
    public NBTTagCompound writeToNbt() {
        NBTTagCompound nbt = super.writeToNbt();
        int total = committed + pending;
        if (currentFluid != null && total > 0) {
            NBTTagCompound fluidTag = new NBTTagCompound();
            currentFluid.writeToNBT(fluidTag);
            nbt.setTag("fluid", fluidTag);
            nbt.setInteger("totalAmount", total);
            for (EnumPipePart p : EnumPipePart.FACES) {
                nbt.setShort("dir[" + p.getIndex() + "]", (short) sections.get(p).ticksInDirection);
            }
        }
        return nbt;
    }

    @Override
    public boolean canConnect(EnumFacing face, PipeFlow other) {
        return other instanceof IFlowFluid;
    }

    @Override
    public boolean canConnect(EnumFacing face, TileEntity oTile) {
        return oTile.hasCapability(CapUtil.CAP_FLUIDS, face.getOpposite());
    }

    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, EnumFacing facing) {
        if (capability == CapUtil.CAP_FLUIDS) {
            return CapUtil.CAP_FLUIDS.cast(sections.get(EnumPipePart.fromFacing(facing)));
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
        super.addDrops(toDrop, fortune);
        int total = committed + pending;
        if (currentFluid != null && total > 0 && BCCoreItems.fragileFluidShard != null) {
            BCCoreItems.fragileFluidShard.addFluidDrops(toDrop, new FluidStack(currentFluid, total));
        }
    }

    public boolean doesContainFluid() {
        return committed + pending > 0;
    }

    @PipeEventHandler
    public static void addTriggers(PipeEventStatement.AddTriggerInternal event) {
        event.triggers.add(BCTransportStatements.TRIGGER_FLUIDS_TRAVERSING);
    }

    // IFlowFluid

    @Override
    public FluidStack tryExtractFluid(int millibuckets, EnumFacing from, FluidStack filter, boolean simulate) {
        FluidExtractor extractor = (mb, c, handler) -> {
            FluidStack f = filter == null ? c : filter;
            return extractSimple(mb, f, handler, simulate);
        };
        return tryExtractFluidInternal(millibuckets, from, extractor, simulate).getResult();
    }

    @Override
    public ActionResult<FluidStack> tryExtractFluidAdv(int millibuckets, EnumFacing from, IFluidFilter filter,
        boolean simulate) {
        FluidExtractor extractor = (mb, c, handler) -> {
            if (c != null) {
                if (!filter.matches(c)) return null;
                return extractSimple(mb, c, handler, simulate);
            }
            if (handler instanceof IFluidHandlerAdv) {
                return ((IFluidHandlerAdv) handler).drain(filter, mb, !simulate);
            }
            IFluidTankProperties[] tanks = handler.getTankProperties();
            if (tanks == null) return null;
            for (IFluidTankProperties tank : tanks) {
                FluidStack contents = tank.getContents();
                if (contents != null && filter.matches(contents)) {
                    FluidStack extracted = extractSimple(mb, contents, handler, simulate);
                    if (extracted != null) return extracted;
                }
            }
            return null;
        };
        return tryExtractFluidInternal(millibuckets, from, extractor, simulate);
    }

    @FunctionalInterface
    private interface FluidExtractor {
        FluidStack extract(int millibuckets, FluidStack current, IFluidHandler handler);
    }

    private ActionResult<FluidStack> tryExtractFluidInternal(int millibuckets, EnumFacing from,
        FluidExtractor extractor, boolean simulate) {
        if (from == null || millibuckets <= 0) return FAILED_EXTRACT;
        IFluidHandler fluidHandler = pipe.getHolder().getCapabilityFromPipe(from, CapUtil.CAP_FLUIDS);
        if (fluidHandler == null) return PASSED_EXTRACT;

        int space = capacity - (committed + pending);
        millibuckets = Math.min(millibuckets, space);
        if (millibuckets <= 0) return FAILED_EXTRACT;

        FluidStack toAdd = extractor.extract(millibuckets, currentFluid, fluidHandler);
        if (toAdd == null || toAdd.amount <= 0) return FAILED_EXTRACT;

        millibuckets = toAdd.amount;
        if (currentFluid == null && !simulate) setFluid(toAdd);

        if (!simulate) {
            pending += millibuckets;
            sections.get(EnumPipePart.fromFacing(from)).ticksInDirection = COOLDOWN_INPUT;
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, toAdd);
    }

    private static FluidStack extractSimple(int millibuckets, FluidStack filter, IFluidHandler handler,
        boolean simulate) {
        if (filter == null) return handler.drain(millibuckets, !simulate);
        filter = filter.copy();
        filter.amount = millibuckets;
        FluidStack drained = handler.drain(filter, !simulate);
        if (drained != null) {
            if (!filter.isFluidEqual(filter)) {
                String detail = "(Filter = " + StringUtilBC.fluidToString(filter);
                detail += ",\nactually drained = " + StringUtilBC.fluidToString(drained) + ")";
                detail += ",\nIFluidHandler = " + handler.getClass() + "(" + handler + ")";
                throw new IllegalStateException("Drained fluid did not equal filter fluid!\n" + detail);
            }
        }
        return drained;
    }

    @Override
    public int insertFluidsForce(FluidStack fluid, @Nullable EnumFacing from, boolean simulate) {
        if (fluid == null || fluid.amount == 0) return 0;
        if (currentFluid != null && !currentFluid.isFluidEqual(fluid)) return 0;
        int space = capacity - (committed + pending);
        int filled = Math.min(space, fluid.amount);
        if (filled <= 0) return 0;
        if (!simulate) {
            if (currentFluid == null) setFluid(fluid.copy());
            pending += filled;
            if (from != null) {
                sections.get(EnumPipePart.fromFacing(from)).ticksInDirection = COOLDOWN_INPUT;
            }
        }
        return filled;
    }

    @Override
    @Nullable
    public FluidStack extractFluidsForce(int min, int max, @Nullable EnumFacing section, boolean simulate) {
        if (min > max) throw new IllegalArgumentException("Minimum (" + min + ") > maximum (" + max + ")");
        int total = committed + pending;
        if (max < 0 || total < min || currentFluid == null) return null;
        int amount = MathUtil.clamp(total, min, max);
        FluidStack fluid = new FluidStack(currentFluid, amount);
        if (!simulate) {
            // Drain from committed first, then pending
            int fromCommitted = Math.min(committed, amount);
            committed -= fromCommitted;
            pending -= (amount - fromCommitted);
            if (committed + pending == 0) setFluid(null);
        }
        return fluid;
    }

    // IDebuggable

    @Override
    public void getDebugInfo(List<String> left, List<String> right, EnumFacing side) {
        boolean isRemote = pipe.getHolder().getPipeWorld().isRemote;
        FluidStack fluid = isRemote ? getFluidStackForRender() : currentFluid;
        left.add(" - FluidType = " + (fluid == null ? "empty" : fluid.getLocalizedName()));
        int total = isRemote ? clientTotalAmountThis : committed + pending;
        left.add(" - committed=" + committed + " pending=" + pending + " / " + capacity + " mB");
        for (EnumPipePart part : EnumPipePart.FACES) {
            Section section = sections.get(part);
            left.add(
                " - " + LocaleUtil.localizeFacing(part.face) + " dir=" + section.getCurrentDirection()
                + " (" + section.ticksInDirection + ")"
            );
        }
    }

    // Rendering

    @SideOnly(Side.CLIENT)
    public FluidStack getFluidStackForRender() {
        return clientFluid == null ? null : clientFluid.get();
    }

    @SideOnly(Side.CLIENT)
    public double[] getAmountsForRender(float partialTicks) {
        double[] arr = new double[7];
        double total = clientTotalAmountLast * (1 - partialTicks) + clientTotalAmountThis * partialTicks;
        arr[EnumPipePart.CENTER.getIndex()] = total;
        for (EnumPipePart p : EnumPipePart.FACES) {
            if (sections.get(p).ticksInDirection != 0) {
                arr[p.getIndex()] = total;
            }
        }
        return arr;
    }

    @SideOnly(Side.CLIENT)
    public Vec3d[] getOffsetsForRender(float partialTicks) {
        Vec3d[] arr = new Vec3d[7];
        for (EnumPipePart part : EnumPipePart.VALUES) {
            Section s = sections.get(part);
            if (s.offsetLast != null && s.offsetThis != null) {
                arr[part.getIndex()] = s.offsetLast.scale(1 - partialTicks).add(s.offsetThis.scale(partialTicks));
            }
        }
        return arr;
    }

    // Internal logic

    private void setFluid(FluidStack fluid) {
        currentFluid = fluid;
    }

    @Override
    public void onTick() {
        World world = pipe.getHolder().getPipeWorld();
        if (world.isRemote) {
            for (Section s : sections.values()) s.tickClient();
            return;
        }

        if (currentFluid != null) {
            if (committed > 0) {
                pushFromCommitted();
            }
            // Promote pending to committed at end of tick
            committed += pending;
            pending = 0;

            if (committed == 0) setFluid(null);

            // Tick direction cooldowns
            for (EnumPipePart part : EnumPipePart.FACES) {
                Section section = sections.get(part);
                if (section.ticksInDirection > 0) section.ticksInDirection--;
                else if (section.ticksInDirection < 0) section.ticksInDirection++;
            }
        }

        int total = committed + pending;
        if (total != lastSentTotal && tracker.markTimeIfDelay(world)) {
            lastSentTotal = total;
            sendPayload(NET_FLUID_AMOUNTS);
        }
    }

    private void pushFromCommitted() {
        // Reset stale OUTPUT faces whose handler has gone away (e.g. container removed)
        for (EnumFacing face : EnumFacing.VALUES) {
            Section s = sections.get(EnumPipePart.fromFacing(face));
            if (s.ticksInDirection > 0) {
                boolean hasHandler = pipe.isConnected(face)
                    && pipe.getHolder().getCapabilityFromPipe(face, CapUtil.CAP_FLUIDS) != null;
                if (!hasHandler) s.ticksInDirection = 0;
            }
        }

        Set<EnumFacing> candidates = EnumSet.noneOf(EnumFacing.class);
        for (EnumFacing face : EnumFacing.VALUES) {
            Section s = sections.get(EnumPipePart.fromFacing(face));
            if (pipe.isConnected(face) && s.getCurrentDirection().canOutput()) {
                if (pipe.getHolder().getCapabilityFromPipe(face, CapUtil.CAP_FLUIDS) != null)
                    candidates.add(face);
            }
        }

        boolean emergency = false;
        if (candidates.isEmpty()) {
            // All direction locks are stale — allow any connected face as fallback
            for (EnumFacing face : EnumFacing.VALUES) {
                if (!pipe.isConnected(face)) continue;
                if (pipe.getHolder().getCapabilityFromPipe(face, CapUtil.CAP_FLUIDS) != null) {
                    sections.get(EnumPipePart.fromFacing(face)).ticksInDirection = 0;
                    candidates.add(face);
                }
            }
            if (candidates.isEmpty()) return;
            emergency = true;
        }

        PipeEventFluid.SideCheck sideCheck = new PipeEventFluid.SideCheck(pipe.getHolder(), this, currentFluid);
        sideCheck.disallowAllExcept(candidates);
        pipe.getHolder().fireEvent(sideCheck);
        EnumSet<EnumFacing> outputs = sideCheck.getOrder();
        if (outputs.isEmpty()) return;

        for (EnumFacing face : outputs) {
            if (committed <= 0) break;
            // Pressure gradient: only push to BC pipes with strictly less fluid (skip in emergency)
            if (!emergency && pipe.getConnectedType(face) == ConnectedType.PIPE) {
                IPipe nbPipe = pipe.getConnectedPipe(face);
                if (nbPipe != null && nbPipe.getFlow() instanceof PipeFlowFluids) {
                    PipeFlowFluids oFlow = (PipeFlowFluids) nbPipe.getFlow();
                    if (oFlow.committed + oFlow.pending > committed + pending) continue;
                }
            }
            int toPush = Math.min(committed, fluidTransferInfo.transferPerTick);
            IFluidHandler neighbor = pipe.getHolder().getCapabilityFromPipe(face, CapUtil.CAP_FLUIDS);
            if (neighbor == null) continue;
            int pushed = neighbor.fill(new FluidStack(currentFluid, toPush), true);
            if (pushed > 0) {
                committed -= pushed;
                sections.get(EnumPipePart.fromFacing(face)).ticksInDirection = COOLDOWN_OUTPUT;
            }
        }
    }

    @Override
    public void writePayload(int id, PacketBuffer buf, Side side) {
        PacketBufferBC buffer = PacketBufferBC.asPacketBufferBc(buf);
        if (side == Side.SERVER) {
            if (id == NET_FLUID_AMOUNTS || id == NET_ID_FULL_STATE) {
                int total = committed + pending;
                if (currentFluid == null) {
                    buffer.writeBoolean(false);
                } else {
                    buffer.writeBoolean(true);
                    buffer.writeInt(BuildCraftObjectCaches.CACHE_FLUIDS.server().store(currentFluid));
                }
                buffer.writeShort(total);
                for (EnumPipePart part : EnumPipePart.FACES) {
                    buffer.writeEnumValue(sections.get(part).getCurrentDirection());
                }
            }
        }
    }

    @Override
    public void readPayload(int id, PacketBuffer buf, Side side) throws IOException {
        PacketBufferBC buffer = PacketBufferBC.asPacketBufferBc(buf);
        if (side == Side.CLIENT) {
            if (id == NET_FLUID_AMOUNTS || id == NET_ID_FULL_STATE) {
                if (buffer.readBoolean()) {
                    int fluidId = buffer.readInt();
                    clientFluid = BuildCraftObjectCaches.CACHE_FLUIDS.client().retrieve(fluidId);
                } else {
                    clientFluid = null;
                }
                clientTotalTarget = buffer.readShort();
                for (EnumPipePart part : EnumPipePart.FACES) {
                    Dir dir = buffer.readEnumValue(Dir.class);
                    sections.get(part).ticksInDirection =
                        dir == Dir.NONE ? 0 : dir == Dir.IN ? COOLDOWN_INPUT : COOLDOWN_OUTPUT;
                }
                lastMessageMinus1 = lastMessage;
                lastMessage = pipe.getHolder().getPipeWorld().getTotalWorldTime();
            }
        }
    }

    /** Holds per-face direction state and rendering data. */
    class Section implements IFluidHandler {
        final EnumPipePart part;

        int ticksInDirection = 0;

        // Client rendering fields
        int clientAmountThis, clientAmountLast;
        Vec3d offsetLast, offsetThis;

        Section(EnumPipePart part) {
            this.part = part;
        }

        Dir getCurrentDirection() {
            if (ticksInDirection == 0) return Dir.NONE;
            return ticksInDirection < 0 ? Dir.IN : Dir.OUT;
        }

        @SideOnly(Side.CLIENT)
        boolean tickClient() {
            clientAmountLast = clientAmountThis;
            int globalTarget = (part == EnumPipePart.CENTER || ticksInDirection != 0) ? clientTotalTarget : 0;

            if (globalTarget != clientAmountThis) {
                int delta = globalTarget - clientAmountThis;
                long msgDelta = lastMessage - lastMessageMinus1;
                msgDelta = MathUtil.clamp((int) msgDelta, 1, 60);
                if (Math.abs(delta) < msgDelta) clientAmountThis += delta;
                else clientAmountThis += delta / (int) msgDelta;
            }

            if (part == EnumPipePart.CENTER) {
                clientTotalAmountLast = clientAmountLast;
                clientTotalAmountThis = clientAmountThis;
            }

            if (offsetThis == null || (clientAmountThis == 0 && clientAmountLast == 0)) {
                offsetThis = Vec3d.ZERO;
            }
            offsetLast = offsetThis;

            if (part.face == null) {
                Vec3d dir = Vec3d.ZERO;
                for (EnumPipePart p : EnumPipePart.FACES) {
                    Section s = sections.get(p);
                    if (s.ticksInDirection > 0) dir = dir.add(new Vec3d(p.face.getDirectionVec()));
                }
                for (EnumPipePart p : EnumPipePart.FACES) {
                    Section s = sections.get(p);
                    if (s.ticksInDirection < 0) dir = dir.add(new Vec3d(p.face.getDirectionVec()).scale(-1));
                }
                dir = new Vec3d(Math.signum(dir.x), Math.signum(dir.y), Math.signum(dir.z));
                offsetThis = offsetThis.add(dir.scale(-FLOW_MULTIPLIER));
            } else {
                double mult = Math.signum(ticksInDirection);
                offsetThis = VecUtil.offset(offsetLast, part.face, -FLOW_MULTIPLIER * mult);
            }

            double dx = offsetThis.x >= 0.5 ? -1 : offsetThis.x <= -0.5 ? 1 : 0;
            double dy = offsetThis.y >= 0.5 ? -1 : offsetThis.y <= -0.5 ? 1 : 0;
            double dz = offsetThis.z >= 0.5 ? -1 : offsetThis.z <= -0.5 ? 1 : 0;
            if (dx != 0 || dy != 0 || dz != 0) {
                offsetThis = offsetThis.addVector(dx, dy, dz);
                offsetLast = offsetLast.addVector(dx, dy, dz);
            }
            return clientAmountThis > 0 | clientAmountLast > 0;
        }

        // IFluidHandler

        @Override @Deprecated
        public FluidStack drain(FluidStack resource, boolean doDrain) { return null; }

        @Override @Deprecated
        public FluidStack drain(int maxDrain, boolean doDrain) { return null; }

        @Override
        public IFluidTankProperties[] getTankProperties() { return new IFluidTankProperties[0]; }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            if (part.face == null || !getCurrentDirection().canInput() || !pipe.isConnected(part.face)
                || resource == null) {
                return 0;
            }
            resource = resource.copy();
            PipeEventFluid.TryInsert tryInsert = new PipeEventFluid.TryInsert(
                pipe.getHolder(), PipeFlowFluids.this, part.face, resource
            );
            pipe.getHolder().fireEvent(tryInsert);
            if (tryInsert.isCanceled()) return 0;

            if (currentFluid != null && !currentFluid.isFluidEqual(resource)) return 0;

            int faceIdx = part.face.getIndex();
            int space = capacity - (committed + pending);
            long now = pipe.getHolder().getPipeWorld().getTotalWorldTime();
            if (now != fillWorldTick) { fillWorldTick = now; Arrays.fill(fillThisTick, 0); }
            int rateLeft = fluidTransferInfo.transferPerTick - fillThisTick[faceIdx];
            int canFill = Math.min(resource.amount, Math.min(space, rateLeft));
            if (canFill <= 0) return 0;

            if (doFill) {
                if (currentFluid == null) setFluid(resource.copy());
                pending += canFill;
                fillThisTick[faceIdx] += canFill;
                ticksInDirection = COOLDOWN_INPUT;
            }
            return canFill;
        }
    }

    enum Dir {
        IN(-1), NONE(0), OUT(1);

        final byte nbtValue;
        private Dir(int nbtValue) { this.nbtValue = (byte) nbtValue; }

        public boolean canInput() { return this != OUT; }
        public boolean canOutput() { return this != IN; }

        public static Dir get(int dir) {
            if (dir == 0) return Dir.NONE;
            return dir < 0 ? IN : OUT;
        }
    }
}
