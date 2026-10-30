package com.muse.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static com.muse.api.ApiModels.Stage;

@Component
public class TaskRunner {
    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);
    private final TaskTransitions transitions;
    private final StoreMapper store;
    private final ImageStorage storage;

    public TaskRunner(TaskTransitions transitions, StoreMapper store, ImageStorage storage) {
        this.transitions = transitions;
        this.store = store;
        this.storage = storage;
    }

    public void run(String id) {
        for (Stage stage : Stage.values()) {
            try {
                Rows.TaskRow task = transitions.startStage(id, stage);
                if (task == null) return;
                // No DB transaction is held while waiting or copying image bytes.
                Thread.sleep(task.stepDelayMs);
                if (stage == task.failStage) {
                    transitions.fail(id, "模拟失败：" + stage.name() + " 阶段未完成，请调整设置后重试");
                    return;
                }
                if (stage == Stage.FINALIZE) {
                    ImageStorage.StoredImage result = storage.copyResult(store.firstTaskAsset(id));
                    try {
                        if (!transitions.succeed(id, result)) storage.deleteResult(result.fileName());
                    } catch (RuntimeException exception) {
                        storage.deleteResult(result.fileName());
                        throw exception;
                    }
                } else {
                    transitions.completeStage(id, stage);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failSafely(id, "服务正在停止，任务已中断，请重新创建任务");
                return;
            } catch (Exception exception) {
                log.error("Task {} failed in stage {}", id, stage, exception);
                failSafely(id, "图片处理失败，请检查上传文件或稍后重试");
                return;
            }
        }
    }

    private void failSafely(String id, String reason) {
        try {
            transitions.fail(id, reason);
        } catch (RuntimeException exception) {
            log.error("Could not persist failure for task {}; startup recovery will stop it", id, exception);
        }
    }
}
