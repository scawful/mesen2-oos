"""Explicit Oracle build-flag mappings for profile-sensitive items."""

from .constants import ITEMS

SENSITIVE_ITEMS = frozenset({"rings", "portalrod", "customrods", "goldstar", "goldstar_inventory_version"})
ITEM_PROFILES = ("default", "legacy")
# ENABLE_GOLDSTAR_CELL = 1 marks migrated saves with $A5; until then the menu's
# GoldstarInventory_Migrate rewrites GoldstarOwned from Hookshot level 2.
GOLDSTAR_MIGRATED = 0xA5
GOLDSTAR_ITEMS = frozenset({"goldstar", "goldstar_inventory_version"})


def item_available(name: str, profile: str | None) -> bool:
    """False when the profile's build has no cell for the item."""
    return not (profile == "legacy" and name in GOLDSTAR_ITEMS)


def item_address(name: str, profile: str | None) -> int:
    if name in SENSITIVE_ITEMS and profile not in ITEM_PROFILES:
        raise ValueError(f"{name} requires --item-profile default or legacy")
    if profile == "legacy":
        if name == "rings":
            return 0x7EF3D8
        if name == "portalrod":
            return 0x7EF351
        if not item_available(name, profile):
            raise ValueError(f"{name} has no cell in the legacy profile; Hookshot level 2 ($7EF342) holds both")
    return ITEMS[name][0]


def decode_legacy_portalrod(raw: int) -> int:
    if raw not in (0, 1, 2):
        raise ValueError(f"Invalid legacy rod level: {raw}")
    return int(raw == 2)
