package dev.androidcad.app

/** Beispielprojekt, das beim ersten Start geladen wird. */
const val DEFAULT_SOURCE = """/* [Maße] */
// Breite der Box (mm)
width = 60; // [20:5:120]
// Tiefe der Box (mm)
depth = 40; // [20:5:100]
// Höhe der Box (mm)
height = 25; // [10:1:60]
// Wandstärke (mm)
wall = 3; // [1:0.5:8]

/* [Bohrungen] */
// Bohrungen im Boden anzeigen
holes = true;
// Durchmesser der Bohrungen (mm)
hole_d = 6; // [2:1:20]
// Anzahl der Bohrungen
hole_count = 2; // [1:1:5]

${'$'}fn = 48;

module holes_cutout() {
    for (i = [1:hole_count])
        translate([width * i / (hole_count + 1), depth / 2, -1])
            cylinder(d = hole_d, h = wall + 2);
}

difference() {
    cube([width, depth, height]);
    translate([wall, wall, wall]) cube([width - 2 * wall, depth - 2 * wall, height]);
    if (holes) holes_cutout();
}
"""
