package mcjty.meecreeps.actions.workers;

import mcjty.meecreeps.api.IMeeCreep;
import mcjty.meecreeps.api.IWorkerHelper;
import mcjty.meecreeps.varia.GeneralTools;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.IPlantable;

import java.util.HashMap;
import java.util.Map;

public class HarvestReplantActionWorker extends HarvestActionWorker {

    private Map<BlockPos, Block> needToReplant = new HashMap<>();

    public HarvestReplantActionWorker(IWorkerHelper helper) {
        super(helper);
    }

    private void replant(BlockPos pos) {
        IMeeCreep entity = helper.getMeeCreep();
        World world = entity.getWorld();
        Block block = needToReplant.get(pos);
        needToReplant.remove(pos);
        for (ItemStack stack : entity.getInventory()) {
            if (stack.getItem() instanceof IPlantable) {
                BlockState plant = ((IPlantable) stack.getItem()).getPlant(world, pos);
                if (plant.getBlock() == block) {
                    // This is a valid seed
                    stack.split(1);
                    world.setBlockState(pos, plant);
                    break;
                }
            }
        }
    }

    @Override
    protected void harvest(BlockPos pos) {
        IMeeCreep entity = helper.getMeeCreep();
        World world = entity.getWorld();
        BlockState state = world.getBlockState(pos);

        NonNullList<ItemStack> drops = NonNullList.create();
        drops.addAll(Block.getDrops(state, (ServerWorld) world, pos, world.getTileEntity(pos)));
        net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(drops, world, pos, state, 0, 1.0f, false, GeneralTools.getHarvester(world));

        world.setBlockState(pos, Blocks.AIR.getDefaultState());
        boolean replanted = false;
        for (ItemStack stack : drops) {
            if ((!replanted) && stack.getItem() instanceof IPlantable) {
                BlockState plant = ((IPlantable) stack.getItem()).getPlant(world, pos);
                if (plant.getBlock() == state.getBlock()) {
                    // This is a valid seed
                    ItemStack seed = stack.split(1);
                    world.setBlockState(pos, plant);
                    replanted = true;
                }
            }
            ItemStack remaining = entity.addStack(stack);
            if (!remaining.isEmpty()) {
                helper.dropAndPutAwayLater(remaining);
            }
        }

        // If we didn't manage to get a seed from the drops we first check if we don't happen to have
        // a seed in our inventory so we can use that.
        for (ItemStack stack : entity.getInventory()) {
            if (stack.getItem() instanceof IPlantable) {
                BlockState plant = ((IPlantable) stack.getItem()).getPlant(world, pos);
                if (plant.getBlock() == state.getBlock()) {
                    // This is a valid seed
                    ItemStack seed = stack.split(1);
                    world.setBlockState(pos, plant);
                    replanted = true;
                    break;
                }
            }
        }

        if (!replanted) {
            // We could not find any seed at all. Remember this so we can pick a seed from the chest next time
            needToReplant.put(pos, state.getBlock());
        }
    }

    private BlockPos hasSuitableSeed() {
        IMeeCreep entity = helper.getMeeCreep();
        World world = entity.getWorld();
        for (Map.Entry<BlockPos, Block> entry : needToReplant.entrySet()) {
            BlockPos pos = entry.getKey();
            Block block = entry.getValue();
            for (ItemStack stack : entity.getInventory()) {
                if (stack.getItem() instanceof IPlantable) {
                    BlockState plant = ((IPlantable) stack.getItem()).getPlant(world, pos);
                    if (plant.getBlock() == block) {
                        // This is a valid seed
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void tick(boolean timeToWrapUp) {
        BlockPos pos;
        if (!needToReplant.isEmpty() && (pos = hasSuitableSeed()) != null) {
            helper.navigateTo(pos, this::replant);
        } else if (timeToWrapUp) {
            helper.done();
        } else {
            tryFindingCropsToHarvest();
        }
    }

}
