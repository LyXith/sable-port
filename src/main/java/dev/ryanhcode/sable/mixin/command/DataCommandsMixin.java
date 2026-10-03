package dev.ryanhcode.sable.mixin.command;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.command.data_accessor.SubLevelDataAccessor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.commands.ArgProvider;
import net.minecraft.server.commands.data.DataAccessor;
import net.minecraft.server.commands.data.DataCommands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(DataCommands.class)
public class DataCommandsMixin {

    @WrapOperation(
            method = "<clinit>",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"
            )
    )
    private static List<ArgProvider.Factory<DataAccessor>> sable$allProviders(
            final Object e1,
            final Object e2,
            final Object e3,
            final Operation<List<ArgProvider.Factory<DataAccessor>>> original
    ) {
        final List<ArgProvider.Factory<DataAccessor>> providers = original.call(e1, e2, e3);

        final ObjectArrayList<ArgProvider.Factory<DataAccessor>> mutableList = new ObjectArrayList<>(providers);
        mutableList.add(SubLevelDataAccessor.PROVIDER);

        return List.copyOf(mutableList);
    }
}