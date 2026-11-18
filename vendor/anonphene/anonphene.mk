# AnonpheneOS product additions, inherited by every device's product
# (scripts/apply.py adds the inherit line to GrapheneOS's handheld_product.mk).

PRODUCT_PACKAGES += \
    AnonSecureFolder \
    AnonPrivacy \
    privapp-permissions-anonphene.xml \
    anonphene-user-types.xml

PRODUCT_SYSTEM_EXT_PROPERTIES += $(shell sed -e '/^\s*\#/d' -e '/^\s*$$/d' vendor/anonphene/config/anonphene.prop)

PRODUCT_COPY_FILES += \
    vendor/anonphene/init/anonphene.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/anonphene.rc

PRODUCT_PACKAGE_OVERLAYS += vendor/anonphene/overlay

SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/anonphene/sepolicy

# Lightweight: things a private phone doesn't need, left out of the image.
# (Every one can still be installed later as a normal app where one exists.)
PRODUCT_PACKAGES_REMOVE += \
    BasicDreams \
    PhotoTable \
    LiveWallpapersPicker \
    PrintSpooler \
    PrintRecommendationService \
    BuiltInPrintService \
    BookmarkProvider \
    Traceur

# perfetto, incidentd and dumpstate stay in the image because other parts
# depend on them, but they only start when asked: anonphene.prop turns
# tracing off, and their output folders are kept in RAM (init/anonphene.rc).
