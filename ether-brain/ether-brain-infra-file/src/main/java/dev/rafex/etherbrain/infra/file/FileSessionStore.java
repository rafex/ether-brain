package dev.rafex.etherbrain.infra.file;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rafex.etherbrain.ports.model.Message;
import dev.rafex.etherbrain.ports.session.ConversationState;
import dev.rafex.etherbrain.ports.session.SessionStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * File-backed SessionStore. Each session is persisted as a JSON file under
 * the configured directory: {@code <baseDir>/<sessionId>.json}.
 *
 * <p>Thread-safe: concurrent reads on the same session are allowed; writes
 * are exclusive per session.
 */
public final class FileSessionStore implements SessionStore {

    private final Path baseDir;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks =
            new ConcurrentHashMap<>();

    public FileSessionStore(Path baseDir) {
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create session directory: " + baseDir, e);
        }
        this.baseDir = baseDir;
        this.mapper = new ObjectMapper();
    }

    @Override
    public ConversationState load(String sessionId) {
        ReentrantReadWriteLock.ReadLock lock = lockFor(sessionId).readLock();
        lock.lock();
        try {
            Path file = sessionFile(sessionId);
            if (!Files.exists(file)) {
                return new ConversationState();
            }
            SessionFile data = mapper.readValue(file.toFile(), SessionFile.class);
            ConversationState state = new ConversationState();
            data.messages().stream()
                    .map(MessageDto::toDomain)
                    .forEach(state::add);
            return state;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load session: " + sessionId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void save(String sessionId, ConversationState state) {
        ReentrantReadWriteLock.WriteLock lock = lockFor(sessionId).writeLock();
        lock.lock();
        try {
            List<MessageDto> dtos = state.messages().stream()
                    .map(MessageDto::from)
                    .toList();
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(sessionFile(sessionId).toFile(), new SessionFile(dtos));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save session: " + sessionId, e);
        } finally {
            lock.unlock();
        }
    }

    private Path sessionFile(String sessionId) {
        return baseDir.resolve(sessionId + ".json");
    }

    private ReentrantReadWriteLock lockFor(String sessionId) {
        return locks.computeIfAbsent(sessionId, ignored -> new ReentrantReadWriteLock());
    }

    // ── DTOs (infra-only, no Jackson annotations on domain classes) ──────────

    private record SessionFile(List<MessageDto> messages) {
        @JsonCreator
        SessionFile(@JsonProperty("messages") List<MessageDto> messages) {
            this.messages = messages == null ? List.of() : messages;
        }
    }

    private record MessageDto(String role, String content, String toolCallId) {

        @JsonCreator
        MessageDto(
                @JsonProperty("role") String role,
                @JsonProperty("content") String content,
                @JsonProperty("toolCallId") String toolCallId) {
            this.role = role;
            this.content = content;
            this.toolCallId = toolCallId;
        }

        static MessageDto from(Message m) {
            return new MessageDto(m.role().name(), m.content(), m.toolCallId());
        }

        Message toDomain() {
            return new Message(Message.Role.valueOf(role), content, toolCallId);
        }
    }
}
