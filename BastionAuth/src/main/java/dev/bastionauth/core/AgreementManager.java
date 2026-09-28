package dev.bastionauth.core;

import dev.bastionauth.BastionAuth;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The user agreement, as something a player can actually read.
 *
 * <p>Section 2 of the agreement requires acceptance <em>before</em>
 * registration, which is why this lives in the authentication mod rather than
 * in MaxCore: it has to be able to stand in front of {@code /register}.
 *
 * <p>The text ships in the jar as {@code bastionauth/agreement-1.3.txt},
 * already split into volumes and pages. Pagination is done ahead of time
 * against real glyph widths because a written-book page silently clips
 * anything past 14 lines or 114 pixels — laying it out at runtime is how you
 * end up with an agreement whose last sentence nobody has ever seen. Each
 * volume is handed over as a real written book: it goes in the inventory,
 * survives relogs, and can be re-read whenever the player likes, which the
 * old click-through chest menu could not do.
 */
public final class AgreementManager {

    /** Stored with every acceptance. Bumping it invalidates every previous accept. */
    public static final String VERSION = "maxcora-eula-1.3";
    /** Shown to players. */
    public static final String REVISION = "1.3";
    public static final String REVISION_DATE = "16 сентября 2026";
    public static final String AUTHOR = "Администрация MaxCora";

    private static final String RESOURCE = "/bastionauth/agreement-1.3.txt";

    /** One bound volume. */
    public record Volume(String title, List<String> pages) {}

    private static volatile List<Volume> volumes = List.of();
    /** The set as ready-made stacks, built once per loaded text. */
    private static volatile List<ItemStack> bookSet = List.of();
    private static volatile List<Volume> bookSetFor = List.of();

    // ------------------------------------------------------------------ loading

