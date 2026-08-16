package com.volumeperapp.app.audio;

import android.service.notification.NotificationListenerService;

/**
 * Exists only to be enabled.
 *
 * <p>{@code MediaSessionManager.getActiveSessions()} refuses to answer unless
 * the caller is an enabled notification listener, so the app needs a listener
 * component to point at. It reads no notifications and overrides nothing — the
 * permission is the whole payload.
 */
public final class SessionListenerService extends NotificationListenerService {
}
