/* ==============================================================================
   UNIVERSAL-STREAMINGHALTERUNG FÜR SMARTPHONES (BRUSTGURT / CHEST MOUNT)
   ==============================================================================
   Professionelle CAD-Konstruktion in OpenSCAD
   © 2026 Kevin Bodenstein – eigene Konstruktion, Beispiel für AxisWright
   
   Haupteigenschaften:
   1. ERGONOMISCHE BRUSTPLATTE:
      - Integrierte Gurtführung für 25 mm Nackengurt (Doppelschlitz für direkte Klemmung)
      - Zusätzliche Seitenschlitze für optionalen Brust-Umlaufgurt
      - Anatomisch abgerundete Kanten für hohen Tragekomfort
   2. ZWEI VERSTELLBARE GELENKE (2-ACHSEN KINEMATIK):
      - Gelenk 1 (Brust -> Arm): Neigung nach vorne/oben (Elevation)
      - Gelenk 2 (Arm -> Smartphone-Halterung): Blickwinkel-Feinjustierung (POV-Winkel)
   3. FORMSCHLÜSSIGE RASTUNG (POSITIVE INDEXING / HIRTH-DETENTS):
      - 24 radiale Rastkerben (360° / 24 = exakt 15° Rastschritte)
      - Absolut verdrehsicher unter Erschütterungen beim Gehen/Laufen
   4. UNIVERSELLE SMARTPHONE-HALTERUNG:
      - Präzise Schwalbenschwanz-Führung (Dovetail) mit Klemmschraube
      - Universeller Spannbereich: 58 mm bis 98 mm (von iPhone Mini bis Max/Ultra mit Hülle)
      - 16 mm Aufnahmetiefe mit 15° Hinterschneidung (Herausrutschsicherung)
      - Integrierter Cold-Shoe-Blitzschuh für Streaming-Mikrofon oder Mini-LED-Leuchte!
      - Aussparung für USB-C Lade-/Audiokabel
      - Unterstützt LANDSCAPE (16:9 Querformat) und PORTRAIT (9:16 Hochformat)!
   5. 100% REINER 3D-DRUCK (KEINE ZUKAUFTEILE NÖTIG):
      - Eigene, saubere 3D-druckbare M12- und M10-Grobgewinde
      - Integrierte Verdrehsicherung für Schraubenköpfe (Einhand-Bedienung)
      - Große ergonomische Flügelmuttern für maximales Drehmoment von Hand
      - 100% Stützstruktur-freier Druck (Support-free auf Standard-FDM-Druckern)
   ============================================================================== */

// ==============================================================================
// 1. PARAMETER (OPENSCAD CUSTOMIZER)
// ==============================================================================

/* [Modus & Ansicht] */
// Was soll gerendert werden?
mode = "assembly"; // [assembly: Zusammenbau (Vorschau), print_layout: Alle Teile druckfertig auf Druckbett, chest_plate: 1. Brustplatte, arm: 2. Verbindungsarm, phone_base: 3. Smartphone-Grundträger, phone_slider: 4. Smartphone-Schieber, phone_bracket: 5. Smartphone-Gelenkadapter, joint_bolt: 6. Gelenkschraube M12 (x2), joint_nut: 7. Gelenk-Flügelmutter M12 (x2), clamp_screw: 8. Klemmschraube M10 (x2)]

// Dummy-Smartphone in der Zusammenbau-Vorschau anzeigen?
show_dummy_phone = true;

// Smartphone-Ausrichtung
phone_orientation = "landscape"; // [landscape: Querformat (16:9 YouTube / Twitch), portrait: Hochformat (9:16 TikTok / Instagram)]

/* [Gelenk-Winkel (15° Rast-Schritte)] */
// Winkel Gelenk 1 (Brustplatte -> Arm): Elevation nach vorne
joint1_angle = 30; // [-60:15:90]

// Winkel Gelenk 2 (Arm -> Smartphone): Tilt / Kamerawinkel
joint2_angle = 15; // [-90:15:90]

// Eingestellte Klemmbreite des Smartphones (mm)
phone_clamp_width = 76; // [58:1:98]

/* [Abmessungen Smartphone-Aufnahme] */
// Maximale Dicke des Geräts inkl. Schutzhülle (mm)
phone_max_thickness = 15.0;
// Breite der Grundplatte des Halters (mm)
phone_mount_width = 56.0;
// Hinterschnitt-Winkel der Haltenasen (Grad)
lip_angle = 15;
// Höhe der vorderen Sicherungslippe (mm)
lip_height = 5.0;

