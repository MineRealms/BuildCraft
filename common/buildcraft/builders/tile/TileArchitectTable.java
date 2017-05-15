/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.builders.tile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ITickable;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandlerModifiable;

import buildcraft.api.core.EnumPipePart;
import buildcraft.api.core.IAreaProvider;
import buildcraft.api.enums.EnumSnapshotType;
import buildcraft.api.schematics.ISchematicBlock;
import buildcraft.api.schematics.ISchematicEntity;
import buildcraft.api.tiles.IDebuggable;

import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.delta.DeltaInt;
import buildcraft.lib.delta.DeltaManager;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.data.BoxIterator;
import buildcraft.lib.misc.data.EnumAxisOrder;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.net.PacketBufferBC;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;

import buildcraft.builders.BCBuildersItems;
import buildcraft.builders.block.BlockArchitectTable;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.SchematicBlockManager;
import buildcraft.builders.snapshot.SchematicEntityManager;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import buildcraft.core.marker.volume.Lock;
import buildcraft.core.marker.volume.VolumeBox;
import buildcraft.core.marker.volume.WorldSavedDataVolumeBoxes;

public class TileArchitectTable extends TileBC_Neptune implements ITickable, IDebuggable {
    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("architect");
    public static final int NET_BOX = IDS.allocId("BOX");
    public static final int NET_SCAN = IDS.allocId("SCAN");

    public final ItemHandlerSimple invSnapshotIn = itemManager.addInvHandler("in", 1, EnumAccess.INSERT, EnumPipePart.VALUES);
    public final ItemHandlerSimple invSnapshotOut = itemManager.addInvHandler("out", 1, EnumAccess.EXTRACT, EnumPipePart.VALUES);

    private EnumSnapshotType snapshotType = EnumSnapshotType.BLUEPRINT;
    private final Box box = new Box();
    private boolean[][][] templateScannedBlocks;
    private ISchematicBlock<?>[][][] blueprintScannedBlocks;
    private final List<ISchematicEntity<?>> blueprintScannedEntities = new ArrayList<>();
    private BoxIterator boxIterator;
    private boolean isValid = false;
    private boolean scanning = false;
    public String name = "<unnamed>";
    public final DeltaInt deltaProgress = deltaManager.addDelta("progress", DeltaManager.EnumNetworkVisibility.GUI_ONLY);

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    @Override
    protected void onSlotChange(IItemHandlerModifiable handler, int slot, ItemStack before, ItemStack after) {
        super.onSlotChange(handler, slot, before, after);
        if (handler == invSnapshotIn) {
            if (invSnapshotOut.getStackInSlot(0).isEmpty() && after.getItem() instanceof ItemSnapshot) {
                snapshotType = ItemSnapshot.EnumItemSnapshotType.getFromStack(after).snapshotType;
            }
        }
    }

    @Override
    public void onPlacedBy(EntityLivingBase placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        if (placer.world.isRemote) {
            return;
        }
        WorldSavedDataVolumeBoxes volumeBoxes = WorldSavedDataVolumeBoxes.get(world);
        IBlockState blockState = world.getBlockState(pos);
        BlockPos offsetPos = pos.offset(blockState.getValue(BlockArchitectTable.PROP_FACING).getOpposite());
        VolumeBox volumeBox = volumeBoxes.getBoxAt(offsetPos);
        TileEntity tile = world.getTileEntity(offsetPos);
        if (volumeBox != null) {
            box.reset();
            box.setMin(volumeBox.box.min());
            box.setMax(volumeBox.box.max());
            isValid = true;
            volumeBox.locks.add(new Lock(new Lock.Cause.CauseBlock(pos, blockState.getBlock()), new Lock.Target.TargetResize(), new Lock.Target.TargetUsedByMachine(
                Lock.Target.TargetUsedByMachine.EnumType.STRIPES_READ)));
            volumeBoxes.markDirty();
            sendNetworkUpdate(NET_BOX);
        } else if (tile instanceof IAreaProvider) {
            IAreaProvider provider = (IAreaProvider) tile;
            box.reset();
            box.setMin(provider.min());
            box.setMax(provider.max());
            isValid = true;
            provider.removeFromWorld();
        } else {
            isValid = false;
            IBlockState state = world.getBlockState(pos);
            state = state.withProperty(BlockArchitectTable.PROP_VALID, Boolean.FALSE);
            world.setBlockState(pos, state);
        }
    }

