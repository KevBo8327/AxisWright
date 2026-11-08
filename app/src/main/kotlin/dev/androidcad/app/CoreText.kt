package dev.androidcad.app

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.androidcad.brep.BrepText
import dev.androidcad.lang.CadLangException
import dev.androidcad.lang.LangText
import dev.androidcad.model.BasePlane
import dev.androidcad.model.ModelText
import dev.androidcad.sketch.SketchText
import dev.androidcad.sketchedit.ActionKind
import dev.androidcad.sketchedit.ArcMode
import dev.androidcad.sketchedit.CircleMode
import dev.androidcad.sketchedit.CornerMode
import dev.androidcad.sketchedit.EditText
import dev.androidcad.sketchedit.LineMode
import dev.androidcad.sketchedit.PolygonMode
import dev.androidcad.sketchedit.RectMode
import dev.androidcad.text.Msg

/*
 * Wortlaut der Meldungen aus den Kernmodulen: Die Kerne liefern [Msg] (Schlüssel + Werte), die App setzt den Text in der
 * Gerätesprache ein (res/values/strings_core.xml = Englisch, values-de/strings_core.xml = Deutsch). Die `when`-Zuordnungen unten
 * sind erschöpfend – ein neuer Schlüssel im Kern ohne Text bricht den Build, statt still Englisch/Deutsch zu mischen.
 *
 * Dazu [UiText]: Text in Zustand außerhalb von Composables (WorkspaceState, CadState), erst beim Anzeigen aufgelöst.
 */

/** Text der Meldung [m]; Argumente, die selbst [Msg], [UiText] oder Schlüssel (Enum) sind, werden zuerst übersetzt. */
fun Resources.text(m: Msg): String {
    val args = args(m.args)
    val id = res(m.key)
    return when {
        m.key in PLURALS -> getQuantityString(id, args.first() as Int, *args)
        args.isEmpty() -> getString(id)
        else -> getString(id, *args)
    }
}

/** Beschriftung eines Schlüssels ohne Werte, z. B. [ActionKind.LENGTH] oder [RectMode.CENTER]. */
fun Resources.text(key: Enum<*>): String = text(Msg(key))

@Composable
@ReadOnlyComposable
fun Msg.text(): String = LocalResources.current.text(this)

/** Beschriftung eines Kern-Enums (Bedingung, Werkzeugvariante, Grundebene) in Composables. */
@Composable
@ReadOnlyComposable
fun coreLabel(key: Enum<*>): String = LocalResources.current.text(key)

/**
 * Text für Zustand außerhalb von Composables (Hinweise, Fehlerlisten): wird erst beim Anzeigen in der aktuellen Sprache aufgelöst.
 * Werte ([args]) dürfen Zahlen, Text, [Msg] oder wieder [UiText] sein.
 */
sealed interface UiText {
    class Res(@StringRes val id: Int, vararg val args: Any) : UiText

    /** Plural; [count] wählt die Form. Ohne [args] wird [count] selbst als `%1$d` eingesetzt. */
    class Plural(@PluralsRes val id: Int, val count: Int, vararg val args: Any) : UiText

    /** Meldung eines Kernmoduls. */
    class Core(val msg: Msg) : UiText

    /** Fertiger Text ohne Übersetzung: Namen, technische Details aus Ausnahmen. */
    class Raw(val text: String) : UiText

    /** Mehrere Texte untereinander. */
    class Lines(val lines: List<UiText>) : UiText
}

fun Resources.text(t: UiText): String = when (t) {
    is UiText.Res -> if (t.args.isEmpty()) getString(t.id) else getString(t.id, *args(t.args.toList()))
    is UiText.Plural -> getQuantityString(t.id, t.count, *(if (t.args.isEmpty()) arrayOf(t.count) else args(t.args.toList())))
    is UiText.Core -> text(t.msg)
    is UiText.Raw -> t.text
    is UiText.Lines -> t.lines.joinToString("\n") { text(it) }
}

@Composable
@ReadOnlyComposable
fun UiText.text(): String = LocalResources.current.text(this)

/** Fehler der Skriptsprache mit Zeilennummer („Line 3: …“), sofern bekannt. */
fun CadLangException.toMsg(): Msg = if (line > 0) Msg(LangText.LINE, line, msg) else msg