/* [Gurtband-Parameter] */
// Gurtband-Nennbreite (Standard: 25 mm)
strap_width = 25.0;
// Gurtband-Dicke (mm)
strap_thickness = 2.2;
// Schlitzbreite (inkl. Toleranz)
slot_w = strap_width + 2.5; // 27.5 mm
// Schlitzhöhe (inkl. Toleranz)
slot_h = strap_thickness + 1.8; // 4.0 mm

/* [Gelenk- & Rastungs-Geometrie] */
// Gelenk-Außendurchmesser (mm)
joint_outer_d = 24.0;
// Gelenk-Breite der Lasche (Gabelmaß innen: 13 mm, Lasche: 12.2 mm)
joint_slot_w = 13.0;
joint_tab_w = 12.2;
// Anzahl der Rastkerben (24 = 15° pro Schritt)
detent_teeth = 24;
// Tiefe der V-Rastkerben (mm)
detent_depth = 1.0;
// Gelenkschrauben-Nenndurchmesser (mm)
bolt_d = 12.0;
// Schrauben-Steigung (mm)
bolt_pitch = 3.0;
// Armlänge Mitte-Mitte (mm)
arm_length = 70.0;

/* [Schwalbenschwanz-Führung] */
dovetail_w_base = 24.0;
dovetail_w_top  = 30.0;
dovetail_h      = 6.0;

/* [Drucktoleranzen (FDM-optimiert)] */
// Allgemeine Spielpassung für Führungen (mm)
clearance = 0.35;
// Gewindespiel (radial) für sauberen Lauf (mm)
thread_clearance = 0.40;

/* [Rendering-Auflösung] */
$fn = 36;
$fs = 0.5;
$fa = 5;

// ==============================================================================
// 2. HILFSMODULE & GRUNDKÖRPER
// ==============================================================================

// 2D Abgerundetes Rechteck (blitzschnell und 100% sauber)
module rounded_rect(w, h, r) {
    hull() {
        translate([-w/2 + r, -h/2 + r]) circle(r=r);
        translate([ w/2 - r, -h/2 + r]) circle(r=r);
        translate([ w/2 - r,  h/2 - r]) circle(r=r);
        translate([-w/2 + r,  h/2 - r]) circle(r=r);
    }
}

// 3D Platte mit abgerundeten Ecken
module rounded_box(w, h, thick, r) {
    linear_extrude(height=thick, center=false)
        rounded_rect(w, h, r);
}

// FDM-optimiertes 3D-Grobgewinde (100% geschlossenes Polyeder)
module metric_thread(d=12, pitch=3, length=15, internal=false, clearance=0.4, fn=24) {
    eff_d = internal ? d + 2 * clearance : d;
    r_maj = eff_d / 2;
    depth = pitch * 0.45;
    r_min = r_maj - depth;
    
    turns = ceil(length / pitch) + 2;
    total_steps = turns * fn;
    dz = pitch / fn;
    da = 360 / fn;
    
    points = [
        for (i = [0:total_steps])
            let (a = i * da,
                 z = i * dz - pitch,
                 c = cos(a), s = sin(a))
            for (p = [
                [r_min - 0.5, -pitch * 0.35],
                [r_maj,        0.0],
                [r_min - 0.5,  pitch * 0.35]
            ])
            [ p[0] * c, p[0] * s, z + p[1] ]
    ];
    
    side_faces = [
        for (i = [0:total_steps-1])
            let (b0 = i * 3, b1 = (i+1) * 3)
            for (quad = [
                [b0+0, b1+0, b1+1, b0+1],
                [b0+1, b1+1, b1+2, b0+2],
                [b0+2, b1+2, b1+0, b0+0]
            ])
            for (tri = [ [quad[0], quad[1], quad[2]], [quad[0], quad[2], quad[3]] ])
            tri
    ];
    
    cap_start = [ [0, 1, 2] ];
    last = total_steps * 3;
    cap_end = [ [last+0, last+2, last+1] ];
    
    all_faces = concat(side_faces, cap_start, cap_end);
    
    intersection() {
        cylinder(r=r_maj + 2, h=length, $fn=fn);
        union() {
            cylinder(r=r_min, h=length, $fn=fn);
            polyhedron(points=points, faces=all_faces, convexity=10);
        }
    }
}

// Radiale Rastkerben (24 V-Nuten auf Kontaktfläche)
module detent_notches(r_in=6.2, r_out=12.0, teeth=24, depth=1.0) {
    da = 360 / teeth;
    len = r_out - r_in + 2;
    for (i = [0:teeth-1]) {
        rotate([0, 0, i * da])
            translate([r_in - 0.5 + len/2, 0, 0])
            rotate([45, 0, 0])
            cube([len, depth * sqrt(2), depth * sqrt(2)], center=true);
    }
}

