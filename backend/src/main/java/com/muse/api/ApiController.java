package com.muse.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.muse.api.ApiModels.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final SessionService sessions;
    private final StoreMapper store;
    private final AssetService assets;
    private final TaskService tasks;
    private final AccountService accounts;
    private final ImageStorage storage;
    private final JdbcTemplate jdbc;

    public ApiController(SessionService sessions, StoreMapper store, AssetService assets,
                         TaskService tasks, AccountService accounts, ImageStorage storage, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.store = store;
        this.assets = assets;
        this.tasks = tasks;
        this.accounts = accounts;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    @PostMapping("/session")
    public Identity session(@RequestParam(defaultValue = "false") boolean fresh,
                            HttpServletRequest request, HttpServletResponse response) {
        return sessions.open(request, response, fresh);
    }

    @PostMapping("/account/register")
    public Identity register(@Valid @RequestBody AccountRegister input,
                             HttpServletRequest request, HttpServletResponse response) {
        String owner = SessionService.owner(request);
        accounts.register(owner, input);
        return sessions.rotate(owner, request, response);
    }

    @PostMapping("/account/login")
    public Identity login(@Valid @RequestBody AccountLogin input,
                          HttpServletRequest request, HttpServletResponse response) {
        String owner = SessionService.owner(request);
        Rows.AccountRow account = accounts.login(owner, input);
        return sessions.rotate(account.identityId, request, response);
    }

    @PostMapping("/account/logout")
    public Identity logout(HttpServletRequest request, HttpServletResponse response) {
        return sessions.logout(request, response);
    }

    @PostMapping("/account/password")
    public Identity changePassword(@Valid @RequestBody PasswordChange input,
                                   HttpServletRequest request, HttpServletResponse response) {
        String owner = SessionService.owner(request);
        accounts.changePassword(owner, input);
        return sessions.rotate(owner, request, response);
    }

    @GetMapping("/templates")
    public List<Template> templates(@RequestParam(required = false) String category) {
        return store.listTemplates(category).stream().map(Rows.TemplateRow::view).toList();
    }

    @GetMapping("/templates/{id}")
    public Template template(@PathVariable String id) {
        Rows.TemplateRow row = store.findTemplate(id);
        if (row == null) throw ApiException.notFound("模板不存在");
        return row.view();
    }

    @GetMapping("/categories")
    public List<String> categories() { return store.listCategories(); }

    @PostMapping(value = "/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Asset upload(@RequestPart("file") MultipartFile file, @RequestParam String templateId,
                        HttpServletRequest request) {
        return assets.upload(SessionService.owner(request), templateId, file);
    }

    @GetMapping("/assets/{id}/content")
    public ResponseEntity<Resource> assetContent(@PathVariable String id, HttpServletRequest request) throws IOException {
        Rows.AssetRow asset = assets.owned(SessionService.owner(request), id);
        return image(storage.uploadResource(asset.fileName), asset.mimeType, "image");
    }

    @PostMapping("/tasks")
    public ResponseEntity<Task> createTask(@Valid @RequestBody CreateTask input, HttpServletRequest request) {
        Task task = tasks.create(SessionService.owner(request), input);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(task);
    }

    @GetMapping("/tasks")
    public List<Task> history(HttpServletRequest request) { return tasks.list(SessionService.owner(request)); }

    @GetMapping("/tasks/{id}")
    public Task task(@PathVariable String id, HttpServletRequest request) {
        return tasks.get(SessionService.owner(request), id);
    }

    @GetMapping("/tasks/{id}/result")
    public ResponseEntity<Resource> result(@PathVariable String id, HttpServletRequest request) throws IOException {
        Rows.TaskRow task = tasks.owned(SessionService.owner(request), id);
        if (task.status != TaskStatus.SUCCEEDED || task.resultFileName == null) {
            throw ApiException.notFound("任务尚未生成可用的结果");
        }
        return image(storage.resultResource(task.resultFileName), task.resultMimeType, "result");
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)) || !storage.ready()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "服务尚未就绪");
        }
        return Map.of("status", "UP");
    }

    private ResponseEntity<Resource> image(Resource resource, String mime, String name) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .contentLength(resource.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(name + ("image/png".equals(mime) ? ".png" : ".jpg")).build().toString())
                .body(resource);
    }
}
