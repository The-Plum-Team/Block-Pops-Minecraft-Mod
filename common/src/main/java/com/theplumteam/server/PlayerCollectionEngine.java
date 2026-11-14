package com.theplumteam.server;

import com.theplumteam.remote.player.PlayerFetcher;
import com.theplumteam.remote.player.PlayerFetcher.Head;
import com.theplumteam.remote.player.PlayerFetcher.Refused;
import com.theplumteam.remote.player.PlayerFetcher.Resolved;
import com.theplumteam.remote.player.PlayerFetcher.Revision;
import com.theplumteam.remote.player.PlayerHead;
import com.theplumteam.remote.player.PlayerIds;
import com.theplumteam.remote.player.PlayerState;
import com.theplumteam.remote.player.PlayerStatus;
import com.theplumteam.server.PlayerCollectionSettings.Entry;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Which player collections a world shows: the ones added to it, each on the revision the world
 * is pinned to, for as long as the signed documents allow it.
 *
 * <p>It knows nothing of the game. It works from the world's list, from what {@link PlayerFetcher}
 * believes and keeps, and from the origin; after each operation {@link #view()} is what the
 * server registers and sends, and {@link #takeNotices()} what it tells whoever manages codes.
 *
 * <p>Every operation blocks, and all of them run on the one thread player collections work on.
 * {@link #view()} and {@link #lines()} may be read from any thread.
 */
public final class PlayerCollectionEngine {
    /** How often the status is checked until a status says otherwise. */
    public static final int STATUS_POLL_SECONDS = 900;
    /** How often the heads are checked until a status says otherwise. */
    public static final int HEAD_POLL_SECONDS = 1800;

    /** What a request came to. */
    public enum Outcome {
        ADDED, ALREADY_ENABLED, UNKNOWN_CODE, FULL, FAILED, UNAVAILABLE, OFF, REMOVED, NOT_ENABLED, UPDATED,
        UP_TO_DATE, FOLLOWING, PINNED
    }

    /**
     * A request's outcome, with the collection's name when there is one.
     *
     * @param rev the revision the world is on after it, 0 when it names none
     */
    public record Result(Outcome outcome, String name, int rev) {
    }

    /** Where an added collection stands. */
    public enum Condition {
        /** Shown in the world. */
        LOADED,
        /** Not shown yet: nothing verified of it is at hand, as on a server that has never been online. */
        WAITING,
        /** Not shown: it, or every player collection, is out of use for now or for good. */
        RETIRED,
        /** Not shown: the revision the world is pinned to is gone, and a newer one is there to move to. */
        PIN_GONE
    }

    /** Why a collection is not shown. */
    public enum Reason { OFF, REVIEW, POLICY, OWNER, UNAVAILABLE }

    /**
     * A collection the world shows.
     *
     * @param head the collection's current head as it was published, for clients to verify
     */
    public record Loaded(Entry entry, byte[] head, Revision revision) {
    }

    /**
     * One added collection, for whoever lists them.
     *
     * @param name   the collection's name when it is or was loaded, its code otherwise
     * @param author who published it; null when nobody is named or it is not loaded
     * @param reason why it is retired; null otherwise
     * @param newest the head's revision when it is above the pinned one, 0 otherwise
     */
    public record Line(Entry entry, Condition condition, String name, @Nullable String author,
                       @Nullable Reason reason, int newest) {
    }

    /** Something whoever manages codes is told once. */
    public enum NoticeKind { RETIRED, ALL_OFF, PIN_GONE, NEW_VERSION }

    /**
     * @param reason why, for {@link NoticeKind#RETIRED}; null otherwise
     * @param rev    the newest revision, for {@link NoticeKind#NEW_VERSION} and {@link NoticeKind#PIN_GONE};
     *               0 otherwise or when none is known
     * @param pinned the revision the world is pinned to
     */
    public record Notice(NoticeKind kind, String name, @Nullable Reason reason, int rev, int pinned) {
    }

    private final PlayerFetcher fetcher;
    private final PlayerCollectionSettings settings;
    private final LongSupplier clock;
    private final boolean online;
    private final Consumer<String> log;

    private final Map<String, Loaded> loaded = new LinkedHashMap<>();
    private final Map<String, Condition> conditions = new HashMap<>();
    private final Map<String, Head> heads = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    /** The pinned index of each collection that is no longer published. */
    private final Map<String, String> gone = new HashMap<>();
    /** The newest revision whoever manages codes was told of, per collection. */
    private final Map<String, Integer> told = new HashMap<>();
    private final List<Notice> notices = new ArrayList<>();
    private boolean allOff;
    private volatile int statusPollSeconds = STATUS_POLL_SECONDS;
    private volatile int headPollSeconds = HEAD_POLL_SECONDS;
    private volatile List<Loaded> view = List.of();
    private volatile List<Line> lines = List.of();

    /**
     * @param clock  the time in Unix seconds
     * @param online false where nothing is ever fetched and what is kept is the whole state
     * @param log    told, in one line, of each thing that failed
     */
    public PlayerCollectionEngine(PlayerFetcher fetcher, PlayerCollectionSettings settings, LongSupplier clock,
                                  boolean online, Consumer<String> log) {
        this.fetcher = fetcher;
        this.settings = settings;
        this.clock = clock;
        this.online = online && fetcher.hasKeys();
        this.log = log;
    }

    /** The collections the world shows, in the order they were added. */
    public List<Loaded> view() {
        return view;
    }

    /** Every added collection and where it stands, in the order they were added. */
    public List<Line> lines() {
        return lines;
    }

    /** What happened since the last call that whoever manages codes should hear. */
    public List<Notice> takeNotices() {
        List<Notice> taken = List.copyOf(notices);
        notices.clear();
        return taken;
    }

    /** How long to wait between two checks of the status, as the last status asks. */
    public int statusPollSeconds() {
        return statusPollSeconds;
    }

    /** How long to wait between two checks of the heads, as the last status asks. */
    public int headPollSeconds() {
        return headPollSeconds;
    }

    /** Loads what is kept of the added collections, with no network: how a world opens offline. */
    public void start() {
        settle(false);
        // Nobody is there to be told what a world found on opening; the list shows it
        notices.clear();
    }

    /** Adds the collection a code names, pinned to the revision its head names now. */
    public Result add(String code) {
        Optional<Entry> existing = settings.byCode(code);
        if (existing.isPresent()) {
            return new Result(Outcome.ALREADY_ENABLED, name(existing.get()), existing.get().rev());
        }
        if (settings.isFull()) {
            return new Result(Outcome.FULL, "", 0);
        }
        Resolved resolved;
        try {
            resolved = resolve(code);
        } catch (Stopped stopped) {
            return new Result(stopped.outcome, "", 0);
        }
        PlayerHead head = resolved.head();
        String name = resolved.revision().collection().getName();
        if (settings.byId(head.id()).isPresent()) {
            return new Result(Outcome.ALREADY_ENABLED, name, head.rev());
        }
        if (!settings.put(new Entry(head.id(), code, head.rev(), head.index(), false))) {
            log.accept("Could not save the player collections of this world");
            return new Result(Outcome.FAILED, name, 0);
        }
        heads.put(head.id(), new Head(head, resolved.headEnvelope()));
        settle(false);
        return new Result(conditions.get(head.id()) == Condition.LOADED ? Outcome.ADDED : Outcome.FAILED, name,
                head.rev());
    }

    /** Takes a collection out of the world, by its code or its id, and forgets it. */
    public Result remove(String codeOrId) {
        Optional<Entry> found = PlayerIds.isPlayerId(codeOrId) ? settings.byId(codeOrId) : settings.byCode(codeOrId);
        if (found.isEmpty()) {
            return new Result(Outcome.NOT_ENABLED, codeOrId, 0);
        }
        Entry entry = found.get();
        String name = name(entry);
        if (!settings.remove(entry.id())) {
            log.accept("Could not save the player collections of this world");
            return new Result(Outcome.FAILED, name, 0);
        }
        heads.remove(entry.id());
        names.remove(entry.id());
        gone.remove(entry.id());
        told.remove(entry.id());
        try {
            fetcher.forget(entry.id());
        } catch (IOException e) {
            log.accept("Could not delete what was kept of player collection " + entry.id() + ": " + e.getMessage());
        }
        settle(false);
        return new Result(Outcome.REMOVED, name, 0);
    }

    /** Moves a collection's pin to the revision its head names now. */
    public Result update(String code) {
        Optional<Entry> found = settings.byCode(code);
        if (found.isEmpty()) {
            return new Result(Outcome.NOT_ENABLED, code, 0);
        }
        Entry entry = found.get();
        Resolved resolved;
        try {
            resolved = resolve(code);
        } catch (Stopped stopped) {
            return new Result(stopped.outcome, name(entry), 0);
        }
        PlayerHead head = resolved.head();
        String name = resolved.revision().collection().getName();
        if (!head.id().equals(entry.id())) {
            log.accept("The code " + code + " names another collection than the one this world added");
            return new Result(Outcome.FAILED, name, 0);
        }
        heads.put(entry.id(), new Head(head, resolved.headEnvelope()));
        boolean same = head.rev() == entry.rev() && head.index().equals(entry.index());
        if (!same && !settings.put(entry.at(head.rev(), head.index()))) {
            log.accept("Could not save the player collections of this world");
            return new Result(Outcome.FAILED, name, 0);
        }
        gone.remove(entry.id());
        boolean wasLoaded = conditions.get(entry.id()) == Condition.LOADED;
        settle(false);
        if (conditions.get(entry.id()) != Condition.LOADED) {
            return new Result(Outcome.FAILED, name, 0);
        }
        return new Result(same && wasLoaded ? Outcome.UP_TO_DATE : Outcome.UPDATED, name, head.rev());
    }

    /** Sets whether a collection moves to each new revision by itself. */
    public Result follow(String code, boolean on) {
        Optional<Entry> found = settings.byCode(code);
        if (found.isEmpty()) {
            return new Result(Outcome.NOT_ENABLED, code, 0);
        }
        Entry entry = found.get();
        if (entry.follow() != on && !settings.put(entry.following(on))) {
            log.accept("Could not save the player collections of this world");
            return new Result(Outcome.FAILED, name(entry), 0);
        }
        settle(false);
        return new Result(on ? Outcome.FOLLOWING : Outcome.PINNED, name(entry), entry.rev());
    }

    /**
     * Checks the root, the status and the revocation list, and stops showing what they take out
     * of use. A check that fails changes nothing: what was verified before stays.
     */
    public void pollStatus() {
        if (!online || settings.entries().isEmpty()) {
            return;
        }
        refresh();
        try {
            PlayerState held = fetcher.state();
            for (Entry entry : settings.entries()) {
                // What the list has just taken out of use: the head says why, or which revision is the newest
                if (conditions.get(entry.id()) == Condition.LOADED && held.revocations() != null
                        && held.revocations().revokes(entry.id(), entry.rev())) {
                    head(entry);
                }
            }
        } catch (IOException e) {
            log.accept("Could not read what is believed of player collections: " + e.getMessage());
        }
        settle(false);
    }

    /**
     * Checks each collection's head: moves the ones that follow to a new revision, tells of a
     * new revision of the ones that do not, and brings back what is live again.
     */
    public void pollHeads() {
        if (!online || settings.entries().isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        try {
            if (!fetcher.state().mayAdd(now)) {
                refresh();
            }
        } catch (IOException e) {
            log.accept("Could not read what is believed of player collections: " + e.getMessage());
            return;
        }
        for (Entry entry : settings.entries()) {
            Head head = head(entry);
            if (head == null || head.head().state() != PlayerHead.State.LIVE || head.head().rev() <= entry.rev()) {
                continue;
            }
            PlayerHead newest = head.head();
            try {
                if (entry.follow()) {
                    if (fetcher.state().mayAdd(entry.id(), newest.rev(), now)) {
                        // Fetched and checked whole before the world moves to it
                        fetcher.revision(entry.id(), newest.rev(), newest.index());
                        if (settings.put(entry.at(newest.rev(), newest.index()))) {
                            gone.remove(entry.id());
                        }
                    }
                } else if (!fetcher.published(entry.id(), entry.index())) {
                    // A revision left behind ages out of what the service keeps
                    gone.put(entry.id(), entry.index());
                } else if (told.getOrDefault(entry.id(), 0) < newest.rev()
                        && conditions.get(entry.id()) == Condition.LOADED) {
                    told.put(entry.id(), newest.rev());
                    notices.add(new Notice(NoticeKind.NEW_VERSION, name(entry), null, newest.rev(), entry.rev()));
                }
            } catch (IOException e) {
                log.accept("Could not check revision " + newest.rev() + " of player collection " + entry.code() + ": "
                        + e.getMessage());
            }
        }
        settle(true);
    }

    private void refresh() {
        try {
            fetcher.refresh();
        } catch (IOException e) {
            log.accept("Could not check the state of player collections: " + e.getMessage());
        }
    }

    @Nullable
    private Head head(Entry entry) {
        try {
            Head head = fetcher.head(entry.code(), entry.id());
            heads.put(entry.id(), head);
            return head;
        } catch (IOException e) {
            log.accept("Could not check player collection " + entry.code() + ": " + e.getMessage());
            return null;
        }
    }

    /** A request that ended before anything changed. */
    private static final class Stopped extends Exception {
        private final Outcome outcome;

        Stopped(Outcome outcome) {
            super(outcome.name(), null, false, false);
            this.outcome = outcome;
        }
    }

    private Resolved resolve(String code) throws Stopped {
        if (!online) {
            throw new Stopped(Outcome.OFF);
        }
        try {
            return fetcher.resolve(code);
        } catch (Refused refused) {
            throw new Stopped(switch (refused.why()) {
                case OFF -> Outcome.OFF;
                case UNKNOWN_CODE -> Outcome.UNKNOWN_CODE;
                case UNAVAILABLE -> Outcome.UNAVAILABLE;
            });
        } catch (IOException e) {
            log.accept("Could not load the player collection of code " + code + ": " + e.getMessage());
            throw new Stopped(Outcome.FAILED);
        }
    }

    private String name(Entry entry) {
        return names.getOrDefault(entry.id(), entry.code());
    }

    /**
     * Brings what the world shows in line with its list and with what is believed and kept.
     *
     * @param fetch whether a revision that is not kept may be fetched
     */
    private void settle(boolean fetch) {
        PlayerState held;
        try {
            held = fetcher.state();
        } catch (IOException e) {
            log.accept("Could not read what is believed of player collections: " + e.getMessage());
            return;
        }
        long now = clock.getAsLong();
        PlayerStatus status = held.status();
        statusPollSeconds = status != null ? status.serverPollSeconds() : STATUS_POLL_SECONDS;
        headPollSeconds = status != null ? status.headPollSeconds() : HEAD_POLL_SECONDS;
        Map<String, Loaded> next = new LinkedHashMap<>();
        List<Line> listed = new ArrayList<>();
        boolean off = held.status() != null && held.revocations() != null && !held.keepsLoading();
        boolean anyShown = false;
        for (Entry entry : settings.entries()) {
            String id = entry.id();
            Condition condition = condition(held, entry);
            Loaded shown = null;
            if (condition == Condition.LOADED) {
                shown = load(held, entry, fetch && online, now);
                condition = shown != null ? Condition.LOADED : Condition.WAITING;
            }
            Reason reason = condition == Condition.RETIRED ? reason(held, id) : null;
            Condition before = conditions.put(id, condition);
            anyShown |= before == Condition.LOADED;
            if (shown != null) {
                next.put(id, shown);
                names.put(id, shown.revision().collection().getName());
            } else if (before != condition && (condition == Condition.RETIRED || condition == Condition.PIN_GONE)) {
                discard(id);
                if (condition == Condition.PIN_GONE) {
                    notices.add(new Notice(NoticeKind.PIN_GONE, name(entry), null, newest(entry), entry.rev()));
                } else if (reason != Reason.OFF && before == Condition.LOADED) {
                    notices.add(new Notice(NoticeKind.RETIRED, name(entry), reason, 0, entry.rev()));
                }
            }
            int newest = newest(entry);
            listed.add(new Line(entry, condition, name(entry),
                    shown != null ? shown.revision().index().author() : null, reason,
                    newest > entry.rev() ? newest : 0));
        }
        // Everything going off at once is told once, not once a collection
        if (off && !allOff && anyShown) {
            notices.add(new Notice(NoticeKind.ALL_OFF, "", Reason.OFF, 0, 0));
        }
        allOff = off;
        conditions.keySet().removeIf(id -> settings.byId(id).isEmpty());
        loaded.clear();
        loaded.putAll(next);
        view = List.copyOf(next.values());
        lines = List.copyOf(listed);
    }

    private int newest(Entry entry) {
        Head head = heads.get(entry.id());
        return head != null && head.head().state() == PlayerHead.State.LIVE ? head.head().rev() : 0;
    }

    private Condition condition(PlayerState held, Entry entry) {
        PlayerHead head = held.head(entry.id());
        if (held.status() == null || held.revocations() == null || head == null) {
            return Condition.WAITING;
        }
        if (!held.keepsLoading() || held.revocations().revokes(entry.id()) || head.state() != PlayerHead.State.LIVE) {
            return Condition.RETIRED;
        }
        if (held.revocations().revokes(entry.id(), entry.rev()) || entry.index().equals(gone.get(entry.id()))) {
            return Condition.PIN_GONE;
        }
        return Condition.LOADED;
    }

    private static Reason reason(PlayerState held, String id) {
        if (!held.keepsLoading()) {
            return Reason.OFF;
        }
        PlayerHead head = held.head(id);
        if (head == null || head.state() == PlayerHead.State.LIVE) {
            return Reason.UNAVAILABLE;
        }
        if (head.state() == PlayerHead.State.QUARANTINED) {
            return Reason.REVIEW;
        }
        return head.reason() == PlayerHead.Reason.OWNER ? Reason.OWNER : Reason.POLICY;
    }

    // The revision the entry is pinned to and the head to send with it, or null while either is missing
    @Nullable
    private Loaded load(PlayerState held, Entry entry, boolean fetch, long now) {
        String id = entry.id();
        try {
            Head head = heads.get(id);
            if (head == null || !head.head().equals(held.head(id))) {
                head = fetcher.keptHead(id).orElse(null);
                if (head == null) {
                    return null;
                }
                heads.put(id, head);
            }
            Loaded current = loaded.get(id);
            Revision revision;
            if (current != null && current.entry().rev() == entry.rev() && current.entry().index().equals(entry.index())) {
                revision = current.revision();
            } else {
                Optional<Revision> kept = fetcher.kept(id, entry.rev(), entry.index());
                if (kept.isPresent()) {
                    revision = kept.get();
                } else if (fetch && held.mayAdd(id, entry.rev(), now)) {
                    revision = fetcher.revision(id, entry.rev(), entry.index());
                } else {
                    return null;
                }
            }
            return new Loaded(entry, head.envelope(), revision);
        } catch (IOException e) {
            log.accept("Could not load player collection " + entry.code() + ": " + e.getMessage());
            return null;
        }
    }

    private void discard(String id) {
        try {
            fetcher.discard(id);
        } catch (IOException e) {
            log.accept("Could not delete what was kept of player collection " + id + ": " + e.getMessage());
        }
    }
}
