package com.misfinanzas.service.economy;

import java.util.Arrays;
import java.util.Optional;

/**
 * Spain and its autonomous communities, as each source names them. The INE names them in its series ("Cataluña.
 * Índice general..."), the Ministry's fuel prices number them — and not in the INE's order: for the Ministry 07
 * is Castilla-La Mancha and 08 Castilla y León, the other way round — so both are written out here.
 */
public enum Region {
    ES("España", "Nacional", null),
    AN("Andalucía", "Andalucía", "01"),
    AR("Aragón", "Aragón", "02"),
    AS("Asturias", "Asturias, Principado de", "03"),
    IB("Illes Balears", "Balears, Illes", "04"),
    CN("Canarias", "Canarias", "05"),
    CB("Cantabria", "Cantabria", "06"),
    CM("Castilla-La Mancha", "Castilla - La Mancha", "07"),
    CL("Castilla y León", "Castilla y León", "08"),
    CT("Cataluña", "Cataluña", "09"),
    VC("Comunitat Valenciana", "Comunitat Valenciana", "10"),
    EX("Extremadura", "Extremadura", "11"),
    GA("Galicia", "Galicia", "12"),
    MD("Madrid", "Madrid, Comunidad de", "13"),
    MC("Murcia", "Murcia, Región de", "14"),
    NC("Navarra", "Navarra, Comunidad Foral de", "15"),
    PV("País Vasco", "País Vasco", "16"),
    RI("La Rioja", "Rioja, La", "17"),
    CE("Ceuta", "Ceuta", "18"),
    ML("Melilla", "Melilla", "19");

    private final String label;
    private final String ineName;
    private final String fuelId;

    Region(String label, String ineName, String fuelId) {
        this.label = label;
        this.ineName = ineName;
        this.fuelId = fuelId;
    }

    public String label() {
        return label;
    }

    /** Some INE tables call Spain "Nacional", others "Total Nacional". */
    public static Optional<Region> fromIneName(String name) {
        if ("Total Nacional".equals(name)) return Optional.of(ES);
        return Arrays.stream(values()).filter(r -> r.ineName.equals(name)).findFirst();
    }

    public static Optional<Region> fromFuelId(String id) {
        return Arrays.stream(values()).filter(r -> id != null && id.equals(r.fuelId)).findFirst();
    }
}