// 4 passende radiale Raststege (greifen formschlüssig in die Rastkerben)
module detent_ribs(r_in=6.2, r_out=12.0, teeth=24, depth=0.9, num_ribs=4, base_overlap=0.4) {
    da = 360 / teeth;
    rib_step = teeth / num_ribs;
    len = r_out - r_in - 0.1;
    w = depth * sqrt(2);
    // Stege wachsen aus z=0 nach +z (Spitze bei z=depth), Profil = Dreieck passend zur V-Kerbe
    intersection() {
        translate([0, 0, -base_overlap]) cylinder(r=r_out - 0.1, h=depth + base_overlap, $fn=teeth*2);
        union() {
            for (k = [0:num_ribs-1]) {
                rotate([0, 0, k * rib_step * da])
                    translate([r_in + len/2, 0, 0])
                    rotate([45, 0, 0])
                    cube([len, w, w], center=true);
            }
        }
    }
}

// Cold-Shoe Zubehörschuh (Standard 18.5 x 18.5 mm für Mikrofon / Licht)
module cold_shoe_mount() {
    difference() {
        translate([0, 0, 2.5])
            cube([20, 18, 5], center=true);
        translate([0, 0, 3.5])
            cube([18.5, 19, 3.2], center=true);
        translate([0, 0, 4.5])
            cube([12.5, 19, 2], center=true);
        translate([0, 8.5, 3.5])
            cube([19, 2, 4], center=true);
    }
}

// ==============================================================================
// 3. BAUTEIL 1: ERGONOMISCHE BRUSTPLATTE
// ==============================================================================
module chest_plate() {
    plate_w = 126.0;
    plate_h = 86.0;
    plate_thick = 4.5;
    
    color([0.22, 0.25, 0.30])
    difference() {
        union() {
            // Grundplatte mit ergonomisch abgerundeten Konturen
            linear_extrude(height=plate_thick, center=false) {
                difference() {
                    hull() {
                        translate([-plate_w/2 + 18, -plate_h/2 + 18]) circle(r=18);
                        translate([ plate_w/2 - 18, -plate_h/2 + 18]) circle(r=18);
                        translate([ plate_w/2 - 20,  plate_h/2 - 16]) circle(r=16);
                        translate([-plate_w/2 + 20,  plate_h/2 - 16]) circle(r=16);
                    }
                    // Leichte anatomische Aussparung oben/unten
                    translate([0,  plate_h/2 + 28]) circle(r=35);
                    translate([0, -plate_h/2 - 28]) circle(r=35);
                }
            }
            
            // Verstärkungsrippen zum Gelenkbock
            translate([-18, -20, 0])
                cube([36, 40, plate_thick + 2.5]);
            
            // Gelenkbock (Gabelkopf für Gelenk 1)
            translate([0, 0, plate_thick]) {
                // Linke Gabelbacke (mit Sechskant-Tasche gegen Mitdrehen der Schraube)
                translate([-joint_slot_w/2 - 6.0, -joint_outer_d/2, 0])
                    cube([6.0, joint_outer_d, 22]);
                translate([-joint_slot_w/2 - 3.0, 0, 22])
                    rotate([0, 90, 0])
                    cylinder(r=joint_outer_d/2, h=6.0, center=true);
                    
                // Rechte Gabelbacke (mit 24 Rastkerben auf Innenseite)
                translate([joint_slot_w/2, -joint_outer_d/2, 0])
                    cube([6.0, joint_outer_d, 22]);
                translate([joint_slot_w/2 + 3.0, 0, 22])
                    rotate([0, 90, 0])
                    cylinder(r=joint_outer_d/2, h=6.0, center=true);
                    
                // Sockel-Ausrundungen für hohe Stabilität
                translate([-joint_slot_w/2 - 6.0, -18, 0])
                    cube([joint_slot_w + 12.0, 6, 12]);
                translate([-joint_slot_w/2 - 6.0, 12, 0])
                    cube([joint_slot_w + 12.0, 6, 12]);
            }
        }
        
        // --- GURTFÜHRUNG (25 mm Gurtband) ---
        // Obere Nackengurt-Schlitze (25° nach außen geneigt für perfekten Sitz am Hals/Nacken)
        for (side = [-1, 1]) {
            translate([side * 42, 24, -1])
                rotate([0, 0, -side * 25])
                rounded_box(slot_w, slot_h, plate_thick + 2, slot_h/2);
            translate([side * 45, 14, -1])
                rotate([0, 0, -side * 25])
                rounded_box(slot_w, slot_h, plate_thick + 2, slot_h/2);
                
            // Seitliche Schlitze für optionalen Brust-Umlaufgurt
            translate([side * 48, -18, -1])
                rotate([0, 0, 0])
                rounded_box(slot_w, slot_h, plate_thick + 2, slot_h/2);
        }
        
        // --- GELENK-DURCHGANGSBOHRUNG & RASTUNG ---
        translate([0, 0, plate_thick + 22]) {
            rotate([0, 90, 0])
                cylinder(r=(bolt_d + 0.5)/2, h=50, center=true);
                
            // Linke Backe: Sechskant-Tasche für Schraubenkopf (SW 19 mm, Tiefe 4.5 mm)
            translate([-joint_slot_w/2 - 6.0 - 0.1, 0, 0])
                rotate([0, 90, 0])
                cylinder(r=11.2, h=5.0, $fn=6);
                
            // Rechte Backe (Innenseite): 24 Rastkerben für 15°-Schritte
            translate([joint_slot_w/2, 0, 0])
                rotate([0, 90, 0])
                detent_notches(r_in=6.4, r_out=joint_outer_d/2, teeth=detent_teeth, depth=detent_depth);
        }
    }
}

