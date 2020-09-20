package mcjty.meecreeps.actions.workers;

import mcjty.lib.varia.BlockTools;
import mcjty.lib.varia.SoundTools;
import mcjty.meecreeps.ForgeEventHandlers;
import mcjty.meecreeps.MeeCreeps;
import mcjty.meecreeps.actions.ActionOptions;
import mcjty.meecreeps.actions.MeeCreepActionType;
import mcjty.meecreeps.actions.ServerActionManager;
import mcjty.meecreeps.actions.Stage;
import mcjty.meecreeps.api.*;
import mcjty.meecreeps.blocks.ModBlocks;
import mcjty.meecreeps.config.ConfigSetup;
import mcjty.meecreeps.entities.EntityMeeCreeps;
import mcjty.meecreeps.items.CreepCubeItem;
import mcjty.meecreeps.network.PacketHandler;
import mcjty.meecreeps.network.PacketShowBalloonToClient;
import mcjty.meecreeps.varia.GeneralTools;
import mcjty.meecreeps.varia.InventoryTools;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.item.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUseContext;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.*;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class WorkerHelper implements IWorkerHelper {

    private final double DISTANCE_TOLERANCE = 1.4;

    private IActionWorker worker;
    private final ActionOptions options;
    private EntityMeeCreeps entity;
    private boolean needsToPutAway = false;
    private int waitABit = 10;
    private int speed = 10;

    private BlockPos movingToPos;
    private Entity movingToEntity;

    // To detect if we're stuck
    private double prevPosX;
    private double prevPosY;
    private double prevPosZ;
    private int stuckCounter;

    private int pathTries = 0;
    private Consumer<BlockPos> job;
    private Runnable delayedJob;
    private int delayedTicks;
    private List<ItemEntity> itemsToPickup = new ArrayList<>();
    private BlockPos materialChest;

    // While building or flattening this will contain positions that we want to skip because they are too hard or unbreakable
    private Set<BlockPos> positionsToSkip = new HashSet<>();

    private String lastMessage = "";

    public WorkerHelper(IActionContext options) {
        this.options = (ActionOptions) options;
    }

    private static final Set<String> TORCHES = new HashSet<>();

    static {
        TORCHES.add("minecraft:torch");
        TORCHES.add("tconstruct:stone_torch");
        TORCHES.add("integrateddynamics:menril_torch");
        TORCHES.add("integrateddynamics:menril_torch_stone");
    }

    public static boolean isTorch(ItemStack stack) {
        return TORCHES.contains(stack.getItem().getRegistryName().toString());
    }

    public static boolean isTorch(Block block) {
        return TORCHES.contains(block.getRegistryName().toString());
    }

    public void setWorker(IActionWorker worker) {
        this.worker = worker;
    }

    public IActionWorker getWorker() {
        return worker;
    }

    public void cancelJob() {
        job = null;
        delayedJob = null;
    }

    @Override
    public void setSpeed(int speed) {
        this.speed = speed;
    }

    @Override
    public int getSpeed() {
        return speed;
    }

    @Override
    public IActionContext getContext() {
        return options;
    }

    @Override
    public IMeeCreep getMeeCreep() {
        return entity;
    }

    private static final IDesiredBlock AIR = new IDesiredBlock() {
        @Override
        public String getName() {
            return "air";
        }

        @Override
        public int getAmount() {
            return 0;
        }

        @Override
        public Predicate<ItemStack> getMatcher() {
            return ItemStack::isEmpty;
        }

        @Override
        public Predicate<BlockState> getStateMatcher() {
            return blockState -> blockState.getBlock() == Blocks.AIR;
        }
    };


    private static final IDesiredBlock IGNORE = new IDesiredBlock() {
        @Override
        public String getName() {
            return "IGNORE";
        }

        @Override
        public int getAmount() {
            return 0;
        }

        @Override
        public int getPass() {
            return -1;          // That way this is ignored
        }

        @Override
        public boolean isOptional() {
            return true;
        }

        @Override
        public Predicate<ItemStack> getMatcher() {
            return stack -> false;
        }

        @Override
        public Predicate<BlockState> getStateMatcher() {
            return blockState -> false;
        }
    };


    @Override
    public IDesiredBlock getAirBlock() {
        return AIR;
    }

    @Override
    public IDesiredBlock getIgnoreBlock() {
        return IGNORE;
    }

    /**
     * Returns absolute position
     */
    @Override
    public BlockPos findSpotToFlatten(@Nonnull IBuildSchematic schematic) {
        BlockPos tpos = options.getTargetPos();
        BlockPos minPos = schematic.getMinPos();
        BlockPos maxPos = schematic.getMaxPos();

        List<BlockPos> todo = new ArrayList<>();
        for (int x = minPos.getX(); x <= maxPos.getX(); x++) {
            for (int y = minPos.getY(); y <= maxPos.getY(); y++) {
                for (int z = minPos.getZ(); z <= maxPos.getZ(); z++) {
                    BlockPos relativePos = new BlockPos(x, y, z);
                    BlockPos p = tpos.add(relativePos);
                    BlockState state = entity.getWorld().getBlockState(p);
                    IDesiredBlock desired = schematic.getDesiredBlock(relativePos);
                    if (desired != IGNORE) {
                        if (!desired.getStateMatcher().test(state) && !entity.getWorld().isAirBlock(p) && !positionsToSkip.contains(p)) {
                            todo.add(p);
                        }
                    }
                }
            }
        }
        if (todo.isEmpty()) {
            return null;
        }

        BlockPos position = entity.getEntity().getPosition();
        todo.sort((o1, o2) -> {
            double d1 = position.distanceSq(o1);
            double d2 = position.distanceSq(o2);
            return Double.compare(d1, d2);
        });
        return todo.get(0);
    }

    /**
     * Return the relative spot to build
     */
    @Override
    public BlockPos findSpotToBuild(@Nonnull IBuildSchematic schematic, @Nonnull BuildProgress progress, @Nonnull Set<BlockPos> toSkip) {
        BlockPos tpos = options.getTargetPos();
        BlockPos minPos = schematic.getMinPos();
        BlockPos maxPos = schematic.getMaxPos();

        List<BlockPos> todo = new ArrayList<>();
        for (int x = minPos.getX(); x <= maxPos.getX(); x++) {
            for (int z = minPos.getZ(); z <= maxPos.getZ(); z++) {
                BlockPos relativePos = new BlockPos(x, progress.getHeight(), z);
                if (!toSkip.contains(relativePos)) {
                    BlockPos p = tpos.add(relativePos);
                    BlockState state = entity.getWorld().getBlockState(p);
                    IDesiredBlock desired = schematic.getDesiredBlock(relativePos);
                    if (desired.getPass() == progress.getPass() && !desired.getStateMatcher().test(state) && !positionsToSkip.contains(p)) {
                        todo.add(relativePos);
                    }
                }
            }
        }
        if (todo.isEmpty()) {
            if (!progress.next(schematic)) {
                return null;    // Done
            }
            return findSpotToBuild(schematic, progress, toSkip);
        }
        BlockPos position = entity.getEntity().getPosition().subtract(tpos);        // Make entity position relative for distance calculation
        todo.sort((o1, o2) -> {
            double d1 = position.distanceSq(o1);
            double d2 = position.distanceSq(o2);
            return Double.compare(d1, d2);
        });
        return todo.get(0);
    }


    @Override
    public void delayForHardBlocks(BlockPos pos, Consumer<BlockPos> nextJob) {
        World world = entity.getEntityWorld();
        if (world.isAirBlock(pos)) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (!allowedToHarvest(state, world, pos, GeneralTools.getHarvester(world))) {
            return;
        }
        Block block = state.getBlock();
        if (block instanceof IFluidBlock) {
            nextJob.accept(pos);
        } else {
            float hardness = state.getBlockHardness(world, pos);
            if (hardness < ConfigSetup.delayAtHardness.get()) {
                nextJob.accept(pos);
            } else {
                delay((int) (hardness * ConfigSetup.delayFactor.get()), () -> nextJob.accept(pos));
            }
        }
    }

    @Override
    public boolean handleFlatten(@Nonnull IBuildSchematic schematic) {
        BlockPos flatSpot = findSpotToFlatten(schematic);
        if (flatSpot == null) {
            return false;
        } else {
            BlockPos navigate = findBestNavigationSpot(flatSpot);
            if (navigate != null) {
                navigateTo(navigate, p -> {
                    delayForHardBlocks(flatSpot, pp -> {
                        if (!harvestAndDrop(flatSpot)) {
                            positionsToSkip.add(flatSpot);
                        }
                    });
                });
            } else {
                // We couldn't reach it. Just drop the block
                delayForHardBlocks(flatSpot, pp -> {
                    if (!harvestAndDrop(flatSpot)) {
                        positionsToSkip.add(flatSpot);
                    }
                });
            }
            return true;
        }
    }


    @Override
    public boolean handleBuilding(@Nonnull IBuildSchematic schematic, @Nonnull BuildProgress progress, @Nonnull Set<BlockPos> toSkip) {
        BlockPos relativePos = findSpotToBuild(schematic, progress, toSkip);
        if (relativePos != null) {
            IDesiredBlock desired = schematic.getDesiredBlock(relativePos);
            if (!entity.hasItem(desired.getMatcher())) {
                if (entity.hasRoom(desired.getMatcher())) {
                    if (desired.isOptional()) {
                        if (!findItemOnGroundOrInChest(desired.getMatcher(), desired.getAmount())) {
                            // We don't have any of these. Just skip them
                            toSkip.add(relativePos);
                        }
                    } else {
                        findItemOnGroundOrInChest(desired.getMatcher(), desired.getAmount(), "message.meecreeps.cannot_find", desired.getName());
                    }
                } else {
                    // First put away stuff
                    putStuffAway();
                }
            } else {
                BlockPos buildPos = relativePos.add(options.getTargetPos());
                BlockPos navigate = findBestNavigationSpot(buildPos);
                if (navigate != null) {
                    navigateTo(navigate, p -> {
                        if (!placeBuildingBlock(buildPos, desired)) {
                            positionsToSkip.add(buildPos);
                        }
                    });
                } else {
                    // We couldn't reach it. Just build the block
                    if (!placeBuildingBlock(buildPos, desired)) {
                        positionsToSkip.add(buildPos);
                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void giveDropsToMeeCreeps(@Nonnull List<ItemStack> drops) {
        for (ItemStack stack : drops) {
            ItemStack remaining = entity.addStack(stack);
            if (!remaining.isEmpty()) {
                itemsToPickup.add(entity.entityDropItem(remaining, 0.0f));
                needsToPutAway = true;
            }
        }
    }

    @Override
    public void showMessage(String message, String... parameters) {
        if (lastMessage.equals(message)) {
            return;
        }
        lastMessage = message;
        ServerPlayerEntity player = getPlayer();
        if (player != null) {
            // todo: verify this is the correct direction, i forget
            PacketHandler.INSTANCE.sendTo(new PacketShowBalloonToClient(message, parameters), player.connection.netManager, NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    @Override
    public void registerHarvestableBlock(BlockPos pos) {
        ForgeEventHandlers.harvestableBlocksToCollect.put(pos, options.getActionId());
    }

    @Override
    public void navigateTo(BlockPos pos, Consumer<BlockPos> job) {
        double d = getSquareDist(entity, pos);
        if (d < DISTANCE_TOLERANCE) {
            job.accept(pos);
        } else if (!entity.getNavigator().tryMoveToXYZ(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 2.0)) {
            // We need to teleport
            entity.setPositionAndUpdate(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
            job.accept(pos);
        } else {
            this.movingToPos = pos;
            this.movingToEntity = null;
            pathTries = 1;
            this.job = job;
            prevPosX = entity.getPosX();
            prevPosY = entity.getPosY();
            prevPosZ = entity.getPosZ();
            stuckCounter = 0;
//            prevPosX = entity.posX;
        }
    }

    @Override
    public boolean navigateTo(Entity dest, Consumer<BlockPos> job, double maxDist) {
        if (dest == null || !dest.isAlive()) {
            return false;
        }
        double d = getSquareDist(entity, dest);
        if (d > maxDist * maxDist) {
            return false;
        } else if (d < DISTANCE_TOLERANCE) {
            job.accept(dest.getPosition());
        } else if (!entity.getNavigator().tryMoveToEntityLiving(dest, 2.0)) {
            // We need to teleport
            entity.setPositionAndUpdate(dest.getPosX(), dest.getPosY(), dest.getPosZ());
            job.accept(dest.getPosition());
        } else {
            this.movingToPos = null;
            this.movingToEntity = dest;
            pathTries = 1;
            this.job = job;
        }
        return true;
    }

    private static double getSquareDist(Entity source, BlockPos dest) {
        double d0 = dest.distanceSq(source.getPosX(), source.getPosY() - 1, source.getPosZ(), true);
        double d1 = dest.distanceSq(source.getPosX(), source.getPosY(), source.getPosZ(), true);
        double d2 = dest.distanceSq(source.getPosX(), source.getPosY() + source.getEyeHeight(), source.getPosZ(), true);
        return Math.min(Math.min(d0, d1), d2);
    }

    private static double getSquareDist(Entity source, Entity dest) {
        Vec3d lowPosition = new Vec3d(source.getPosX(), source.getPosY() - 1, source.getPosZ());
        Vec3d position = new Vec3d(source.getPosX(), source.getPosY(), source.getPosZ());
        Vec3d eyePosition = new Vec3d(source.getPosX(), source.getPosY() + source.getEyeHeight(), source.getPosZ());
        double d0 = lowPosition.squareDistanceTo(dest.getPosX(), dest.getPosY(), dest.getPosZ());
        double d1 = position.squareDistanceTo(dest.getPosX(), dest.getPosY(), dest.getPosZ());
        double d2 = eyePosition.squareDistanceTo(dest.getPosX(), dest.getPosY(), dest.getPosZ());
        return Math.min(Math.min(d0, d1), d2);
    }

    @Override
    public boolean navigateTo(Entity dest, Consumer<BlockPos> job) {
        return navigateTo(dest, job, 1000000000);
    }

    private boolean isStuck() {
        return Math.abs(entity.getPosX() - prevPosX) < 0.01 && Math.abs(entity.getPosY() - prevPosY) < 0.01 && Math.abs(entity.getPosZ() - prevPosZ) < 0.01;
    }

    private boolean isCube(ItemStack stack) {
        return stack.getItem() instanceof CreepCubeItem;
    }

    @Override
    public void delay(int ticks, Runnable task) {
        delayedTicks = ticks;
        delayedJob = task;
    }

    public void tick(EntityMeeCreeps entity, boolean timeToWrapUp) {
        waitABit--;
        if (waitABit > 0) {
            return;
        }
        // @todo config
        waitABit = speed;

        this.entity = entity;
        if (delayedJob != null) {
            delayedTicks -= speed;
            if (delayedTicks < 0) {
                Runnable d = this.delayedJob;
                delayedJob = null;
                d.run();
            }
        } else if (job != null) {
            handleJob();
        } else if (entity.hasItem(this::isCube)) {
            spawnAngryCreep();
        } else if (findMeeCreepBoxOnGround()) {
            entity.dropInventory();
            setSpeed(20);
        } else if (!options.getDrops().isEmpty()) {
            handleDropCollection();
        } else if (needToFindChest(timeToWrapUp)) {
            handlePutAway();
        } else if (!itemsToPickup.isEmpty()) {
            tryFindingItemsToPickup();
        } else {
            worker.tick(timeToWrapUp);
        }
        this.entity = null;
    }

    private void spawnAngryCreep() {
        entity.setHeldBlockState(ModBlocks.CREEP_CUBE.get().getDefaultState());
        entity.setVariationFace(1);
        ServerActionManager manager = ServerActionManager.getManager(entity.world);
        World world = entity.getWorld();

        // todo: come back to this and fix
        // todo: re-add this @since port
//        int cnt = world.countEntities(EntityMeeCreeps.class);
//        if (cnt >= ConfigSetup.maxSpawnCount.get()) {
//            return;
//        }

        Random r = entity.getRandom();
        BlockPos targetPos = new BlockPos(entity.getPosX() + r.nextFloat() * 8 - 4, entity.getPosY(), entity.getPosZ() + r.nextFloat() * 8 - 4);
        int actionId = manager.createActionOptions(world, targetPos, Direction.UP, getPlayer());
        ActionOptions.spawn(world, targetPos, Direction.UP, actionId, false);
        manager.performAction(null, actionId, new MeeCreepActionType("meecreeps.angry"), null);
    }

    private void handlePutAway() {
        if (!findChestToPutItemsIn()) {
            if (!navigateTo(getPlayer(), (p) -> giveToPlayerOrDrop(), 12)) {
                entity.dropInventory();
            }
        }
        needsToPutAway = false;
    }

    private void handleDropCollection() {
        // There are drops we need to collect first.
        for (Pair<BlockPos, ItemStack> pair : options.getDrops()) {
            ItemStack drop = pair.getValue();
            if (!drop.isEmpty()) {
                ItemStack remaining = entity.addStack(drop);
                if (!remaining.isEmpty()) {
                    entity.entityDropItem(remaining, 0.0f);
                    needsToPutAway = true;
                }
            }
        }
        options.clearDrops();
        ServerActionManager.getManager(this.entity.world).save();
        waitABit = 1;   // Process faster
    }

    private void handleJob() {
        if (movingToEntity != null) {
            if (!movingToEntity.isAlive()) {
                job = null;
            } else {
                double d = getSquareDist(entity, movingToEntity);
                if (d < DISTANCE_TOLERANCE) {
                    job.accept(movingToEntity.getPosition());
                    job = null;
                } else if (entity.getNavigator().noPath()) {
                    if (pathTries > 2) {
                        entity.setPositionAndUpdate(movingToEntity.getPosX(), movingToEntity.getPosY(), movingToEntity.getPosZ());
                        job.accept(movingToEntity.getPosition());
                        job = null;
                    } else {
                        pathTries++;
                        entity.getNavigator().tryMoveToEntityLiving(movingToEntity, 2.0);
                        stuckCounter = 0;
                    }
                } else if (isStuck()) {
                    stuckCounter++;
                    if (stuckCounter > 5) {
                        entity.setPositionAndUpdate(movingToEntity.getPosX(), movingToEntity.getPosY(), movingToEntity.getPosZ());
                        job.accept(movingToEntity.getPosition());
                        job = null;
                    }
                }
            }
        } else {
            double d = getSquareDist(entity, movingToPos);
            if (d < DISTANCE_TOLERANCE) {
                job.accept(movingToPos);
                job = null;
            } else if (entity.getNavigator().noPath()) {
                if (pathTries > 2) {
                    entity.setPositionAndUpdate(movingToPos.getX() + .5, movingToPos.getY(), movingToPos.getZ() + .5);
                    job.accept(movingToPos);
                    job = null;
                } else {
                    pathTries++;
                    entity.getNavigator().tryMoveToXYZ(movingToPos.getX() + .5, movingToPos.getY(), movingToPos.getZ() + .5, 2.0);
                    stuckCounter = 0;
                }
            } else if (isStuck()) {
                stuckCounter++;
                if (stuckCounter > 5) {
                    entity.setPositionAndUpdate(movingToPos.getX() + .5, movingToPos.getY(), movingToPos.getZ() + .5);
                    job.accept(movingToPos);
                    job = null;
                }
            }
        }
        prevPosX = entity.getPosX();
        prevPosY = entity.getPosY();
        prevPosZ = entity.getPosZ();
    }

    @Override
    public boolean placeBuildingBlock(BlockPos pos, IDesiredBlock desiredBlock) {
        World world = entity.getWorld();
        // todo: verify this works
        if (!world.isAirBlock(pos) && !world.getBlockState(pos).isReplaceable(new BlockItemUseContext(new ItemUseContext(getPlayer(), Hand.MAIN_HAND, (BlockRayTraceResult) GeneralTools.getHarvester(world).pick(10, 0, false))))) {
            if (!allowedToHarvest(world.getBlockState(pos), world, pos, GeneralTools.getHarvester(world))) {
                return false;
            }
            delayForHardBlocks(pos, pp -> {
                harvestAndDrop(pos);
                reallyPlace(pos, desiredBlock, world);
            });
        } else {
            reallyPlace(pos, desiredBlock, world);
        }
        return true;
    }

    private void reallyPlace(BlockPos pos, IDesiredBlock desiredBlock, World world) {
        ItemStack blockStack = entity.consumeItem(desiredBlock.getMatcher(), 1);
        if (!blockStack.isEmpty()) {
            placeStackAt(blockStack, world, pos);
            boolean jump = !entity.isNotColliding(world);
            if (jump) {
                entity.getEntity().getJumpController().setJumping();
            }
        }
    }

    @Override
    public void placeStackAt(ItemStack blockStack, World world, BlockPos pos) {
        BlockState state = BlockTools.placeStackAt(GeneralTools.getHarvester(world), blockStack, world, pos, null);
        SoundTools.playSound(world, state.getBlock().getSoundType(state).getBreakSound(), pos.getX(), pos.getY(), pos.getZ(), 1.0f, 1.0f);
    }


    @Override
    public boolean harvestAndPickup(BlockPos pos) {
        World world = entity.getEntityWorld();
        if (world.isAirBlock(pos)) {
            return true;
        }
        BlockState state = world.getBlockState(pos);
        if (!allowedToHarvest(state, world, pos, GeneralTools.getHarvester(world))) {
            return false;
        }
        Block block = state.getBlock();
        NonNullList<ItemStack> drops = NonNullList.create();
        drops.addAll(Block.getDrops(state, (ServerWorld) world, pos,null));

        net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(drops, world, pos, state, 0, 1.0f, false, GeneralTools.getHarvester(world));
        SoundTools.playSound(world, block.getSoundType(state).getBreakSound(), pos.getX(), pos.getY(), pos.getZ(), 1.0f, 1.0f);
        block.onBlockHarvested(world, pos, state, GeneralTools.getHarvester(world));
        entity.getEntityWorld().setBlockState(pos, Blocks.AIR.getDefaultState());
        giveDropsToMeeCreeps(drops);
        return true;
    }


    @Override
    public boolean harvestAndDrop(BlockPos pos) {
        World world = entity.getEntityWorld();
        if (world.isAirBlock(pos)) {
            return true;
        }
        BlockState state = world.getBlockState(pos);
        if (!allowedToHarvest(state, world, pos, GeneralTools.getHarvester(world))) {
            return false;
        }

        Block block = state.getBlock();

        NonNullList<ItemStack> drops = NonNullList.create();
        drops.addAll(Block.getDrops(state, (ServerWorld) world, pos,null));

        net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(drops, world, pos, state, 0, 1.0f, false, GeneralTools.getHarvester(world));
        SoundTools.playSound(world, block.getSoundType(state).getBreakSound(), pos.getX(), pos.getY(), pos.getZ(), 1.0f, 1.0f);
        block.onBlockHarvested(world, pos, state, GeneralTools.getHarvester(world));
        entity.getEntityWorld().setBlockState(pos, Blocks.AIR.getDefaultState());
        for (ItemStack stack : drops) {
            entity.entityDropItem(stack, 0.0f);
        }
        return true;
    }


    @Override
    public void pickup(ItemEntity item) {
        ItemStack remaining = entity.addStack(item.getItem().copy());
        if (remaining.isEmpty()) {
            item.remove();
        } else {
            item.setItem(remaining);
            needsToPutAway = true;
        }
    }

    @Override
    public boolean allowedToHarvest(BlockState state, World world, BlockPos pos, PlayerEntity entityPlayer) {
        if (state.getBlock().getBlockHardness(state, world, pos) < 0) {
            return false;
        }
        if (!state.getBlock().canEntityDestroy(state, world, pos, entityPlayer)) {
            return false;
        }
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, pos, state, entityPlayer);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return false;
        }
        return state.getBlock().canHarvestBlock(state, world, pos, entityPlayer);
    }

    @Override
    public void done() {
        options.setStage(Stage.DONE);
        ServerActionManager.getManager(entity.world).save();
    }

    // Indicate the task is done and that it is time to do the last task (putting back stuff etc)
    @Override
    public void taskIsDone() {
        options.setStage(Stage.TASK_IS_DONE);
        ServerActionManager.getManager(entity.world).save();
    }

    @Override
    public void putStuffAway() {
        needsToPutAway = true;
    }

    @Override
    public void speedUp(int t) {
        waitABit = t;
    }

    @Override
    public void dropAndPutAwayLater(ItemStack stack) {
        ItemEntity entityItem = entity.getEntity().entityDropItem(stack, 0.0f);
        itemsToPickup.add(entityItem);
        putStuffAway();
    }

    @Override
    public BlockPos findSuitablePositionNearPlayer(double distance) {
        return findSuitablePositionNearPlayer(this.entity, options.getPlayer(), distance);
    }

    public static BlockPos findSuitablePositionNearPlayer(@Nonnull EntityMeeCreeps meeCreep, @Nonnull PlayerEntity player, double distance) {
        Vec3d playerPos = player.getPositionVector();
        Vec3d entityPos = meeCreep.getPositionVector();

        if (entityPos.distanceTo(playerPos) < (distance * 1.2)) {
            // No need to move
            return meeCreep.getPosition();
        }

        double dx = playerPos.x - entityPos.x;
        double dy = playerPos.x - entityPos.x;
        double dz = playerPos.x - entityPos.x;
        Vec3d v = new Vec3d(-dx, -dy, -dz);
        v = v.normalize();
        Vec3d pos = new Vec3d(playerPos.x + v.x * distance, playerPos.y + v.y * distance, playerPos.z + v.z * distance);
        // First find a good spot at the specific location
        World world = player.getEntityWorld();

        float width = meeCreep.getWidth();
        float eyeHeight = meeCreep.getEyeHeight();

        // First try on the prefered spot
        BlockPos p = scanSuitablePos(new BlockPos(pos.x, pos.y + .5, pos.z), world, width, eyeHeight);
        if (p != null) return p;
        // No good spot to stand on found. Try other spots around the prefered spot
        p = scanAround(pos, world, width, eyeHeight);
        if (p != null) return p;
        // No good spot to stand on found. Try other spots around the player
        p = scanAround(playerPos, world, width, eyeHeight);
        if (p != null) return p;

        // If all else fails we go stand where the player is
        return player.getPosition();
    }

    private static BlockPos scanAround(Vec3d vec, World world, float width, float eyeHeight) {
        BlockPos pos = new BlockPos(vec.x, vec.y + 0.5, vec.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = pos.add(dx, 0, dz);
                p = scanSuitablePos(p, world, width, eyeHeight);
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    private static BlockPos scanSuitablePos(BlockPos pos, World world, float width, float eyeHeight) {
        for (int d = 0; d < 6; d++) {
            BlockPos p = pos.down(d);
            if (isSuitableStandingPos(world, p, width, eyeHeight)) {
                return p;
            }
            p = pos.up(d);
            if (isSuitableStandingPos(world, p, width, eyeHeight)) {
                return p;
            }
        }
        return null;
    }

    private static boolean isSuitableStandingPos(World world, BlockPos p, float width, float eyeHeight) {
        return canStandOn(world.getBlockState(p.down()), world, p)
                && !canStandOn(world.getBlockState(p), world, p)
                && !willSuffocateHere(world, p.getX() + .5, p.getY(), p.getZ() + .5, width, eyeHeight);
    }

    private static boolean canStandOn(BlockState state, World world, BlockPos pos) {
        return state.getMaterial().blocksMovement() && state.getShape(world, pos) == VoxelShapes.fullCube();
    }

    private static boolean willSuffocateHere(World world, double posX, double posY, double posZ, float width, float eyeHeight) {
        BlockPos.PooledMutable mutableBlockPos = BlockPos.PooledMutable.retain();

        for (int i = 0; i < 8; ++i) {
            int x = MathHelper.floor(posX + ((((i >> 1) % 2) - 0.5F) * width * 0.8F));
            int y = MathHelper.floor(posY + ((((i) % 2) - 0.5F) * 0.1F) + eyeHeight);
            int z = MathHelper.floor(posZ + ((((i >> 2) % 2) - 0.5F) * width * 0.8F));

            if (mutableBlockPos.getX() != x || mutableBlockPos.getY() != y || mutableBlockPos.getZ() != z) {
                mutableBlockPos.setPos(x, y, z);

                if (world.getBlockState(mutableBlockPos).causesSuffocation(world, new BlockPos(posX, posY, posZ))) {
                    mutableBlockPos.close();
                    return true;
                }
            }
        }

        mutableBlockPos.close();
        return false;
    }

    @Override
    public void giveToPlayerOrDrop() {
        ServerPlayerEntity player = getPlayer();
        BlockPos position = entity.getPosition();
        if (player == null || position.distanceSq(player.getPosition()) > 2 * 2) {
            if (player != null) {
                showMessage("message.meecreeps.where_are_you");
            }
            entity.dropInventory();
        } else {
            showMessage("message.meecreeps.i_gave_some_things");
            List<ItemStack> remaining = new ArrayList<>();
            for (ItemStack stack : entity.getInventory()) {
                if (!stack.isEmpty()) {
                    if (!player.inventory.addItemStackToInventory(stack)) {
                        remaining.add(stack);
                    }
                }
            }
            player.openContainer.detectAndSendChanges();
            for (ItemStack stack : remaining) {
                entity.entityDropItem(stack, 0.0f);
            }
            entity.getInventory().clear();
        }

    }

    @Nullable
    protected ServerPlayerEntity getPlayer() {
        return (ServerPlayerEntity) options.getPlayer();
    }

    @Override
    public boolean findItemOnGroundOrInChest(Predicate<ItemStack> matcher, int maxAmount, String message, String... parameters) {
        List<BlockPos> meeCreepChests = findMeeCreepChests(worker.getSearchBox());
        if (meeCreepChests.isEmpty()) {
            if (!findItemOnGround(worker.getSearchBox(), matcher, this::pickup)) {
                if (!findInventoryContainingMost(worker.getSearchBox(), matcher, p -> fetchFromInventory(p, matcher, maxAmount))) {
                    showMessage(message, parameters);
                    return false;
                }
            }
        } else {
            if (!findInventoryContainingMost(meeCreepChests, matcher, p -> fetchFromInventory(p, matcher, maxAmount))) {
                showMessage(message, parameters);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean findItemOnGroundOrInChest(Predicate<ItemStack> matcher, int maxAmount) {
        List<BlockPos> meeCreepChests = findMeeCreepChests(worker.getSearchBox());
        if (meeCreepChests.isEmpty()) {
            if (!findItemOnGround(worker.getSearchBox(), matcher, this::pickup)) {
                if (!findInventoryContainingMost(worker.getSearchBox(), matcher, p -> fetchFromInventory(p, matcher, maxAmount))) {
                    return false;
                }
            }
        } else {
            if (!findInventoryContainingMost(meeCreepChests, matcher, p -> fetchFromInventory(p, matcher, maxAmount))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find all chests that have an item frame attached to them with an meecreep cube in them
     */
    private List<BlockPos> findMeeCreepChests(AxisAlignedBB box) {
        List<ItemFrameEntity> frames = entity.getEntityWorld().getEntitiesWithinAABB(ItemFrameEntity.class, box, input -> {
            if (!input.getDisplayedItem().isEmpty() && input.getDisplayedItem().getItem() instanceof CreepCubeItem) {
                BlockPos position = input.getHangingPosition().offset(input.getHorizontalFacing().getOpposite());
                if (InventoryTools.isInventory(entity.getEntityWorld(), position)) {
                    return true;
                }
            }
            return false;
        });
        return frames.stream().map(entityItemFrame -> entityItemFrame.getHangingPosition().offset(entityItemFrame.getHorizontalFacing().getOpposite())).collect(Collectors.toList());
    }

    private boolean findMeeCreepBoxOnGround() {
        BlockPos position = entity.getEntity().getPosition();
        List<ItemEntity> items = entity.getWorld().getEntitiesWithinAABB(ItemEntity.class, worker.getSearchBox(),
                input -> !input.getItem().isEmpty() && input.getItem().getItem() instanceof CreepCubeItem);
        if (!items.isEmpty()) {
            items.sort((o1, o2) -> {
                double d1 = position.distanceSq(o1.getPosX(), o1.getPosY(), o1.getPosZ(), false);
                double d2 = position.distanceSq(o2.getPosX(), o2.getPosY(), o2.getPosZ(), false);
                return Double.compare(d1, d2);
            });
            ItemEntity entityItem = items.get(0);
            navigateTo(entityItem, (pos) -> pickup(entityItem));
            return true;
        }
        return false;
    }

    /**
     * See if there is a specific item around. If so start navigating to it and return true
     */
    @Override
    public boolean findItemOnGround(AxisAlignedBB box, Predicate<ItemStack> matcher, Consumer<ItemEntity> job) {
        BlockPos position = entity.getPosition();
        List<ItemEntity> items = entity.getEntityWorld().getEntitiesWithinAABB(ItemEntity.class, box, input -> matcher.test(input.getItem()));
        if (!items.isEmpty()) {
            items.sort((o1, o2) -> {
                double d1 = position.distanceSq(o1.getPosX(), o1.getPosY(), o1.getPosZ(), false);
                double d2 = position.distanceSq(o2.getPosX(), o2.getPosY(), o2.getPosZ(), false);
                return Double.compare(d1, d2);
            });
            ItemEntity entityItem = items.get(0);
            navigateTo(entityItem, (pos) -> job.accept(entityItem));
            return true;
        }
        return false;
    }

    @Override
    public void putInventoryInChest(BlockPos pos) {
        putInventoryInChestWithMessage(pos, null);
    }

    private void putInventoryInChestWithMessage(BlockPos pos, String message, String... parameters) {
        if (!InventoryTools.isInventory(entity.getEntityWorld(), pos)) {
            // No longer an inventory here. Just drop the items on the ground here
            if (message != null) {
                showMessage("message.meecreeps.inventory_missing");
            }
            entity.dropInventory();
        } else {
            if (message != null) {
                showMessage(message, parameters);
            }
            TileEntity te = entity.getEntityWorld().getTileEntity(pos);

            // todo: verify this works with the changed cap
            LazyOptional<IItemHandler> handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, Direction.UP);
            handler.ifPresent(e -> {
                for (ItemStack stack : entity.getInventory()) {
                    if (!stack.isEmpty()) {
                        ItemStack remaining = ItemHandlerHelper.insertItem(e, stack, false);
                        if (!remaining.isEmpty()) {
                            entity.entityDropItem(remaining, 0.0f);
                        }
                    }
                }
                entity.getInventory().clear();
            });
        }
    }

    private void fetchFromInventory(BlockPos pos, Predicate<ItemStack> matcher, int maxAmount) {
        materialChest = pos;
        World world = entity.getEntityWorld();
        if (!InventoryTools.isInventory(world, pos)) {
            // No longer an inventory. We cannot get the items from here
            return;
        }
        TileEntity te = world.getTileEntity(pos);

        // todo: ensure this works
        LazyOptional<IItemHandler> handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, Direction.UP);
        handler.ifPresent(e -> {
            int amount = maxAmount;
            for (int i = 0; i < e.getSlots(); i++) {
                if (amount <= 0) {
                    return;
                }
                ItemStack stack = e.getStackInSlot(i);
                if (stack == null) {
                    // There are still bad mods!
                    String badBlock = world.getBlockState(pos).getBlock().getRegistryName().toString();
                    MeeCreeps.LOGGER.warn("Block " + badBlock + " is returning null for e.getStackInSlot()! That's a bug!");
                } else if (!stack.isEmpty() && matcher.test(stack)) {
                    ItemStack extracted = e.extractItem(i, Math.min(maxAmount, stack.getCount()), false);
                    ItemStack remaining = entity.addStack(extracted);
                    amount -= extracted.getCount() - remaining.getCount();
                    if (!remaining.isEmpty()) {
                        e.insertItem(i, remaining, false);
                    }
                }
            }
        });
    }

    private float calculateScore(int countMatching, int countFreeForMatching) {
        return 2.0f * countMatching + countFreeForMatching;
    }

    protected boolean findInventoryContainingMost(List<BlockPos> inventoryList, Predicate<ItemStack> matcher, Consumer<BlockPos> job) {
        World world = entity.getEntityWorld();
        List<BlockPos> inventories = new ArrayList<>();
        Map<BlockPos, Float> countMatching = new HashMap<>();
        for (BlockPos pos : inventoryList) {
            TileEntity te = world.getTileEntity(pos);

            // todo: ensure this works
            LazyOptional<IItemHandler> inventory = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, Direction.UP);
            inventory.ifPresent(handler -> {
                int cnt = 0;
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack stack = handler.getStackInSlot(i);
                    if (stack == null) {
                        // There are still bad mods!
                        String badBlock = world.getBlockState(pos).getBlock().getRegistryName().toString();
                        MeeCreeps.LOGGER.warn("Block " + badBlock + " is returning null for handler.getStackInSlot()! That's a bug!");
                    } else if (!stack.isEmpty()) {
                        if (matcher.test(stack)) {
                            cnt += stack.getCount();
                        }
                    }
                }
                if (cnt > 0) {
                    inventories.add(pos);
                    countMatching.put(pos, (float) cnt);
                }
            });
        }
        if (inventories.isEmpty()) {
            return false;
        } else {
            // Sort so that highest score goes first
            inventories.sort((p1, p2) -> Float.compare(countMatching.get(p2), countMatching.get(p1)));
            navigateTo(inventories.get(0), job);
            return true;
        }
    }

    protected boolean findInventoryContainingMost(AxisAlignedBB box, Predicate<ItemStack> matcher, Consumer<BlockPos> job) {
        World world = entity.getEntityWorld();
        List<BlockPos> inventories = new ArrayList<>();
        Map<BlockPos, Float> countMatching = new HashMap<>();
        GeneralTools.traverseBox(world, box,
                (pos, state) -> InventoryTools.isInventory(world, pos),
                (pos, state) -> {
                    TileEntity te = world.getTileEntity(pos);
                    LazyOptional<IItemHandler> inventory = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, Direction.UP);


                    // todo: ensure this works
                    inventory.ifPresent(handler -> {
                        int cnt = 0;
                        for (int i = 0; i < handler.getSlots(); i++) {
                            ItemStack stack = handler.getStackInSlot(i);
                            if (stack == null) {
                                // There are still bad mods!
                                String badBlock = world.getBlockState(pos).getBlock().getRegistryName().toString();
                                MeeCreeps.LOGGER.warn("Block " + badBlock + " is returning null for handler.getStackInSlot()! That's a bug!");
                            } else if (!stack.isEmpty()) {
                                if (matcher.test(stack)) {
                                    cnt += stack.getCount();
                                }
                            }
                        }
                        if (cnt > 0) {
                            inventories.add(pos);
                            countMatching.put(pos, (float) cnt);
                        }
                    });
                });
        if (inventories.isEmpty()) {
            return false;
        } else {
            // Sort so that highest score goes first
            inventories.sort((p1, p2) -> Float.compare(countMatching.get(p2), countMatching.get(p1)));
            navigateTo(inventories.get(0), job);
            return true;
        }
    }

    // Default implementation checks materialChest first and otherwise assumes the action was centered on the chest. Override if that's not applicable
    private boolean findChestToPutItemsIn() {
        for (PreferedChest chest : worker.getPreferedChests()) {
            switch (chest) {
                case MARKED:
                    List<BlockPos> meeCreepChests = findMeeCreepChests(worker.getSearchBox());
                    if (!meeCreepChests.isEmpty()) {
                        navigateTo(meeCreepChests.get(0), p -> putInventoryInChestWithMessage(p, "message.meecreeps.put_stuff_away_marked"));
                        return true;
                    }
                    break;
                case TARGET:
                    BlockPos pos = options.getTargetPos();
                    if (InventoryTools.isInventory(entity.getEntityWorld(), pos)) {
                        navigateTo(pos, p -> putInventoryInChestWithMessage(p, "message.meecreeps.put_stuff_away_target"));
                        return true;
                    }
                    break;
                case FIND_MATCHING_INVENTORY:
                    if (findSuitableInventory(worker.getSearchBox(), entity.getInventoryMatcher(),
                            this::putAwayAndTellPlayerTheDistance)) {
                        return true;
                    }
                    break;
                case LAST_CHEST:
                    if (materialChest != null) {
                        if (InventoryTools.isInventory(entity.getEntityWorld(), materialChest)) {
                            navigateTo(materialChest, this::putAwayAndTellPlayerTheDistance);
                            return true;
                        }
                    }
                    break;
            }
        }

        return false;
    }

    private void putAwayAndTellPlayerTheDistance(BlockPos p) {
        ServerPlayerEntity player = getPlayer();
        double dist = 0;
        if (player != null) {
            dist = player.getPosition().distanceSq(p.getX(), p.getY(), p.getZ(), false);
        }
        putInventoryInChestWithMessage(p, "message.meecreeps.put_stuff_away_specific",
                Integer.toString((int) dist));
    }

    protected boolean needToFindChest(boolean timeToWrapUp) {
        return needsToPutAway || (timeToWrapUp && entity.hasStuffInInventory());
    }

    @Override
    public boolean findSuitableInventory(AxisAlignedBB box, Predicate<ItemStack> matcher, Consumer<BlockPos> job) {
        World world = entity.getEntityWorld();
        List<BlockPos> inventories = new ArrayList<>();
        Map<BlockPos, Float> countMatching = new HashMap<>();
        GeneralTools.traverseBox(world, box,
                (pos, state) -> InventoryTools.isInventory(world, pos),
                (pos, state) -> {
                    TileEntity te = world.getTileEntity(pos);
                    LazyOptional<IItemHandler> inventory = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, Direction.UP);
                    inventory.ifPresent(handler -> {
                        // @todo config?
                        if (handler.getSlots() > 8) {
                            int cnt = 0;
                            int free = 0;
                            for (int i = 0; i < handler.getSlots(); i++) {
                                ItemStack stack = handler.getStackInSlot(i);
                                if (stack == null) {
                                    // There are still bad mods!
                                    String badBlock = world.getBlockState(pos).getBlock().getRegistryName().toString();
                                    MeeCreeps.LOGGER.warn("Block " + badBlock + " is returning null for handler.getStackInSlot()! That's a bug!");
                                } else if (!stack.isEmpty()) {
                                    if (matcher.test(stack)) {
                                        cnt += stack.getCount();
                                        free += handler.getSlotLimit(i) - stack.getCount();
                                    }
                                } else {
                                    free += handler.getSlotLimit(i);
                                }
                            }
                            if (cnt >= 0) {
                                inventories.add(pos);
                                countMatching.put(pos, calculateScore(cnt, free));
                            }
                        }
                    });
                });
        if (inventories.isEmpty()) {
            return false;
        } else {
            // Sort so that highest score goes first
            inventories.sort((p1, p2) -> Float.compare(countMatching.get(p2), countMatching.get(p1)));
            navigateTo(inventories.get(0), job);
            return true;
        }
    }

//    @Override
//    public List<BlockPos> findInventoriesWithMostSpace(AxisAlignedBB box) {
//        World world = entity.getEntityWorld();
//        List<BlockPos> inventories = new ArrayList<>();
//        Map<BlockPos, Float> countMatching = new HashMap<>();
//        GeneralTools.traverseBox(world, box,
//                (pos, state) -> InventoryTools.isInventory(world, pos),
//                (pos, state) -> {
//                    TileEntity te = world.getTileEntity(pos);
//                    IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
//                    // @todo config?
//                    if (handler.getSlots() > 8) {
//                        int free = 0;
//                        for (int i = 0 ; i < handler.getSlots() ; i++) {
//                            ItemStack stack = handler.getStackInSlot(i);
//                            if (stack.isEmpty()) {
//                                free += handler.getSlotLimit(i);
//                            }
//                        }
//                        inventories.add(pos);
//                        countMatching.put(pos, (float) free);
//                    }
//                });
//        // Sort so that highest score goes first
//        inventories.sort((p1, p2) -> Float.compare(countMatching.get(p2), countMatching.get(p1)));
//        return inventories;
//    }

    private boolean tryFindingItemsToPickup() {
        BlockPos position = entity.getPosition();
        List<ItemEntity> items = itemsToPickup;
        if (!items.isEmpty()) {
            items.sort((o1, o2) -> {
                double d1 = position.distanceSq(o1.getPosX(), o1.getPosY(), o1.getPosZ(), false);
                double d2 = position.distanceSq(o2.getPosX(), o2.getPosY(), o2.getPosZ(), false);
                return Double.compare(d1, d2);
            });
            ItemEntity entityItem = items.get(0);
            items.remove(0);
            navigateTo(entityItem, (p) -> pickup(entityItem));
            return true;
        }
        return false;
    }

    /**
     * Return true if the given postion is air, the postion below is not and the postion above is also air
     */
    boolean isStandable(BlockPos pos) {
        World world = entity.getWorld();
        return !world.isAirBlock(pos.down()) && world.isAirBlock(pos) && world.isAirBlock(pos.up());
    }

    /**
     * Find the nearest suitable spot to stand on at this x,z
     * Or null if there is no suitable position
     */
    private BlockPos findSuitableSpot(BlockPos pos) {
        if (isStandable(pos)) {
            return pos;
        }
        if (isStandable(pos.down())) {
            return pos.down();
        }
        if (isStandable(pos.up())) {
            return pos.up();
        }
        if (isStandable(pos.down(2))) {
            return pos.down(2);
        }
        return null;
    }

    /**
     * Calculate the best spot to move too for reaching the given position
     */
    @Override
    public BlockPos findBestNavigationSpot(BlockPos pos) {
        Entity ent = entity.getEntity();
        World world = entity.getWorld();

        BlockPos spotN = findSuitableSpot(pos.north());
        BlockPos spotS = findSuitableSpot(pos.south());
        BlockPos spotW = findSuitableSpot(pos.west());
        BlockPos spotE = findSuitableSpot(pos.east());

        double dn = spotN == null ? Double.MAX_VALUE : spotN.distanceSq(ent.getPosX(), ent.getPosY(), ent.getPosZ(), true);
        double ds = spotS == null ? Double.MAX_VALUE : spotS.distanceSq(ent.getPosX(), ent.getPosY(), ent.getPosZ(), true);
        double de = spotE == null ? Double.MAX_VALUE : spotE.distanceSq(ent.getPosX(), ent.getPosY(), ent.getPosZ(), true);
        double dw = spotW == null ? Double.MAX_VALUE : spotW.distanceSq(ent.getPosX(), ent.getPosY(), ent.getPosZ(), true);
        BlockPos p;
        if (dn <= ds && dn <= de && dn <= dw) {
            p = spotN;
        } else if (ds <= de && ds <= dw && ds <= dn) {
            p = spotS;
        } else if (de <= dn && de <= dw && de <= ds) {
            p = spotE;
        } else {
            p = spotW;
        }

        if (p == null) {
            // No suitable spot. Try standing on top
            p = findSuitableSpot(pos);
            // We also need to be able to jump up one spot
            if (p != null && !world.isAirBlock(p.up(2))) {
                p = null;
            }
        }

        return p;
    }

    public void readFromNBT(CompoundNBT tag) {
        worker.readFromNBT(tag);
        if (tag.contains("materialChest")) {
            materialChest = BlockPos.fromLong(tag.getLong("materialChest"));
        }
    }

    public void writeToNBT(CompoundNBT tag) {
        worker.writeToNBT(tag);
        if (materialChest != null) {
            tag.putLong("materialChest", materialChest.toLong());
        }
    }
}
