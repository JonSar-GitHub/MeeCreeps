package mcjty.meecreeps.actions.factories;

import mcjty.meecreeps.actions.workers.MakePlatformActionWorker;
import mcjty.meecreeps.api.IActionFactory;
import mcjty.meecreeps.api.IActionWorker;
import mcjty.meecreeps.api.IWorkerHelper;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class MakePlatformActionFactory implements IActionFactory {

    @Override
    public boolean isPossible(World world, BlockPos pos, Direction side) {
        return true;
    }

    @Override
    public boolean isPossibleSecondary(World world, BlockPos pos, Direction side) {
        return false;
    }

    @Nullable
    @Override
    public String getFurtherQuestionHeading(World world, BlockPos pos, Direction side) {
        return "message.meecreeps.action.platform_size";
    }

    @Nonnull
    @Override
    public List<Pair<String, String>> getFurtherQuestions(World world, BlockPos pos, Direction side) {
        List<Pair<String, String>> result = new ArrayList<>();
        result.add(Pair.of("9x9", "message.meecreeps.action.platform_9x9"));
        result.add(Pair.of("11x11", "message.meecreeps.action.platform_11x11"));
        result.add(Pair.of("13x13", "message.meecreeps.action.platform_13x13"));
        return result;
    }

    @Nullable
    @Override
    public IActionWorker createWorker(@Nonnull IWorkerHelper helper) {
        return new MakePlatformActionWorker(helper);
    }
}
