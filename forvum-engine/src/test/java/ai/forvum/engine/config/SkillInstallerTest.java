package ai.forvum.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * {@link SkillInstaller#install}: download (file:// + http://), validate through the real
 * {@link SkillReader} parser, derive the skill id (front-matter name, else the URL stem), and write the
 * file. Error paths (unsupported scheme, malformed skill, missing source, non-200) all throw and write
 * nothing. A plain unit test — file:// + an in-test {@code com.sun.net.httpserver}, no network.
 */
class SkillInstallerTest {

    private final SkillInstaller installer = new SkillInstaller();

    private static final String VALID_SKILL = """
            ---
            { "name": "summarize", "inputSchema": { "type": "object", "required": ["text"],
              "properties": { "text": { "type": "string" } } } }
            ---
            Summarize: {{text}}
            """;

    private static String fileUrl(Path dir, String name, String content) throws IOException {
        Path file = Files.writeString(dir.resolve(name), content);
        return file.toUri().toString();
    }

    @Test
    void installsAValidFileUrlSkillUsingTheFrontMatterName(@TempDir Path tmp) throws IOException {
        Path remote = Files.createDirectories(tmp.resolve("remote"));
        Path skills = tmp.resolve("skills");
        String url = fileUrl(remote, "whatever.md", VALID_SKILL);

        SkillInstallResult result = installer.install(url, skills);

        assertEquals("summarize", result.id(), "the front-matter name wins over the URL filename");
        assertEquals(skills.resolve("summarize.md"), result.path());
        assertTrue(Files.isRegularFile(result.path()));
        assertTrue(Files.readString(result.path()).contains("Summarize: {{text}}"));
    }

    @Test
    void derivesTheIdFromTheUrlWhenThereIsNoFrontMatterName(@TempDir Path tmp) throws IOException {
        Path remote = Files.createDirectories(tmp.resolve("remote"));
        Path skills = tmp.resolve("skills");
        String url = fileUrl(remote, "my-helper.md", "Just a template, no front-matter.");

        SkillInstallResult result = installer.install(url, skills);

        assertEquals("my-helper", result.id(), "no front-matter name → the URL filename stem (no .md)");
        assertTrue(Files.isRegularFile(skills.resolve("my-helper.md")));
    }

    @Test
    void downloadsOverHttp(@TempDir Path tmp) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/summarize.md", exchange -> {
            byte[] body = VALID_SKILL.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/summarize.md";
            SkillInstallResult result = installer.install(url, tmp.resolve("skills"));
            assertEquals("summarize", result.id());
            assertTrue(Files.isRegularFile(result.path()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aNon200HttpResponseIsRejected(@TempDir Path tmp) {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        server.createContext("/missing.md", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing.md";
            SkillInstallException e = assertThrows(SkillInstallException.class,
                    () -> installer.install(url, tmp.resolve("skills")));
            assertTrue(e.getMessage().contains("404"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnsupportedSchemeIsRejected(@TempDir Path tmp) {
        assertThrows(SkillInstallException.class,
                () -> installer.install("ftp://example.org/s.md", tmp.resolve("skills")));
    }

    @Test
    void aMissingFileSourceIsRejected(@TempDir Path tmp) {
        String url = tmp.resolve("nope.md").toUri().toString();
        assertThrows(SkillInstallException.class, () -> installer.install(url, tmp.resolve("skills")));
    }

    @Test
    void aMalformedSkillIsRejectedAndNothingIsWritten(@TempDir Path tmp) throws IOException {
        Path remote = Files.createDirectories(tmp.resolve("remote"));
        Path skills = tmp.resolve("skills");
        String url = fileUrl(remote, "bad.md", "---\n{ not json }\n---\nbody\n");

        assertThrows(SkillSpecException.class, () -> installer.install(url, skills));
        // parse() throws BEFORE the installer creates the skills dir / writes — so nothing landed.
        assertFalse(Files.exists(skills), "a malformed skill must not create the skills dir or any file");
    }

    @Test
    void aTraversalFrontMatterNameIsSanitizedAndStaysUnderTheSkillsDir(@TempDir Path tmp)
            throws IOException {
        Path remote = Files.createDirectories(tmp.resolve("remote"));
        Path skills = tmp.resolve("skills");
        String evil = "---\n{ \"name\": \"../../etc/evil\", \"description\": \"x\" }\n---\nbody\n";
        String url = fileUrl(remote, "innocent.md", evil);

        SkillInstallResult result = installer.install(url, skills);

        assertFalse(result.id().contains("/"), "the id must carry no path separator");
        assertFalse(result.id().contains(".."), "the id must carry no parent-dir token");
        Path normalizedSkills = skills.toAbsolutePath().normalize();
        assertTrue(result.path().toAbsolutePath().normalize().startsWith(normalizedSkills),
                "the written file must stay under the skills dir; got " + result.path());
        assertEquals(normalizedSkills, result.path().toAbsolutePath().normalize().getParent(),
                "the file must be a DIRECT child of skills/ (no traversal up or down)");
    }

    @Test
    void aNameThatSanitizesToEmptyIsRejected(@TempDir Path tmp) throws IOException {
        Path remote = Files.createDirectories(tmp.resolve("remote"));
        // name ".." → only dots/dashes → stripped to empty → no derivable id.
        String url = fileUrl(remote, "dots.md", "---\n{ \"name\": \"..\" }\n---\nbody\n");
        assertThrows(SkillInstallException.class, () -> installer.install(url, tmp.resolve("skills")));
    }

    @Test
    void theInstalledSkillIsOwnerOnly(@TempDir Path tmp) throws IOException {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (!posix) {
            return; // permission bits are a POSIX concern (the production code is POSIX-guarded too)
        }
        Path remote = Files.createDirectories(tmp.resolve("remote"));
        Path skills = tmp.resolve("skills");
        SkillInstallResult result = installer.install(fileUrl(remote, "x.md", VALID_SKILL), skills);

        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(result.path()), "the skill file must be 0600");
        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(skills), "the skills dir must be 0700");
    }

    @Test
    void reinstallingTheSameSkillOverwritesIt(@TempDir Path tmp) throws IOException {
        Path remote = Files.createDirectories(tmp.resolve("remote"));
        Path skills = tmp.resolve("skills");
        installer.install(fileUrl(remote, "v1.md",
                "---\n{ \"name\": \"sum\" }\n---\nVersion one"), skills);
        SkillInstallResult second = installer.install(fileUrl(remote, "v2.md",
                "---\n{ \"name\": \"sum\" }\n---\nVersion two"), skills);

        assertEquals("sum", second.id());
        assertTrue(Files.readString(second.path()).contains("Version two"),
                "a re-install with the same id overwrites (upgrade semantics)");
        assertFalse(Files.readString(second.path()).contains("Version one"));
    }
}
