package de.Minefalo.economySystem.gui;

import de.Minefalo.economySystem.storage.AccountData;

import java.util.ArrayList;
import java.util.List;

/**
 * Zustand einer laufenden Eingabe. Die Eingaben eines Items werden
 * nacheinander in Regler-, Amboss- oder Spielerauswahl-Inventaren
 * abgefragt; die Antworten ersetzen anschließend {@code {input1}} usw.
 */
final class GuiSession {

    private final List<GuiConfig.Input> inputs;
    private final List<String> commands;
    private final String originMenu;
    private final List<String> answers = new ArrayList<>();

    private int index;
    private long value;
    private Picker picker;

    GuiSession(List<GuiConfig.Input> inputs, List<String> commands, String originMenu) {
        this.inputs = inputs;
        this.commands = commands;
        this.originMenu = originMenu;
        this.value = inputs.isEmpty() ? 0 : inputs.get(0).initial();
    }

    GuiConfig.Input current() {
        return inputs.get(index);
    }

    boolean finished() {
        return index >= inputs.size();
    }

    /**
     * Übernimmt eine Antwort und schaltet zur nächsten Eingabe weiter.
     */
    void answer(String answer) {
        answers.add(answer);
        index++;
        if (!finished()) {
            value = current().initial();
        }
    }

    List<String> answers() {
        return List.copyOf(answers);
    }

    List<String> commands() {
        return commands;
    }

    String originMenu() {
        return originMenu;
    }

    long value() {
        return value;
    }

    void value(long value) {
        this.value = value;
    }

    Picker picker() {
        return picker;
    }

    Picker startPicker(GuiConfig.Input input) {
        this.picker = new Picker(input.filter(), input.sort());
        return picker;
    }

    /**
     * Suchtext, Filter, Sortierung und Seiten der Spielerauswahl.
     */
    static final class Picker {

        private final GuiConfig.Input.Filter defaultFilter;
        private final GuiConfig.Input.Sort defaultSort;

        private GuiConfig.Input.Filter filter;
        private GuiConfig.Input.Sort sort;
        private String search = "";
        private int page;
        private List<AccountData> accounts = List.of();
        private boolean loading = true;
        private boolean loadingStarted;

        Picker(GuiConfig.Input.Filter filter, GuiConfig.Input.Sort sort) {
            this.defaultFilter = filter;
            this.defaultSort = sort;
            this.filter = filter;
            this.sort = sort;
        }

        GuiConfig.Input.Filter filter() {
            return filter;
        }

        void filter(GuiConfig.Input.Filter filter) {
            this.filter = filter;
        }

        GuiConfig.Input.Filter defaultFilter() {
            return defaultFilter;
        }

        GuiConfig.Input.Sort sort() {
            return sort;
        }

        void sort(GuiConfig.Input.Sort sort) {
            this.sort = sort;
        }

        GuiConfig.Input.Sort defaultSort() {
            return defaultSort;
        }

        String search() {
            return search;
        }

        void search(String search) {
            this.search = search == null ? "" : search.trim();
        }

        int page() {
            return page;
        }

        void page(int page) {
            this.page = Math.max(0, page);
        }

        List<AccountData> accounts() {
            return accounts;
        }

        void accounts(List<AccountData> accounts) {
            this.accounts = accounts == null ? List.of() : List.copyOf(accounts);
        }

        boolean loading() {
            return loading;
        }

        void loading(boolean loading) {
            this.loading = loading;
        }

        boolean loadingStarted() {
            return loadingStarted;
        }

        void loadingStarted(boolean loadingStarted) {
            this.loadingStarted = loadingStarted;
        }
    }
}
