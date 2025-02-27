package de.johni0702.minecraft.betterportals.impl.transition.server

import de.johni0702.minecraft.betterportals.common.pos
import de.johni0702.minecraft.betterportals.impl.transition.common.LOGGER
import de.johni0702.minecraft.betterportals.impl.transition.common.forgeCapabilities
import de.johni0702.minecraft.betterportals.impl.transition.net.TransferToDimension
import de.johni0702.minecraft.betterportals.impl.transition.net.sendTo
import de.johni0702.minecraft.view.server.ServerView
import de.johni0702.minecraft.view.server.Ticket
import de.johni0702.minecraft.view.server.viewManager
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.server.management.PlayerList
import net.minecraftforge.common.util.ITeleporter

internal object DimensionTransitionHandler {
    val tickets = mutableMapOf<ServerView, Ticket>()
    var enabled = true
    private val knownBadTeleporterClasses = listOf(
            // Stargate Network, see https://github.com/Johni0702/BetterPortals/issues/145
            "gcewing.sg.util.FakeTeleporter"
    )

    fun transferPlayerToDimension(playerList: PlayerList, player: EntityPlayerMP, dimension: Int, teleporter: ITeleporter): Boolean {
        if (!enabled) {
            return false
        }

        if (teleporter.javaClass.name in knownBadTeleporterClasses) {
            LOGGER.debug("Skipping fancy dimension transition because of bad teleporter class: {}", teleporter.javaClass)
            return false
        }

        val world = player.server!!.getWorld(dimension)
        val viewManager = player.viewManager
        val oldView = viewManager.mainView

        // Hold on to the old main view until the client has finished the transition
        // (released in TransferToDimensionDone#Handler)
        tickets[oldView] = oldView.allocateExclusiveTicket() ?:
                // Even though optimally we'd want an exclusive ticket here, we're much more likely to get a fixed location one
                // and if they aren't moving super fast (and why would they during the transition?), that should do as well.
                oldView.allocateFixedLocationTicket() ?:
                // For maximum compatibility (and because we really only need it for 10 seconds), we'll even make due with a plain one.
                oldView.allocatePlainTicket()

        // Create a new view entity in the destination dimension
        val view = viewManager.createView(world, player.pos) {
            // Some teleporter require capabilities attached to the player but not the view entity (e.g. Cavern II)
            val oldCapabilities = forgeCapabilities
            forgeCapabilities = player.forgeCapabilities

            // Some teleporter require the world of the player to be the one they just came from (e.g. Misty World)
            // Note: In the vanilla code path, the entity has already been removed from the old world but its `world`
            //       has not yet been updated. With our code, its world has already been updated and it has also already
            //       been added to the new world's entity list (that only happens after the teleporter for vanilla).
            //       Let's just hope that no one relies on the fact that the entity is not yet in the entity list.
            setWorld(player.world)

            // Let the teleporter position the view entity
            playerList.transferEntityToWorld(this, player.dimension, world, world, teleporter)

            // Reset world, no need to spawn the entity in the new world (as would happen in vanilla now) because that
            // has already happened in createView before we were even called.
            setWorld(world)

            // Reset view entity capabilities since we're going to swap in the player soon enough anyway
            forgeCapabilities = oldCapabilities
        }

        // Start transaction to allow the handler of TransferToDimension to update the camera in the target dimension before switching to it
        viewManager.beginTransaction()

        // Inform the client of the transition to allow it to prepare any graphical transitions
        TransferToDimension(viewManager.mainView.id, view.id).sendTo(player)

        // And immediately swap it with the main view (calling code expects the player to have been transferred when the method returns)
        // This will inform the client that the server main view has changed and it'll adapt accordingly
        view.releaseAndMakeMainView(view.allocateExclusiveTicket()!!)

        viewManager.endTransaction()

        // Finally send a poslook packet to have the client confirm the teleport (and to be able to discard any UsePortal messages until the confirmation)
        player.connection.setPlayerLocation(player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch)
        viewManager.flushPackets()

        return true
    }
}