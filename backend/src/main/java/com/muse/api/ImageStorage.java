package com.muse.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

@Component
public class ImageStorage {
    private static final Logger log = LoggerFactory.getLogger(ImageStorage.class);
    static final int MAX_DIMENSION = 8192;
    static final long MAX_PIXELS = 16_777_216;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final Path uploads;
    private final Path results;

    public ImageStorage(AppProperties properties) throws IOException {
        Path root = Path.of(properties.storagePath()).toAbsolutePath().normalize();
        uploads = Files.createDirectories(root.resolve("uploads"));
        results = Files.createDirectories(root.resolve("results"));
    }

    public record StoredImage(String fileName, String mimeType, long sizeBytes) {}

    public StoredImage save(MultipartFile file, Rows.TemplateRow template) {
        if (file.isEmpty()) throw ApiException.badRequest("请选择非空的 JPEG 或 PNG 图片");
        if (file.getSize() > template.maxFileSizeBytes) throw tooLarge();
        if (!template.mimeTypes().contains(file.getContentType())) {
            throw ApiException.badRequest("仅支持 JPEG 和 PNG 图片");
        }
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(Math.toIntExact(template.maxFileSizeBytes + 1));
        } catch (IOException exception) {
            throw ApiException.badRequest("无法读取上传的图片");
        }
        if (bytes.length > template.maxFileSizeBytes) throw tooLarge();
        String mime = validateImage(bytes);
        if (!mime.equals(file.getContentType()) || !template.mimeTypes().contains(mime)) {
            throw ApiException.badRequest("图片实际格式与声明类型不一致");
        }
        String name = UUID.randomUUID() + extension(mime);
        Path destination = resolve(uploads, name);
        try {
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            deleteQuietly(destination);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "图片存储暂时不可用");
        }
        return new StoredImage(name, mime, bytes.length);
    }

    private String validateImage(byte[] bytes) {
        boolean png = bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), PNG_SIGNATURE);
        boolean jpeg = bytes.length >= 3 && (bytes[0] & 255) == 255
                && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
        if (!png && !jpeg) throw ApiException.badRequest("文件内容不是有效的 JPEG 或 PNG 图片");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw ApiException.badRequest("无法识别图片内容");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(png && format.equals("png")) && !(jpeg && (format.equals("jpeg") || format.equals("jpg")))) {
                    throw ApiException.badRequest("图片格式无效");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw ApiException.badRequest("图片尺寸过大：边长最多 8192 像素，总像素最多 16777216");
                }
                // Header checks precede full decoding so compressed images cannot allocate unbounded memory.
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw ApiException.badRequest("图片内容损坏");
                }
                decoded.flush();
                return png ? "image/png" : "image/jpeg";
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw ApiException.badRequest("图片内容损坏或无法完整解码");
        }
    }

    public StoredImage copyResult(Rows.AssetRow source) throws IOException {
        if (source == null) throw new IOException("Task source image missing");
        Path original = existing(uploads, source.fileName);
        String name = UUID.randomUUID() + extension(source.mimeType);
        Path destination = resolve(results, name);
        try {
            Files.copy(original, destination);
            return new StoredImage(name, source.mimeType, Files.size(destination));
        } catch (IOException exception) {
            deleteQuietly(destination);
            throw exception;
        }
    }

    public Resource uploadResource(String name) { return new FileSystemResource(existing(uploads, name)); }
    public Resource resultResource(String name) { return new FileSystemResource(existing(results, name)); }
    public boolean uploadExists(String name) { return Files.isRegularFile(resolve(uploads, name), LinkOption.NOFOLLOW_LINKS); }
    public boolean ready() { return Files.isWritable(uploads) && Files.isWritable(results); }
    public void deleteUpload(String name) { deleteQuietly(resolve(uploads, name)); }
    public void deleteResult(String name) { deleteQuietly(resolve(results, name)); }

    private Path existing(Path directory, String name) {
        Path file = resolve(directory, name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw ApiException.notFound("图片文件不存在");
        return file;
    }

    private Path resolve(Path directory, String name) {
        if (name == null || !name.matches("[a-f0-9-]{36}\\.(jpg|png)")) {
            throw ApiException.notFound("图片文件不存在");
        }
        return directory.resolve(name);
    }

    private String extension(String mime) { return "image/png".equals(mime) ? ".png" : ".jpg"; }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Could not remove unused private image", exception);
        }
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 5MB");
    }
}
