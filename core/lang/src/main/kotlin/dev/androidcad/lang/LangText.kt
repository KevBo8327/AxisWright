package dev.androidcad.lang

/**
 * Schlüssel der Fehler und Warnungen der Skriptsprache (in [dev.androidcad.text.Msg]); den Wortlaut liefert die App
 * (`app/…/CoreText.kt`, `strings_core.xml`). Die Einträge ab [END_OF_FILE] sind Bausteine, die als Argument in andere Meldungen
 * eingesetzt werden.
 */
public enum class LangText {
    LINE,
    CANCELLED,
    INVALID_OVERRIDE,
    TOO_DEEP,
    NOT_A_NUMBER,
    RANGE_TOO_LARGE,
    INCLUDE_IGNORED,
    FILE_NOT_FOUND,
    INCLUDE_TOO_DEEP,
    INCLUDE_MISSING_GT,
    ASSERT_FAILED,
    UNKNOWN_FUNCTION,
    UNKNOWN_MODULE,
    UNDEFINED_VARIABLE,
    RECURSION_MODULE,
    RECURSION_FUNCTION,
    POLYHEDRON_POINTS_MISSING,
    POLYHEDRON_INVALID_POINT,
    POLYHEDRON_FACES_MISSING,
    POLYHEDRON_INVALID_FACE,
    POLYHEDRON_INDEX,
    POLYGON_POINTS_MISSING,
    POLYGON_INVALID_POINT,
    POLYGON_INVALID_PATH,
    MULTMATRIX_INVALID,
    UNCLOSED_COMMENT,
    UNCLOSED_STRING,
    UNEXPECTED_CHAR,
    TRAILING_CHARS,
    UNEXPECTED_PATH,
    EXPECTED_FOUND,
    MISSING_BRACE,
    EXPRESSION_EXPECTED,
    END_OF_FILE,
    PATH,
    MODULE_NAME,
    FUNCTION_NAME,
    PARAMETER_NAME,
    VARIABLE_NAME,
    NAME,
}
