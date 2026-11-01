package dev.androidcad.app

/** Ersatz für `getString` in Unit-Tests (keine Android-Ressourcen): deutsche Standardnamen, damit Erwartungen wie „Skizze 1“ gelten. */
internal val workspaceTestStrings: (Int) -> String = { id ->
    mapOf(
        R.string.ws_name_sketch to "Skizze", R.string.ws_name_extrusion to "Extrusion", R.string.ws_name_cut to "Abtrag",
        R.string.ws_name_revolve to "Drehung", R.string.ws_name_revolve_cut to "Drehabtrag", R.string.ws_name_fillet to "Verrundung",
        R.string.ws_name_chamfer to "Fase", R.string.ws_name_mirror_body to "Körperspiegelung", R.string.ws_name_mirror to "Spiegelung",
        R.string.ws_name_pattern to "Muster", R.string.ws_name_plane to "Ebene", R.string.ws_name_shell to "Schale",
        R.string.ws_name_hole to "Bohrung", R.string.ws_import_name to "%1\$s (Import)", R.string.ws_example_name to "Platte mit vier Bohrungen",
    )[id] ?: "?"
}
