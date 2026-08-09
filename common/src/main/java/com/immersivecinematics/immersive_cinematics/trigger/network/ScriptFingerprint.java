package com.immersivecinematics.immersive_cinematics.trigger.network;

import java.security.MessageDigest;

/**
 * 脚本文件指纹（N2b）— 内容 SHA-256 前 16 字符 hex。
 * <p>
 * 服务端/客户端只做指纹对比，不解析内容，避免无谓的整体重算。
 * 异常时用内容长度作为弱指纹兜底（简单可靠）。
 */
public final class ScriptFingerprint {

    private ScriptFingerprint() {}

    public static String of(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(content.length);
        }
    }
}