    @Override
    public void update() {
        deltaManager.tick();

        if (world.isRemote) {
            return;
        }

        if (!invSnapshotIn.getStackInSlot(0).isEmpty() && invSnapshotOut.getStackInSlot(0).isEmpty() && isValid) {
            if (!scanning) {
                int size = box.size().getX() * box.size().getY() * box.size().getZ();
                size /= snapshotType.maxPerTick;
                deltaProgress.addDelta(0, size, 1);
                deltaProgress.addDelta(size, size + 10, -1);
                scanning = true;
            }
        } else {
            scanning = false;
        }

        if (scanning) {
            scanMultipleBlocks();
            if (!scanning) {
                if (snapshotType == EnumSnapshotType.BLUEPRINT) {
                    scanEntities();
                }
                finishScanning();
            }
        }
    }

    private void scanMultipleBlocks() {
        for (int i = snapshotType.maxPerTick; i > 0; i--) {
            scanSingleBlock();
            if (!scanning) {
                break;
            }
        }
    }

    private void scanSingleBlock() {
        BlockPos size = box.size();
        if (templateScannedBlocks == null || blueprintScannedBlocks == null) {
            boxIterator = new BoxIterator(box, EnumAxisOrder.XZY.getMinToMaxOrder(), true);
            blueprintScannedBlocks = new ISchematicBlock<?>[size.getX()][size.getY()][size.getZ()];
            templateScannedBlocks = new boolean[size.getX()][size.getY()][size.getZ()];
        }

        // Read from world
        BlockPos worldScanPos = boxIterator.getCurrent();
        BlockPos schematicIndex = worldScanPos.subtract(box.min());
        if (snapshotType == EnumSnapshotType.TEMPLATE) {
            boolean solid = !world.isAirBlock(worldScanPos);
            templateScannedBlocks[schematicIndex.getX()][schematicIndex.getY()][schematicIndex.getZ()] = solid;
        }
        if (snapshotType == EnumSnapshotType.BLUEPRINT) {
            ISchematicBlock<?> schematic = readSchematicForBlock(worldScanPos);
            blueprintScannedBlocks[schematicIndex.getX()][schematicIndex.getY()][schematicIndex.getZ()] = schematic;
        }

        createAndSendMessage(NET_SCAN, buffer -> buffer.writeBlockPos(worldScanPos));

        sendNetworkUpdate(NET_RENDER_DATA);

        // Move scanPos along
        boxIterator.advance();

        if (boxIterator.hasFinished()) {
            scanning = false;
            boxIterator = null;
        }
    }

    private ISchematicBlock<?> readSchematicForBlock(BlockPos worldScanPos) {
        return SchematicBlockManager.getSchematicBlock(world, pos.offset(world.getBlockState(pos).getValue(BlockBCBase_Neptune.PROP_FACING).getOpposite()), worldScanPos, world.getBlockState(
            worldScanPos), world.getBlockState(worldScanPos).getBlock());
    }

    private void scanEntities() {
        BlockPos basePos = pos.offset(world.getBlockState(pos).getValue(BlockArchitectTable.PROP_FACING).getOpposite());
        world.getEntitiesWithinAABB(Entity.class, box.getBoundingBox()).stream().map(entity -> SchematicEntityManager.getSchematicEntity(world, basePos, entity)).filter(Objects::nonNull).forEach(
            blueprintScannedEntities::add);
    }

