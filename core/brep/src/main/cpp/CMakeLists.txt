cmake_minimum_required(VERSION 3.22)
project(cadbrep LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# OpenCascade wird vorab per scripts/build-occt.ps1 je ABI als gemeinsame Bibliotheken gebaut und installiert. Gemeinsam statt
# statisch wegen der LGPL 2.1: Die libTK*.so liegen unverändert in der App und lassen sich austauschen.
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(OCCT_PREFIX "${REPO_ROOT}/third_party/occt-android-shared/${ANDROID_ABI}")
if(NOT EXISTS "${OCCT_PREFIX}/include/opencascade")
  message(FATAL_ERROR "OpenCascade für ${ANDROID_ABI} fehlt: ${OCCT_PREFIX}
"
                      "Bitte scripts/build-occt.ps1 -Abis ${ANDROID_ABI} ausführen.")
endif()

# Auch im Debug-Build optimieren (sonst sind B-Rep-Algorithmen um Größenordnungen langsamer).
add_compile_options("$<$<CONFIG:Debug>:-O2>")

# Benötigte OCCT-Toolkits als importierte gemeinsame Bibliotheken – der Android-Gradle-Plugin packt sie mit in die App.
set(OCCT_LIBS
    TKFillet TKOffset TKBO TKBool TKFeat TKMesh TKShHealing TKTopAlgo TKPrim TKGeomAlgo
    TKBRep TKGeomBase TKG3d TKG2d TKMath TKernel)
set(OCCT_LINK "")
foreach(lib ${OCCT_LIBS})
  set(file "${OCCT_PREFIX}/lib/lib${lib}.so")
  if(NOT EXISTS "${file}")
    message(FATAL_ERROR "OpenCascade-Bibliothek fehlt: ${file} (scripts/build-occt.ps1 -Abis ${ANDROID_ABI})")
  endif()
  add_library(occt_${lib} SHARED IMPORTED)
  set_target_properties(occt_${lib} PROPERTIES IMPORTED_LOCATION "${file}")
  list(APPEND OCCT_LINK occt_${lib})
endforeach()

add_library(cadbrep SHARED cadbrep.cpp)
target_include_directories(cadbrep PRIVATE "${OCCT_PREFIX}/include/opencascade")
target_link_libraries(cadbrep PRIVATE ${OCCT_LINK} log)
target_link_options(cadbrep PRIVATE -Wl,-z,max-page-size=16384)
