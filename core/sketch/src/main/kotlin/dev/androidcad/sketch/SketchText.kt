package dev.androidcad.sketch

/**
 * Schlüssel der nutzersichtbaren Meldungen dieses Moduls (in [dev.androidcad.text.Msg]); den Wortlaut liefert die App
 * (`app/…/CoreText.kt`, `strings_core.xml`). Werte in Klammern = Argumente der Meldung.
 */
public enum class SketchText {
    LINE_NO_LENGTH, LINE_DELETED, CIRCLE_DELETED, ARC_DELETED, INTERSECTIONS_COINCIDE, TRIM_CIRCLE_NEEDS_TWO,
    END_CONNECTED, NOTHING_TO_EXTEND_TO, DISTANCE_NOT_POSITIVE, OFFSET_EXCEEDS_RADIUS, CHAIN_LINE_NO_LENGTH,
    RADIUS_NOT_POSITIVE, CORNER_NEEDS_TWO_LINES, A_LINE_NO_LENGTH, LINES_COLLINEAR, RADIUS_TOO_LARGE, CHAMFER_TOO_LARGE,
    SELECT_SHAPE_FOR_ORIGIN, SHAPE_ALREADY_AT_ORIGIN, CIRCLE_ALREADY_AT_ORIGIN, SELECT_SHAPE_TO_CENTER, SHAPE_NO_EXTENT,
    EDGE_NO_LENGTH, FACE_NO_EXTENT, NOT_POSSIBLE, CENTERED_ON_EDGE,
    NOTHING_TO_MIRROR, MIRROR_AXIS_NO_LENGTH, NOTHING_TO_PATTERN, COUNT_AT_LEAST_2, SPACING_NOT_POSITIVE, DIRECTION_MISSING,
    ANGLE_RANGE_360, CENTER_ONLY_ELEMENT,

    /** Plural (Anzahl Kopien). */
    LINEAR_COPIES,

    /** Plural (Anzahl Kopien). */
    CIRCULAR_COPIES,

    /** (Höchstzahl) */
    MAX_INSTANCES,

    /** Plural (Anzahl). */
    PATTERN_ALREADY_HAS,
    PATTERN_SOURCE_INCOMPLETE,

    /** Plural (ergänzt, jetzt). */
    INSTANCES_ADDED,

    /** Plural (entfernt, jetzt). */
    INSTANCES_REMOVED,
    TAP_BETWEEN_LINE_ENDS, TAP_BETWEEN_ARC_ENDS, TAP_ON_CIRCLE,

    SCALE_NOT_POSITIVE,
    NOTHING_TO_TRANSFORM,

    /** Plural (Anzahl angepasster Bedingungen). */
    CONSTRAINTS_ADAPTED,

    /** Plural (Anzahl entfernter Bedingungen). */
    CONSTRAINTS_REMOVED,

    /** Plural (Anzahl Kopien). */
    COPIES_CREATED,

    /** (x, y) */
    OPEN_END,

    /** (x, y) */
    BRANCH,
    CONTOUR_INCOMPLETE,
}