private fun Resources.args(list: List<Any>): Array<Any> = list.map { a ->
    when (a) {
        is Msg -> text(a)
        is UiText -> text(a)
        is Enum<*> -> text(a)
        else -> a
    }
}.toTypedArray()

internal fun res(key: Enum<*>): Int = when (key) {
    is SketchText -> key.res()
    is EditText -> key.res()
    is ModelText -> key.res()
    is LangText -> key.res()
    is ActionKind -> key.res()
    is RectMode -> key.res()
    is CircleMode -> key.res()
    is PolygonMode -> key.res()
    is LineMode -> key.res()
    is ArcMode -> key.res()
    is CornerMode -> key.res()
    is BasePlane -> key.res()
    is BrepText -> key.res()
    else -> error("Kein Text für ${key::class.simpleName}.${key.name} – in CoreText.kt zuordnen")
}

/** Schlüssel, deren Text ein `<plurals>` ist (erstes Argument = Anzahl). */
private val PLURALS: Set<Enum<*>> = setOf(
    SketchText.LINEAR_COPIES, SketchText.CIRCULAR_COPIES, SketchText.PATTERN_ALREADY_HAS,
    SketchText.INSTANCES_ADDED, SketchText.INSTANCES_REMOVED, SketchText.CONSTRAINTS_ADAPTED,
    SketchText.CONSTRAINTS_REMOVED, SketchText.COPIES_CREATED,
    EditText.STATUS_DOF, ModelText.PROJECTIONS_MISSING
)

