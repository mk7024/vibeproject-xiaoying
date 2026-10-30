package com.muse.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.muse.api.ApiModels.*;

final class Rows {
    private Rows() {}

    public static class IdentityRow {
        public String id;
        public String displayName;
        public Instant createdAt;

        Identity view(String email) { return new Identity(id, displayName, email); }
    }

    public static class AccountRow {
        public String identityId;
        public String email;
        public String passwordHash;
        public Instant createdAt;
        public Instant updatedAt;
    }

    public static class TemplateRow {
        public String id;
        public String title;
        public String category;
        public String coverUrl;
        public String description;
        public int requiredImageCount;
        public String acceptedMimeTypes;
        public long maxFileSizeBytes;
        public String tag;
        public int sortOrder;

        List<String> mimeTypes() { return Arrays.asList(acceptedMimeTypes.split(",")); }

        Template view() {
            return new Template(id, title, category, coverUrl, description, requiredImageCount,
                    mimeTypes(), maxFileSizeBytes, tag);
        }
    }

    public static class AssetRow {
        public String id;
        public String ownerId;
        public String templateId;
        public String originalName;
        public String mimeType;
        public long sizeBytes;
        public String fileName;
        public Instant createdAt;

        Asset view() {
            return new Asset(id, "/api/assets/" + id + "/content", originalName, mimeType, sizeBytes);
        }
    }

    public static class TaskRow {
        public String id;
        public String ownerId;
        public String templateId;
        public String templateTitle;
        public String templateCoverUrl;
        public TaskStatus status;
        public Stage currentStage;
        public Instant createdAt;
        public Instant updatedAt;
        public String resultFileName;
        public String resultMimeType;
        public String failureReason;
        public Stage failStage;
        public int stepDelayMs;

        boolean terminal() { return status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED; }

        Task view(List<TaskEvent> events) {
            return new Task(id, templateId, templateTitle, templateCoverUrl, status, currentStage,
                    createdAt, updatedAt, status == TaskStatus.SUCCEEDED ? "/api/tasks/" + id + "/result" : null,
                    failureReason, events);
        }
    }

    public static class EventRow {
        public String taskId;
        public Stage stage;
        public int stageOrder;
        public EventStatus status;
        public Instant startedAt;
        public Instant finishedAt;

        TaskEvent view() { return new TaskEvent(stage, status, startedAt, finishedAt); }
    }
}
