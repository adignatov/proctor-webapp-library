package com.indeed.proctor.webapp.controllers;

import com.google.common.collect.Lists;
import com.indeed.proctor.webapp.extensions.AfterBackgroundJobExecute;
import com.indeed.proctor.webapp.extensions.BeforeBackgroundJobExecute;
import org.apache.log4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 */
public abstract class BackgroundJob<T> implements Callable<T> {
    private static final Logger LOGGER = Logger.getLogger(BackgroundJob.class);

    private Future<T> future;
    private JobStatus status = JobStatus.PENDING;
    protected final StringBuilder logBuilder = new StringBuilder();

    private Long id;
    private UUID uuid;

    private final long createdTime = System.currentTimeMillis();

    // URL to direct users to upon completion
    private final List<ResultUrl> urls = Lists.newArrayList();

    private String endMessage = "";

    private Throwable error = null;
    private boolean executeFinished = false;

    public void log(final String message) {
        logBuilder.append(message).append("\n");
    }

    public String getLog() {
        return logBuilder.toString();
    }

    public JobStatus getStatus() {
        if (future != null && status == JobStatus.PENDING) {
            if (future.isCancelled()) {
                setStatus(JobStatus.CANCELLED);
            } else if(error != null) {
                setStatus(JobStatus.FAILED);
            } else if (executeFinished){
                setStatus(JobStatus.DONE);
            }
        }
        return status;
    }

    public void setStatus(final JobStatus status) {
        this.status = status;
    }

    public Future<T> getFuture() {
        return future;
    }

    public void setFuture(final Future<T> future) {
        this.future = future;
    }

    public void setId(final long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public void setUUID(final UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUUID() {
        return uuid;
    }

    public List<ResultUrl> getUrls() {
        return urls;
    }

    public void addUrl(final String url, final String text) {
        this.addUrl(url, text, "");
    }

    public void addUrl(final String url, final String text, final String target) {
        this.addUrl(new ResultUrl(url, text, target));
    }

    public void addUrl(final ResultUrl url) {
        this.urls.add(url);
    }

    public String getEndMessage() {
        return endMessage;
    }

    public void setEndMessage(final String endMessage) {
        this.endMessage = endMessage;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(final Throwable error) {
        this.error = error;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public String toString() {
        return id + ": " + status;
    }

    public boolean isRunning() {
        return future == null || (!future.isDone() && !future.isCancelled());
    }

    public abstract String getTitle();

    public JobType getJobType() {
        return JobType.UNKNOWN;
    }

    public void logFailedJob(final Throwable t) {
        log("Failed:");
        Throwable cause = t;
        final StringBuilder level = new StringBuilder(10);
        while (cause != null) {
            log(level.toString() + cause.getMessage());
            cause = cause.getCause();
            level.append("-- ");
        }
        if (!future.isCancelled() && !executeFinished) {
            setError(t);
        }
    }

    public static class ResultUrl {
        private final String href;
        private final String text;
        private final String target;

        public ResultUrl(final String href,
                         final String text,
                         final String target) {
            this.href = href;
            this.text = text;
            this.target = target;
        }

        public String getHref() {
            return href;
        }

        public String getTarget() {
            return target;
        }

        public String getText() {
            return text;
        }
    }

    protected abstract List<BeforeBackgroundJobExecute> getBeforeBackgroundJobExecutes();
    protected abstract List<AfterBackgroundJobExecute> getAfterBackgroundJobExecutes();

    protected abstract T execute() throws Exception;

    @Override
    public T call() throws Exception {
        T result = null;

        try {
            for (final BeforeBackgroundJobExecute beforeBackgroundJobExecute : getBeforeBackgroundJobExecutes()) {
                beforeBackgroundJobExecute.beforeExecute(this);
            }
        } catch (final Exception e) {
            LOGGER.error("BeforeBackgroundJobExecute Failed: " + getTitle(), e);
            logFailedJob(e);
            return null;
        }

        try {
            result = execute();
        } catch (final Exception e) {
            LOGGER.error("Background Job Failed: " + getTitle(), e);
            logFailedJob(e);
        } finally {
            executeFinished = true;
        }

        try {
            for (final AfterBackgroundJobExecute afterBackgroundJobExecute : getAfterBackgroundJobExecutes()) {
                afterBackgroundJobExecute.afterExecute(this, result);
            }
        } catch (final Exception e) {
            LOGGER.error("AfterBackgroundJobExecute Failed: " + getTitle(), e);
            logFailedJob(e);
        }

        return result;
    }

    public enum JobType {
        TEST_CREATION("test-creation"),
        TEST_EDIT("test-edit"),
        TEST_DELETION("test-deletion"),
        TEST_PROMOTION("test-promotion"),
        WORKING_DIRECTORY_CLEANING("working-directory-cleaning"),
        JOB_TEST("job-test"),
        UNKNOWN("unknown");

        private final String name;

        JobType(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public enum JobStatus {
        PENDING("PENDING"),
        DONE("DONE"),
        CANCELLED("CANCELLED"),
        FAILED("FAILED");

        private final String name;

        JobStatus(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