// ==============================================================================
// 4. BAUTEIL 2: VERBINDUNGSARM (ARTICULATION ARM)
// ==============================================================================
module articulation_arm() {
    color([0.80, 0.35, 0.15])
    difference() {
        union() {
            // --- GELENK 1 (UNTEN): LASCHE (FITS INTO CHEST FORK) ---
            translate([0, 0, 0])
                rotate([0, 90, 0])
                cylinder(r=joint_outer_d/2, h=joint_tab_w, center=true);
                
            // 4 formschlüssige Raststege auf der rechten Stirnseite
            translate([joint_tab_w/2, 0, 0])
                rotate([0, 90, 0])
                detent_ribs(r_in=6.4, r_out=joint_outer_d/2, teeth=detent_teeth, depth=detent_depth, num_ribs=4, base_overlap=0.4);
                
            // --- ARMKÖRPER ---
            hull() {
                translate([0, 0, 0])
                    rotate([0, 90, 0])
                    cylinder(r=joint_outer_d/2, h=joint_tab_w, center=true);
                translate([0, arm_length, 0])
                    rotate([0, 90, 0])
                    cylinder(r=joint_outer_d/2, h=joint_slot_w + 12.0, center=true);
            }
        }
        
        // --- BOHRUNGEN & RASTUNGEN ---
        // Gelenk 1 (Unten): Durchgangsbohrung 12.5 mm
        rotate([0, 90, 0])
            cylinder(r=(bolt_d + 0.5)/2, h=40, center=true);
            
        // Gelenk 2 (Oben): U-förmiger Gabelschlitz (13 mm lichte Weite)
        translate([0, arm_length, 0]) {
            hull() {
                rotate([0, 90, 0])
                    cylinder(r=joint_outer_d/2 + 0.5, h=joint_slot_w, center=true);
                translate([0, joint_outer_d, 0])
                    rotate([0, 90, 0])
                    cylinder(r=joint_outer_d/2 + 0.5, h=joint_slot_w, center=true);
            }
            
            // Durchgangsbohrung 12.5 mm
            rotate([0, 90, 0])
                cylinder(r=(bolt_d + 0.5)/2, h=50, center=true);
                
            // Sechskant-Tasche an der linken Backe außen (für Schraubenkopf)
            translate([-joint_slot_w/2 - 6.0 - 0.1, 0, 0])
                rotate([0, 90, 0])
                cylinder(r=11.2, h=5.0, $fn=6);
                
            // 24 Rastkerben an der rechten Backe innen
            translate([joint_slot_w/2, 0, 0])
                rotate([0, 90, 0])
                detent_notches(r_in=6.4, r_out=joint_outer_d/2, teeth=detent_teeth, depth=detent_depth);
        }
        
        // Gewichts- & Versteifungstaschen im Mittelarm
        for (y_off = [20, 42]) {
            translate([0, y_off, 0])
                rotate([0, 90, 0])
                cylinder(r=5.5, h=joint_tab_w + 20, center=true);
        }
    }
}

// ==============================================================================
// 5. BAUTEIL 3: SMARTPHONE-GRUNDTRÄGER (PHONE BASE)
// ==============================================================================
module phone_base() {
    base_h = 82.0;
    shelf_depth = phone_max_thickness + 3.0; // 18 mm
    jaw_w = 68.0;
    socket_y = 26.0; // Position der Adapteraufnahme nahe der Basis
    
