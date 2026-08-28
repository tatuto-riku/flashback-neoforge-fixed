package dev.flashbackfix.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Direct mapped access avoids reflection silently failing on LivingEntity's protected lerp field. */
@Mixin(LivingEntity.class)
public interface AccessorEntityInterpolation {

    @Accessor("lerpSteps")
    int flashbackNeoForgeFixed$getLerpSteps();

    @Accessor("lerpSteps")
    void flashbackNeoForgeFixed$setLerpSteps(int steps);
}
