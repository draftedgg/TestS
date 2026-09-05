cmake_minimum_required(VERSION 3.22.1)
project(mcpanel_stub C)

# MCPanel ABI stub: an intentionally empty shared library. Its only job is
# to give each per-ABI APK a native library for exactly one architecture
# (armeabi-v7a for the armv7 flavor, arm64-v8a for armv8), so Android:
#   1. installs the APK only on devices supporting that ABI, and
#   2. runs the app process in the matching bitness,
# which must always agree with the bundled Termux bootstrap.
add_library(mcpanel SHARED stub.c)