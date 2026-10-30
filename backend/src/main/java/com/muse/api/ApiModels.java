package com.muse.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ApiModels {
    private ApiModels() {}

    public enum Stage { CHECK, GENERATE, FINALIZE }
    public enum TaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }
    public enum EventStatus { RUNNING, SUCCEEDED, FAILED }

    public record Identity(String id, String displayName, String email) {}

    public record AccountRegister(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(min = 8, max = 72) String password) {}

    public record AccountLogin(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 72) String password) {}

    public record PasswordChange(@NotBlank @Size(max = 72) String currentPassword,
                                 @NotBlank @Size(min = 8, max = 72) String newPassword) {}

    public record Template(String id, String title, String category, String coverUrl,
                           String description, int requiredImageCount, List<String> acceptedMimeTypes,
                           long maxFileSizeBytes, String tag) {}

    public record Asset(String id, String url, String originalName, String mimeType, long sizeBytes) {}

    public record TaskEvent(Stage stage, EventStatus status, Instant startedAt, Instant finishedAt) {}

    public record Task(String id, String templateId, String templateTitle, String templateCoverUrl,
                       TaskStatus status, Stage currentStage, Instant createdAt, Instant updatedAt,
                       String resultUrl, String failureReason, List<TaskEvent> events) {}

    public record Simulation(Stage failStage, @NotNull @Min(0) @Max(30000) Integer stepDelayMs) {}

    public record CreateTask(@NotBlank String templateId,
                             @NotNull @Size(min = 1, max = 10) List<@NotBlank String> assetIds,
                             @Valid Simulation simulation) {}

    public record Error(String message) {}
}
