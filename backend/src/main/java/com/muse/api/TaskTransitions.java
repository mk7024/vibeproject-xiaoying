package com.muse.api;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

import static com.muse.api.ApiModels.*;

@Service
public class TaskTransitions {
    private final StoreMapper store;
    private final Clock clock;
    private final ImageStorage storage;

    public TaskTransitions(StoreMapper store, Clock clock, ImageStorage storage) {
        this.store = store;
        this.clock = clock;
        this.storage = storage;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Rows.TaskRow startStage(String id, Stage stage) {
        Rows.TaskRow task = store.lockTask(id);
        if (task == null || task.terminal()) return null;
        var events = store.events(id);
        // A repeated delivery cannot rerun a stage or jump over an unfinished stage.
        if (events.size() != stage.ordinal()
                || events.stream().anyMatch(event -> event.status != EventStatus.SUCCEEDED)) return null;
        task.status = TaskStatus.RUNNING;
        task.currentStage = stage;
        task.updatedAt = clock.instant();
        store.updateTask(task);
        store.startEvent(id, stage, stage.ordinal(), task.updatedAt);
        return task;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeStage(String id, Stage stage) {
        Rows.TaskRow task = store.lockTask(id);
        if (task == null || task.terminal() || task.currentStage != stage) return;
        task.updatedAt = clock.instant();
        store.finishEvent(id, stage, EventStatus.SUCCEEDED, task.updatedAt);
        store.updateTask(task);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean succeed(String id, ImageStorage.StoredImage image) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) storage.deleteResult(image.fileName());
            }
        });
        Rows.TaskRow task = store.lockTask(id);
        if (task == null || task.terminal() || task.currentStage != Stage.FINALIZE) return false;
        task.status = TaskStatus.SUCCEEDED;
        task.updatedAt = clock.instant();
        task.resultFileName = image.fileName();
        task.resultMimeType = image.mimeType();
        task.failureReason = null;
        store.finishEvent(id, Stage.FINALIZE, EventStatus.SUCCEEDED, task.updatedAt);
        store.updateTask(task);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String id, String reason) {
        Rows.TaskRow task = store.lockTask(id);
        if (task == null || task.terminal()) return;
        task.status = TaskStatus.FAILED;
        task.failureReason = reason;
        task.updatedAt = clock.instant();
        if (task.currentStage != null) {
            store.finishEvent(id, task.currentStage, EventStatus.FAILED, task.updatedAt);
        }
        store.updateTask(task);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverInterrupted() {
        var now = clock.instant();
        store.failInterruptedEvents(now);
        return store.failInterruptedTasks(now, "服务已重启，未完成的任务已停止，请重新创建任务");
    }
}
