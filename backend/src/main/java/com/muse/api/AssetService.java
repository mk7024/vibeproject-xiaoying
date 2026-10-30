package com.muse.api;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.util.UUID;

@Service
public class AssetService {
    private final StoreMapper store;
    private final ImageStorage storage;
    private final Clock clock;

    public AssetService(StoreMapper store, ImageStorage storage, Clock clock) {
        this.store = store;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public ApiModels.Asset upload(String ownerId, String templateId, MultipartFile file) {
        Rows.TemplateRow template = store.findTemplate(templateId);
        if (template == null) throw ApiException.notFound("模板不存在");
        ImageStorage.StoredImage image = storage.save(file, template);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) storage.deleteUpload(image.fileName());
            }
        });
        var asset = new Rows.AssetRow();
        asset.id = UUID.randomUUID().toString();
        asset.ownerId = ownerId;
        asset.templateId = templateId;
        asset.originalName = safeOriginalName(file.getOriginalFilename());
        asset.mimeType = image.mimeType();
        asset.sizeBytes = image.sizeBytes();
        asset.fileName = image.fileName();
        asset.createdAt = clock.instant();
        store.insertAsset(asset);
        return asset.view();
    }

    public Rows.AssetRow owned(String ownerId, String id) {
        Rows.AssetRow asset = store.findOwnedAsset(id, ownerId);
        if (asset == null) throw ApiException.notFound("图片不存在或不属于当前访客");
        return asset;
    }

    private String safeOriginalName(String original) {
        if (original == null || original.isBlank()) return "image";
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isBlank()) return "image";
        return name.codePoints().limit(200).collect(StringBuilder::new, StringBuilder::appendCodePoint,
                StringBuilder::append).toString();
    }
}
