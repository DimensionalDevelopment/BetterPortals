package de.johni0702.minecraft.betterportals.impl.vanilla.common.entity

import de.johni0702.minecraft.betterportals.common.entity.AbstractPortalEntity
import de.johni0702.minecraft.betterportals.impl.vanilla.common.NETHER_PORTAL_CONFIG
import net.minecraft.util.EnumFacing
import net.minecraft.util.Rotation
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

class NetherPortalEntity(
        world: World,
        plane: EnumFacing.Plane,
        portalBlocks: Set<BlockPos>,
        localDimension: Int, localPosition: BlockPos, localRotation: Rotation
) : AbstractPortalEntity(
        world, plane, portalBlocks,
        localDimension, localPosition, localRotation,
        null, BlockPos.ORIGIN, Rotation.NONE,
        NETHER_PORTAL_CONFIG
) {
    @Suppress("unused")
    constructor(world: World) : this(world, EnumFacing.Plane.VERTICAL, emptySet(), 0, BlockPos.ORIGIN, Rotation.NONE)
}