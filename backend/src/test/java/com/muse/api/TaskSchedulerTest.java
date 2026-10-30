package com.muse.api;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.muse.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskSchedulerTest {
    @Test
    void httpsCookieConfigurationAddsSecureWithoutExposingTokenInIdentity() {
        StoreMapper store = mock(StoreMapper.class);
        AppProperties properties = new AppProperties("unused", true, true, 2500, 128);
        SessionService sessions = new SessionService(store, Clock.systemUTC(), properties);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        Identity identity = sessions.open(request, response, false);
        assertThat(response.getHeader("Set-Cookie")).contains("Secure", "HttpOnly", "SameSite=Lax");
        assertThat(identity.id()).hasSize(36);
        verify(store).insertSession(argThat(hash -> hash.matches("[a-f0-9]{64}")), eq(identity.id()), any());
    }

    @Test
    void actualBoundedSingleWorkerRejectsOverflowWithoutCallerRunningTask() throws Exception {
        AppProperties properties = new AppProperties("unused", false, true, 0, 1);
        var executor = new TaskScheduler.ExecutorConfiguration().taskExecutor(properties);
        TaskRunner runner = mock(TaskRunner.class);
        TaskTransitions transitions = mock(TaskTransitions.class);
        TaskScheduler scheduler = new TaskScheduler(executor, runner, transitions);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(runner).run("first");
        try {
            scheduler.created(new TaskService.TaskCreated("first"));
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            scheduler.created(new TaskService.TaskCreated("queued"));
            assertThat(executor.getQueue()).hasSize(1);
            scheduler.created(new TaskService.TaskCreated("rejected"));
            verify(transitions).fail(eq("rejected"), contains("队列"));
            verify(runner, never()).run("rejected");
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        verify(runner).run("queued");
    }

    @Test
    void disabledSimulationRejectsSettingsBeforeInsertAndDefaultDelayIs2500() {
        StoreMapper store = mock(StoreMapper.class);
        ImageStorage storage = mock(ImageStorage.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AppProperties disabled = new AppProperties("unused", false, false, 2500, 128);
        TaskService service = new TaskService(store, storage, disabled, publisher, Clock.systemUTC());
        Rows.TemplateRow template = new Rows.TemplateRow();
        template.id = "demo";
        template.title = "Demo";
        template.coverUrl = "/demo/pet.jpg";
        template.requiredImageCount = 1;
        template.acceptedMimeTypes = "image/jpeg,image/png";
        template.maxFileSizeBytes = 5242880;
        Rows.AssetRow asset = new Rows.AssetRow();
        asset.id = "asset";
        asset.fileName = "image.png";
        asset.mimeType = "image/png";
        asset.sizeBytes = 100;
        when(store.findTemplate("demo")).thenReturn(template);
        when(store.findOwnedAsset("asset", "owner")).thenReturn(asset);
        when(storage.uploadExists("image.png")).thenReturn(true);
        assertThatThrownBy(() -> service.create("owner", new CreateTask("demo", List.of("asset"), new Simulation(null, 0))))
                .isInstanceOf(ApiException.class).hasMessageContaining("未开启");
        verify(store, never()).insertTask(any());
        verifyNoInteractions(publisher);
        Task task = service.create("owner", new CreateTask("demo", List.of("asset"), null));
        assertThat(task.status()).isEqualTo(TaskStatus.QUEUED);
        verify(store).insertTask(argThat(row -> row.stepDelayMs == 2500 && row.failStage == null));
    }
}
