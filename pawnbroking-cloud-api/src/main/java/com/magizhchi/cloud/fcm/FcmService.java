package com.magizhchi.cloud.fcm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import com.magizhchi.cloud.sync.SyncController.NotificationItem;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.util.List;

@Service
public class FcmService {
    private static final Logger log = LoggerFactory.getLogger(FcmService.class);

    private final JdbcTemplate jdbc;
    private final String credsPath;
    private final boolean enabled;
    private volatile boolean ready;

    public FcmService(JdbcTemplate jdbc,
                      @Value("${pawnbroking.fcm.credentials-path:}") String credsPath,
                      @Value("${pawnbroking.fcm.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.credsPath = credsPath; this.enabled = enabled;
    }

    @PostConstruct
    public void init() {
        if (!enabled) { log.warn("FCM disabled"); return; }
        try {
            FirebaseOptions opts = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(new FileInputStream(credsPath)))
                .build();
            if (FirebaseApp.getApps().isEmpty()) FirebaseApp.initializeApp(opts);
            ready = true;
            log.info("FCM initialised");
        } catch (Exception e) {
            log.error("FCM init failed: {}", e.toString());
        }
    }

    public void broadcast(String shopId, List<NotificationItem> items) {
        if (!ready || items.isEmpty()) return;
        List<String> tokens = jdbc.queryForList(
            "SELECT fcm_token FROM public.devices WHERE shop_id = ?",
            String.class, shopId);
        if (tokens.isEmpty()) return;

        for (NotificationItem ni : items) {
            MulticastMessage msg = MulticastMessage.builder()
                .setNotification(Notification.builder()
                    .setTitle(ni.title()).setBody(ni.body()).build())
                .putData("table",   ni.table())
                .putData("row_pk",  ni.rowPk() == null ? "" : ni.rowPk())
                .putData("shop_id", shopId)
                .addAllTokens(tokens)
                .build();
            try {
                BatchResponse br = FirebaseMessaging.getInstance().sendEachForMulticast(msg);
                if (br.getFailureCount() > 0) cleanupBadTokens(br, tokens);
            } catch (FirebaseMessagingException e) {
                log.warn("fcm send failed: {}", e.getMessagingErrorCode());
            }
        }
    }

    private void cleanupBadTokens(BatchResponse br, List<String> tokens) {
        for (int i = 0; i < br.getResponses().size(); i++) {
            SendResponse r = br.getResponses().get(i);
            if (r.isSuccessful()) continue;
            MessagingErrorCode code = r.getException() == null ? null : r.getException().getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                jdbc.update("DELETE FROM public.devices WHERE fcm_token = ?", tokens.get(i));
            }
        }
    }
}
