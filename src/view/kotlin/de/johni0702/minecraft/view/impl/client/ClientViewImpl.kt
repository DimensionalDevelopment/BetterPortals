package de.johni0702.minecraft.view.impl.client

import de.johni0702.minecraft.view.client.ClientView
import de.johni0702.minecraft.view.impl.LOGGER
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.client.gui.GuiBossOverlay
import net.minecraft.client.multiplayer.WorldClient
import net.minecraft.client.particle.ParticleManager
import net.minecraft.client.renderer.*
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher
import net.minecraft.entity.Entity
import net.minecraft.network.*
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher
import java.nio.FloatBuffer
import java.nio.IntBuffer

internal class ClientViewImpl(
        override val manager: ClientViewManagerImpl,
        override val id: Int,
        var world: WorldClient?,
        var player: EntityPlayerSP?,
        var channel: EmbeddedChannel?,
        var netManager: NetworkManager?
) : ClientView {
    override var isValid = true
    override val camera: EntityPlayerSP get() = if (manager.activeView == this) {
        Minecraft.getMinecraft().player
    } else {
        player!!
    }

    internal var renderViewEntity: Entity? = null
    private var itemRenderer: ItemRenderer? = null
    private var renderGlobal: RenderGlobal? = null
    private var entityRenderer: EntityRenderer? = null
    private var particleManager: ParticleManager? = null
    private var pointedEntity: Entity? = null
    private var objectMouseOver: RayTraceResult? = null
    private var guiBossOverlay: GuiBossOverlay? = null

    // RenderManager
    private var renderPosX = 0.0
    private var renderPosY = 0.0
    private var renderPosZ = 0.0
    private var playerViewX = 0f
    private var playerViewY = 0f
    private var viewerPosX = 0.0
    private var viewerPosY = 0.0
    private var viewerPosZ = 0.0

    // ClippingHelper
    private val frustum = Array(6) { FloatArray(4) }
    private val projectionMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val clippingMatrix = FloatArray(16)

    // ActiveRenderInfo
    private var viewport: IntBuffer? = GLAllocation.createDirectIntBuffer(16)
    private var modelView: FloatBuffer? = GLAllocation.createDirectFloatBuffer(16)
    private var projection: FloatBuffer? = GLAllocation.createDirectFloatBuffer(16)
    private var objectCoords: FloatBuffer? = GLAllocation.createDirectFloatBuffer(3)
    private var position: Vec3d? = Vec3d(0.0, 0.0, 0.0) // Needs to be set now because MC uses it before initializing it
    private var rotationX: Float = 0f
    private var rotationXZ: Float = 0f
    private var rotationZ: Float = 0f
    private var rotationYZ: Float = 0f
    private var rotationXY: Float = 0f

    // TileEntityRendererDispatcher
    private var staticPlayerX = 0.0
    private var staticPlayerY = 0.0
    private var staticPlayerZ = 0.0

    override fun toString(): String = "View $id of $world from $camera"

    internal fun captureState(mc: Minecraft) {
        itemRenderer = mc.itemRenderer
        particleManager = mc.effectRenderer
        renderGlobal = mc.renderGlobal
        entityRenderer = mc.entityRenderer
        renderPosX = mc.renderManager.renderPosX
        renderPosY = mc.renderManager.renderPosY
        renderPosZ = mc.renderManager.renderPosZ
        playerViewX = mc.renderManager.playerViewX
        playerViewY = mc.renderManager.playerViewY
        viewerPosX = mc.renderManager.viewerPosX
        viewerPosY = mc.renderManager.viewerPosY
        viewerPosZ = mc.renderManager.viewerPosZ
        world = mc.world
        player = mc.player
        renderViewEntity = mc.renderViewEntity
        netManager = mc.connection?.netManager
        pointedEntity = mc.pointedEntity
        objectMouseOver = mc.objectMouseOver
        guiBossOverlay = mc.ingameGUI?.bossOverlay
        for (i in frustum.indices) {
            val dst = frustum[i]
            System.arraycopy(clippingHelper.frustum[i], 0, dst, 0, dst.size)
        }
        System.arraycopy(clippingHelper.projectionMatrix, 0, projectionMatrix, 0, projectionMatrix.size)
        System.arraycopy(clippingHelper.modelviewMatrix, 0, modelViewMatrix, 0, modelViewMatrix.size)
        System.arraycopy(clippingHelper.clippingMatrix, 0, clippingMatrix, 0, clippingMatrix.size)
        viewport = ActiveRenderInfo.VIEWPORT
        modelView = ActiveRenderInfo.MODELVIEW
        projection = ActiveRenderInfo.PROJECTION
        objectCoords = ActiveRenderInfo.OBJECTCOORDS
        position = ActiveRenderInfo.position
        rotationX = ActiveRenderInfo.rotationX
        rotationXZ = ActiveRenderInfo.rotationXZ
        rotationZ = ActiveRenderInfo.rotationZ
        rotationYZ = ActiveRenderInfo.rotationYZ
        rotationXY = ActiveRenderInfo.rotationXY

        staticPlayerX = TileEntityRendererDispatcher.staticPlayerX
        staticPlayerY = TileEntityRendererDispatcher.staticPlayerY
        staticPlayerZ = TileEntityRendererDispatcher.staticPlayerZ
    }

    internal fun restoreState(mc: Minecraft) {
        mc.itemRenderer = itemRenderer
        mc.effectRenderer = particleManager
        mc.renderGlobal = renderGlobal
        mc.entityRenderer = entityRenderer
        mc.renderManager.renderPosX = renderPosX
        mc.renderManager.renderPosY = renderPosY
        mc.renderManager.renderPosZ = renderPosZ
        mc.renderManager.playerViewX = playerViewX
        mc.renderManager.playerViewY = playerViewY
        mc.renderManager.viewerPosX = viewerPosX
        mc.renderManager.viewerPosY = viewerPosY
        mc.renderManager.viewerPosZ = viewerPosZ
        mc.renderManager.world = world
        mc.renderManager.renderViewEntity = player
        mc.renderManager.pointedEntity = pointedEntity
        mc.player = player
        mc.world = world
        val connection = mc.connection
        if (connection != null) {
            connection.netManager = netManager?.apply { netHandler = connection }
            connection.clientWorldController = mc.world
        }
        TileEntityRendererDispatcher.instance.setWorld(mc.world)

        mc.pointedEntity = pointedEntity
        mc.objectMouseOver = objectMouseOver
        if (mc.ingameGUI != null && guiBossOverlay != null) {
            mc.ingameGUI.overlayBoss = guiBossOverlay
        }
        for (i in frustum.indices) {
            val dst = clippingHelper.frustum[i]
            System.arraycopy(frustum[i], 0, dst, 0, dst.size)
        }
        System.arraycopy(projectionMatrix, 0, clippingHelper.projectionMatrix, 0, projectionMatrix.size)
        System.arraycopy(modelViewMatrix, 0, clippingHelper.modelviewMatrix, 0, modelViewMatrix.size)
        System.arraycopy(clippingMatrix, 0, clippingHelper.clippingMatrix, 0, clippingMatrix.size)
        ActiveRenderInfo.VIEWPORT = viewport
        ActiveRenderInfo.MODELVIEW = modelView
        ActiveRenderInfo.PROJECTION = projection
        ActiveRenderInfo.OBJECTCOORDS = objectCoords
        ActiveRenderInfo.position = position
        ActiveRenderInfo.rotationX = rotationX
        ActiveRenderInfo.rotationXZ = rotationXZ
        ActiveRenderInfo.rotationZ = rotationZ
        ActiveRenderInfo.rotationYZ = rotationYZ
        ActiveRenderInfo.rotationXY = rotationXY

        TileEntityRendererDispatcher.staticPlayerX = staticPlayerX
        TileEntityRendererDispatcher.staticPlayerY = staticPlayerY
        TileEntityRendererDispatcher.staticPlayerZ = staticPlayerZ

        if (mc.entityRenderer != null) {
            mc.renderViewEntity = renderViewEntity
        }
    }

    override fun makeMainView() {
        if (isMainView) return

        manager.makeMainView(this)
    }

    internal fun copyRenderState(from: ClientViewImpl) {
        entityRenderer!!.fovModifierHand = from.entityRenderer!!.fovModifierHand
        entityRenderer!!.fovModifierHandPrev = from.entityRenderer!!.fovModifierHandPrev
        itemRenderer!!.itemStackMainHand = from.itemRenderer!!.itemStackMainHand
        itemRenderer!!.itemStackOffHand = from.itemRenderer!!.itemStackOffHand
        itemRenderer!!.equippedProgressMainHand = from.itemRenderer!!.equippedProgressMainHand
        itemRenderer!!.prevEquippedProgressMainHand = from.itemRenderer!!.prevEquippedProgressMainHand
        itemRenderer!!.equippedProgressOffHand = from.itemRenderer!!.equippedProgressOffHand
        itemRenderer!!.prevEquippedProgressOffHand = from.itemRenderer!!.prevEquippedProgressOffHand
    }

    companion object {
        fun reuseOrCreate(manager: ClientViewManagerImpl, viewId: Int, world: WorldClient, oldView: ClientViewImpl?): ClientViewImpl {
            val mc = manager.mc
            val connection = mc.connection ?: throw IllegalStateException("Cannot create view without active connection")
            val networkManager = ViewNetworkManager()
            networkManager.netHandler = connection
            val channel = EmbeddedChannel()
            channel.pipeline()
                    .addLast("splitter", NettyVarint21FrameDecoder())
                    .addLast("decoder", NettyPacketDecoder(EnumPacketDirection.CLIENTBOUND))
                    .addLast("packet_handler", networkManager)
                    .fireChannelActive()
            networkManager.setConnectionState(EnumConnectionState.PLAY)

            val networkDispatcher = NetworkDispatcher.allocAndSet(networkManager)
            channel.pipeline().addBefore("packet_handler", "fml:packet_handler", networkDispatcher)

            val stateField = NetworkDispatcher::class.java.getDeclaredField("state")
            val connectedState = stateField.type.asSubclass(Enum::class.java).enumConstants.last()
            stateField.isAccessible = true
            stateField.set(networkDispatcher, connectedState)

            val camera = ViewEntity(world, connection)
            world.spawnEntity(camera)

            val view: ClientViewImpl
            if (oldView == null) {
                LOGGER.debug("Creating new view")
                view = ClientViewImpl(manager, viewId, world, camera, channel, networkManager)

            } else {
                LOGGER.debug("Reusing stored view")
                view = ClientViewImpl(manager, viewId, world, camera, channel, networkManager)
                view.itemRenderer = oldView.itemRenderer
                view.renderGlobal = oldView.renderGlobal
                view.entityRenderer = oldView.entityRenderer
                view.particleManager = oldView.particleManager
            }

            view.withView {
                if (view.itemRenderer == null) {
                    // Need to initialize the newly create view state while it's active since several of the components
                    // get their own dependencies implicitly via the Minecraft instance
                    view.itemRenderer = ItemRenderer(mc)
                    mc.itemRenderer = view.itemRenderer // Implicitly passed to EntityRenderer via mc
                    view.renderGlobal = RenderGlobal(mc)
                    mc.renderGlobal = view.renderGlobal
                    view.entityRenderer = EntityRenderer(mc, mc.resourceManager)
                    mc.entityRenderer = view.entityRenderer
                    view.particleManager = ParticleManager(world, mc.textureManager)
                    mc.effectRenderer = view.particleManager
                }

                with (mc.renderGlobal) {
                    if (renderDispatcher != null) {
                        renderDispatcher.stopWorkerThreads()
                    }
                    renderDispatcher = manager.mainView.renderGlobal!!.renderDispatcher
                    setWorldAndLoadRenderers(world)
                }
                mc.effectRenderer.clearEffects(world)
                mc.ingameGUI.overlayBoss = GuiBossOverlay(mc)
            }

            return view
        }
    }
}