private fun SketchText.res(): Int = when (this) {
    SketchText.LINE_NO_LENGTH -> R.string.core_sketch_line_no_length
    SketchText.LINE_DELETED -> R.string.core_sketch_line_deleted
    SketchText.CIRCLE_DELETED -> R.string.core_sketch_circle_deleted
    SketchText.ARC_DELETED -> R.string.core_sketch_arc_deleted
    SketchText.INTERSECTIONS_COINCIDE -> R.string.core_sketch_intersections_coincide
    SketchText.TRIM_CIRCLE_NEEDS_TWO -> R.string.core_sketch_trim_circle_needs_two
    SketchText.END_CONNECTED -> R.string.core_sketch_end_connected
    SketchText.NOTHING_TO_EXTEND_TO -> R.string.core_sketch_nothing_to_extend_to
    SketchText.DISTANCE_NOT_POSITIVE -> R.string.core_sketch_distance_not_positive
    SketchText.OFFSET_EXCEEDS_RADIUS -> R.string.core_sketch_offset_exceeds_radius
    SketchText.CHAIN_LINE_NO_LENGTH -> R.string.core_sketch_chain_line_no_length
    SketchText.RADIUS_NOT_POSITIVE -> R.string.core_sketch_radius_not_positive
    SketchText.CORNER_NEEDS_TWO_LINES -> R.string.core_sketch_corner_needs_two_lines
    SketchText.A_LINE_NO_LENGTH -> R.string.core_sketch_a_line_no_length
    SketchText.LINES_COLLINEAR -> R.string.core_sketch_lines_collinear
    SketchText.RADIUS_TOO_LARGE -> R.string.core_sketch_radius_too_large
    SketchText.CHAMFER_TOO_LARGE -> R.string.core_sketch_chamfer_too_large
    SketchText.SELECT_SHAPE_FOR_ORIGIN -> R.string.core_sketch_select_shape_for_origin
    SketchText.SHAPE_ALREADY_AT_ORIGIN -> R.string.core_sketch_shape_already_at_origin
    SketchText.CIRCLE_ALREADY_AT_ORIGIN -> R.string.core_sketch_circle_already_at_origin
    SketchText.SELECT_SHAPE_TO_CENTER -> R.string.core_sketch_select_shape_to_center
    SketchText.SHAPE_NO_EXTENT -> R.string.core_sketch_shape_no_extent
    SketchText.EDGE_NO_LENGTH -> R.string.core_sketch_edge_no_length
    SketchText.FACE_NO_EXTENT -> R.string.core_sketch_face_no_extent
    SketchText.NOT_POSSIBLE -> R.string.core_sketch_not_possible
    SketchText.CENTERED_ON_EDGE -> R.string.core_sketch_centered_on_edge
    SketchText.NOTHING_TO_MIRROR -> R.string.core_sketch_nothing_to_mirror
    SketchText.MIRROR_AXIS_NO_LENGTH -> R.string.core_sketch_mirror_axis_no_length
    SketchText.NOTHING_TO_PATTERN -> R.string.core_sketch_nothing_to_pattern
    SketchText.COUNT_AT_LEAST_2 -> R.string.core_sketch_count_at_least_2
    SketchText.SPACING_NOT_POSITIVE -> R.string.core_sketch_spacing_not_positive
    SketchText.DIRECTION_MISSING -> R.string.core_sketch_direction_missing
    SketchText.ANGLE_RANGE_360 -> R.string.core_sketch_angle_range_360
    SketchText.CENTER_ONLY_ELEMENT -> R.string.core_sketch_center_only_element
    SketchText.SCALE_NOT_POSITIVE -> R.string.core_sketch_scale_not_positive
    SketchText.NOTHING_TO_TRANSFORM -> R.string.core_sketch_nothing_to_transform
    SketchText.CONSTRAINTS_ADAPTED -> R.plurals.core_sketch_constraints_adapted
    SketchText.CONSTRAINTS_REMOVED -> R.plurals.core_sketch_constraints_removed
    SketchText.COPIES_CREATED -> R.plurals.core_sketch_copies_created
    SketchText.LINEAR_COPIES -> R.plurals.core_sketch_linear_copies
    SketchText.CIRCULAR_COPIES -> R.plurals.core_sketch_circular_copies
    SketchText.MAX_INSTANCES -> R.string.core_sketch_max_instances
    SketchText.PATTERN_ALREADY_HAS -> R.plurals.core_sketch_pattern_already_has
    SketchText.PATTERN_SOURCE_INCOMPLETE -> R.string.core_sketch_pattern_source_incomplete
    SketchText.INSTANCES_ADDED -> R.plurals.core_sketch_instances_added
    SketchText.INSTANCES_REMOVED -> R.plurals.core_sketch_instances_removed
    SketchText.TAP_BETWEEN_LINE_ENDS -> R.string.core_sketch_tap_between_line_ends
    SketchText.TAP_BETWEEN_ARC_ENDS -> R.string.core_sketch_tap_between_arc_ends
    SketchText.TAP_ON_CIRCLE -> R.string.core_sketch_tap_on_circle
    SketchText.OPEN_END -> R.string.core_sketch_open_end
    SketchText.BRANCH -> R.string.core_sketch_branch
    SketchText.CONTOUR_INCOMPLETE -> R.string.core_sketch_contour_incomplete
}