    color([0.25, 0.45, 0.65])
    difference() {
        union() {
            // Basisplatte hinten (liegt im Druckbett-Layout 100% flach auf der Rückseite)
            translate([0, base_h/2, 2.0])
                cube([phone_mount_width, base_h, 4.0], center=true);
                
            // Schwalbenschwanz-Schiene (Dovetail Rail)
            translate([0, base_h/2, 4.0])
                linear_extrude(height=dovetail_h, scale=[dovetail_w_top / dovetail_w_base, 1.0])
                square([dovetail_w_base, base_h], center=true);
                
            // Untere Auflagebacke (Bottom Shelf)
            translate([0, 0, 0]) {
                translate([0, 4.0, shelf_depth/2 + 2.0])
                    cube([jaw_w, 8.0, shelf_depth], center=true);
                translate([0, 0.0, shelf_depth + 2.0])
                    rotate([lip_angle, 0, 0])
                    cube([jaw_w, 4.0, lip_height * 2], center=true);
            }
            
            // Adapter-Aufnahmesockel auf der Rückseite (quadratische 4-Punkt Aufnahme)
            translate([0, socket_y, -2.0])
                cube([30.0, 30.0, 4.0], center=true);
        }
        
        // USB-C / Ladekabel & Mikrofon-Aussparung
        translate([0, 0, shelf_depth/2 + 2.0])
            cube([22.0, 20.0, shelf_depth + 10], center=true);
            
        // Rutschhemmende Rillen
        for (z_rib = [15:8:75]) {
            translate([0, z_rib, 4.0 + dovetail_h])
                cube([dovetail_w_top + 2, 1.2, 0.8], center=true);
        }
        
        // Quadratische Passungsaufnahme für den Gelenkadapter (Landscape & Portrait)
        translate([0, socket_y, -1.5])
            cube([26.0 + clearance*2, 26.0 + clearance*2, 5.0], center=true);
            
        // Zentrales M10 Innengewinde zur Befestigung des Adapters
        translate([0, socket_y, -5.0])
            metric_thread(d=10.0, pitch=2.5, length=12.0, internal=true, clearance=thread_clearance, fn=24);
            
        // Schutzausrundungen
        for (sx = [-1, 1]) {
            translate([sx * (jaw_w/2), 0, shelf_depth + 4])
                rotate([0, 45, 0])
                cube([6, 20, 6], center=true);
        }
    }
}

// ==============================================================================
// 6. BAUTEIL 4: SMARTPHONE-SCHIEBER (TOP SLIDER MIT SCHWALBENSCHWANZ)
// ==============================================================================
module phone_slider() {
    slider_h = 28.0;
    shelf_depth = phone_max_thickness + 3.0; // 18 mm
    jaw_w = 68.0;
    
    color([0.25, 0.60, 0.45])
    difference() {
        union() {
            // Führungskörper
            translate([0, 0, 4.0 + dovetail_h/2 + 2.0])
                cube([dovetail_w_top + 14.0, slider_h, dovetail_h + 8.0], center=true);
                
            // Obere Greifbacke
            translate([0, slider_h/2 - 4.0, shelf_depth/2 + 2.0])
                cube([jaw_w, 8.0, shelf_depth], center=true);
                
            // Vordere Sicherungslippe
            translate([0, slider_h/2 - 0.0, shelf_depth + 2.0])
                rotate([-lip_angle, 0, 0])
                cube([jaw_w, 4.0, lip_height * 2], center=true);
                
            // Integrierter Cold-Shoe Blitzschuh
            translate([0, slider_h/2 - 4.0, shelf_depth + 6.0])
                cold_shoe_mount();
        }
        
        // Innere Schwalbenschwanz-Nut (mit Spielpassung)
        translate([0, 0, 4.0 - 0.1])
            linear_extrude(height=dovetail_h + 0.4, scale=[(dovetail_w_top + clearance*2) / (dovetail_w_base + clearance*2), 1.0])
            square([dovetail_w_base + clearance*2, slider_h + 4], center=true);
            
        // Freischnitt für Rückenplatte
        translate([0, 0, 1.0])
            cube([phone_mount_width + clearance*2, slider_h + 4, 4.0 + clearance], center=true);
            
        // Gewindebohrung für Klemmschraube M10
        translate([0, 0, 10.0])
            metric_thread(d=10.0, pitch=2.5, length=12.0, internal=true, clearance=thread_clearance, fn=24);
            
        // Schutzausrundungen
        for (sx = [-1, 1]) {
            translate([sx * (jaw_w/2), slider_h/2, shelf_depth + 4])
                rotate([0, 45, 0])
                cube([6, 20, 6], center=true);
        }
    }
}

// ==============================================================================
// 7. BAUTEIL 5: SMARTPHONE-GELENKADAPTER (DUAL-ORIENTATION BRACKET)
// ==============================================================================
// Positioniert die Smartphone-Halterung nach VORNE (+Z) und OBEN (+Y),
// sodass das Smartphone niemals mit dem Arm kollidiert!
module phone_bracket() {
    bracket_offset_z = 20.0;
    bracket_offset_y = 14.0;
    
