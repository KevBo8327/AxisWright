cmake_minimum_required(VERSION 3.22)
project(cadgeom LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party")
get_filename_component(THIRD_PARTY "${THIRD_PARTY}" ABSOLUTE)

# Auch im Debug-Build optimieren: ein unoptimiertes Manifold ist um Größenordnungen langsamer und
# verfälscht jede Performance-Messung. Debug-Symbole bleiben (-g).
add_compile_options("$<$<CONFIG:Debug>:-O2>")

# --- Manifold (statisch in libcadgeom.so) ------------------------------------------------------
# Alles Nötige liegt im Repo (third_party/): kein Download zur Build-Zeit.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MANIFOLD_PAR OFF CACHE BOOL "" FORCE)            # TBB-Backend: später, einzeln bewerten
set(MANIFOLD_CROSS_SECTION ON CACHE BOOL "" FORCE)   # 2D (Clipper2)
set(MANIFOLD_TEST OFF CACHE BOOL "" FORCE)
set(MANIFOLD_CBIND OFF CACHE BOOL "" FORCE)
set(MANIFOLD_PYBIND OFF CACHE BOOL "" FORCE)
set(MANIFOLD_JSBIND OFF CACHE BOOL "" FORCE)
# Manifold prüft FETCHCONTENT_SOURCE_DIR_<Name> case-sensitiv (hier "Clipper2"); FetchContent selbst
# nutzt die großgeschriebene Variante. Beide setzen → es wird nichts heruntergeladen.
set(FETCHCONTENT_SOURCE_DIR_Clipper2 "${THIRD_PARTY}/Clipper2" CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_CLIPPER2 "${THIRD_PARTY}/Clipper2" CACHE PATH "" FORCE)
set(FETCHCONTENT_FULLY_DISCONNECTED ON CACHE BOOL "" FORCE)
set(CLIPPER2_UTILS OFF CACHE BOOL "" FORCE)
set(CLIPPER2_EXAMPLES OFF CACHE BOOL "" FORCE)
set(CLIPPER2_TESTS OFF CACHE BOOL "" FORCE)

add_subdirectory("${THIRD_PARTY}/manifold" manifold-build)

# --- JNI-Bibliothek ----------------------------------------------------------------------------
add_library(cadgeom SHARED cadgeom.cpp)
target_link_libraries(cadgeom PRIVATE manifold log)
# 16-KB-Page-Größe (Android 15+ Geräte mit 16-KB-Kernel)
target_link_options(cadgeom PRIVATE -Wl,-z,max-page-size=16384)
