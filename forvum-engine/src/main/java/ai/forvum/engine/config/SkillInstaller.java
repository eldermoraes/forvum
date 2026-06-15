package ai.forvum.engine.config;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Installs a skill from a URL into {@code ~/.forvum/skills/} (P2-7 #32): download the {@code .md}, validate
 * it through the REAL {@link SkillReader} (so an install accepts exactly what the runtime will — the P2-9
 * doctor lesson; a malformed front-matter / bad {@code inputSchema} is rejected before the file lands),
 * derive a safe skill id (the front-matter {@code name}, else the URL filename), and write it owner-only
 * ({@code 0600} file / {@code 0700} dir, the {@code InitCommand} recipe). {@code skills/} is hot-loaded
 * ({@code ConfigWatcher.WATCHED_SUBFOLDERS}), so the new skill is visible without a restart. NATIVE: pure
 * {@code java.net.http} download + {@code java.nio} write — no reflection.
 */
@Singleton
public class SkillInstaller {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");

    /**
     * Download, validate, and install the skill at {@code source} into {@code skillsDir}. Re-installing a
     * skill with the same derived id OVERWRITES the existing file (upgrade semantics) — the validation
     * gate runs first, so a malformed re-install never clobbers a good skill.
     *
     * @throws SkillInstallException on an invalid/unsupported URL, a download failure, an undeterminable
     *         id, or a write failure
     * @throws SkillSpecException    if the downloaded body is a malformed skill (front-matter / inputSchema)
     */
    public SkillInstallResult install(String source, Path skillsDir) {
        String markdown = download(source);
        // Validate through the REAL reader's parser (the P2-9 doctor lesson) — a malformed front-matter or
        // inputSchema throws SkillSpecException here, before the file is written.
        SkillSpec spec = SkillReader.parse(source, markdown);
        String id = deriveId(spec.name().orElseGet(() -> fileStem(source)), source);
        Path target = skillsDir.resolve(id + ".md");
        try {
            createDir(skillsDir);
            Files.writeString(target, markdown);
            if (POSIX) {
                Files.setPosixFilePermissions(target, FILE_PERMS);
            }
        } catch (IOException e) {
            throw new SkillInstallException(
                "could not write the skill to " + target + ": " + e.getMessage(), e);
        }
        return new SkillInstallResult(id, target);
    }

    private static String download(String source) {
        URI uri;
        try {
            uri = URI.create(source);
        } catch (RuntimeException e) {
            throw new SkillInstallException("invalid skill URL '" + source + "': " + e.getMessage(), e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        try {
            if (scheme.equals("file")) {
                return Files.readString(Path.of(uri));
            }
            if (scheme.equals("http") || scheme.equals("https")) {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new SkillInstallException(
                        "downloading " + source + " returned HTTP " + response.statusCode() + ".");
                }
                return response.body();
            }
            if (scheme.isEmpty()) {
                throw new SkillInstallException("skill source '" + source + "' has no URL scheme — "
                        + "prefix a local path with 'file://', or use an http/https URL.");
            }
            throw new SkillInstallException(
                "unsupported skill URL scheme '" + scheme + "' (use http, https, or file).");
        } catch (IOException e) {
            throw new SkillInstallException(
                "could not download the skill from " + source + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkillInstallException("interrupted downloading the skill from " + source + ".", e);
        }
    }

    /** A safe skill id from {@code raw}: drop a {@code .md} suffix, keep only {@code [A-Za-z0-9._-]}. */
    private static String deriveId(String raw, String source) {
        String base = raw.strip();
        if (base.toLowerCase(Locale.ROOT).endsWith(".md")) {
            base = base.substring(0, base.length() - ".md".length());
        }
        String id = base.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-{2,}", "-")
                .replaceAll("^[-.]+|[-.]+$", "");
        if (id.isEmpty()) {
            throw new SkillInstallException(
                "could not derive a skill id from '" + source + "'; declare a \"name\" in the front-matter.");
        }
        return id;
    }

    /** The last path segment of a URL (query stripped) — the default skill id when no front-matter name. */
    private static String fileStem(String source) {
        String path = source;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static void createDir(Path dir) throws IOException {
        if (POSIX) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_PERMS));
        } else {
            Files.createDirectories(dir);
        }
    }
}