private fun EditText.res(): Int = when (this) {
    EditText.SELECT_TO_MIRROR -> R.string.core_edit_select_to_mirror
    EditText.SELECT_TO_PATTERN -> R.string.core_edit_select_to_pattern
    EditText.SELECT_TO_TRANSFORM -> R.string.core_edit_select_to_transform
    EditText.TAP_TRANSFORM_PIVOT -> R.string.core_edit_tap_transform_pivot
    EditText.TANGENT_START_AT_END -> R.string.core_edit_tangent_start_at_end
    EditText.CONFLICTS_WITH_CONSTRAINTS -> R.string.core_edit_conflicts_with_constraints
    EditText.TAP_LINE_CIRCLE_ARC -> R.string.core_edit_tap_line_circle_arc
    EditText.TAP_END_OF_LINE_OR_ARC -> R.string.core_edit_tap_end_of_line_or_arc
    EditText.TAP_CORNER -> R.string.core_edit_tap_corner
    EditText.TAP_MIRROR_AXIS -> R.string.core_edit_tap_mirror_axis
    EditText.TAP_PATTERN_DIRECTION -> R.string.core_edit_tap_pattern_direction
    EditText.TAP_PATTERN_CENTER -> R.string.core_edit_tap_pattern_center
    EditText.DIM_SECOND_AFTER_LINE -> R.string.core_edit_dim_second_after_line
    EditText.DIM_SECOND_AFTER_POINT -> R.string.core_edit_dim_second_after_point
    EditText.DIM_ALREADY_DETERMINED -> R.string.core_edit_dim_already_determined
    EditText.DIM_NOT_POSSIBLE -> R.string.core_edit_dim_not_possible
    EditText.POINT_ON_LINE -> R.string.core_edit_point_on_line
    EditText.TAP_ELEMENT -> R.string.core_edit_tap_element
    EditText.TAP_FARTHER -> R.string.core_edit_tap_farther
    EditText.POINT_EXISTS -> R.string.core_edit_point_exists
    EditText.TANGENT_NEEDS_ROUND -> R.string.core_edit_tangent_needs_round
    EditText.NO_TANGENT -> R.string.core_edit_no_tangent
    EditText.ALREADY_SELECTED -> R.string.core_edit_already_selected
    EditText.NO_TANGENT_CIRCLE -> R.string.core_edit_no_tangent_circle
    EditText.POINTS_COLLINEAR -> R.string.core_edit_points_collinear
    EditText.END_STRAIGHT_AHEAD -> R.string.core_edit_end_straight_ahead
    EditText.PATTERN_COUNT_RANGE -> R.string.core_edit_pattern_count_range
    EditText.INVALID_VALUE -> R.string.core_edit_invalid_value
    EditText.SELECT_SHAPE_AND_REFERENCE -> R.string.core_edit_select_shape_and_reference
    EditText.NOT_A_PATTERN -> R.string.core_edit_not_a_pattern
    EditText.FACE_OUTLINE_UNKNOWN -> R.string.core_edit_face_outline_unknown
    EditText.NOT_POSSIBLE_FOR_SELECTION -> R.string.core_edit_not_possible_for_selection
    EditText.CONFLICTS -> R.string.core_edit_conflicts
    EditText.ALREADY_DETERMINED -> R.string.core_edit_already_determined
    EditText.NOT_SOLVABLE_WITH_VALUE -> R.string.core_edit_not_solvable_with_value
    EditText.SKETCH_CONFLICTING -> R.string.core_edit_sketch_conflicting
    EditText.ANGLE_DEG -> R.string.core_edit_angle_deg
    EditText.STATUS_CONFLICT -> R.string.core_edit_status_conflict
    EditText.STATUS_FULLY_CONSTRAINED -> R.string.core_edit_status_fully_constrained
    EditText.STATUS_POSITION_FREE -> R.string.core_edit_status_position_free
    EditText.STATUS_POSITION_ROTATION_FREE -> R.string.core_edit_status_position_rotation_free
    EditText.STATUS_DOF -> R.plurals.core_edit_status_dof
}

