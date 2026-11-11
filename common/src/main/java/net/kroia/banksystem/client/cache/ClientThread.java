package net.kroia.banksystem.client.cache;

import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;

/**
 * Task #167: true on the client (render) thread. Client classes are only touched in a client
 * environment, through {@link Client}, so a dedicated server never loads them.
 */
public final class ClientThread {
    private ClientThread() {}

    public static boolean isCurrent() {
        return Platform.getEnvironment() == Env.CLIENT && Client.isCurrent();
    }

    private static final class Client {
        static boolean isCurrent() {
            return net.minecraft.client.Minecraft.getInstance().isSameThread();
        }
    }
}
