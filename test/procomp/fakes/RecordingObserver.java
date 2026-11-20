package procomp.fakes;

import procomp.shell.ports.ProgressObserver;

import java.util.ArrayList;
import java.util.List;

public final class RecordingObserver implements ProgressObserver {

    private final List<ProgressEvent> events = new ArrayList<>();

    @Override
    public synchronized void onEvent(ProgressEvent event) {
        events.add(event);
    }

    public synchronized List<ProgressEvent> events() {
        return List.copyOf(events);
    }
}