private fun ModelText.res(): Int = when (this) {
    ModelText.FEATURE_FAILED -> R.string.core_model_feature_failed
    ModelText.SKETCH_PLANE_MISSING -> R.string.core_model_sketch_plane_missing
    ModelText.SKETCH_FACE_MISSING -> R.string.core_model_sketch_face_missing
    ModelText.SKETCH_CONFLICTING -> R.string.core_model_sketch_conflicting
    ModelText.PLANE_SOURCE_MISSING -> R.string.core_model_plane_source_missing
    ModelText.PATTERN_FILLETS_FAILED -> R.string.core_model_pattern_fillets_failed
    ModelText.PROJECTIONS_MISSING -> R.plurals.core_model_projections_missing
    ModelText.NO_BODY -> R.string.core_model_no_body
    ModelText.NO_BODY_TO_MIRROR -> R.string.core_model_no_body_to_mirror
    ModelText.NO_BODY_TO_CUT -> R.string.core_model_no_body_to_cut
    ModelText.PATTERN_SOURCE_GONE -> R.string.core_model_pattern_source_gone
    ModelText.PATTERN_NO_COPIES -> R.string.core_model_pattern_no_copies
    ModelText.MIRROR_PLANE_GONE -> R.string.core_model_mirror_plane_gone
    ModelText.THROUGH_ALL_NEEDS_BODY -> R.string.core_model_through_all_needs_body
    ModelText.REVOLVE_AXIS_GONE -> R.string.core_model_revolve_axis_gone
    ModelText.NO_CLOSED_REGION -> R.string.core_model_no_closed_region
    ModelText.FACE_GONE -> R.string.core_model_face_gone
    ModelText.FACE_NOT_PLANAR -> R.string.core_model_face_not_planar
    ModelText.HOLE_REF_EDGE_GONE -> R.string.core_model_hole_ref_edge_gone
    ModelText.HOLE_REF_EDGE_NOT_IN_FACE -> R.string.core_model_hole_ref_edge_not_in_face
    ModelText.HOLE_REF_EDGE_NOT_STRAIGHT -> R.string.core_model_hole_ref_edge_not_straight
    ModelText.HOLE_REF_EDGES_PARALLEL -> R.string.core_model_hole_ref_edges_parallel
    ModelText.A_FACE_GONE -> R.string.core_model_a_face_gone
    ModelText.AN_EDGE_GONE -> R.string.core_model_an_edge_gone
    ModelText.PATTERN_NESTING -> R.string.core_model_pattern_nesting
    ModelText.GRID_NOT_PATTERNABLE -> R.string.core_model_grid_not_patternable
    ModelText.PATTERN_OF_PATTERN_ONE_WAY -> R.string.core_model_pattern_of_pattern_one_way
    ModelText.NOT_PATTERNABLE -> R.string.core_model_not_patternable
    ModelText.DIMENSION_NOT_POSITIVE -> R.string.core_model_dimension_not_positive
    ModelText.LENGTH_NOT_POSITIVE -> R.string.core_model_length_not_positive
    ModelText.REVOLVE_ANGLE_RANGE -> R.string.core_model_revolve_angle_range
    ModelText.THICKNESS_NOT_POSITIVE -> R.string.core_model_thickness_not_positive
    ModelText.SIZE_NOT_POSITIVE -> R.string.core_model_size_not_positive
    ModelText.DIAMETER_NOT_POSITIVE -> R.string.core_model_diameter_not_positive
    ModelText.DEPTH_NOT_POSITIVE -> R.string.core_model_depth_not_positive
    ModelText.PARAMETER_NAME_INVALID -> R.string.core_model_parameter_name_invalid
    ModelText.PARAMETER_NAMES_NOT_UNIQUE -> R.string.core_model_parameter_names_not_unique
    ModelText.PARAMETER_ERROR -> R.string.core_model_parameter_error
    ModelText.EXPRESSION_VALUE_ERROR -> R.string.core_model_expression_value_error
    ModelText.EXPRESSION_ERROR -> R.string.core_model_expression_error
    ModelText.COUNT_RANGE -> R.string.core_model_count_range
    ModelText.SPACING_ZERO -> R.string.core_model_spacing_zero
    ModelText.DIRECTION_MISSING -> R.string.core_model_direction_missing
    ModelText.COUNT2_RANGE -> R.string.core_model_count2_range
    ModelText.MAX_INSTANCES_GRID -> R.string.core_model_max_instances_grid
    ModelText.SPACING2_ZERO -> R.string.core_model_spacing2_zero
    ModelText.DIRECTION2_MISSING -> R.string.core_model_direction2_missing
    ModelText.DIRECTION2_PARALLEL -> R.string.core_model_direction2_parallel
    ModelText.ANGLE_RANGE_360 -> R.string.core_model_angle_range_360
    ModelText.AXIS_MISSING -> R.string.core_model_axis_missing
    ModelText.MIRROR_PLANE_MISSING -> R.string.core_model_mirror_plane_missing
    ModelText.DISTANCE_INVALID -> R.string.core_model_distance_invalid
    ModelText.PLANE_ANGLE_RANGE -> R.string.core_model_plane_angle_range
    ModelText.MIRROR_ORIGIN_MISSING -> R.string.core_model_mirror_origin_missing
    ModelText.MIRROR_NORMAL_MISSING -> R.string.core_model_mirror_normal_missing
    ModelText.REGION_CROSSES_AXIS -> R.string.core_model_region_crosses_axis
    ModelText.REGION_ON_AXIS -> R.string.core_model_region_on_axis
    ModelText.MODEL_NOT_FOUND -> R.string.core_model_model_not_found
    ModelText.MODEL_DAMAGED -> R.string.core_model_model_damaged
    ModelText.MODEL_RESTORED_FROM_BACKUP -> R.string.core_model_model_restored_from_backup
    ModelText.SAVE_FIRST -> R.string.core_model_save_first
    ModelText.COPY_FAILED -> R.string.core_model_copy_failed
}

