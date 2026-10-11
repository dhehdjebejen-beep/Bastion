package dev.bastionac.argus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bastionac.BastionAC;
import dev.bastionac.util.SafeFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every case, newest last, in {@code config/bastionac/argus/cases.json}.
 * Mutated on the server thread; written off it as a snapshot.
 */
final class CaseStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final List<CaseFile> cases = new ArrayList<>();
    private int counter;

    private static final class Stored {
        int counter;
        List<CaseFile> cases = new ArrayList<>();
    }

    CaseStore(Path file) {
        this.file = file;
    }

    synchronized void load() {
        cases.clear();
        try {
            if (Files.exists(file)) {
                Stored s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
                if (s != null) {
                    counter = s.counter;
                    if (s.cases != null) cases.addAll(s.cases);
                }
            }
        } catch (Exception e) {
            BastionAC.LOGGER.warn("Argus: cases.json unreadable ({}), starting with an empty list", e.toString());
        }
        // A case that was mid-analysis when the server stopped goes back to waiting.
        for (CaseFile c : cases) {
            if (c.status == CaseFile.Status.QUEUED || c.status == CaseFile.Status.ANALYZING) {
                c.status = c.analyses > 0 ? CaseFile.Status.ANALYZED : CaseFile.Status.COLLECTING;
            }
        }
    }

    synchronized void save() {
        Stored s = new Stored();
        s.counter = counter;
        s.cases = new ArrayList<>(cases);
        String json = GSON.toJson(s);
        SafeFiles.writeAsync(file, () -> json, t -> BastionAC.LOGGER.warn("Argus: could not save cases: {}", t.toString()));
    }

    synchronized CaseFile create(String uuid, String name, long now) {
        CaseFile c = new CaseFile();
        counter++;
        c.id = Integer.toString(counter, 36).toUpperCase(Locale.ROOT);
        c.uuid = uuid;
        c.name = name;
        c.openedAt = now;
        cases.add(c);
        return c;
    }

    /** The player's latest case that is still open. */
    synchronized CaseFile openFor(String uuid) {
        for (int i = cases.size() - 1; i >= 0; i--) {
            CaseFile c = cases.get(i);
            if (c.uuid.equals(uuid) && c.open()) return c;
        }
        return null;
    }

    synchronized CaseFile byId(String id) {
        for (CaseFile c : cases) if (c.id.equalsIgnoreCase(id)) return c;
        return null;
    }

    synchronized List<CaseFile> all() {
        return new ArrayList<>(cases);
    }

    synchronized List<CaseFile> ofPlayer(String uuid) {
        List<CaseFile> out = new ArrayList<>();
        for (CaseFile c : cases) if (c.uuid.equals(uuid)) out.add(c);
        return out;
    }

    /** Closed, unlabelled cases older than the retention go; labelled ones are training data and stay. */
    synchronized int prune(long now, int retentionDays) {
        long cutoff = now - retentionDays * 86_400_000L;
        int before = cases.size();
        cases.removeIf(c -> !c.open() && c.label == CaseFile.Label.NONE && c.closedAt > 0 && c.closedAt < cutoff);
        while (cases.size() > 3000) cases.remove(0);
        return before - cases.size();
    }
}
