/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import dbeaver.openwebui.async.core.BackgroundTask;
import dbeaver.openwebui.async.core.ChatsApi;
import dbeaver.openwebui.async.core.JobStore;
import dbeaver.openwebui.model.OpenWebUIProperties;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.AIConfigurationProfile;
import org.jkiss.dbeaver.model.ai.engine.AIEngineProperties;
import org.jkiss.dbeaver.model.ai.registry.AISettingsManager;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Delivers answers of background requests nobody is waiting for: detached requests, and requests that
 * were in progress when DBeaver was closed. Checks the server every few seconds while there are jobs.
 */
final class JobPoller {

    private static final Log log = Log.getLog(JobPoller.class);
    /** A finished answer that can't be put anywhere (conversation gone) is dropped after this time. */
    private static final long UNDELIVERABLE_MS = 24 * 3600_000L;

    /** Jobs whose answer a waiting chat is streaming right now. */
    private static final Set<String> OWNED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> NOT_FOUND_SINCE = new ConcurrentHashMap<>();

    private static ScheduledExecutorService executor;
    private static ScheduledFuture<?> schedule;
    private static int scheduledSeconds;

    private JobPoller() {
    }

    static void own(String jobId) {
        OWNED.add(jobId);
    }

    static void release(String jobId) {
        OWNED.remove(jobId);
    }

    /** Starts (or re-times) periodic checks. */
    static synchronized void start() {
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Open WebUI background answers");
                t.setDaemon(true);
                return t;
            });
        }
        int seconds = Math.max(2, AsyncPlugin.getSettings().pollSeconds());
        if (schedule == null || seconds != scheduledSeconds) {
            if (schedule != null) {
                schedule.cancel(false);
            }
            scheduledSeconds = seconds;
            schedule = executor.scheduleWithFixedDelay(JobPoller::tick, seconds, seconds, TimeUnit.SECONDS);
        }
    }

    /** Checks right away (a new detached job, or a waiting chat released its job). */
    static synchronized void wake() {
        start();
        executor.execute(JobPoller::tick);
    }

    static int pendingCount() {
        return AsyncPlugin.getJobStore().jobs().size();
    }

    private static void tick() {
        JobStore store;
        try {
            store = AsyncPlugin.getJobStore();
        } catch (RuntimeException e) {
            return; // workspace not ready yet
        }
        for (JobStore.Job job : store.jobs()) {
            if (OWNED.contains(job.id)) {
                continue;
            }
            try {
                if (!job.isFinished()) {
                    ChatsApi api = apiFor(job);
                    if (api == null || !BackgroundTask.check(api, store, job)) {
                        continue;
                    }
                }
                JobStore.Job finished = store.find(job.id).orElse(null);
                if (finished == null) {
                    continue;
                }
                if (ResultApplier.deliver(finished)) {
                    store.delivered(finished.id);
                    NOT_FOUND_SINCE.remove(finished.id);
                } else {
                    long since = NOT_FOUND_SINCE.computeIfAbsent(finished.id, k -> System.currentTimeMillis());
                    if (System.currentTimeMillis() - since > UNDELIVERABLE_MS) {
                        log.debug("Open WebUI answer " + finished.id + " has nowhere to go, dropped");
                        store.remove(finished.id);
                        NOT_FOUND_SINCE.remove(finished.id);
                    }
                }
            } catch (Exception e) {
                log.debug("Error checking Open WebUI background answer " + job.id, e);
            }
        }
    }

    /** API client with the credentials of the profile the request was made with. */
    @Nullable
    private static ChatsApi apiFor(JobStore.Job job) {
        try {
            var settings = AISettingsManager.getInstance().getSettings();
            AIConfigurationProfile profile = job.profileId == null ? null : settings.getConfigurationOrNull(job.profileId);
            if (profile == null) {
                profile = settings.getDefaultConfigurationOrNull();
            }
            if (profile != null && AsyncPlugin.isOpenWebUIEngine(profile.getEngineId())) {
                profile.resolveSecrets();
                AIEngineProperties props = profile.getConfiguration();
                if (props instanceof OpenWebUIProperties p) {
                    String base = dbeaver.openwebui.model.OpenWebUIClient.normalizeBaseUrl(p.getBaseUrl());
                    if (job.apiBase == null || job.apiBase.equals(base)) {
                        return AsyncOpenWebUIEngine.createApi(p, base);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Can't resolve Open WebUI profile for background answer " + job.id, e);
        }
        return null;
    }
}
