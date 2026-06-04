package ai.forvum.engine.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double that collects {@link ProviderCall}s in order, so decorator logic is testable sans DB. */
public class InMemoryProviderCallRecorder implements ProviderCallRecorder {

    final List<ProviderCall> calls = new CopyOnWriteArrayList<>();

    @Override
    public void record(ProviderCall call) {
        calls.add(call);
    }
}