private fun LangText.res(): Int = when (this) {
    LangText.LINE -> R.string.core_lang_line
    LangText.CANCELLED -> R.string.core_lang_cancelled
    LangText.INVALID_OVERRIDE -> R.string.core_lang_invalid_override
    LangText.TOO_DEEP -> R.string.core_lang_too_deep
    LangText.NOT_A_NUMBER -> R.string.core_lang_not_a_number
    LangText.RANGE_TOO_LARGE -> R.string.core_lang_range_too_large
    LangText.INCLUDE_IGNORED -> R.string.core_lang_include_ignored
    LangText.FILE_NOT_FOUND -> R.string.core_lang_file_not_found
    LangText.INCLUDE_TOO_DEEP -> R.string.core_lang_include_too_deep
    LangText.INCLUDE_MISSING_GT -> R.string.core_lang_include_missing_gt
    LangText.ASSERT_FAILED -> R.string.core_lang_assert_failed
    LangText.UNKNOWN_FUNCTION -> R.string.core_lang_unknown_function
    LangText.UNKNOWN_MODULE -> R.string.core_lang_unknown_module
    LangText.UNDEFINED_VARIABLE -> R.string.core_lang_undefined_variable
    LangText.RECURSION_MODULE -> R.string.core_lang_recursion_module
    LangText.RECURSION_FUNCTION -> R.string.core_lang_recursion_function
    LangText.POLYHEDRON_POINTS_MISSING -> R.string.core_lang_polyhedron_points_missing
    LangText.POLYHEDRON_INVALID_POINT -> R.string.core_lang_polyhedron_invalid_point
    LangText.POLYHEDRON_FACES_MISSING -> R.string.core_lang_polyhedron_faces_missing
    LangText.POLYHEDRON_INVALID_FACE -> R.string.core_lang_polyhedron_invalid_face
    LangText.POLYHEDRON_INDEX -> R.string.core_lang_polyhedron_index
    LangText.POLYGON_POINTS_MISSING -> R.string.core_lang_polygon_points_missing
    LangText.POLYGON_INVALID_POINT -> R.string.core_lang_polygon_invalid_point
    LangText.POLYGON_INVALID_PATH -> R.string.core_lang_polygon_invalid_path
    LangText.MULTMATRIX_INVALID -> R.string.core_lang_multmatrix_invalid
    LangText.UNCLOSED_COMMENT -> R.string.core_lang_unclosed_comment
    LangText.UNCLOSED_STRING -> R.string.core_lang_unclosed_string
    LangText.UNEXPECTED_CHAR -> R.string.core_lang_unexpected_char
    LangText.TRAILING_CHARS -> R.string.core_lang_trailing_chars
    LangText.UNEXPECTED_PATH -> R.string.core_lang_unexpected_path
    LangText.EXPECTED_FOUND -> R.string.core_lang_expected_found
    LangText.MISSING_BRACE -> R.string.core_lang_missing_brace
    LangText.EXPRESSION_EXPECTED -> R.string.core_lang_expression_expected
    LangText.END_OF_FILE -> R.string.core_lang_end_of_file
    LangText.PATH -> R.string.core_lang_path
    LangText.MODULE_NAME -> R.string.core_lang_module_name
    LangText.FUNCTION_NAME -> R.string.core_lang_function_name
    LangText.PARAMETER_NAME -> R.string.core_lang_parameter_name
    LangText.VARIABLE_NAME -> R.string.core_lang_variable_name
    LangText.NAME -> R.string.core_lang_name
}

