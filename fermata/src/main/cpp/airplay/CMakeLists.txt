cmake_minimum_required(VERSION 3.22)
project(fermata_airplay LANGUAGES C CXX)

if(NOT DEFINED FERMATA_AIRPLAY_SOURCE OR (ANDROID AND NOT DEFINED FERMATA_AIRPLAY_OPENSSL))
    message(FATAL_ERROR "Set the AirPlay source and Android OpenSSL directories")
endif()
set(UXPLAY "${FERMATA_AIRPLAY_SOURCE}/UxPlay/lib")
set(PLIST "${FERMATA_AIRPLAY_SOURCE}/libplist")
set(CRYPTO "${FERMATA_AIRPLAY_OPENSSL}/${ANDROID_ABI}")

add_library(fermata_airplay SHARED airplay.cpp android_dnssd_shim.c)
target_compile_features(fermata_airplay PRIVATE c_std_17 cxx_std_17)
target_compile_definitions(fermata_airplay PRIVATE TARGET_RT_LITTLE_ENDIAN=1
        FERMATA_REQUIRE_PIN PLIST_210 _GNU_SOURCE HAVE_STRNDUP PACKAGE_VERSION="2.6.0")
target_include_directories(fermata_airplay PRIVATE "${CRYPTO}/include" "${UXPLAY}"
        "${UXPLAY}/llhttp" "${UXPLAY}/playfair" "${PLIST}/include" "${PLIST}/src"
        "${PLIST}/libcnary/include")
foreach(src airplay_video byteutils compat crypto fairplay_playfair http_request http_response
        httpd logger mirror_buffer netutils pairing raop raop_buffer raop_ntp raop_rtp
        raop_rtp_mirror srp utils llhttp/api llhttp/http llhttp/llhttp playfair/hand_garble
        playfair/modified_md5 playfair/omg_hax playfair/playfair playfair/sap_hash)
    target_sources(fermata_airplay PRIVATE "${UXPLAY}/${src}.c")
endforeach()
foreach(src base64 bplist bytearray hashtable jplist jsmn oplist out-default out-limd
        out-plutil plist ptrarray time64 xplist)
    target_sources(fermata_airplay PRIVATE "${PLIST}/src/${src}.c")
endforeach()
target_sources(fermata_airplay PRIVATE "${PLIST}/libcnary/node.c"
        "${PLIST}/libcnary/node_list.c")
if(ANDROID)
    target_compile_definitions(fermata_airplay PRIVATE ANDROID)
    target_link_libraries(fermata_airplay PRIVATE "${CRYPTO}/libcrypto.a" log m)
else()
    find_package(JNI REQUIRED)
    find_package(OpenSSL REQUIRED)
    target_include_directories(fermata_airplay PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(fermata_airplay PRIVATE OpenSSL::Crypto m)
endif()
