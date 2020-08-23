package mcjty.meecreeps.blocks;

import mcjty.lib.McJtyLib;
import mcjty.meecreeps.MeeCreeps;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

public class PortalBlock extends Block implements ITileEntityProvider {

    public PortalBlock() {
        super(Material.IRON);
        setUnlocalizedName(MeeCreeps.MODID + ".portalblock");
        setRegistryName("portalblock");
    }

    @Override
    public RayTraceResult collisionRayTrace(BlockState blockState, World worldIn, BlockPos pos, Vec3d start, Vec3d end) {
        return null;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public boolean shouldSideBeRendered(BlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return false;
    }

    @Override
    public boolean isBlockNormalCube(BlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(BlockState state) {
        return false;
    }

    @Override
    public boolean isFullBlock(BlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(BlockState state) {
        return false;
    }


    @Override
    public EnumBlockRenderType getRenderType(BlockState state) {
        return EnumBlockRenderType.INVISIBLE;
    }

    @Override
    public void addCollisionBoxToList(BlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, @Nullable Entity entityIn, boolean isActualState) {

    }

    public void initModel() {
        McJtyLib.proxy.initStandardItemModel(this);
        PortalTESR.register();
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new PortalTileEntity();
    }
}
