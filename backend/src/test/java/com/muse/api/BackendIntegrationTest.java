package com.muse.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static com.muse.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StoreMapper store;
    @Autowired TaskService tasks;
    @Autowired TaskRunner runner;
    @Autowired TaskTransitions transitions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired AppProperties properties;
    @MockitoBean(name = "taskExecutor") ThreadPoolExecutor executor;

    record Visitor(String id, Cookie cookie) {}

    @BeforeEach
    void clean() throws Exception {
        reset(executor);
        for (String table : List.of("task_events", "task_assets", "tasks", "assets", "sessions", "accounts", "identities")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (String directory : List.of("uploads", "results")) {
            try (var paths = Files.list(Path.of(properties.storagePath(), directory))) {
                for (Path path : paths.toList()) Files.delete(path);
            }
        }
    }

    @Test
    void publicTemplatesHaveExactSeedAndFilterAndHealthIsReady() throws Exception {
        mvc.perform(get("/api/templates")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].id").value("cloud-pet"))
                .andExpect(jsonPath("$[0].coverUrl").value("/demo/pet.jpg"))
                .andExpect(jsonPath("$[0].maxFileSizeBytes").value(5242880))
                .andExpect(jsonPath("$[0].acceptedMimeTypes[1]").value("image/png"));
        mvc.perform(get("/api/templates").param("category", "人像写真"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/templates").param("category", "' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/templates/seaside-postcard"))
                .andExpect(jsonPath("$.requiredImageCount").value(2));
        mvc.perform(get("/api/templates/absent")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isString());
        mvc.perform(get("/api/categories")).andExpect(status().isOk())
                .andExpect(content().json("[\"萌宠时刻\",\"旅行灵感\",\"人像写真\"]"));
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void sessionReusesIdentityHashesTokenAndFreshCreatesIsolatedIdentity() throws Exception {
        MvcResult opened = mvc.perform(post("/api/session").header("X-Muse-Request", "1")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store")).andReturn();
        String setCookie = opened.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Secure");
        Cookie cookie = MockCookie.parse(setCookie);
        String id = json.readTree(opened.getResponse().getContentAsString()).get("id").asText();
        String storedHash = jdbc.queryForObject("SELECT token_hash FROM sessions", String.class);
        assertThat(storedHash).hasSize(64).isNotEqualTo(cookie.getValue());
        assertThat(cookie.getValue()).hasSize(43);
        mvc.perform(post("/api/session").header("X-Muse-Request", "1").cookie(cookie)).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/session").header("X-Muse-Request", "1").cookie(cookie).param("fresh", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(id)));
        mvc.perform(get("/api/tasks").cookie(new Cookie("muse_session", "invalid")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").isString());
        jdbc.update("UPDATE sessions SET expires_at = TIMESTAMP '2000-01-01 00:00:00'");
        mvc.perform(get("/api/tasks").cookie(cookie)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/session").header("X-Muse-Request", "1").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(id)));
    }

    @Test
    void everyPrivateRouteRequiresIdentity() throws Exception {
        for (String path : List.of("/api/tasks", "/api/tasks/x", "/api/tasks/x/result", "/api/assets/x/content")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "private, no-store"));
        }
        mvc.perform(post("/api/tasks").header("X-Muse-Request", "1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/assets").file(imageFile("png", Color.RED))
                        .param("templateId", "cloud-pet").header("X-Muse-Request", "1"))
                .andExpect(status().isUnauthorized());
        for (String path : List.of("/api/account/register", "/api/account/login",
                "/api/account/logout", "/api/account/password")) {
            mvc.perform(post(path).header("X-Muse-Request", "1")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "TRACE"})
    void everyApiMutationRequiresRequestHeaderWithoutFetchMetadata(String method) throws Exception {
        for (String path : List.of("/api/session", "/api/tasks", "/api/assets", "/api/unknown",
                "/api/account/register", "/api/account/login", "/api/account/logout", "/api/account/password")) {
            assertMutationRejected(request(HttpMethod.valueOf(method), path).queryParam("fresh", "true"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "true", "1, 1"})
    void invalidRequestHeaderIsRejectedBeforeCreatingIdentity(String value) throws Exception {
        assertMutationRejected(post("/api/session?fresh=true").header("X-Muse-Request", value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cross-site", "same-site"})
    void foreignFetchMetadataRejectsEvenValidRequestHeader(String fetchSite) throws Exception {
        assertMutationRejected(post("/api/session?fresh=true").header("X-Muse-Request", "1")
                .header("Sec-Fetch-Site", fetchSite));
        Visitor visitor = visitor();
        assertMutationRejected(post("/api/session?fresh=true").cookie(visitor.cookie())
                .header("X-Muse-Request", "1").header("Sec-Fetch-Site", fetchSite));
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-origin", "none"})
    void allowedFetchMetadataDoesNotReplaceRequiredRequestHeader(String fetchSite) throws Exception {
        assertMutationRejected(post("/api/session").header("Sec-Fetch-Site", fetchSite));
        mvc.perform(post("/api/session").header("X-Muse-Request", "1").header("Sec-Fetch-Site", fetchSite))
                .andExpect(status().isOk()).andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void formWithoutFetchMetadataCannotForceFreshCookieOrIdentity() throws Exception {
        Visitor visitor = visitor();
        assertMutationRejected(post("/api/session?fresh=true").cookie(visitor.cookie())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED));
        mvc.perform(post("/api/session").cookie(visitor.cookie()).header("X-Muse-Request", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(visitor.id()))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void safeMethodsDoNotRequireRequestHeader(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), "/api/health").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg"})
    void successPersistsAllStagesCopiesFirstUploadAndPreservesMime(String format) throws Exception {
        Visitor visitor = visitor();
        MockMultipartFile first = imageFile(format, Color.RED);
        Asset asset = upload(visitor, "seaside-postcard", first);
        Asset second = upload(visitor, "seaside-postcard", imageFile(format, Color.BLUE));
        Task queued = create(visitor, "seaside-postcard", List.of(asset.id(), second.id()), null, 0);
        assertThat(queued.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(queued.events()).isEmpty();
        assertThat(queued.createdAt().toString()).endsWith("Z");
        for (int i = 0; i < 3; i++) assertThat(getTask(visitor, queued.id())).isEqualTo(queued);
        mvc.perform(get("/api/tasks/" + queued.id() + "/result").cookie(visitor.cookie()))
                .andExpect(status().isNotFound());
        ArgumentCaptor<Runnable> submitted = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(submitted.capture());
        submitted.getValue().run();
        Task done = getTask(visitor, queued.id());
        assertThat(done.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(done.events()).extracting(TaskEvent::stage).containsExactly(Stage.values());
        assertThat(done.events()).allSatisfy(event -> {
            assertThat(event.status()).isEqualTo(EventStatus.SUCCEEDED);
            assertThat(event.startedAt()).isNotNull();
            assertThat(event.finishedAt()).isAfterOrEqualTo(event.startedAt());
        });
        assertThat(done.resultUrl()).isEqualTo("/api/tasks/" + done.id() + "/result");
        Rows.TaskRow row = store.findOwnedTask(done.id(), visitor.id());
        assertThat(row.resultFileName).isNotEqualTo(store.findOwnedAsset(asset.id(), visitor.id()).fileName);
        mvc.perform(get(done.resultUrl()).cookie(visitor.cookie())).andExpect(status().isOk())
                .andExpect(content().contentType(first.getContentType()))
                .andExpect(content().bytes(first.getBytes()))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        runner.run(done.id());
        transitions.fail(done.id(), "late failure");
        for (int i = 0; i < 3; i++) assertThat(getTask(visitor, done.id())).isEqualTo(done);
        assertThat(tasks.list(visitor.id())).containsExactly(done);
        mvc.perform(post("/api/session").header("X-Muse-Request", "1").cookie(visitor.cookie())).andExpect(jsonPath("$.id").value(visitor.id()));
        mvc.perform(get("/api/tasks").cookie(visitor.cookie())).andExpect(jsonPath("$[0].id").value(done.id()));
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void eachSimulatedFailureStopsImmediatelyAndTerminalQueriesStayReadOnly(Stage failure) throws Exception {
        Visitor visitor = visitor();
        Asset asset = upload(visitor, "cloud-pet", imageFile("png", Color.GREEN));
        Task task = create(visitor, "cloud-pet", List.of(asset.id()), failure, 0);
        runner.run(task.id());
        Task failed = getTask(visitor, task.id());
        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.currentStage()).isEqualTo(failure);
        assertThat(failed.failureReason()).contains(failure.name());
        assertThat(failed.resultUrl()).isNull();
        assertThat(failed.events()).hasSize(failure.ordinal() + 1);
        assertThat(failed.events()).extracting(TaskEvent::stage)
                .containsExactly(Arrays.copyOf(Stage.values(), failure.ordinal() + 1));
        for (int i = 0; i < failed.events().size(); i++) {
            TaskEvent event = failed.events().get(i);
            assertThat(event.status()).isEqualTo(i == failure.ordinal() ? EventStatus.FAILED : EventStatus.SUCCEEDED);
            assertThat(event.finishedAt()).isAfterOrEqualTo(event.startedAt());
        }
        runner.run(task.id());
        transitions.fail(task.id(), "late failure");
        transitions.completeStage(task.id(), failure);
        for (int i = 0; i < 3; i++) {
            assertThat(getTask(visitor, task.id())).isEqualTo(failed);
            assertThat(tasks.list(visitor.id())).containsExactly(failed);
        }
        assertThat(resultFileCount()).isZero();
        mvc.perform(get("/api/tasks/" + task.id() + "/result").cookie(visitor.cookie())).andExpect(status().isNotFound());
    }

    @Test
    void asynchronousWorkerPersistsRunningBeforeEveryDelayAndDoesNotNeedBrowserPolling() throws Exception {
        Visitor visitor = visitor();
        Asset asset = upload(visitor, "cloud-pet", imageFile("png", Color.ORANGE));
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Void>> future = new AtomicReference<>();
        doAnswer(invocation -> {
            Runnable command = invocation.getArgument(0);
            future.set(CompletableFuture.runAsync(() -> {
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("HTTP did not return");
                    command.run();
                } catch (InterruptedException exception) {
                    throw new AssertionError(exception);
                }
            }));
            return null;
        }).when(executor).execute(any(Runnable.class));
        Task queued = create(visitor, "cloud-pet", List.of(asset.id()), null, 300);
        assertThat(store.findOwnedTask(queued.id(), visitor.id()).status).isEqualTo(TaskStatus.QUEUED);
        start.countDown();
        for (Stage stage : Stage.values()) {
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10)).untilAsserted(() -> {
                Task running = tasks.get(visitor.id(), queued.id());
                assertThat(running.currentStage()).isEqualTo(stage);
                assertThat(running.status()).isEqualTo(TaskStatus.RUNNING);
                TaskEvent event = running.events().getLast();
                assertThat(event.stage()).isEqualTo(stage);
                assertThat(event.status()).isEqualTo(EventStatus.RUNNING);
                assertThat(event.finishedAt()).isNull();
            });
        }
        // No HTTP request triggers completion: wait for the independent worker itself.
        future.get().get(5, TimeUnit.SECONDS);
        assertThat(getTask(visitor, queued.id()).status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void missingSourceDuringProcessingFailsSafelyWithoutPublishingResult() throws Exception {
        Visitor visitor = visitor();
        Asset asset = upload(visitor, "cloud-pet", imageFile("png", Color.RED));
        Task task = create(visitor, "cloud-pet", List.of(asset.id()), null, 0);
        Rows.AssetRow row = store.findOwnedAsset(asset.id(), visitor.id());
        Files.delete(Path.of(properties.storagePath(), "uploads", row.fileName));
        runner.run(task.id());
        Task failed = getTask(visitor, task.id());
        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.currentStage()).isEqualTo(Stage.FINALIZE);
        assertThat(failed.events().getLast().status()).isEqualTo(EventStatus.FAILED);
        assertThat(failed.failureReason()).doesNotContain(properties.storagePath(), "Exception");
        assertThat(failed.resultUrl()).isNull();
        assertThat(resultFileCount()).isZero();
    }

    @Test
    void executorRejectionIsPersistedUsingNewTransactionAfterCommit() throws Exception {
        Visitor visitor = visitor();
        Asset asset = upload(visitor, "cloud-pet", imageFile("png", Color.RED));
        doThrow(new RejectedExecutionException("internal details must not leak"))
                .when(executor).execute(any(Runnable.class));
        Task accepted = create(visitor, "cloud-pet", List.of(asset.id()), null, 0);
        Task rejected = getTask(visitor, accepted.id());
        assertThat(rejected.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(rejected.currentStage()).isNull();
        assertThat(rejected.events()).isEmpty();
        assertThat(rejected.failureReason()).contains("队列").doesNotContain("internal details");
    }

    @Test
    void rolledBackCreationNeverSchedulesOrLeavesTaskRows() throws Exception {
        Visitor visitor = visitor();
        Asset asset = upload(visitor, "cloud-pet", imageFile("png", Color.RED));
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            tasks.create(visitor.id(), new CreateTask("cloud-pet", List.of(asset.id()), new Simulation(null, 0)));
            verifyNoInteractions(executor);
            transaction.setRollbackOnly();
        });
        verifyNoInteractions(executor);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assets", Integer.class)).isZero();
    }

    @Test
    void uploadRollbackRemovesPrivateFile() throws Exception {
        Visitor visitor = visitor();
        MockMultipartFile file = imageFile("png", Color.RED);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            try {
                upload(visitor, "cloud-pet", file);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            transaction.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assets", Integer.class)).isZero();
        try (var files = Files.list(Path.of(properties.storagePath(), "uploads"))) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void ownershipHidesUploadsTasksResultsAndHistory() throws Exception {
        Visitor owner = visitor();
        Visitor other = visitor();
        Asset asset = upload(owner, "cloud-pet", imageFile("png", Color.RED));
        Task task = create(owner, "cloud-pet", List.of(asset.id()), null, 0);
        runner.run(task.id());
        for (String path : List.of(asset.url(), "/api/tasks/" + task.id(), "/api/tasks/" + task.id() + "/result")) {
            mvc.perform(get(path).cookie(other.cookie())).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/tasks").cookie(other.cookie())).andExpect(content().json("[]"));
        postTask(other, new CreateTask("cloud-pet", List.of(asset.id()), null), 404);
        mvc.perform(get(asset.url()).cookie(owner.cookie())).andExpect(status().isOk());
        mvc.perform(get("/storage/uploads/" + store.findOwnedAsset(asset.id(), owner.id()).fileName))
                .andExpect(status().isNotFound());
    }

    @Test
    void taskValidationRejectsCountDuplicatesMissingAssetsMimeAndSizeBeforeInsert() throws Exception {
        Visitor visitor = visitor();
        Asset first = upload(visitor, "cloud-pet", imageFile("png", Color.RED));
        Asset second = upload(visitor, "cloud-pet", imageFile("png", Color.BLUE));
        postTask(visitor, new CreateTask("cloud-pet", List.of(), null), 400);
        postTask(visitor, new CreateTask("cloud-pet", List.of(first.id(), second.id()), null), 400);
        postTask(visitor, new CreateTask("seaside-postcard", List.of(first.id(), first.id()), null), 400);
        postTask(visitor, new CreateTask("cloud-pet", List.of("missing"), null), 404);
        postTask(visitor, new CreateTask("missing", List.of(first.id()), null), 404);
        jdbc.update("UPDATE assets SET mime_type = ? WHERE id = ?", "text/html", first.id());
        postTask(visitor, new CreateTask("cloud-pet", List.of(first.id()), null), 400);
        jdbc.update("UPDATE assets SET mime_type = ?, size_bytes = ? WHERE id = ?", "image/png", 5242881L, first.id());
        postTask(visitor, new CreateTask("cloud-pet", List.of(first.id()), null), 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isZero();
        verifyNoInteractions(executor);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 30001})
    void invalidDelayIsRejected(int delay) throws Exception {
        Visitor visitor = visitor();
        Asset asset = upload(visitor, "cloud-pet", imageFile("png", Color.RED));
        postTask(visitor, new CreateTask("cloud-pet", List.of(asset.id()), new Simulation(null, delay)), 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isZero();
    }

    @Test
    void malformedTaskJsonUnknownStageAndNullDelayUseSafeErrors() throws Exception {
        Visitor visitor = visitor();
        for (String body : List.of("{", "{\"templateId\":\"cloud-pet\",\"assetIds\":[null]}",
                "{\"templateId\":\"cloud-pet\",\"assetIds\":[\"x\"],\"simulation\":{\"failStage\":\"OTHER\",\"stepDelayMs\":0}}",
                "{\"templateId\":\"cloud-pet\",\"assetIds\":[\"x\"],\"simulation\":{\"stepDelayMs\":null}}")) {
            mvc.perform(post("/api/tasks").header("X-Muse-Request", "1").cookie(visitor.cookie()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.trace").doesNotExist());
        }
    }

    @Test
    void uploadChecksActualSignatureMimeDecodeSizeAndTemplate() throws Exception {
        Visitor visitor = visitor();
        List<MockMultipartFile> invalid = List.of(
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0]),
                new MockMultipartFile("file", "fake.png", "image/png", "<html>not an image</html>".getBytes()),
                new MockMultipartFile("file", "wrong.png", "image/jpeg", imageFile("png", Color.RED).getBytes()),
                new MockMultipartFile("file", "bad.png", "image/png", new byte[]{(byte)137,80,78,71,13,10,26,10}),
                new MockMultipartFile("file", "file.gif", "image/gif", imageFile("png", Color.RED).getBytes()));
        for (MockMultipartFile file : invalid) {
            mvc.perform(multipart("/api/assets").file(file).param("templateId", "cloud-pet").header("X-Muse-Request", "1").cookie(visitor.cookie()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        }
        mvc.perform(multipart("/api/assets").file(new MockMultipartFile("file", "big.png", "image/png", new byte[5242881]))
                        .param("templateId", "cloud-pet").header("X-Muse-Request", "1").cookie(visitor.cookie()))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/assets").file(imageFile("png", Color.RED)).param("templateId", "absent").header("X-Muse-Request", "1")
                        .cookie(visitor.cookie())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assets", Integer.class)).isZero();
    }

    @Test
    void decompressionBombDimensionsRejectedBeforeDecoding() throws Exception {
        Visitor visitor = visitor();
        byte[] png = imageFile("png", Color.RED).getBytes();
        ByteBuffer.wrap(png).putInt(16, 100_000).putInt(20, 100_000);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        mvc.perform(multipart("/api/assets").file(new MockMultipartFile("file", "bomb.png", "image/png", png))
                        .param("templateId", "cloud-pet").header("X-Muse-Request", "1").cookie(visitor.cookie()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("尺寸")));
    }

    @Test
    void startupRecoveryStopsInterruptedWorkPreservesTerminalHistoryAndSeedIsIdempotent() throws Exception {
        Visitor visitor = visitor();
        Asset asset = upload(visitor, "cloud-pet", imageFile("png", Color.RED));
        Task done = create(visitor, "cloud-pet", List.of(asset.id()), null, 0);
        runner.run(done.id());
        Task snapshot = getTask(visitor, done.id());
        Task running = create(visitor, "cloud-pet", List.of(asset.id()), null, 0);
        transitions.startStage(running.id(), Stage.CHECK);
        Task queued = create(visitor, "cloud-pet", List.of(asset.id()), null, 0);
        assertThat(transitions.recoverInterrupted()).isEqualTo(2);
        assertThat(transitions.recoverInterrupted()).isZero();
        Task interrupted = getTask(visitor, running.id());
        assertThat(interrupted.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(interrupted.events().getFirst().status()).isEqualTo(EventStatus.FAILED);
        assertThat(interrupted.failureReason()).contains("重启");
        assertThat(getTask(visitor, queued.id()).currentStage()).isNull();
        assertThat(getTask(visitor, done.id())).isEqualTo(snapshot);
        assertThat(tasks.list(visitor.id())).extracting(Task::id).containsExactly(queued.id(), running.id(), done.id());
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("data.sql"))
                .execute(dataSource);
        assertThat(store.listTemplates(null)).hasSize(6);
        assertThat(tasks.list(visitor.id())).hasSize(3);
    }

    @Test
    void registrationClaimsAnonymousWorkspaceAndRotatesSession() throws Exception {
        Visitor anonymous = visitor();
        Asset asset = upload(anonymous, "cloud-pet", imageFile("png", Color.RED));
        Task task = create(anonymous, "cloud-pet", List.of(asset.id()), null, 0);
        runner.run(task.id());
        Task done = getTask(anonymous, task.id());

        MvcResult result = account("register", anonymous,
                new AccountRegister("User@Example.com", "correct-horse"), 200);
        Identity identity = identity(result);
        assertThat(identity.id()).isEqualTo(anonymous.id());
        assertThat(identity.email()).isEqualTo("user@example.com");
        Cookie rotatedCookie = MockCookie.parse(result.getResponse().getHeader("Set-Cookie"));
        assertThat(rotatedCookie.getValue()).isNotEqualTo(anonymous.cookie().getValue());
        Visitor account = new Visitor(identity.id(), rotatedCookie);

        mvc.perform(get("/api/tasks").cookie(anonymous.cookie())).andExpect(status().isUnauthorized());
        assertThat(getTask(account, done.id())).isEqualTo(done);
        mvc.perform(get(asset.url()).cookie(account.cookie())).andExpect(status().isOk());
        assertThat(sessionIdentity(account).email()).isEqualTo("user@example.com");

        String hash = jdbc.queryForObject("SELECT password_hash FROM accounts WHERE identity_id = ?",
                String.class, account.id());
        assertThat(hash).startsWith("$2").doesNotContain("correct-horse");
        account("register", account, new AccountRegister("other@example.com", "password123"), 409);
    }

    @Test
    void loginMergesAnonymousWorkspaceAndLogoutPreservesAccountHistory() throws Exception {
        Visitor owner = visitor();
        Asset ownerAsset = upload(owner, "cloud-pet", imageFile("png", Color.RED));
        Task ownerTask = create(owner, "cloud-pet", List.of(ownerAsset.id()), null, 0);
        runner.run(ownerTask.id());
        Visitor account = register(owner, "merge@example.com", "password123");
        assertThat(account.id()).isEqualTo(owner.id());

        Visitor guest = visitor();
        Asset guestAsset = upload(guest, "cloud-pet", imageFile("png", Color.BLUE));
        Task guestTask = create(guest, "cloud-pet", List.of(guestAsset.id()), null, 0);
        runner.run(guestTask.id());

        Visitor loggedIn = login(guest, "MERGE@example.com", "password123");
        assertThat(loggedIn.id()).isEqualTo(account.id());
        assertThat(getTask(loggedIn, ownerTask.id()).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(getTask(loggedIn, guestTask.id()).status()).isEqualTo(TaskStatus.SUCCEEDED);
        mvc.perform(get(guestAsset.url()).cookie(loggedIn.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/tasks").cookie(guest.cookie())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identities WHERE id = ?",
                Integer.class, guest.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT owner_id FROM tasks WHERE id = ?",
                String.class, guestTask.id())).isEqualTo(account.id());

        Visitor anonymous = logout(loggedIn);
        assertThat(anonymous.id()).isNotEqualTo(account.id());
        assertThat(sessionIdentity(anonymous).email()).isNull();
        mvc.perform(get("/api/tasks").cookie(anonymous.cookie())).andExpect(content().json("[]"));
        mvc.perform(get("/api/tasks").cookie(loggedIn.cookie())).andExpect(status().isUnauthorized());

        Visitor again = login(anonymous, "merge@example.com", "password123");
        assertThat(getTask(again, ownerTask.id()).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(getTask(again, guestTask.id()).status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void loginRejectsWrongCredentialsAndAccountSwitching() throws Exception {
        Visitor first = visitor();
        Asset firstAsset = upload(first, "cloud-pet", imageFile("png", Color.RED));
        Task firstTask = create(first, "cloud-pet", List.of(firstAsset.id()), null, 0);
        runner.run(firstTask.id());
        Visitor firstAccount = register(first, "first@example.com", "password123");

        Visitor second = visitor();
        Asset secondAsset = upload(second, "cloud-pet", imageFile("png", Color.BLUE));
        Task secondTask = create(second, "cloud-pet", List.of(secondAsset.id()), null, 0);
        runner.run(secondTask.id());
        Visitor secondAccount = register(second, "second@example.com", "password123");

        account("login", firstAccount, new AccountLogin("first@example.com", "wrong-password"), 401);
        account("login", visitor(), new AccountLogin("missing@example.com", "password123"), 401);
        account("login", firstAccount, new AccountLogin("second@example.com", "password123"), 409);
        mvc.perform(get("/api/tasks").cookie(firstAccount.cookie())).andExpect(status().isOk());
        mvc.perform(get(secondAsset.url()).cookie(firstAccount.cookie())).andExpect(status().isNotFound());

        Visitor secondLogin = login(visitor(), "second@example.com", "password123");
        assertThat(secondLogin.id()).isEqualTo(secondAccount.id());
        mvc.perform(get("/api/tasks").cookie(secondLogin.cookie()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(secondTask.id()));
        mvc.perform(get("/api/tasks/" + firstTask.id()).cookie(secondLogin.cookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndRotatesSession() throws Exception {
        Visitor account = register(visitor(), "password@example.com", "old-password");
        Visitor secondDevice = login(visitor(), "password@example.com", "old-password");
        account("password", account, new PasswordChange("wrong-password", "new-password"), 400);

        MvcResult result = account("password", account,
                new PasswordChange("old-password", "new-password"), 200);
        Identity identity = identity(result);
        assertThat(identity.id()).isEqualTo(account.id());
        Visitor rotated = new Visitor(identity.id(),
                MockCookie.parse(result.getResponse().getHeader("Set-Cookie")));
        mvc.perform(get("/api/tasks").cookie(account.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tasks").cookie(secondDevice.cookie())).andExpect(status().isUnauthorized());
        account("login", visitor(), new AccountLogin("password@example.com", "old-password"), 401);

        Visitor relogin = login(visitor(), "PASSWORD@example.com", "new-password");
        assertThat(relogin.id()).isEqualTo(account.id());
        account("password", visitor(), new PasswordChange("new-password", "another-password"), 401);
    }

    @Test
    void accountInputValidationAndEmailNormalization() throws Exception {
        Visitor registered = register(visitor(), "unique@example.com", "password123");
        account("register", visitor(), new AccountRegister("UNIQUE@example.com", "password123"), 409);
        account("register", visitor(), new AccountRegister("not-an-email", "password123"), 400);
        account("register", visitor(), new AccountRegister("short@example.com", "short"), 400);
        account("register", registered, new AccountRegister("another@example.com", "password123"), 409);

        Visitor logged = login(visitor(), "UnIqUe@Example.com", "password123");
        assertThat(logged.id()).isEqualTo(registered.id());
        account("login", visitor(), new AccountLogin("unique@example.com", "wrong-password"), 401);
    }

    private MvcResult account(String action, Visitor visitor, Object body, int expected) throws Exception {
        return mvc.perform(post("/api/account/" + action)
                        .header("X-Muse-Request", "1").cookie(visitor.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private Visitor register(Visitor visitor, String email, String password) throws Exception {
        return visitor(account("register", visitor, new AccountRegister(email, password), 200));
    }

    private Visitor login(Visitor visitor, String email, String password) throws Exception {
        return visitor(account("login", visitor, new AccountLogin(email, password), 200));
    }

    private Visitor logout(Visitor visitor) throws Exception {
        MvcResult result = mvc.perform(post("/api/account/logout")
                        .header("X-Muse-Request", "1").cookie(visitor.cookie()))
                .andExpect(status().isOk()).andReturn();
        return visitor(result);
    }

    private Identity sessionIdentity(Visitor visitor) throws Exception {
        MvcResult result = mvc.perform(post("/api/session")
                        .header("X-Muse-Request", "1").cookie(visitor.cookie()))
                .andExpect(status().isOk()).andReturn();
        return identity(result);
    }

    private Identity identity(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), Identity.class);
    }

    private Visitor visitor(MvcResult result) throws Exception {
        Identity identity = identity(result);
        return new Visitor(identity.id(), MockCookie.parse(result.getResponse().getHeader("Set-Cookie")));
    }

    private void assertMutationRejected(MockHttpServletRequestBuilder request) throws Exception {
        int identities = jdbc.queryForObject("SELECT COUNT(*) FROM identities", Integer.class);
        int sessions = jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class);
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"message\":\"不允许的请求\"}"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identities", Integer.class)).isEqualTo(identities);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class)).isEqualTo(sessions);
    }

    private Visitor visitor() throws Exception {
        MvcResult result = mvc.perform(post("/api/session").header("X-Muse-Request", "1")).andExpect(status().isOk()).andReturn();
        Identity identity = json.readValue(result.getResponse().getContentAsByteArray(), Identity.class);
        return new Visitor(identity.id(), MockCookie.parse(result.getResponse().getHeader("Set-Cookie")));
    }

    private Asset upload(Visitor visitor, String template, MockMultipartFile file) throws Exception {
        MvcResult response = mvc.perform(multipart("/api/assets").file(file).param("templateId", template).header("X-Muse-Request", "1")
                .cookie(visitor.cookie())).andExpect(status().isOk()).andReturn();
        return json.readValue(response.getResponse().getContentAsByteArray(), Asset.class);
    }

    private Task create(Visitor visitor, String template, List<String> assets, Stage failStage, int delay) throws Exception {
        MvcResult result = postTask(visitor, new CreateTask(template, assets, new Simulation(failStage, delay)), 202);
        return json.readValue(result.getResponse().getContentAsByteArray(), Task.class);
    }

    private MvcResult postTask(Visitor visitor, CreateTask input, int expected) throws Exception {
        return mvc.perform(post("/api/tasks").header("X-Muse-Request", "1").cookie(visitor.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(input))).andExpect(status().is(expected)).andReturn();
    }

    private Task getTask(Visitor visitor, String id) throws Exception {
        return json.readValue(mvc.perform(get("/api/tasks/" + id).cookie(visitor.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), Task.class);
    }

    private long resultFileCount() throws Exception {
        try (var files = Files.list(Path.of(properties.storagePath(), "results"))) { return files.count(); }
    }

    private MockMultipartFile imageFile(String format, Color color) throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) image.setRGB(x, y, color.getRGB());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        image.flush();
        return new MockMultipartFile("file", "photo." + format, "image/" + format, bytes.toByteArray());
    }
}
