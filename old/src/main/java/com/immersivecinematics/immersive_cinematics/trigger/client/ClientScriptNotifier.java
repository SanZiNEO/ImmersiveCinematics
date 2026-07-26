package com.immersivecinematics.immersive_cinematics.trigger.client;

import com.immersivecinematics.immersive_cinematics.control.CompletionReason;
import com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptFinishedPacket;
import com.immersivecinematics.immersive_cinematics.trigger.network.NetworkHandler;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class ClientScriptNotifier {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static void notifyScriptFinished(String scriptId, CompletionReason reason) {
        NetworkHandler.sendToServer(new C2SScriptFinishedPacket(scriptId, reason));
        LOGGER.debug("Sent script finished notification: {} reason={}", scriptId, reason);
    }
}
