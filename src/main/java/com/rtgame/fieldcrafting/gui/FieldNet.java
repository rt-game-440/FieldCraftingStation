package com.rtgame.fieldcrafting.gui;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;

import com.rtgame.fieldcrafting.FieldCrafting;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 界面专用的网络通道，把数据转给当前打开的 {@link FieldStationContainer}。
 * 1.7.10 的自定义包在主线程处理，并和原版点击包同一队列，所以滚动、搜索一定先于之后的点击生效。
 */
public final class FieldNet {

    /** 写入一个包的内容。 */
    public interface Writer {

        void write(PacketBuffer buf) throws IOException;
    }

    private static SimpleNetworkWrapper channel;

    private FieldNet() {}

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(FieldCrafting.MODID);
        channel.registerMessage(ToServer.class, Packet.class, 0, Side.SERVER);
        channel.registerMessage(ToClient.class, Packet.class, 1, Side.CLIENT);
    }

    public static void sendToServer(int windowId, int id, Writer writer) {
        channel.sendToServer(new Packet(windowId, id, encode(writer)));
    }

    public static void sendToPlayer(EntityPlayerMP player, int windowId, int id, Writer writer) {
        channel.sendTo(new Packet(windowId, id, encode(writer)), player);
    }

    private static byte[] encode(Writer writer) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        try {
            writer.write(buf);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    private static void dispatch(EntityPlayer player, Packet packet, boolean toServer) {
        if (player == null || !(player.openContainer instanceof FieldStationContainer container)
            || container.windowId != packet.windowId) {
            return; // 界面已经关了
        }
        PacketBuffer buf = new PacketBuffer(Unpooled.wrappedBuffer(packet.data));
        try {
            if (toServer) container.sync()
                .readOnServer(packet.id, buf);
            else container.sync()
                .readOnClient(packet.id, buf);
        } catch (IOException e) {
            FieldCrafting.LOG.error("场域合成站界面数据包解析失败", e);
        }
    }

    public static final class Packet implements IMessage {

        private int windowId;
        private int id;
        private byte[] data;

        public Packet() {}

        Packet(int windowId, int id, byte[] data) {
            this.windowId = windowId;
            this.id = id;
            this.data = data;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            windowId = buf.readInt();
            id = buf.readByte();
            data = new byte[buf.readableBytes()];
            buf.readBytes(data);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(windowId);
            buf.writeByte(id);
            buf.writeBytes(data);
        }
    }

    public static final class ToServer implements IMessageHandler<Packet, IMessage> {

        @Override
        public IMessage onMessage(Packet packet, MessageContext ctx) {
            dispatch(ctx.getServerHandler().playerEntity, packet, true);
            return null;
        }
    }

    public static final class ToClient implements IMessageHandler<Packet, IMessage> {

        @Override
        public IMessage onMessage(Packet packet, MessageContext ctx) {
            dispatch(FieldCrafting.proxy.clientPlayer(), packet, false);
            return null;
        }
    }
}
