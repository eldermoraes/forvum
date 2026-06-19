package ai.forvum.app;

import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.devui.ConfigEditorService;
import ai.forvum.engine.devui.ConfigEditorService.SaveResult;
import ai.forvum.engine.doctor.Finding;
import ai.forvum.engine.doctor.Severity;
import ai.forvum.sdk.ModelProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.vertx.web.Route;

import io.vertx.ext.web.RoutingContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Dev UI live config editor (P3-6 #54, ULTRAPLAN §3.2 Dev UI surface). A browser surface that lists the
 * {@code ~/.forvum/} config files, lets the developer edit one, VALIDATES the edit through the same
 * {@link ai.forvum.engine.doctor.ConfigDoctor} oracle the engine loads with, writes the file, and lets the
 * engine hot-reload it (a fired {@link ConfigurationChangedEvent}) — all without a restart.
 *
 * <p><strong>Dev-mode ONLY — explicit, documented native carve-out.</strong> The Quarkus Dev UI is a
 * fast-jar dev-mode feature; it is NOT part of the GraalVM native binary. This whole bean is gated with
 * {@link IfBuildProperty @IfBuildProperty("forvum.devui.config-editor.enabled")}, which is {@code true}
 * only in the {@code %dev} (and {@code %test}, to exercise the wiring) profiles and absent — hence
 * {@code false} — in {@code prod}. Because {@code @IfBuildProperty} is evaluated at BUILD time, the bean
 * is removed entirely from the {@code prod}/native image: its {@code @Route} handlers do not exist there
 * and nothing it references enters the production/native surface (CLAUDE.md §5; this feature's job is to
 * NOT regress native). A full Dev UI <em>card</em> (a {@code CardPageBuildItem} web component) needs a
 * {@code *-deployment} module, which would force {@code forvum-engine} into a runtime+deployment split
 * that breaks its headless-library setup (CLAUDE.md [M6]); so — per this issue's sanctioned fallback — the
 * editor is a {@code quarkus-reactive-routes} {@code @Route} surface over the Web channel's already-present
 * {@code vertx-http} (the same mechanism as {@link CaprDashboardRoute}/{@link ApprovalDashboardRoute}),
 * reachable in {@code quarkus:dev} at {@value #PAGE_PATH}.
 *
 * <p>The editor page links from the standard Dev UI ({@code /q/dev/}) via its rendered link; it is also
 * directly reachable at {@value #PAGE_PATH} while {@code quarkus:dev} is running. All handlers are
 * {@code type = BLOCKING}: they do blocking file IO via {@link ConfigEditorService} on a worker thread
 * (CLAUDE.md §11), never on the Vert.x event loop, and there is no {@code @Startup}/{@code StartupEvent}
 * observer — the work happens only inside a handler.
 *
 * <p>Validation reuses {@link ConfigEditorService} (which drives {@code ConfigDoctor}); this command supplies
 * the two things only the assembled app knows: the resolved {@link ForvumHome} (injected) and the installed
 * model-provider extension ids (from {@code Instance<ModelProvider>}, the {@code DoctorCommand} idiom), so a
 * model ref naming an uninstalled provider is flagged. A successful save fires {@code Event<ConfigurationChangedEvent>},
 * the same event the {@code WatchService} emits, so the running engine re-reads the edited spec.
 */
@ApplicationScoped
@IfBuildProperty(name = "forvum.devui.config-editor.enabled", stringValue = "true")
public class DevConfigEditorRoute {

    static final String PAGE_PATH = "/q/dev-ui/config-editor";
    static final String API_BASE = PAGE_PATH + "/api";

    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    ForvumHome home;

    @Inject
    ConfigLoader loader;

    @Inject
    Instance<ModelProvider> providers;

    @Inject
    Event<ConfigurationChangedEvent> changeEvent;

    private ConfigEditorService editor() {
        Set<String> knownProviders = providers.stream()
                .map(ModelProvider::extensionId)
                .collect(Collectors.toUnmodifiableSet());
        return new ConfigEditorService(home, loader, knownProviders, changeEvent::fire);
    }

    /** The self-contained editor HTML page (dev-only). */
    @Route(path = PAGE_PATH, methods = Route.HttpMethod.GET, type = Route.HandlerType.BLOCKING,
            produces = "text/html")
    public void page(RoutingContext rc) {
        rc.response().putHeader("content-type", "text/html; charset=utf-8").end(EDITOR_HTML);
    }

    /** {@code GET .../api/files} — the editable config files under {@code $FORVUM_HOME}, as a JSON array. */
    @Route(path = API_BASE + "/files", methods = Route.HttpMethod.GET, type = Route.HandlerType.BLOCKING,
            produces = "application/json")
    public void files(RoutingContext rc) {
        ArrayNode array = mapper.createArrayNode();
        editor().files().forEach(array::add);
        json(rc, 200, array);
    }

    /** {@code GET .../api/file?path=agents/main.json} — the current content of one file. */
    @Route(path = API_BASE + "/file", methods = Route.HttpMethod.GET, type = Route.HandlerType.BLOCKING,
            produces = "application/json")
    public void file(RoutingContext rc) {
        String path = rc.request().getParam("path");
        try {
            String content = editor().read(path).orElse("");
            ObjectNode out = mapper.createObjectNode();
            out.put("path", path);
            out.put("content", content);
            json(rc, 200, out);
        } catch (IllegalArgumentException e) {
            json(rc, 400, error(e.getMessage()));
        }
    }

    /** {@code POST .../api/validate} — dry-run validation of a candidate edit; never writes. */
    @Route(path = API_BASE + "/validate", methods = Route.HttpMethod.POST, type = Route.HandlerType.BLOCKING,
            produces = "application/json")
    public void validate(RoutingContext rc) {
        try {
            JsonNode body = mapper.readTree(rc.body().asString("UTF-8"));
            String path = body.path("path").asText();
            String content = body.path("content").asText();
            List<Finding> findings = editor().validate(path, content);
            ObjectNode out = mapper.createObjectNode();
            out.put("valid", findings.stream().noneMatch(f -> f.severity() == Severity.ERROR));
            out.set("findings", findingsJson(findings));
            json(rc, 200, out);
        } catch (IllegalArgumentException e) {
            json(rc, 400, error(e.getMessage()));
        } catch (Exception e) {
            json(rc, 400, error("Bad request: " + e.getMessage()));
        }
    }

    /** {@code POST .../api/save} — validate and, when error-free, write + fire a hot-reload event. */
    @Route(path = API_BASE + "/save", methods = Route.HttpMethod.POST, type = Route.HandlerType.BLOCKING,
            produces = "application/json")
    public void save(RoutingContext rc) {
        try {
            JsonNode body = mapper.readTree(rc.body().asString("UTF-8"));
            String path = body.path("path").asText();
            String content = body.path("content").asText();
            SaveResult result = editor().save(path, content);
            ObjectNode out = mapper.createObjectNode();
            out.put("saved", result.saved());
            out.set("findings", findingsJson(result.findings()));
            json(rc, 200, out);
        } catch (IllegalArgumentException e) {
            json(rc, 400, error(e.getMessage()));
        } catch (Exception e) {
            json(rc, 400, error("Bad request: " + e.getMessage()));
        }
    }

    private ArrayNode findingsJson(List<Finding> findings) {
        ArrayNode array = mapper.createArrayNode();
        for (Finding f : findings) {
            ObjectNode node = mapper.createObjectNode();
            node.put("severity", f.severity().name());
            node.put("location", f.location());
            node.put("problem", f.problem());
            node.put("hint", f.hint());
            array.add(node);
        }
        return array;
    }

    private ObjectNode error(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message == null ? "invalid request" : message);
        return node;
    }

    private void json(RoutingContext rc, int status, JsonNode body) {
        rc.response().setStatusCode(status)
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(body.toString());
    }

    /** A self-contained editor page (no external assets — works offline in dev mode). */
    private static final String EDITOR_HTML =
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Forvum — config editor (dev)</title>
              <style>
                body { font-family: system-ui, sans-serif; margin: 0; display: flex; height: 100vh; }
                #files { width: 16rem; border-right: 1px solid #ccc; overflow-y: auto; padding: .5rem; }
                #files h2 { font-size: .9rem; text-transform: uppercase; color: #666; }
                #files button { display: block; width: 100%; text-align: left; border: 0; background: none;
                  padding: .35rem .5rem; cursor: pointer; border-radius: 4px; font: inherit; }
                #files button:hover { background: #eef; }
                #files button.active { background: #dde; font-weight: 600; }
                #editor { flex: 1; display: flex; flex-direction: column; padding: .5rem; }
                #editor textarea { flex: 1; width: 100%; box-sizing: border-box; font-family: monospace;
                  font-size: .85rem; }
                #toolbar { padding: .5rem 0; display: flex; gap: .5rem; align-items: center; }
                #findings { max-height: 12rem; overflow-y: auto; font-size: .85rem; }
                .error { color: #b00; } .warning { color: #b80; } .ok { color: #080; }
                .loc { font-family: monospace; }
              </style>
            </head>
            <body>
              <div id="files"><h2>~/.forvum config</h2><div id="filelist">loading…</div></div>
              <div id="editor">
                <div id="toolbar">
                  <strong id="current">(no file)</strong>
                  <button id="validate" disabled>Validate</button>
                  <button id="save" disabled>Save &amp; reload</button>
                  <span id="status"></span>
                </div>
                <textarea id="content" spellcheck="false" disabled></textarea>
                <div id="findings"></div>
              </div>
              <script>
                const api = location.pathname.replace(/\\/$/, '') + '/api';
                let current = null;
                const $ = (id) => document.getElementById(id);

                async function loadFiles() {
                  const files = await (await fetch(api + '/files')).json();
                  const list = $('filelist'); list.innerHTML = '';
                  if (!files.length) { list.textContent = 'No config files yet. Run "forvum init".'; return; }
                  for (const f of files) {
                    const b = document.createElement('button');
                    b.textContent = f; b.onclick = () => open(f, b);
                    list.appendChild(b);
                  }
                }
                async function open(path, btn) {
                  document.querySelectorAll('#filelist button').forEach(x => x.classList.remove('active'));
                  if (btn) btn.classList.add('active');
                  const data = await (await fetch(api + '/file?path=' + encodeURIComponent(path))).json();
                  current = path; $('current').textContent = path;
                  $('content').value = data.content; $('content').disabled = false;
                  $('validate').disabled = false; $('save').disabled = false;
                  $('findings').innerHTML = ''; $('status').textContent = '';
                }
                function renderFindings(findings, header) {
                  const box = $('findings'); box.innerHTML = '';
                  const h = document.createElement('div'); h.innerHTML = header; box.appendChild(h);
                  for (const f of findings) {
                    const d = document.createElement('div');
                    d.className = f.severity.toLowerCase();
                    d.innerHTML = '<b>' + f.severity + '</b> <span class="loc">' + f.location +
                      '</span>: ' + f.problem + (f.hint ? ' — <i>' + f.hint + '</i>' : '');
                    box.appendChild(d);
                  }
                }
                async function post(action) {
                  const res = await fetch(api + '/' + action, {
                    method: 'POST', headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({ path: current, content: $('content').value })
                  });
                  return res.json();
                }
                $('validate').onclick = async () => {
                  const r = await post('validate');
                  renderFindings(r.findings || [], r.valid
                    ? '<span class="ok">Valid — the engine can load this.</span>'
                    : '<span class="error">Validation failed.</span>');
                };
                $('save').onclick = async () => {
                  const r = await post('save');
                  $('status').textContent = r.saved ? 'saved + hot-reloaded' : 'NOT saved (errors)';
                  renderFindings(r.findings || [], r.saved
                    ? '<span class="ok">Saved — change event fired, the engine reloaded it.</span>'
                    : '<span class="error">Not saved — fix the errors below.</span>');
                  if (r.saved) loadFiles();
                };
                loadFiles();
              </script>
            </body>
            </html>
            """;
}
