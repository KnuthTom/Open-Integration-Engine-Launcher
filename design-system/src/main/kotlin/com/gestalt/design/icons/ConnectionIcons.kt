/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.icons

/**
 * The user-pickable icon library for connections / connection groups — a pathology-lab set drawn from
 * [Health Icons](https://healthicons.org) (CC0), outline style to sit beside the Lucide chrome icons.
 * Lean lab / diagnostic glyphs (microscope, specimens, blood work, cells, DNA, lab, data) rather than
 * general clinical care. Recolorable from the color tokens.
 *
 * A connection persists only the semantic [key]; resolve it back with [byKey].
 */
enum class ConnectionIcon(val key: String, val label: String) {
    Microscope("microscope", "Microscope"),
    MicroscopeSpecimen("microscope-specimen", "Microscope + specimen"),
    Specimen("specimen", "Specimen sample"),
    TestTubes("test-tubes", "Test tubes"),
    UrineSample("urine-sample", "Sample container"),
    BloodBag("blood-bag", "Blood bag"),
    BloodDrop("blood-drop", "Blood drop"),
    BloodCells("blood-cells", "Blood cells"),
    CellNuclei("cell-nuclei", "Cell nuclei"),
    CancerCells("cancer-cells", "Cancerous cells"),
    Dna("dna", "DNA"),
    Bacteria("bacteria", "Bacteria"),
    Tissue("tissue", "Tissue"),
    Biopsy("biopsy", "Biopsy"),
    HematologyLab("hematology-lab", "Hematology lab"),
    BiochemistryLab("biochemistry-lab", "Biochemistry lab"),
    LabSearch("lab-search", "Lab search"),
    Database("database", "Data / server"),
    Virus("virus", "Virus");

    /** Classpath-relative location of the bundled SVG. */
    val resourcePath: String get() = "icons/connection/$key.svg"

    companion object {
        /** All connection icons, in display order — listable for the picker grid. */
        val all: List<ConnectionIcon> = entries.toList()

        /** The default glyph for a new connection. */
        val default: ConnectionIcon = Microscope

        fun byKey(key: String): ConnectionIcon? = entries.firstOrNull { it.key == key }
    }
}
