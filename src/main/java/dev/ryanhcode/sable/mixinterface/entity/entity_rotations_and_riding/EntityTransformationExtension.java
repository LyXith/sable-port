package dev.ryanhcode.sable.mixinterface.entity.entity_rotations_and_riding;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public interface EntityTransformationExtension {
    void sable$setSubLevelOrientation(Quaternionf orientation);

    void sable$setSubLevelScale(Vector3f scale);

    Quaternionf sable$getSubLevelOrientation();

    Vector3f sable$getSubLevelScale();
}
