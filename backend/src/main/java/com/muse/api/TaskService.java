package com.muse.api;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.muse.api.ApiModels.*;

@Service
public class TaskService {
    public record TaskCreated(String id) {}
    private final StoreMapper store;
    private final ImageStorage storage;
    private final AppProperties properties;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public TaskService(StoreMapper store, ImageStorage storage, AppProperties properties,
                       ApplicationEventPublisher publisher, Clock clock) {
        this.store = store;
        this.storage = storage;
        this.properties = properties;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public Task create(String ownerId, CreateTask request) {
        Rows.TemplateRow template = store.findTemplate(request.templateId());
        if (template == null) throw ApiException.notFound("模板不存在");
        List<String> ids = request.assetIds();
        if (ids == null || ids.size() != template.requiredImageCount) {
            throw ApiException.badRequest("此模板需要恰好 " + template.requiredImageCount + " 张图片");
        }
        if (new HashSet<>(ids).size() != ids.size()) throw ApiException.badRequest("不能重复使用同一张图片");
        for (String id : ids) {
            Rows.AssetRow asset = store.findOwnedAsset(id, ownerId);
            if (asset == null) throw ApiException.notFound("图片不存在或不属于当前访客");
            if (!template.mimeTypes().contains(asset.mimeType) || asset.sizeBytes > template.maxFileSizeBytes
                    || asset.sizeBytes <= 0) {
                throw ApiException.badRequest("上传的图片不符合此模板的格式或大小限制");
            }
            if (!storage.uploadExists(asset.fileName)) throw ApiException.notFound("上传的图片文件已不可用，请重新上传");
        }
        Simulation simulation = request.simulation();
        if (simulation != null && !properties.simulationEnabled()) {
            throw ApiException.badRequest("当前服务未开启模拟设置");
        }
        int delay = simulation == null ? properties.defaultStepDelayMs()
                : simulation.stepDelayMs() == null ? -1 : simulation.stepDelayMs();
        if (delay < 0 || delay > 30000) throw ApiException.badRequest("每步延迟必须在 0–30000 毫秒之间");
        var task = new Rows.TaskRow();
        task.id = UUID.randomUUID().toString();
        task.ownerId = ownerId;
        task.templateId = template.id;
        task.templateTitle = template.title;
        task.templateCoverUrl = template.coverUrl;
        task.status = TaskStatus.QUEUED;
        task.createdAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        task.updatedAt = task.createdAt;
        task.failStage = simulation == null ? null : simulation.failStage();
        task.stepDelayMs = delay;
        store.insertTask(task);
        for (int index = 0; index < ids.size(); index++) store.linkAsset(task.id, ids.get(index), index);
        // The listener runs only after this externally invoked transaction has committed.
        publisher.publishEvent(new TaskCreated(task.id));
        return task.view(List.of());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Task> list(String ownerId) {
        return store.listTasks(ownerId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Task get(String ownerId, String id) { return view(owned(ownerId, id)); }

    public Rows.TaskRow owned(String ownerId, String id) {
        Rows.TaskRow task = store.findOwnedTask(id, ownerId);
        if (task == null) throw ApiException.notFound("任务不存在或不属于当前访客");
        return task;
    }

    private Task view(Rows.TaskRow task) {
        return task.view(store.events(task.id).stream().map(Rows.EventRow::view).toList());
    }
}
