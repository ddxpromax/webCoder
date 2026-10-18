package io.github.ddxpromax.webcoder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Polling SSE file watcher compatible with Majo's workspace/watch contract.
 *
 * <p>The watcher deliberately takes a snapshot before it starts publishing.
 * A newly opened Coding page therefore receives no artificial initial change
 * event. Every later snapshot is compared by normalized relative path, mtime,
 * and size so atomic editor writes are reported as either an addition or a
 * modification. The protocol is JSON in a standard {@code data:} SSE frame:</p>
 *
 * <pre>{@code
 * {"type":"file_change","events":[{"change":"modified","path":"src/App.java"}]}
 * }</pre>
 */
final class WorkspaceWatchService {
    private static final long POLL_INTERVAL_MILLIS = 2_000L;

    private final ObjectMapper objectMapper;

    WorkspaceWatchService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ResponseEntity<SseEmitter> watch(
        Path root,
        Predicate<String> skippedName
    ) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("workspace-watch-", 0).factory()
        );
        Runnable stop = () -> {
            running.set(false);
            executor.shutdownNow();
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(error -> stop.run());

        executor.submit(() -> {
            try {
                Map<String, FileStamp> previous = snapshot(root, skippedName);
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                    Map<String, FileStamp> current = snapshot(root, skippedName);
                    List<FileChange> changes = changes(previous, current);
                    previous = current;
                    if (changes.isEmpty() || !running.get()) {
                        continue;
                    }
                    emitter.send(SseEmitter.event().data(
                        objectMapper.writeValueAsString(Map.of(
                            "type", "file_change",
                            "events", changes
                        )),
                        MediaType.APPLICATION_JSON
                    ));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                if (running.get()) {
                    emitter.completeWithError(error);
                }
            } finally {
                executor.shutdown();
            }
        });
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    static Map<String, FileStamp> snapshot(
        Path root,
        Predicate<String> skippedName
    ) {
        Map<String, FileStamp> files = new LinkedHashMap<>();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            return files;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes
                ) {
                    if (directory.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (Files.isSymbolicLink(directory)
                        || skippedName.test(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
                ) {
                    if (!Files.isSymbolicLink(file)
                        && attributes.isRegularFile()
                        && !skippedName.test(file.getFileName().toString())) {
                        files.put(relative(root, file), new FileStamp(
                            attributes.lastModifiedTime().toMillis(),
                            attributes.size()
                        ));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // A project can disappear while a client is still connected. The
            // next successful scan will naturally emit the deletions.
        }
        return files;
    }

    static List<FileChange> changes(
        Map<String, FileStamp> previous,
        Map<String, FileStamp> current
    ) {
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(previous.keySet());
        paths.addAll(current.keySet());
        List<FileChange> result = new ArrayList<>();
        for (String path : paths) {
            FileStamp before = previous.get(path);
            FileStamp after = current.get(path);
            if (before == null) {
                result.add(new FileChange("added", path));
            } else if (after == null) {
                result.add(new FileChange("deleted", path));
            } else if (!before.equals(after)) {
                result.add(new FileChange("modified", path));
            }
        }
        result.sort(Comparator.comparing(FileChange::path));
        return result;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    record FileStamp(long modifiedAtMillis, long size) { }

    record FileChange(String change, String path) { }
}
