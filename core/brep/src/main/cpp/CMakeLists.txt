cmake_minimum_required(VERSION 3.22)
project(cadbrep LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# OpenCascade wird vorab per scripts/build-occt.ps1 je ABI gebaut und installiert.
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(OCCT_PREFIX "${REPO_ROOT}/third_party/occt-android/${ANDROID_ABI}")
if(NOT EXISTS "${OCCT_PREFIX}/include/opencascade")
  message(FATAL_ERROR "OpenCascade für ${ANDROID_ABI} fehlt: ${OCCT_PREFIX}\n"
                      "Bitte scripts/build-occt.ps1 -Abis ${ANDROID_ABI} ausführen.")
endif()

# Auch im Debug-Build optimieren (sonst sind B-Rep-Algorithmen um Größenordnungen langsamer).
add_compile_options("$<$<CONFIG:Debug>:-O2>")

# Statische OCCT-Bibliotheken; Reihenfolge von den abhängigsten zu den grundlegenden (Linker-Auflösung).
set(OCCT_LIBS
    TKFillet TKOffset TKBO TKBool TKFeat TKMesh TKShHealing TKTopAlgo TKPrim TKGeomAlgo
    TKBRep TKGeomBase TKG3d TKG2d TKMath TKernel)
set(OCCT_LINK "")
foreach(lib ${OCCT_LIBS})
  # NO_CMAKE_FIND_ROOT_PATH: der Android-Toolchain beschränkt find_library sonst auf das NDK-Sysroot.
  find_library(OCCT_${lib} NAMES ${lib} PATHS "${OCCT_PREFIX}/lib" NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH REQUIRED)
  list(APPEND OCCT_LINK "${OCCT_${lib}}")
endforeach()

add_library(cadbrep SHARED cadbrep.cpp)
target_include_directories(cadbrep PRIVATE "${OCCT_PREFIX}/include/opencascade")
target_link_libraries(cadbrep PRIVATE -Wl,--start-group ${OCCT_LINK} -Wl,--end-group log)
target_link_options(cadbrep PRIVATE -Wl,-z,max-page-size=16384)