    public static void load() {
        List<Volume> out = new ArrayList<>();
        try (InputStream in = AgreementManager.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                BastionAuth.LOGGER.error("Agreement resource {} is missing from the jar", RESOURCE);
                volumes = List.of();
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String title = null;
                List<String> pages = new ArrayList<>();
                StringBuilder page = null;
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("---VOLUME---")) {
                        if (page != null) pages.add(page.toString());
                        if (title != null) out.add(new Volume(title, List.copyOf(pages)));
                        title = line.substring("---VOLUME---".length()).trim();
                        pages = new ArrayList<>();
                        page = null;
                    } else if (line.startsWith("---PAGE---")) {
                        if (page != null) pages.add(page.toString());
                        page = new StringBuilder();
                    } else if (page != null) {
                        if (page.length() > 0) page.append('\n');
                        page.append(line);
                    }
                }
                if (page != null) pages.add(page.toString());
                if (title != null) out.add(new Volume(title, List.copyOf(pages)));
            }
        } catch (Exception e) {
            BastionAuth.LOGGER.error("Could not read the agreement text", e);
        }
        volumes = Collections.unmodifiableList(out);
        int pages = out.stream().mapToInt(v -> v.pages().size()).sum();
        BastionAuth.LOGGER.info("Agreement {} loaded: {} volume(s), {} page(s)", REVISION, out.size(), pages);
    }

    public static List<Volume> volumes() {
        return volumes;
    }

    public static boolean available() {
        return !volumes.isEmpty();
    }

    // ------------------------------------------------------------------ books

    /** One volume as a written book, ready to be put in an inventory. */
    public static ItemStack book(Volume volume, int index) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        List<RawFilteredPair<Text>> pages = new ArrayList<>(volume.pages().size());
        for (String page : volume.pages()) {
            pages.add(RawFilteredPair.of(Text.literal(page)));
        }
        String title = "Соглашение " + REVISION + " · " + romanOf(index);
        // MAX_TITLE_LENGTH is 32; a longer title makes the component invalid.
        if (title.length() > 32) title = title.substring(0, 32);
        stack.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(
                RawFilteredPair.of(title), AUTHOR, 0, pages, true));
        stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("§6" + volume.title()).styled(s -> s.withItalic(false)));
        stack.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(List.of(
                Text.literal("§7Пользовательское соглашение MaxCora").styled(s -> s.withItalic(false)),
                Text.literal("§8Редакция " + REVISION + " от " + REVISION_DATE).styled(s -> s.withItalic(false)),
                Text.literal("§8Страниц: " + volume.pages().size()).styled(s -> s.withItalic(false)))));
        return stack;
    }

    private static String romanOf(int index) {
        return switch (index) {
            case 0 -> "том I";
            case 1 -> "том II";
            case 2 -> "том III";
            default -> "том " + (index + 1);
        };
    }

    /**
     * The whole set, one stack per volume, in volume order. Built on first use
     * rather than at load: the text loads during mod init, before an item stack
     * is safe to make, and the result is reused for every connection because a
     * volume is 140 pages and the inventory mask draws it several times a join.
     * Callers copy before handing a stack to anyone.
     */
    public static List<ItemStack> bookSet() {
        List<Volume> vols = volumes;
        if (bookSetFor != vols) {
            List<ItemStack> built = new ArrayList<>(vols.size());
            for (int i = 0; i < vols.size(); i++) built.add(book(vols.get(i), i));
            bookSet = Collections.unmodifiableList(built);
            bookSetFor = vols;
        }
        return bookSet;
    }

    /**
     * Puts the whole set in the player's real inventory, dropping at their feet
     * when it is full. For players who are already logged in and ask for the
     * books to re-read them ({@code /agreement books}). Before login the set is
     * never put in the inventory at all — see {@link InventoryMask}.
     *
     * <p>(It used to be, and that was a bug: the volumes went into the first
     * free slots of a returning player's inventory — anywhere in the backpack,
     * since the hotbar was full — and a frozen player can neither move an item
     * nor select a backpack slot, so the book the server was asking them to
     * read could not be taken in hand at all.)
     *
     * <p>The volumes are put in the player's inventory, dropping at their feet
     * when it is full. Existing copies are replaced rather than stacked up,
     * so asking twice does not fill a chest with agreements.
     */
    public static int giveBooks(ServerPlayerEntity player) {
        if (!available()) return 0;
        removeBooks(player);
        int given = 0;
        List<Volume> vols = volumes;
        for (int i = 0; i < vols.size(); i++) {
            player.getInventory().offerOrDrop(book(vols.get(i), i));
            given++;
        }
        return given;
    }

    /** Whether the player already carries every volume of the current revision. */
    public static boolean hasCurrentBooks(ServerPlayerEntity player) {
        if (!available()) return false;
        int found = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (!isAgreementBook(st)) continue;
            WrittenBookContentComponent c = st.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            String title = c == null ? null : c.title().get(false);
            if (title != null && title.contains(REVISION)) found++;
        }
        return found >= volumes.size();
    }

    /** Removes agreement volumes the player already carries (any revision). */
    public static void removeBooks(ServerPlayerEntity player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (isAgreementBook(st)) inv.setStack(i, ItemStack.EMPTY);
        }
    }

    /** Whether the stack is one of our agreement volumes. */
    public static boolean isAgreementBook(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.WRITTEN_BOOK)) return false;
        WrittenBookContentComponent c = stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (c == null) return false;
        String title = c.title().get(false);
        return AUTHOR.equals(c.author()) && title != null && title.startsWith("Соглашение ");
    }

    /** Plain-text of one page, for the chat fallback when books cannot be given. */
    public static Optional<String> page(int volume, int page) {
        List<Volume> vols = volumes;
        if (volume < 0 || volume >= vols.size()) return Optional.empty();
        List<String> pages = vols.get(volume).pages();
        if (page < 0 || page >= pages.size()) return Optional.empty();
        return Optional.of(pages.get(page));
    }

    public static int totalPages() {
        return volumes.stream().mapToInt(v -> v.pages().size()).sum();
    }

    private AgreementManager() {}
}