    color([0.30, 0.50, 0.75])
    difference() {
        union() {
            // Gelenk 2 Lasche bei [0, 0, 0]
            rotate([0, 90, 0])
                cylinder(r=joint_outer_d/2, h=joint_tab_w, center=true);
                
            // 4 formschlüssige Raststege auf rechter Stirnseite
            translate([joint_tab_w/2, 0, 0])
                rotate([0, 90, 0])
                detent_ribs(r_in=6.4, r_out=joint_outer_d/2, teeth=detent_teeth, depth=detent_depth, num_ribs=4, base_overlap=0.4);
                
            // Stabiler Auslegerhals nach vorne (+Z) und leicht nach oben (+Y)
            // (Hals nur so breit wie die Lasche, damit er nicht mit den Gabelbacken des Arms kollidiert)
            hull() {
                rotate([0, 90, 0])
                    cylinder(r=joint_outer_d/2, h=joint_tab_w, center=true);
                translate([0, bracket_offset_y, bracket_offset_z - 3.0])
                    cube([joint_tab_w, 30.0, 6.0], center=true);
            }
            // Montageflansch (breiter als der Hals, liegt vor den Gabelbacken)
            translate([0, bracket_offset_y, bracket_offset_z - 3.0])
                cube([30.0, 30.0, 6.0], center=true);
            
            // Quadratischer Montagezapfen (26x26x4 mm)
            translate([0, bracket_offset_y, bracket_offset_z + 2.0])
                cube([26.0, 26.0, 4.0], center=true);
            translate([0, bracket_offset_y, bracket_offset_z])
                cube([32.0, 32.0, 2.0], center=true);
        }
        
        // Gelenk 2 Achsbohrung (12.5 mm für M12 Schraube)
        rotate([0, 90, 0])
            cylinder(r=(bolt_d + 0.5)/2, h=30, center=true);
            
        // Zentrales Durchgangsloch für M10 Klemmschraube
        translate([0, bracket_offset_y, -5])
            cylinder(r=(10.0 + 0.8)/2, h=bracket_offset_z + 15, $fn=24);
        // Versenkung für Schraubenkopf von hinten
        translate([0, bracket_offset_y, 0])
            cylinder(r=16/2, h=bracket_offset_z, $fn=24);
    }
}

// ==============================================================================
// 8. BAUTEIL 6: GELENKSCHRAUBE M12 (2X BENÖTIGT)
// ==============================================================================
module joint_bolt() {
    thread_len = 36.0;
    head_d = 28.0;
    hex_sw = 18.5; // Passt in Sechskant-Tasche der Gabel
    
    color([0.85, 0.70, 0.10])
    union() {
        cylinder(r=head_d/2, h=5.0, $fn=36);
        for (i = [0:18]) {
            rotate([0, 0, i * (360/18)])
                translate([head_d/2, 0, 2.5])
                cylinder(r=1.2, h=5.0, center=true, $fn=12);
        }
        
        // Sechskant-Verdrehsicherung
        translate([0, 0, 5.0])
            cylinder(r=(hex_sw/sqrt(3)), h=4.0, $fn=6);
            
        // M12 Grobgewinde
        translate([0, 0, 9.0])
            metric_thread(d=bolt_d, pitch=bolt_pitch, length=thread_len, internal=false, clearance=0, fn=24);
            
        // Fase an der Spitze
        translate([0, 0, 9.0 + thread_len])
            cylinder(r1=bolt_d/2, r2=(bolt_d/2 - 1.5), h=2.0, $fn=24);
    }
}

// ==============================================================================
// 9. BAUTEIL 7: GELENK-FLÜGELMUTTER M12 (2X BENÖTIGT)
// ==============================================================================
module joint_nut() {
    nut_h = 13.0;
    collar_d = 23.0;
    wing_span = 44.0;
    
    color([0.85, 0.70, 0.10])
    difference() {
        union() {
            cylinder(r=collar_d/2, h=4.0, $fn=36);
            hull() {
                cylinder(r=14/2, h=nut_h, $fn=24);
                translate([-wing_span/2 + 6, 0, nut_h/2])
                    cylinder(r=5.0, h=nut_h, center=true, $fn=24);
                translate([ wing_span/2 - 6, 0, nut_h/2])
                    cylinder(r=5.0, h=nut_h, center=true, $fn=24);
            }
            for (side = [-1, 1]) {
                translate([side * 14, 0, nut_h])
                    sphere(r=3.0, $fn=18);
            }
        }
        
        translate([0, 0, -1])
            metric_thread(d=bolt_d, pitch=bolt_pitch, length=nut_h + 2, internal=true, clearance=thread_clearance, fn=24);
            
        translate([0, 0, -0.1])
            cylinder(r1=bolt_d/2 + 1.2, r2=bolt_d/2 - 1.0, h=2.2, $fn=24);
    }
}

