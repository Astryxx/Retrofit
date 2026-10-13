package retrofit.testmods.hello;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server to client: the reply to a {@link PingMessage}. */
public class PongMessage implements IMessage {
    int ticks;

    public PongMessage() {
    }

    PongMessage(int ticks) {
        this.ticks = ticks;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        ticks = buffer.readInt();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(ticks);
    }

    public static class Handler implements IMessageHandler<PongMessage, IMessage> {
        @Override
        public IMessage onMessage(PongMessage message, MessageContext context) {
            CrateGui.pongs++;
            System.out.println("[hello1710] Pong received on the client: the crate has ticked " + message.ticks + " times");
            return null;
        }
    }
}