private fun ActionKind.res(): Int = when (this) {
    ActionKind.HORIZONTAL -> R.string.core_action_horizontal
    ActionKind.VERTICAL -> R.string.core_action_vertical
    ActionKind.PARALLEL -> R.string.core_action_parallel
    ActionKind.PERPENDICULAR -> R.string.core_action_perpendicular
    ActionKind.COLLINEAR -> R.string.core_action_collinear
    ActionKind.EQUAL -> R.string.core_action_equal
    ActionKind.COINCIDENT -> R.string.core_action_coincident
    ActionKind.CONCENTRIC -> R.string.core_action_concentric
    ActionKind.POINT_ON -> R.string.core_action_point_on
    ActionKind.MIDPOINT -> R.string.core_action_midpoint
    ActionKind.TANGENT -> R.string.core_action_tangent
    ActionKind.SYMMETRIC -> R.string.core_action_symmetric
    ActionKind.FIX -> R.string.core_action_fix
    ActionKind.LENGTH -> R.string.core_action_length
    ActionKind.DISTANCE -> R.string.core_action_distance
    ActionKind.HORIZONTAL_DISTANCE -> R.string.core_action_horizontal_distance
    ActionKind.VERTICAL_DISTANCE -> R.string.core_action_vertical_distance
    ActionKind.RADIUS -> R.string.core_action_radius
    ActionKind.DIAMETER -> R.string.core_action_diameter
    ActionKind.ANGLE -> R.string.core_action_angle
    ActionKind.CONSTRUCTION -> R.string.core_action_construction
    ActionKind.ORIGIN_CORNER -> R.string.core_action_origin_corner
    ActionKind.ORIGIN_CENTER -> R.string.core_action_origin_center
    ActionKind.FACE_CENTER -> R.string.core_action_face_center
    ActionKind.HOLE_CENTER -> R.string.core_action_hole_center
    ActionKind.EDGE_CENTER -> R.string.core_action_edge_center
    ActionKind.PATTERN_COUNT -> R.string.core_action_pattern_count
    ActionKind.TRANSFORM -> R.string.core_action_transform
}

private fun RectMode.res(): Int = when (this) {
    RectMode.CORNERS -> R.string.core_mode_corners
    RectMode.CENTER -> R.string.core_mode_center
    RectMode.THREE_POINTS -> R.string.core_mode_three_points
}

private fun CircleMode.res(): Int = when (this) {
    CircleMode.CENTER -> R.string.core_mode_center
    CircleMode.TWO_POINTS -> R.string.core_mode_two_points
    CircleMode.THREE_POINTS -> R.string.core_mode_three_points
    CircleMode.TANGENT2 -> R.string.core_mode_two_tangents
    CircleMode.TANGENT3 -> R.string.core_mode_three_tangents
}

private fun PolygonMode.res(): Int = when (this) {
    PolygonMode.INSCRIBED -> R.string.core_mode_inscribed
    PolygonMode.CIRCUMSCRIBED -> R.string.core_mode_circumscribed
}

private fun LineMode.res(): Int = when (this) {
    LineMode.NORMAL -> R.string.core_mode_line
    LineMode.TANGENT -> R.string.core_mode_tangent
}

private fun ArcMode.res(): Int = when (this) {
    ArcMode.CENTER -> R.string.core_mode_center
    ArcMode.THREE_POINTS -> R.string.core_mode_three_points
    ArcMode.TANGENT -> R.string.core_mode_tangent
}

private fun CornerMode.res(): Int = when (this) {
    CornerMode.ROUND -> R.string.core_mode_round
    CornerMode.CHAMFER -> R.string.core_mode_chamfer
}

private fun BasePlane.res(): Int = when (this) {
    BasePlane.XY -> R.string.core_plane_xy
    BasePlane.XZ -> R.string.core_plane_xz
    BasePlane.YZ -> R.string.core_plane_yz
}

private fun BrepText.res(): Int = when (this) {
    BrepText.NOT_RIGID_TRANSFORM -> R.string.core_brep_not_rigid_transform
    BrepText.BREP_ERROR -> R.string.core_brep_error
    BrepText.BREP_ERROR_DETAIL -> R.string.core_brep_error_detail
    BrepText.EXTRUSION_HEIGHT_ZERO -> R.string.core_brep_extrusion_height_zero
    BrepText.REVOLVE_AXIS_NO_DIRECTION -> R.string.core_brep_revolve_axis_no_direction
    BrepText.REVOLVE_ANGLE_RANGE -> R.string.core_brep_revolve_angle_range
}