// ==============================================================================
// 10. BAUTEIL 8: KLEMMSCHRAUBE M10 (2X BENÖTIGT: 1X SCHIEBER, 1X ADAPTER)
// ==============================================================================
module clamp_screw() {
    knob_d = 22.0;
    thread_len = 14.0;
    
    color([0.85, 0.70, 0.10])
    union() {
        cylinder(r=knob_d/2, h=6.0, $fn=30);
        for (i = [0:15]) {
            rotate([0, 0, i * (360/15)])
                translate([knob_d/2, 0, 3.0])
                cylinder(r=1.0, h=6.0, center=true, $fn=10);
        }
        
        translate([0, 0, 6.0])
            metric_thread(d=10.0, pitch=2.5, length=thread_len, internal=false, clearance=0, fn=24);
            
        translate([0, 0, 6.0 + thread_len])
            cylinder(r=4.0, h=1.5, $fn=24);
    }
}

// ==============================================================================
// 11. DUMMY-SMARTPHONE (VORSCHAU)
// ==============================================================================
module dummy_phone(phone_w=160, phone_h=76, thick=9.5) {
    color([0.15, 0.15, 0.18, 0.70]) {
        difference() {
            translate([0, phone_h/2, thick/2])
                linear_extrude(height=thick, center=true)
                rounded_rect(phone_w, phone_h, 10);
                
            // Bildschirm-Vertiefung
            translate([0, phone_h/2, -thick/2 + 0.4])
                linear_extrude(height=1.0, center=true)
                rounded_rect(phone_w - 8, phone_h - 6, 8);
        }
        
        // Kamera-Insel (zeigt nach vorne in Aufnahmerichtung!)
        translate([-phone_w/2 + 22, phone_h - 22, thick + 1.2])
            rounded_box(32, 32, 2.5, 6);
    }
}

// ==============================================================================
// 12. ZUSAMMENBAU (ASSEMBLY VIEW)
// ==============================================================================
module full_assembly() {
    // 1. Brustplatte (liegt in der Grundebene Z=0)
    chest_plate();
    
