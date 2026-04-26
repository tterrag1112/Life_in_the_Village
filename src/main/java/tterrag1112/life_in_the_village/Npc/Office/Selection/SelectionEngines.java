package tterrag1112.life_in_the_village.Npc.Office.Selection;

import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;

import java.util.EnumMap;
import java.util.Map;

/**
 * Static lookup from {@link SelectionMethod} → engine instance. Engines
 * are stateless (the {@link OfficeSelectionContext} carries everything
 * they need), so a single shared instance per method suffices.
 */
public final class SelectionEngines {

    private static final Map<SelectionMethod, OfficeSelectionEngine> ENGINES = build();

    private SelectionEngines() {}

    public static OfficeSelectionEngine forMethod(SelectionMethod method) {
        OfficeSelectionEngine e = ENGINES.get(method);
        if (e == null) throw new IllegalStateException("No engine for " + method);
        return e;
    }

    private static Map<SelectionMethod, OfficeSelectionEngine> build() {
        Map<SelectionMethod, OfficeSelectionEngine> m = new EnumMap<>(SelectionMethod.class);
        m.put(SelectionMethod.MERITOCRATIC, new MeritocraticSelection());
        m.put(SelectionMethod.ASCENSION,    new AscensionSelection());
        m.put(SelectionMethod.COUNCIL,      new CouncilSelection());
        m.put(SelectionMethod.ELECTIVE,     new ElectiveSelection());
        m.put(SelectionMethod.HEREDITARY,   new HereditarySelection());
        m.put(SelectionMethod.APPOINTED,    new AppointedSelection());
        m.put(SelectionMethod.DICTATORIAL,  new DictatorialSelection());
        return Map.copyOf(m);
    }
}
