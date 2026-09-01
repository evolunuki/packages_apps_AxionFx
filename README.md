# AxionFx

Dependencies:
https://github.com/AxionAOSP/android_external_steam-audio -> external/steam-audio

For hardware/interfaces adds see https://github.com/evolunuki/android_hardware_interfaces

Pick this to external/pffft:
https://github.com/AxionAOSP/android_external_pffft/commit/7541e3eb7e8440c9e5d8744794e47ebcbc2333dfgo

For axion sdk adds, see:
https://github.com/evolunuki/android_vendor_extras


1. Clone to `packages/apps/AxionFx`
2. `$(call inherit-product-if-exists, packages/apps/AxionFx/config.mk)` in device.mk
