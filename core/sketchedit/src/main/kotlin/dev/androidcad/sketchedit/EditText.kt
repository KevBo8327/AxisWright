package dev.androidcad.sketchedit

/**
 * Schlüssel der Hinweise und Meldungen des Skizzen-Editors (in [dev.androidcad.text.Msg]); den Wortlaut liefert die App
 * (`app/…/CoreText.kt`, `strings_core.xml`). Beschriftungen von [ActionKind] und den Werkzeugvarianten ([RectMode] …) bildet die App
 * direkt über deren Einträge ab.
 */
public enum class EditText {
    SELECT_TO_MIRROR,
    SELECT_TO_PATTERN,
    TANGENT_START_AT_END,
    CONFLICTS_WITH_CONSTRAINTS,
    TAP_LINE_CIRCLE_ARC,
    TAP_END_OF_LINE_OR_ARC,
    TAP_CORNER,
    TAP_MIRROR_AXIS,
    TAP_PATTERN_DIRECTION,
    TAP_PATTERN_CENTER,
    DIM_SECOND_AFTER_LINE,
    DIM_SECOND_AFTER_POINT,
    DIM_ALREADY_DETERMINED,
    DIM_NOT_POSSIBLE,
    POINT_ON_LINE,
    TAP_ELEMENT,
    TAP_FARTHER,
    POINT_EXISTS,
    TANGENT_NEEDS_ROUND,
    NO_TANGENT,
    ALREADY_SELECTED,
    NO_TANGENT_CIRCLE,
    POINTS_COLLINEAR,
    END_STRAIGHT_AHEAD,
    /** (Höchstzahl) */
    PATTERN_COUNT_RANGE,
    INVALID_VALUE,
    SELECT_SHAPE_AND_REFERENCE,
    NOT_A_PATTERN,
    FACE_OUTLINE_UNKNOWN,
    NOT_POSSIBLE_FOR_SELECTION,
    CONFLICTS,
    ALREADY_DETERMINED,
    NOT_SOLVABLE_WITH_VALUE,
    SKETCH_CONFLICTING,
    ANGLE_DEG,
    STATUS_CONFLICT,
    STATUS_FULLY_CONSTRAINED,
    STATUS_POSITION_FREE,
    STATUS_POSITION_ROTATION_FREE,
    /** Plural (Freiheitsgrade). */
    STATUS_DOF,
}
