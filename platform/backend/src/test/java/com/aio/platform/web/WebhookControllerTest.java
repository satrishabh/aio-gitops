package com.aio.platform.web;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookControllerTest {

    @Test
    void verifiesGithubSignature() throws Exception {
        byte[] body = "{\"zen\":\"hi\"}".getBytes(StandardCharsets.UTF_8);
        // echo -n '{"zen":"hi"}' | openssl dgst -sha256 -hmac secret
        String good = "sha256=" + hmac("secret", body);
        assertTrue(WebhookController.validSignature("secret", body, good));
        assertFalse(WebhookController.validSignature("other", body, good));
        assertFalse(WebhookController.validSignature("secret", body, null));
        assertFalse(WebhookController.validSignature("secret", body, "sha1=abc"));
    }

    private static String hmac(String secret, byte[] body) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }
}