    private void finishScanning() {
        EnumFacing facing = world.getBlockState(pos).getValue(BlockArchitectTable.PROP_FACING);
        Snapshot snapshot = Snapshot.create(snapshotType);
        snapshot.size = box.size();
        snapshot.facing = facing;
        snapshot.offset = box.min().subtract(pos.offset(facing.getOpposite()));
        if (snapshotType == EnumSnapshotType.TEMPLATE) {
            // noinspection ConstantConditions
            ((Template) snapshot).data = templateScannedBlocks;
        }
        if (snapshotType == EnumSnapshotType.BLUEPRINT) {
            // noinspection ConstantConditions
            ((Blueprint) snapshot).data = blueprintScannedBlocks;
            // noinspection ConstantConditions
            ((Blueprint) snapshot).entities = new ArrayList<>(blueprintScannedEntities);
        }
        snapshot.header.id = UUID.randomUUID();
        snapshot.header.owner = getOwner().getId();
        snapshot.header.created = new Date();
        snapshot.header.name = name;
        GlobalSavedDataSnapshots.get(world).snapshots.add(snapshot);
        GlobalSavedDataSnapshots.get(world).markDirty();
        ItemStack stackIn = invSnapshotIn.getStackInSlot(0);
        stackIn.setCount(stackIn.getCount() - 1);
        if (stackIn.getCount() == 0) {
            stackIn = ItemStack.EMPTY;
        }
        invSnapshotIn.setStackInSlot(0, stackIn);
        invSnapshotOut.setStackInSlot(0, BCBuildersItems.snapshot.getUsed(snapshotType, snapshot.header));
        templateScannedBlocks = null;
        blueprintScannedBlocks = null;
        blueprintScannedEntities.clear();
        boxIterator = null;
        sendNetworkUpdate(NET_RENDER_DATA);
    }

    @Override
    public void writePayload(int id, PacketBufferBC buffer, Side side) {
        super.writePayload(id, buffer, side);
        if (side == Side.SERVER) {
            if (id == NET_RENDER_DATA) {
                writePayload(NET_BOX, buffer, side);
                buffer.writeString(name);
            }
            if (id == NET_BOX) {
                box.writeData(buffer);
            }
        }
    }

    @Override
    public void readPayload(int id, PacketBufferBC buffer, Side side, MessageContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == Side.CLIENT) {
            if (id == NET_RENDER_DATA) {
                readPayload(NET_BOX, buffer, side, ctx);
                name = buffer.readString();
            } else if (id == NET_BOX) {
                box.readData(buffer);
            } else if (id == NET_SCAN) {
                BlockPos pos = buffer.readBlockPos();
                double x = pos.getX() + 0.5;
                double y = pos.getY() + 0.5;
                double z = pos.getZ() + 0.5;
                world.spawnParticle(EnumParticleTypes.CLOUD, x, y, z, 0, 0, 0);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag("box", box.writeToNBT());
        if (boxIterator != null) {
            nbt.setTag("iter", boxIterator.writeToNbt());
        }
        nbt.setBoolean("scanning", scanning);
        nbt.setTag("snapshotType", NBTUtilBC.writeEnum(snapshotType));
        nbt.setBoolean("isValid", isValid);
        nbt.setString("name", name);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        box.initialize(nbt.getCompoundTag("box"));
        if (nbt.hasKey("iter")) {
            boxIterator = BoxIterator.readFromNbt(nbt.getCompoundTag("iter"));
        }
        scanning = nbt.getBoolean("scanning");
        snapshotType = NBTUtilBC.readEnum(nbt.getTag("snapshotType"), EnumSnapshotType.class);
        isValid = nbt.getBoolean("isValid");
        name = nbt.getString("name");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getDebugInfo(List<String> left, List<String> right, EnumFacing side) {
        left.add("");
        left.add("box:");
        left.add(" - min = " + box.min());
        left.add(" - max = " + box.max());
        left.add("scanning = " + scanning);
        left.add("current = " + (boxIterator == null ? null : boxIterator.getCurrent()));
    }

    // Rendering

    @Override
    @SideOnly(Side.CLIENT)
    public boolean hasFastRenderer() {
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return BoundingBoxUtil.makeFrom(pos, box);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return Double.MAX_VALUE;
    }
}
