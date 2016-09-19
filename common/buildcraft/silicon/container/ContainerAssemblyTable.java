package buildcraft.silicon.container;

import buildcraft.core.lib.gui.slots.SlotBase;
import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.silicon.tile.TileAssemblyTable;
import net.minecraft.entity.player.EntityPlayer;

public class ContainerAssemblyTable extends ContainerBCTile<TileAssemblyTable> {
    private static final int PLAYER_INV_START = 123;

    public ContainerAssemblyTable(EntityPlayer player, TileAssemblyTable tile) {
        super(player, tile);
        addFullPlayerInventory(PLAYER_INV_START);

        for(int y = 0; y < 4; y++) {
            for(int x = 0; x < 3; x++) {
                addSlotToContainer(new SlotBase(tile.inv, x + y * 3, 8 + x * 18, 36 + y * 18));
            }
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return true;
    }
}
