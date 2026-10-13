package retrofit.testmods.hello;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;

/**
 * Client to server through a SimpleNetworkWrapper: the crate GUI's Ping button. The server
 * answers in chat and replies with a {@link PongMessage} (a REPLY, back to the sender).
 */
public class PingMessage implements IMessage {
    int x, y, z;

    public PingMessage() {
    }

    PingMessage(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        x = buffer.readInt();
        y = buffer.readInt();
        z = buffer.readInt();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
    }

    public static class Handler implements IMessageHandler<PingMessage, PongMessage> {
        @Override
        public PongMessage onMessage(PingMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().field_147369_b; // playerEntity
            TileEntity tile = player.field_70170_p.func_147438_o(message.x, message.y, message.z); // worldObj.getTileEntity
            int ticks = tile instanceof CrateTile ? ((CrateTile) tile).ticks : -1;
            System.out.println("[hello1710] Ping from " + player.func_70005_c_() + " for the crate at " + message.x + ","
                    + message.y + "," + message.z + " (" + ticks + " ticks)");
            player.func_145747_a(new ChatComponentText("[hello1710] Pong! The crate has ticked " + ticks + " times"));
            return new PongMessage(ticks);
        }
    }
}