    // Gelenk 1 Koordinatenursprung (bei Z = 26.5)
    translate([0, 0, 4.5 + 22]) {
        translate([-joint_slot_w/2 - 10.0, 0, 0])
            rotate([0, 90, 0])
            joint_bolt();
            
        translate([joint_slot_w/2 + 6.0 + 1.0, 0, 0])
            rotate([0, 90, 0])
            joint_nut();
            
        // 2. Verbindungsarm (neigt sich nach vorne/oben)
        rotate([joint1_angle, 0, 0]) {
            articulation_arm();
            
            // Gelenk 2 Koordinatenursprung (am oberen Ende des Arms bei Y = 70)
            translate([0, arm_length, 0]) {
                translate([-joint_slot_w/2 - 10.0, 0, 0])
                    rotate([0, 90, 0])
                    joint_bolt();
                    
                translate([joint_slot_w/2 + 6.0 + 1.0, 0, 0])
                    rotate([0, 90, 0])
                    joint_nut();
                    
                // Gelenk 2 Neigung (nach vorne geneigt für optimalen POV-Winkel)
                rotate([joint2_angle, 0, 0]) {
                    // Gelenkadapter sitzt in Gelenk 2
                    phone_bracket();
                    
                    // Befestigungsschraube M10 von hinten in den Adapter
                    translate([0, 14.0, 0])
                        rotate([0, 0, 0])
                        clamp_screw();
                    
                    // Smartphone-Halterung auf der nach VORNE (+Z) ragenden Flanschplatte
                    bracket_offset_z = 20.0;
                    bracket_offset_y = 14.0;
                    socket_y = 26.0;
                    
                    translate([0, bracket_offset_y, bracket_offset_z + 4.0]) {
                        
                        if (phone_orientation == "landscape") {
                            // --- QUERFORMAT (16:9) ---
                            // Halterung erstreckt sich nach oben (+Y)
                            translate([0, -socket_y, 0]) {
                                phone_base();
                                
                                // Oberer Schieber
                                translate([0, phone_clamp_width, 0]) {
                                    phone_slider();
                                    
                                    // Klemmschraube für Schieber
                                    translate([0, 0, 22.0])
                                        rotate([180, 0, 0])
                                        clamp_screw();
                                }
                                
                                // Dummy-Smartphone (Querformat: 160 mm breit, 76 mm hoch)
                                if (show_dummy_phone) {
                                    translate([0, 0, 4.0 + dovetail_h])
                                        dummy_phone(phone_w=160, phone_h=phone_clamp_width, thick=phone_max_thickness - 3.0);
                                }
                            }
                        } else {
                            // --- HOCHFORMAT (9:16) ---
                            // Halterung um 90° gedreht, klemmt das Smartphone von links und rechts
                            rotate([0, 0, 90])
                            translate([0, -socket_y, 0]) {
                                phone_base();
                                
                                translate([0, phone_clamp_width, 0]) {
                                    phone_slider();
                                    
                                    translate([0, 0, 22.0])
                                        rotate([180, 0, 0])
                                        clamp_screw();
                                }
                            }
                            
                            // Dummy-Smartphone (Hochformat: 76 mm breit entlang X, 160 mm hoch entlang Y)
                            // Steht aufrecht nach OBEN (+Y) vor dem Arm, KEINE Kollision!
                            if (show_dummy_phone) {
                                translate([0, 0, 4.0 + dovetail_h + 10.0])
                                color([0.15, 0.15, 0.18, 0.75]) {
                                    difference() {
                                        // Smartphone-Körper steht aufrecht von Y=-15 bis Y=145
                                        translate([0, 60, 0])
                                            linear_extrude(height=phone_max_thickness - 3.0, center=true)
                                            rounded_rect(phone_clamp_width, 160, 10);
                                            
                                        // Display vertieft
                                        translate([0, 60, (phone_max_thickness - 3.0)/2 - 0.4])
                                            linear_extrude(height=1.0, center=true)
                                            rounded_rect(phone_clamp_width - 6, 150, 8);
                                    }
                                    // Kamera-Insel oben auf der Rückseite (zeigt nach vorne)
                                    translate([-phone_clamp_width/2 + 20, 125, -(phone_max_thickness - 3.0)/2 - 1.2])
                                        rounded_box(30, 30, 2.5, 6);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==============================================================================
// 13. DRUCKBETT-LAYOUT (PRINT LAYOUT FÜR DEN DIREKTEN 3D-DRUCK)
// ==============================================================================
module print_layout() {
    echo("Generiere optimiertes Druckbett-Layout (220x220 mm Bauraum, 100% ohne Stützen)");
    
    // 1. Brustplatte (flach auf Rückseite im oberen Bereich zentriert)
    translate([0, 50, 0])
        chest_plate();
        
    // 2. Verbindungsarm (flach auf Seite liegend, links neben Brustplatte)
    translate([-86, 50, joint_tab_w/2])
        rotate([0, -90, 0])
        articulation_arm();
        
    // 3. Smartphone-Grundträger (flach auf Rückseite im rechten unteren Bereich)
    translate([48, -42, 0])
        phone_base();
        
    // 4. Smartphone-Schieber (flach liegend im mittleren linken Bereich)
    translate([-36, -42, 0])
        phone_slider();
        
    // 5. Smartphone-Gelenkadapter (flach auf 30x30 mm Montageflansch)
    translate([-36, -6, 24.0])
        rotate([180, 0, 0])
        translate([0, -14.0, 0])
        phone_bracket();
        
    // 6. Zwei Gelenkschrauben M12 (aufrecht auf Schraubenkopf, ganz unten links)
    translate([-65, -85, 0])
        joint_bolt();
    translate([-25, -85, 0])
        joint_bolt();
        
    // 7. Zwei Gelenk-Flügelmuttern M12 (flach auf Bund, ganz unten rechts)
    translate([20, -85, 0])
        joint_nut();
    translate([68, -85, 0])
        joint_nut();
        
    // 8. Zwei Klemmschrauben M10 (aufrecht auf Rändelkopf, ganz rechts außen)
    translate([95, -42, 0])
        clamp_screw();
    translate([95, -10, 0])
        clamp_screw();
}

// ==============================================================================
// 14. HAUPTAUSWAHL (STEUERUNG DURCH DIE VARIABLE 'mode')
// ==============================================================================
if (mode == "assembly") {
    full_assembly();
} else if (mode == "print_layout") {
    print_layout();
} else if (mode == "chest_plate") {
    chest_plate();
} else if (mode == "arm") {
    articulation_arm();
} else if (mode == "phone_base") {
    phone_base();
} else if (mode == "phone_slider") {
    phone_slider();
} else if (mode == "phone_bracket") {
    phone_bracket();
} else if (mode == "joint_bolt") {
    joint_bolt();
} else if (mode == "joint_nut") {
    joint_nut();
} else if (mode == "clamp_screw") {
    clamp_screw();
}
